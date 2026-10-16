package app.localcache

import android.app.Application
import android.util.Log
import app.localcache.storage.StorageMode
import app.localcache.storage.UsbDriveDetector
import java.io.File
import java.util.concurrent.CountDownLatch

class LocalCacheApp : Application() {
    private val storageReady = CountDownLatch(1)

    override fun onCreate() {
        super.onCreate()
        instance = this
        // A disconnected or slow USB device must not hold Android's application thread.
        Thread({
            try {
                DiagLog.attach(this)
                val started = System.nanoTime()
                DiagLog.i("storage recovery event=begin")
                healCachePath()
                DiagLog.i("storage recovery event=end elapsedMs=${(System.nanoTime() - started) / 1_000_000}")
            } catch (e: Exception) {
                Log.w(TAG, "cache recovery failed", e)
                DiagLog.e("storage recovery event=failed exception=${e.javaClass.simpleName} error=${e.message}")
            } finally {
                storageReady.countDown()
            }
        }, "LocalCache-storage-init").apply { isDaemon = true; start() }
    }

    /** Background callers wait before reading/replacing recovered storage preferences. */
    fun awaitStorageReady() = storageReady.await()

    /**
     * Reattach to the cache folder if the drive came back under a different mount point,
     * otherwise the app reports an empty cache while movies are sitting on the stick.
     */
    private fun healCachePath() {
        if (StorageMode.isInternal(this)) {
            val internal = StorageMode.internalCacheDir(this)
            if (!internal.isDirectory) internal.mkdirs()
            if (Prefs.cacheDirPath(this) != internal.absolutePath) {
                Prefs.setInternalSelection(
                    this,
                    internal.absolutePath,
                    Prefs.cacheMaxGb(this),
                )
            }
            return
        }

        val saved = Prefs.cacheDirPath(this) ?: return
        if (File(saved).isDirectory) return

        val found = UsbDriveDetector.relocateCacheDir(this)
        if (found == null) {
            Log.w(TAG, "cache folder $saved is gone and no USB drive can replace it")
            return
        }

        Prefs.setUsbSelection(this, found.dir.absolutePath, found.label, found.usbRootPath)
        runCatching {
            app.localcache.config.AddonConfig.importFromUsb(
                this,
                File(found.usbRootPath),
                found.dir,
            )
        }
        val how = if (found.reused) "found existing cache" else "adopted drive"
        Log.i(TAG, "$how: $saved -> ${found.dir.absolutePath}")
    }

    companion object {
        private const val TAG = "LocalCacheApp"

        lateinit var instance: LocalCacheApp
            private set
    }
}
