package app.localcache.storage

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.localcache.Prefs
import app.localcache.BuildConfig
import app.localcache.DiagLog
import app.localcache.HardwareDiagnostics
import app.localcache.server.LocalHttpServer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Real HTTP/coroutine/filesystem lifecycle on a dedicated Android test device. */
@RunWith(AndroidJUnit4::class)
class DownloadLifecycleTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var server: MockWebServer
    private val bodySize = 4 * 1024 * 1024
    private val firstA = AtomicInteger()
    private val replacementRange = mutableListOf<String?>()

    @Before
    fun setup() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = "download-test-${UUID.randomUUID()}-"
        context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = File(root, "app-files").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences(scope + name, mode)
        }
        root = File(base.cacheDir, scope).apply { check(mkdir()) }
        Prefs.setCacheDirPath(context, root.absolutePath)
        Prefs.setCacheMaxGb(context, 2)
        DownloadEngine.resumeAfterShutdown()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val first = request.path == "/a.mkv" && firstA.getAndIncrement() == 0
                if (!first && request.path == "/a.mkv") synchronized(replacementRange) {
                    replacementRange.add(request.getHeader("Range"))
                }
                val body = ByteArray(bodySize) { if (first) 1 else 2 }
                return MockResponse().setHeader("Content-Type", "video/x-matroska")
                    .setBody(Buffer().write(body))
                    .throttleBody(64 * 1024, if (first) 100 else 1, TimeUnit.MILLISECONDS)
            }
        }
        server.start()
    }

    @After
    fun teardown() {
        DiagLog.setEnabled(context, false)
        DownloadEngine.cancelActive(deletePartial = false)
        await("worker closed", 15_000) { !DownloadEngine.isBusy() }
        DownloadEngine.resumeAfterShutdown()
        server.shutdown()
        root.deleteRecursively() // Owned test directory only, after the real worker is done.
    }

    @Test
    fun hardwareContextSurvivesLogEvictionAndTracksTheSelectedStorage() {
        DiagLog.setEnabled(context, true)
        Prefs.setUsbSelection(context, root.absolutePath, "Synthetic memory card A", root.absolutePath)
        await("first hardware sample", 10_000) {
            val text = DiagLog.dump()
            text.contains("hardwareCollection=idle sampleMatchesSelection=true") &&
                text.contains("label=\"Synthetic memory card A\"") && text.contains("storageSpace totalBytes=")
        }
        val initial = DiagLog.dump()
        assertTrue(initial.contains("model=\"${Build.MODEL}\""))
        assertTrue(initial.contains("firmwareDisplay="))
        assertTrue(initial.contains("socModel="))
        assertTrue(initial.contains("ramTotal="))
        assertTrue(initial.contains("heapMax="))
        assertTrue(initial.contains("storageMount point="))
        assertTrue(initial.contains("usbMassStorage"))

        val missing = File(root, "disconnected-card")
        Prefs.setUsbSelection(context, missing.absolutePath, "Synthetic memory card B", missing.absolutePath)
        await("changed storage sample", 10_000) {
            val text = DiagLog.dump()
            text.contains("hardwareCollection=idle sampleMatchesSelection=true") &&
                text.contains("label=\"Synthetic memory card B\"") && text.contains("storagePath exists=false")
        }
        repeat(950) { DiagLog.t("evict ordinary event $it") }
        val exported = DiagLog.dump()
        assertTrue(exported.contains("Hardware context:"))
        assertTrue(exported.contains("model=\"${Build.MODEL}\""))
        assertTrue(exported.contains("label=\"Synthetic memory card B\""))
        assertFalse("diagnostic collection created a missing storage folder", missing.exists())
        assertFalse(exported.contains("label=\"Synthetic memory card A\""))
    }

    @Test
    fun exportingLogsDoesNotWaitForABlockedStorageInventory() {
        DiagLog.attach(context)
        await("baseline hardware sample", 10_000) {
            HardwareDiagnostics.dump().contains("hardwareCollection=idle sampleMatchesSelection=true")
        }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocked = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSystemService(name: String): Any? {
                if (name == Context.STORAGE_SERVICE) {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS)) { "test storage inventory timed out" }
                }
                return super.getSystemService(name)
            }
        }
        try {
            HardwareDiagnostics.request(blocked, force = true)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val exported = AtomicReference<String>()
            val reader = thread { exported.set(DiagLog.dump()) }
            reader.join(1_000)
            assertFalse("log export blocked on USB inventory", reader.isAlive)
            assertTrue(exported.get().contains("hardwareCollection=in-progress"))
            assertTrue(exported.get().contains("ramTotal="))
        } finally {
            release.countDown()
        }
        await("inventory released", 10_000) { HardwareDiagnostics.dump().contains("hardwareCollection=idle") }
    }

    @Test
    fun aVideoRequestWhileAdmissionIsClosedReturnsRetryable503() {
        val entry = register("not-admitted")
        val local = LocalHttpServer(context, "127.0.0.1", 0)
        try {
            local.startServer()
            assertNull(DownloadEngine.pauseForShutdown())
            val untouched = File(root, "closed-admission-must-not-probe")
            Prefs.setCacheDirPath(context, untouched.absolutePath)
            OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()
                .newCall(Request.Builder().url("http://127.0.0.1:${local.listeningPort}/video/${entry.cacheKey}").build())
                .execute().use { response ->
                    assertEquals(503, response.code)
                    assertEquals("2", response.header("Retry-After"))
                }
            assertFalse(DownloadEngine.isBusy())
            assertFalse("closed admission touched the drive", untouched.exists())
            assertEquals(0, server.requestCount)
        } finally {
            local.stop()
            DownloadEngine.resumeAfterShutdown()
        }
    }

    @Test
    fun shutdownCancelsAStalledGetAndFencesReplacementWriters() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setBody(Buffer().write(ByteArray(bodySize)))
                    .setSocketPolicy(SocketPolicy.NO_RESPONSE)
        }
        val entry = register("headers")
        entry.totalBytes = 0 // Previously entered the separate, unowned HEAD/probe path.
        DownloadEngine.ensureStarted(context, entry.cacheKey)
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("GET", request.method)
        assertEquals("LocalCache/${BuildConfig.VERSION_NAME}", request.getHeader("User-Agent"))
        val tail = DownloadEngine.pauseForShutdown()
        val replacement = register("b")
        DownloadEngine.ensureStarted(context, replacement.cacheKey)
        runBlocking { withTimeout(5_000) { tail?.join() } }
        assertFalse(DownloadEngine.isBusy())
        assertFalse(File(entry.filePath!!).exists())
        assertFalse(File(replacement.filePath!!).exists())
        assertEquals(1, server.requestCount)
        assertEquals("paused", entry.status)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setBody(Buffer().write(ByteArray(bodySize) { 3 }))
        }
        DownloadEngine.resumeAfterShutdown()
        DownloadEngine.ensureStarted(context, replacement.cacheKey)
        await("restart after closed writer", 15_000) { replacement.status == "complete" && !DownloadEngine.isBusy() }
        assertArrayEquals(ByteArray(bodySize) { 3 }, File(replacement.filePath!!).readBytes())
    }

    @Test
    fun aShortSuccessfulRangeKeepsThePartialInsteadOfPublishingACompleteMovie() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse()
                .setResponseCode(206).setHeader("Content-Range", "bytes 0-${bodySize - 1}/${2L * bodySize}")
                .setBody(Buffer().write(ByteArray(bodySize) { 4 }))
        }
        val entry = register("short")
        DownloadEngine.ensureStarted(context, entry.cacheKey)
        await("short range rejected", 15_000) { !DownloadEngine.isBusy() }
        assertEquals("error", entry.status)
        assertTrue(entry.lastError!!.contains("Incomplete download"))
        assertFalse(File(entry.filePath!!).exists())
        assertEquals(bodySize.toLong(), CachePaths.partFile(File(entry.filePath!!)).length())
    }

    @Test
    fun aWrongResumeOffsetDoesNotAppendOrTruncateTheExistingPartial() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse()
                .setResponseCode(206).setHeader("Content-Range", "bytes 0-${bodySize - 1}/$bodySize")
                .setBody(Buffer().write(ByteArray(bodySize) { 4 }))
        }
        val entry = register("wrong-offset")
        val part = CachePaths.partFile(File(entry.filePath!!))
        val sentinel = ByteArray(1024 * 1024) { 7 }
        part.writeBytes(sentinel)
        DownloadEngine.ensureStarted(context, entry.cacheKey)
        await("wrong offset rejected", 15_000) { !DownloadEngine.isBusy() }
        assertEquals("error", entry.status)
        assertFalse(File(entry.filePath!!).exists())
        assertArrayEquals(sentinel, part.readBytes())
        assertEquals("bytes=${sentinel.size}-", server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Range"))
    }

    @Test
    fun concurrentDiagnosticsAreBoundedRedactedAndPersistAwayFromPayload() {
        DiagLog.setEnabled(context, true)
        val workers = (0 until 4).map { worker ->
            thread {
                repeat(350) { index ->
                    DiagLog.t("worker=$worker event=$index https://user:password@tracker.test/PASSKEY/announce?token=SECRET")
                }
            }
        }
        workers.forEach { it.join(5_000); assertFalse("logging caller blocked on IO", it.isAlive) }
        val dump = DiagLog.dump()
        val versionIdentity = "appVersion=${BuildConfig.VERSION_NAME} versionCode=${BuildConfig.VERSION_CODE}"
        assertTrue(dump.contains(versionIdentity))
        assertFalse(dump.contains("PASSKEY"))
        assertFalse(dump.contains("SECRET"))
        assertFalse(dump.contains("password"))
        assertTrue(dump.contains("tracker.test"))
        assertTrue(dump.lineSequence().count() < 930)
        val persisted = File(context.filesDir, "local-cache-debug.log")
        await("asynchronous diagnostic persistence", 10_000) { persisted.length() > 0 }
        assertTrue(persisted.length() <= 1024 * 1024)
        assertTrue("persisted events have no build version", persisted.readText().contains(versionIdentity))
        assertFalse(File(root, "local-cache-debug.log").exists())
    }

    @Test
    fun wipeKeepsAnOwnedHttpFileAndSelectedDeletionWaitsForClose() {
        val entry = register("a")
        DownloadEngine.ensureStarted(context, entry.cacheKey)
        val part = CachePaths.partFile(File(entry.filePath!!))
        await("first flushed HTTP bytes", 15_000) { part.exists() && entry.downloadedBytes >= 512 * 1024 }
        val wiped = DiskQuota.deleteAll(context)
        assertTrue("wipe deleted a live writer's file", part.exists())
        assertTrue(wiped.failed > 0)
        DownloadEngine.deleteSelected(context, listOf(entry.cacheKey))
        await("selected deletion completed", 15_000) { !DownloadEngine.isBusy() }
        assertFalse(part.exists())
        assertFalse(File(entry.filePath!!).exists())
    }

    @Test
    fun cancellingAndRapidlySwitchingBackKeepsTheReplacementData() {
        val a = register("a")
        val b = register("b")
        DownloadEngine.ensureStarted(context, a.cacheKey)
        await("first HTTP transfer", 15_000) { a.downloadedBytes >= 512 * 1024 }
        DownloadEngine.cancelActive(deletePartial = true)
        DownloadEngine.ensureStarted(context, b.cacheKey)
        DownloadEngine.ensureStarted(context, a.cacheKey)
        await("replacement HTTP transfer", 20_000) { a.status == "complete" && !DownloadEngine.isBusy() }
        assertArrayEquals(ByteArray(bodySize) { 2 }, File(a.filePath!!).readBytes())
        synchronized(replacementRange) {
            assertTrue("replacement never requested", replacementRange.isNotEmpty())
            assertTrue("cancelled bytes were reused", replacementRange.all { it == null })
        }
    }

    @Test
    fun anAlreadyFinalizedTorrentPlaysWithNewTorrentDownloadsDisabled() {
        Prefs.setAllowTorrents(context, false)
        val key = "cached-torrent-${UUID.randomUUID()}"
        val url = "magnet:?xt=urn:btih:" + "c".repeat(40)
        val final = CachePaths.finalFile(context, key, url)!!.apply { writeBytes(ByteArray(bodySize) { 2 }) }
        CacheRegistry.registerLocal(LocalLibrary.Item(
            cacheKey = key, url = url, rawName = "synthetic finalized torrent", type = "movie", id = "test",
            source = "test", file = final, complete = true, downloadedBytes = bodySize.toLong(),
            totalBytes = bodySize.toLong(), isTorrent = true,
        ))
        DownloadEngine.ensureStarted(context, key)
        assertEquals("complete", CacheRegistry.get(key)!!.status)
        assertFalse(DownloadEngine.isBusy())
        assertEquals(bodySize.toLong(), final.length())
    }

    private fun register(name: String): CacheEntry {
        val key = "test-$name-${UUID.randomUUID()}"
        val url = server.url("/$name.mkv").toString()
        val final = CachePaths.finalFile(context, key, url)!!
        CacheRegistry.registerLocal(LocalLibrary.Item(
            cacheKey = key, url = url, rawName = "synthetic HTTP", type = "movie", id = "test", source = "test",
            file = final, complete = false, downloadedBytes = 0, totalBytes = bodySize.toLong(),
        ))
        return CacheRegistry.get(key)!!
    }

    private fun await(message: String, timeoutMs: Long, done: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!done()) {
            check(System.nanoTime() < deadline) { "Timed out: $message" }
            Thread.sleep(10)
        }
    }
}
