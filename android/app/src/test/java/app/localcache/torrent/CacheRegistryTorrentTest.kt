package app.localcache.torrent

import app.localcache.storage.CacheRegistry
import app.localcache.storage.LocalLibrary
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.UUID

class CacheRegistryTorrentTest {
    @Test
    fun rehydrationDoesNotCompleteCollidingTorrentPaths() = withCache { final ->
        final.writeText("keep final")
        File("${final.path}.part").writeText("keep partial")
        val key = register(final, torrent = true, complete = true)
        CacheRegistry.refreshFromDisk(key)
        assertEquals("error", CacheRegistry.get(key)!!.status)
        assertEquals(0, CacheRegistry.progress(key))
        assertEquals("keep final", final.readText())
        assertEquals("keep partial", File("${final.path}.part").readText())
    }

    @Test
    fun sparseTorrentLengthDoesNotBecomeVerifiedProgress() = withCache { final ->
        RandomAccessFile(File("${final.path}.part"), "rw").use { it.setLength(8L * 1024 * 1024 * 1024) }
        val key = register(final, torrent = true, complete = false)
        CacheRegistry.refreshFromDisk(key)
        assertEquals(0L, CacheRegistry.get(key)!!.downloadedBytes)
        assertEquals(0L, CacheRegistry.get(key)!!.verifiedBytes)
        assertEquals(0, CacheRegistry.progress(key))
    }

    @Test
    fun httpPartLengthStillRestoresProgress() = withCache { final ->
        File("${final.path}.part").writeText("HTTP data")
        val key = register(final, torrent = false, complete = false)
        CacheRegistry.refreshFromDisk(key)
        assertEquals(9L, CacheRegistry.get(key)!!.downloadedBytes)
    }

    @Test
    fun finalizedFileWithoutAPartRemainsPlayable() = withCache { final ->
        final.writeText("finalized")
        val key = register(final, torrent = true, complete = true)
        assertEquals("complete", CacheRegistry.get(key)!!.status)
        assertEquals(100, CacheRegistry.progress(key))
    }

    private fun register(final: File, torrent: Boolean, complete: Boolean): String {
        val key = UUID.randomUUID().toString()
        CacheRegistry.registerLocal(LocalLibrary.Item(
            cacheKey = key, url = "synthetic", rawName = "synthetic", type = "movie", id = "test",
            source = null, file = final, complete = complete, downloadedBytes = 8L * 1024 * 1024 * 1024,
            totalBytes = 8L * 1024 * 1024 * 1024, isTorrent = torrent,
        ))
        return key
    }

    private fun withCache(check: (File) -> Unit) {
        val root = Files.createTempDirectory("cache-registry-test-").toFile()
        try { check(File(root, "movie.mkv")) } finally { root.deleteRecursively() }
    }
}
