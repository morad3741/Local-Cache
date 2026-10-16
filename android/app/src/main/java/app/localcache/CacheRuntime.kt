package app.localcache

import android.os.PowerManager
import app.localcache.server.ServerManager
import app.localcache.storage.DownloadEngine
import app.localcache.torrent.TorrentEngine
import java.io.IOException
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking

/** Process owner: startup, cancelled writers and native teardown share one ordered lane. */
object CacheRuntime {
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "LocalCache-lifecycle").apply { isDaemon = true }
    }
    private val lifecycle = SerialLifecycle(worker, object : SerialLifecycle.Backend {
        override fun start() = timed("start") {
            val context = LocalCacheApp.instance
            context.awaitStorageReady()
            DiagLog.attach(context)
            DownloadEngine.resumeAfterShutdown()
            ServerManager.start(context)
        }

        override fun stop() = timed("stop") {
            val context = LocalCacheApp.instance
            // Keep cleanup runnable after the service releases its download wake lock.
            val wake = context.getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocalCache::close-storage")
            runCatching { wake?.acquire(120_000) }
            try {
                val tail = DownloadEngine.pauseForShutdown()
                try {
                    ServerManager.stop()
                } finally {
                    timed("join-writers") { runBlocking { tail?.join() } }
                    timed("close-native") { TorrentEngine.shutdown() }
                }
                TorrentEngine.storageReleaseError()?.let { throw IOException(it) }
            } finally {
                runCatching { if (wake?.isHeld == true) wake.release() }
            }
        }
    })

    fun start(): Long = lifecycle.start()
    fun stop(owner: Long) = lifecycle.stop(owner)
    fun snapshot(): SerialLifecycle.Snapshot = lifecycle.snapshot()

    private inline fun timed(stage: String, action: () -> Unit) {
        val started = System.nanoTime()
        DiagLog.i("runtime stage=$stage event=begin")
        try {
            action()
            DiagLog.i("runtime stage=$stage event=end elapsedMs=${(System.nanoTime() - started) / 1_000_000}")
        } catch (e: Exception) {
            DiagLog.e("runtime stage=$stage event=failed elapsedMs=${(System.nanoTime() - started) / 1_000_000} " +
                "exception=${e.javaClass.simpleName} error=${e.message}")
            throw e
        }
    }
}
