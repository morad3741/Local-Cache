package app.localcache.storage

import android.content.Context
import android.util.Log
import app.localcache.Prefs
import android.system.Os
import app.localcache.DiagLog
import app.localcache.torrent.TorrentEngine
import java.io.File
import java.util.Locale

/**
 * Keeps the cache folder under a fixed size on the USB stick (default 120 GB of a 128 GB
 * drive) by deleting the least recently used movies to make room for a new one.
 */
object DiskQuota {
    private const val TAG = "DiskQuota"
    private const val GB = 1024L * 1024 * 1024

    /** Never delete something being watched or downloaded right now. */
    private const val PROTECT_RECENT_MS = 15 * 60 * 1000L

    /** Never let the cache grow into the last few GB of the drive. */
    private const val DRIVE_HEADROOM = 5L * GB

    fun quotaBytes(context: Context): Long = Prefs.cacheMaxGb(context) * GB

    fun capacityBytes(context: Context): Long {
        val root = CachePaths.cacheRoot(context) ?: return 0
        return runCatching { root.totalSpace }.getOrDefault(0L)
    }

    /**
     * A configured limit larger than the stick can never be reached, so eviction would
     * never run and downloads would fail on a physically full drive instead of making
     * room. Clamp it to what the drive actually holds.
     */
    fun effectiveQuotaBytes(context: Context): Long {
        val configured = quotaBytes(context)
        val capacity = capacityBytes(context)
        if (capacity <= 0) return configured
        return minOf(configured, (capacity - DRIVE_HEADROOM).coerceAtLeast(GB))
    }

    /**
     * A zero here used to be ambiguous: no files, no USB selected, and an unreadable
     * folder all looked identical on screen. [problem] says which one it is.
     */
    data class Usage(val bytes: Long, val files: Int, val problem: String?)

    fun usage(context: Context): Usage {
        val path = Prefs.cacheDirPath(context)
            ?: return Usage(0, 0, "no cache folder chosen yet")

        val root = File(path)
        if (!root.exists()) return Usage(0, 0, "folder missing — USB unplugged or reformatted")

        val entries = root.listFiles()
            ?: return Usage(0, 0, "folder cannot be read")

        val movies = entries.filter { it.isFile && !it.name.endsWith(".meta.json") }
        return Usage(movies.sumOf { it.length() }, movies.size, null)
    }

    fun usageBytes(context: Context): Long = usage(context).bytes

    /** Physical space left on the stick — the drive can be smaller than the quota. */
    fun freeSpaceOnDrive(context: Context): Long {
        val root = CachePaths.cacheRoot(context) ?: return 0
        return runCatching { root.usableSpace }.getOrDefault(0L)
    }

    /** st_blocks is measured in 512-byte units, independently of filesystem block size. */
    fun allocatedBytes(file: File): Long = if (!file.exists()) 0L else runCatching {
        val blocks = Os.stat(file.absolutePath).st_blocks
        if (blocks < 0 || blocks > Long.MAX_VALUE / 512) 0L else minOf(file.length(), blocks * 512)
    }.getOrDefault(0L) // Unknown allocation must reserve the full file, never trust sparse length.

    data class Result(
        val freedBytes: Long,
        val usageBytes: Long,
        val roomBytes: Long,
        val enough: Boolean,
    )

