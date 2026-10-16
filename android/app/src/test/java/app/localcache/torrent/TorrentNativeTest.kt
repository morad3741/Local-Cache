package app.localcache.torrent

import app.localcache.DiagLog
import app.localcache.storage.CacheEntry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.experimental.categories.Category
import org.libtorrent4j.SessionManager
import org.libtorrent4j.Address
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.swig.settings_pack
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Opt-in Linux JNI integration, using the production TorrentEngine and synthetic content. */
interface NativeIntegration

@Category(NativeIntegration::class)
class TorrentNativeTest {
    private val root = Files.createTempDirectory("torrent-native-").toFile()
    private val cache = File(root, "cache").apply { mkdir() }
    private val part = File(cache, "selected.mkv.part")
    private val final = File(cache, "selected.mkv")
    private val cancelled = AtomicBoolean()
    private val executor = Executors.newSingleThreadExecutor()
    private var seeder: SessionManager? = null
    private var seedHandle: TorrentHandle? = null
    private val movie = ByteArray(4 * 1024 * 1024 + 101).also { Random(42).nextBytes(it) }
    private val sentinel = "keep-original-path".toByteArray()

    @After
    fun close() {
        cancelled.set(true)
        executor.shutdown()
        val stopped = executor.awaitTermination(90, TimeUnit.SECONDS)
        TorrentEngine.shutdown()
        seeder?.stop()
        // Never delete the test directory while the tested worker might still own it.
        if (stopped) root.deleteRecursively()
        assertTrue("download worker did not stop", stopped)
    }

    @Test
    fun metadataApprovalGatesPayloadAndMapsANonzeroOffsetFile() {
        val entry = seededEntry()
        val original = File(cache, "fixture/movie.mkv").apply { parentFile!!.mkdirs(); writeBytes(sentinel) }
        val ready = CountDownLatch(1)
        val approve = CountDownLatch(1)
        val transfer = download(entry) {
            ready.countDown()
            check(approve.await(15, TimeUnit.SECONDS)) { "test approval was not released" }
            true
        }
        try {
            assertTrue("metadata did not arrive", ready.await(30, TimeUnit.SECONDS))
            // Observe a live seeder while setup is deliberately blocked, not a fake handle.
            Thread.sleep(500)
            assertEquals(0L, seedHandle!!.status(true).totalPayloadUpload())
            assertFalse("payload path appeared before approval", part.exists())
            assertArrayEquals(sentinel, original.readBytes())
            assertFalse("diagnostics must be off for this regression", DiagLog.enabled)
        } finally {
            approve.countDown()
        }
        val result = transfer.get(60, TimeUnit.SECONDS)
        assertTrue(result.error, result.ok)
        assertArrayEquals(movie, final.readBytes())
        assertArrayEquals(sentinel, original.readBytes())
        assertEquals(movie.size.toLong(), entry.downloadedBytes)
        assertFalse(part.exists())
    }

    @Test
    fun existingLogicalLengthIsRehashedAndCorruptBytesAreReplaced() {
        val entry = seededEntry()
        RandomAccessFile(part, "rw").use {
            it.write(sentinel)
            it.setLength(movie.size.toLong())
        }
        val transfer = download(entry) { true }
        val result = transfer.get(60, TimeUnit.SECONDS)
        assertTrue(result.error, result.ok)
        assertArrayEquals(movie, final.readBytes())
    }

    @Test
    fun collidingFinalAndPartialPathsKeepBothSentinels() {
        val entry = entry("magnet:?xt=urn:btih:" + "a".repeat(40))
        part.writeBytes(sentinel)
        val existingFinal = "keep-final-path".toByteArray()
        final.writeBytes(existingFinal)
        val result = download(entry) { fail("storage collision must fail before metadata approval"); false }
            .get(10, TimeUnit.SECONDS)
        assertNull("collision must be tested with a working native engine", TorrentEngine.nativeError())
        assertFalse(result.ok)
        assertNotEquals("complete", entry.status)
        assertArrayEquals(sentinel, part.readBytes())
        assertArrayEquals(existingFinal, final.readBytes())
    }

    @Test
    fun cancellationDuringSetupNeverStartsPayload() {
        val entry = seededEntry()
        val result = download(entry) { cancelled.set(true); true }.get(30, TimeUnit.SECONDS)
        assertFalse(result.ok)
        assertFalse(part.exists())
        assertFalse(final.exists())
        assertEquals(0L, seedHandle!!.status(true).totalPayloadUpload())
    }

    @Test
    fun cancellationDuringMetadataLeavesNoLateWriter() {
        val entry = entry("magnet:?xt=urn:btih:" + "b".repeat(40))
        val transfer = download(entry) { fail("synthetic dead magnet unexpectedly resolved"); false }
        await("metadata search", 10_000) { TorrentEngine.statusDetail()?.contains("peers") == true }
        cancelled.set(true)
        assertFalse(transfer.get(10, TimeUnit.SECONDS).ok)
        assertNoLateWrites()
    }

    @Test
    fun cancellationDuringPayloadReleasesTheMappedFileBeforeReturn() {
        val entry = seededEntry(uploadLimit = 64 * 1024)
        val transfer = download(entry) { true }
        await("first verified payload", 30_000) { entry.verifiedBytes > 0 }
        assertFalse("fixture finished before cancellation", transfer.isDone)
        cancelled.set(true)
        assertFalse(transfer.get(15, TimeUnit.SECONDS).ok)
        assertFalse(final.exists())
        assertNoLateWrites()
    }

