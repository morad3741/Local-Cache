package app.localcache.storage

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.localcache.Prefs
import app.localcache.DiagLog
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

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
        server.shutdown()
        root.deleteRecursively() // Owned test directory only, after the real worker is done.
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
        assertFalse(dump.contains("PASSKEY"))
        assertFalse(dump.contains("SECRET"))
        assertFalse(dump.contains("password"))
        assertTrue(dump.contains("tracker.test"))
        assertTrue(dump.lineSequence().count() < 930)
        val persisted = File(context.filesDir, "local-cache-debug.log")
        await("asynchronous diagnostic persistence", 10_000) { persisted.length() > 0 }
        assertTrue(persisted.length() <= 1024 * 1024)
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