    /**
     * Delete oldest cached movies until [requiredBytes] will fit inside the quota.
     * Files belonging to [keepKeys], to an active download, or watched in the last
     * 15 minutes are left alone.
     */
    fun ensureSpace(
        context: Context,
        requiredBytes: Long,
        keepKeys: Set<String>,
        requiredDiskBytes: Long = requiredBytes,
    ): Result {
        val root = CachePaths.cacheRoot(context) ?: return Result(0, 0, 0, false)
        val quota = effectiveQuotaBytes(context)
        var usage = usageBytes(context)
        var free = freeSpaceOnDrive(context)
        var freed = 0L

        // An impossible request must not wipe every idle movie before being rejected.
        if (requiredBytes > quota) return Result(0, usage, StorageBudget.room(quota, usage, free, DRIVE_HEADROOM), false)

        // Free space matters as much as the quota: the drive can be full while usage is
        // still under the limit, and then no amount of quota headroom helps.
        if (!StorageBudget.fits(quota, usage, requiredBytes, free, requiredDiskBytes, DRIVE_HEADROOM)) {
            val protectedPaths = protectedPaths(keepKeys)
            val candidates = root.listFiles()
                ?.filter {
                    it.isFile &&
                        it.absolutePath !in protectedPaths &&
                        !it.name.endsWith(".meta.json")
                }
                ?.sortedBy { it.lastModified() }
                ?: emptyList()

            for (file in candidates) {
                if (StorageBudget.fits(quota, usage, requiredBytes, free, requiredDiskBytes, DRIVE_HEADROOM)) break
                val size = file.length()
                val name = file.name
                if (DownloadEngine.deleteIdleFile(file) {
                        LocalLibrary.deleteMeta(File(file.absolutePath.removeSuffix(".part")))
                        forgetByPath(file.absolutePath)
                    }) {
                    usage = usageBytes(context)
                    val measuredFree = freeSpaceOnDrive(context)
                    freed += (measuredFree - free).coerceAtLeast(0)
                    free = measuredFree
                    Log.i(TAG, "removed $name (${size / (1024 * 1024)} MB) to make room")
                }
            }
        }

        val room = StorageBudget.room(quota, usage, free, DRIVE_HEADROOM)
        if (freed > 0) {
            Log.i(TAG, "freed ${freed / (1024 * 1024)} MB — now ${usage / GB} / ${quota / GB} GB")
        }
        val enough = StorageBudget.fits(quota, usage, requiredBytes, free, requiredDiskBytes, DRIVE_HEADROOM)
        DiagLog.t("space quota=$quota logical=$usage free=$free headroom=$DRIVE_HEADROOM " +
            "quotaGrowth=$requiredBytes diskGrowth=$requiredDiskBytes freed=$freed enough=$enough")
        return Result(freed, usage, room, enough)
    }

    /** Trim back under quota, e.g. when the server starts. */
    fun trim(context: Context): Result = ensureSpace(context, 0, emptySet())

    data class Wipe(val deleted: Int, val freedBytes: Long, val failed: Int)

    /** Empties the cache folder completely, including partial downloads and sidecars. */
    fun deleteAll(context: Context): Wipe {
        val root = CachePaths.cacheRoot(context) ?: return Wipe(0, 0, 0)
        val files = root.listFiles() ?: return Wipe(0, 0, 0)

        var deleted = 0
        var freed = 0L
        var failed = 0
        val pending = DownloadEngine.pendingPaths()

        files.filter { it.isFile }.forEach { file ->
            if (file.absolutePath in pending || TorrentEngine.storageReleaseError() != null) {
                failed++
                return@forEach
            }
            val size = file.length()
            if (DownloadEngine.deleteIdleFile(file) {
                    if (!file.name.endsWith(".meta.json")) forgetByPath(file.absolutePath)
                }) {
                if (!file.name.endsWith(".meta.json")) {
                    deleted++
                    freed += size
                }
            } else {
                failed++
            }
        }

        Log.i(TAG, "cache wiped: $deleted file(s), ${freed / (1024 * 1024)} MB")
        return Wipe(deleted, freed, failed)
    }

    private fun protectedPaths(keepKeys: Set<String>): Set<String> {
        val now = System.currentTimeMillis()
        val paths = DownloadEngine.pendingPaths().toMutableSet()
        if (TorrentEngine.storageReleaseError() != null) {
            CacheRegistry.all().forEach { entry ->
                entry.filePath?.let { paths.add(it); paths.add("$it.part") }
            }
        }
        CacheRegistry.all().forEach { entry ->
            val busy = entry.status == "downloading" || entry.cacheKey in keepKeys
            val recent = now - entry.lastAccessMs < PROTECT_RECENT_MS
            if (busy || recent) {
                entry.filePath?.let {
                    paths.add(it)
                    paths.add("$it.part")
                }
            }
        }
        return paths
    }

    private fun forgetByPath(path: String) {
        val base = path.removeSuffix(".part")
        CacheRegistry.all()
            .filter { it.filePath == base }
            .forEach { CacheRegistry.markEvicted(it.cacheKey) }
    }

    fun summary(context: Context): String {
        val quota = effectiveQuotaBytes(context)
        val usage = usage(context)
        val line = "%.1f / %d GB used, %d file%s".format(
            Locale.US,
            usage.bytes.toDouble() / GB,
            quota / GB,
            usage.files,
            if (usage.files == 1) "" else "s",
        )
        return usage.problem?.let { "$line — $it" } ?: line
    }

    fun asJson(context: Context): Map<String, Any> {
        val usage = usage(context)
        return mapOf(
            "limitGb" to Prefs.cacheMaxGb(context),
            "effectiveLimitBytes" to effectiveQuotaBytes(context),
            "driveCapacityBytes" to capacityBytes(context),
            "usedBytes" to usage.bytes,
            "files" to usage.files,
            "problem" to (usage.problem ?: ""),
            "path" to (Prefs.cacheDirPath(context) ?: ""),
            "freeOnDriveBytes" to freeSpaceOnDrive(context),
        )
    }
}