    @Test
    fun aCleanSessionShutdownDoesNotPoisonTheNextAttempt() {
        val entry = seededEntry(uploadLimit = 64 * 1024)
        val transfer = download(entry) { true }
        await("first verified payload", 30_000) { entry.verifiedBytes > 0 }
        assertFalse("fixture finished before shutdown", transfer.isDone)
        TorrentEngine.shutdown()
        assertFalse(transfer.get(10, TimeUnit.SECONDS).ok)
        assertNull(TorrentEngine.storageReleaseError())
        seeder!!.applySettings(SettingsPack().uploadRateLimit(1024 * 1024))
        val retried = download(entry) { true }.get(60, TimeUnit.SECONDS)
        assertTrue(retried.error, retried.ok)
        assertArrayEquals(movie, final.readBytes())
    }

    @Test
    fun repeatedCancellationAndAlertReuseKeepStorageBarriersMatched() {
        val entry = seededEntry(uploadLimit = 64 * 1024)
        repeat(3) {
            cancelled.set(false)
            val transfer = download(entry) { true }
            await("payload on retry $it", 30_000) { entry.verifiedBytes > 0 && !transfer.isDone }
            cancelled.set(true)
            assertFalse(transfer.get(20, TimeUnit.SECONDS).ok)
            // Native alert memory has been recycled by now. Only callback-copied state is safe.
            System.gc()
            assertNull(TorrentEngine.storageReleaseError())
            assertNoLateWrites()
            assertTrue(part.delete())
        }
    }

    private fun assertNoLateWrites() {
        part.writeBytes(sentinel)
        Thread.sleep(500)
        assertArrayEquals("old native worker wrote into a replacement file", sentinel, part.readBytes())
    }

    private fun download(entry: CacheEntry, approve: (Long) -> Boolean): Future<TorrentEngine.Result> =
        executor.submit<TorrentEngine.Result> {
            TorrentEngine.download(entry, part, final, { cancelled.get() }, approve)
        }

    private fun entry(magnet: String) = CacheEntry(
        cacheKey = "synthetic", url = magnet, label = "synthetic.mkv", type = "movie", id = "test",
        source = "local test seeder", status = "downloading", isTorrent = true, fileIndex = 1,
    )

    private fun seededEntry(uploadLimit: Int = 1024 * 1024): CacheEntry {
        val before = ByteArray(19) { 3 }
        val after = ByteArray(37) { 7 }
        val all = before + movie + after
        val pieceLength = 16 * 1024
        val hashes = ByteArrayOutputStream()
        for (offset in all.indices step pieceLength) {
            hashes.write(MessageDigest.getInstance("SHA-1").digest(all.copyOfRange(offset, minOf(all.size, offset + pieceLength))))
        }
        val info = mapOf(
            // Private torrents disable libtorrent's ut_metadata extension. A magnet
            // fixture must permit metadata exchange; discovery is via the loopback x.pe.
            "name" to "fixture", "piece length" to pieceLength, "pieces" to hashes.toByteArray(), "private" to 0,
            "files" to listOf(
                mapOf("length" to before.size, "path" to listOf("before.bin")),
                mapOf("length" to movie.size, "path" to listOf("movie.mkv")),
                mapOf("length" to after.size, "path" to listOf("after.bin")),
            ),
        )
        val ti = TorrentInfo(bencode(mapOf("info" to info)))
        val seedDir = File(root, "seed").apply { mkdir() }
        File(seedDir, "fixture").mkdir()
        File(seedDir, "fixture/before.bin").writeBytes(before)
        File(seedDir, "fixture/movie.mkv").writeBytes(movie)
        File(seedDir, "fixture/after.bin").writeBytes(after)
        val manager = SessionManager()
        seeder = manager
        val settings = SettingsPack().apply {
            listenInterfaces("127.0.0.1:0")
            setEnableDht(false)
            setEnableLsd(false)
            uploadRateLimit(uploadLimit)
            setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), false)
            setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), false)
        }
        val params = SessionParams(settings).apply { setPosixDiskIO() }
        manager.start(params)
        // Local peers otherwise use the unlimited local class, bypassing uploadRateLimit.
        // Assign loopback to global class 0 (mask 1) before any peer can connect.
        val classes = manager.swig().get_peer_class_filter()
        val loopback = Address.parseIp("127.0.0.1")
        classes.add_rule(loopback.swig(), loopback.swig(), 1L)
        manager.swig().set_peer_class_filter(classes)
        classes.delete()
        manager.download(ti, seedDir)
        await("local seeder ready", 10_000) {
            seedHandle = manager.find(ti.infoHash())
            seedHandle?.status(true)?.isSeeding == true && manager.swig().listen_port() > 0
        }
        return entry("magnet:?xt=urn:btih:${ti.infoHash().toHex()}&x.pe=127.0.0.1:${manager.swig().listen_port()}")
    }

    private fun await(message: String, timeoutMs: Long, done: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!done()) {
            check(System.nanoTime() < deadline) { "Timed out: $message" }
            Thread.sleep(25)
        }
    }

    private fun bencode(value: Any): ByteArray {
        val out = ByteArrayOutputStream()
        fun write(v: Any) {
            when (v) {
                is String -> write(v.toByteArray(Charsets.UTF_8))
                is ByteArray -> { out.write("${v.size}:".toByteArray()); out.write(v) }
                is Number -> out.write("i${v.toLong()}e".toByteArray())
                is List<*> -> { out.write('l'.code); v.forEach { write(it!!) }; out.write('e'.code) }
                is Map<*, *> -> {
                    out.write('d'.code)
                    v.entries.sortedBy { it.key as String }.forEach { write(it.key!!); write(it.value!!) }
                    out.write('e'.code)
                }
                else -> error("Unsupported fixture value")
            }
        }
        write(value)
        return out.toByteArray()
    }
}
