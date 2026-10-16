package app.localcache

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.SystemClock
import android.os.storage.StorageManager
import app.localcache.storage.UsbDriveDetector
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Read-only hardware inventory. A slow/missing USB never blocks a log export or caller. */
object HardwareDiagnostics {
    private const val REFRESH_MS = 60_000L
    private const val MAX_USB_DEVICES = 8
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "LocalCache-hardware-info").apply { isDaemon = true }
    }
    private val collecting = AtomicBoolean()
    @Volatile private var latest: Request? = null
    @Volatile private var sample: Sample? = null
    @Volatile private var deviceSnapshot = "deviceResources=pending/unavailable"

    private data class Selection(val mode: String, val path: String?, val root: String?, val label: String?) {
        fun line() = "selectedStorage mode=${field(mode)} path=${field(path)} root=${field(root)} label=${field(label)}"
    }
    private data class Request(val context: Context, val selection: Selection)
    private data class Sample(val selection: Selection, val atMs: Long, val lines: List<String>)

    fun request(context: Context, force: Boolean = false) {
        val ctx = context.applicationContext
        val selection = Selection(Prefs.storageMode(ctx), Prefs.cacheDirPath(ctx), Prefs.usbRootPath(ctx), Prefs.usbLabel(ctx))
        latest = Request(ctx, selection)
        val previous = sample
        if (!force && previous?.selection == selection && SystemClock.elapsedRealtime() - previous.atMs < REFRESH_MS) return
        schedule()
    }

    /** Cached-only export, including the sample's selection and age to avoid stale attribution. */
    fun dump(): String {
        latest?.let { request(it.context) }
        val wanted = latest?.selection
        val captured = sample
        return buildString {
            appendLine(DeviceDiagnostics.identity())
            appendLine(deviceSnapshot)
            appendLine(wanted?.line() ?: "selectedStorage unavailable")
            appendLine("hardwareCollection=${if (collecting.get()) "in-progress" else "idle"} " +
                "sampleMatchesSelection=${captured != null && captured.selection == wanted} " +
                "sampleAgeMs=${captured?.let { SystemClock.elapsedRealtime() - it.atMs } ?: "unavailable"}")
            if (captured == null || captured.selection != wanted) {
                append("storageDetails=pending/unavailable")
            } else {
                captured.lines.forEach { appendLine(it) }
            }
        }
    }

    private fun schedule() {
        if (!collecting.compareAndSet(false, true)) return
        worker.execute {
            val requested = latest
            try {
                if (requested != null) {
                    deviceSnapshot = DiagnosticText.sanitize(DeviceDiagnostics.snapshot())
                    val lines = runCatching { collect(requested) }.getOrElse {
                        listOf("hardwareCollectionError=${it.javaClass.simpleName} message=${field(it.message)}")
                    }.map { DiagnosticText.sanitize(it) }
                    // A delayed answer from the previous drive must not describe the new selection.
                    if (latest?.selection == requested.selection) {
                        sample = Sample(requested.selection, SystemClock.elapsedRealtime(), lines)
                        if (DiagLog.enabled) {
                            DiagLog.t("hardware ${requested.selection.line()}")
                            DiagLog.t("hardware ${DeviceDiagnostics.identity()}")
                            DiagLog.t("hardware $deviceSnapshot")
                            lines.forEach { DiagLog.t("hardware $it") }
                        }
                    }
                }
            } finally {
                collecting.set(false)
                // Coalesce selection changes into one next task, never an unbounded queue.
                if (latest?.selection != requested?.selection) schedule()
            }
        }
    }

    private fun collect(request: Request): List<String> = buildList {
        val path = request.selection.path
        if (path == null) {
            add("storageDetails=no-selection")
        } else {
            val root = File(path)
            runCatching {
                add("storagePath exists=${root.exists()} directory=${root.isDirectory} readable=${root.canRead()} writable=${root.canWrite()}")
                add("storageSpace totalBytes=${root.totalSpace} freeBytes=${root.freeSpace} usableBytes=${root.usableSpace} " +
                    "cacheLimitGb=${Prefs.cacheMaxGb(request.context)}")
                val mount = UsbDriveDetector.mountInfo(path)
                add("storageMount point=${field(mount?.mountPoint)} fsType=${field(mount?.fsType)} " +
                    "readOnly=${mount?.readOnly ?: "unavailable"} filesystemView=android-visible-mount")
            }.onFailure { add("storageDetails unavailable reason=${it.javaClass.simpleName}") }
            // Android may expose FUSE rather than the underlying exFAT/NTFS filesystem.
            runCatching {
                val volume = request.context.getSystemService(StorageManager::class.java)?.getStorageVolume(root)
                if (volume == null) {
                    add("storageVolume unavailable")
                } else {
                    add("storageVolume description=${field(volume.getDescription(request.context))} uuid=${field(volume.uuid)} " +
                        "state=${field(volume.state)} removable=${volume.isRemovable} emulated=${volume.isEmulated} primary=${volume.isPrimary} " +
                        "directory=${field(if (Build.VERSION.SDK_INT >= 30) volume.directory?.absolutePath else null)}")
                }
            }.onFailure { add("storageVolume unavailable reason=${it.javaClass.simpleName}") }
        }
        addAll(usbDevices(request.context))
    }

    private fun usbDevices(context: Context): List<String> = runCatching {
        val manager = context.getSystemService(UsbManager::class.java)
            ?: return listOf("usbMassStorage unavailable reason=no-usb-service")
        val devices = manager.deviceList.values.filter { device ->
            device.deviceClass == UsbConstants.USB_CLASS_MASS_STORAGE ||
                (0 until device.interfaceCount).any { device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE }
        }
        buildList {
            // Public Android APIs cannot correlate a StorageVolume with a UsbDevice reliably.
            add("usbMassStorage count=${devices.size} shown=${minOf(devices.size, MAX_USB_DEVICES)} " +
                "selectedVolumeAssociation=unavailable linkSpeedMbps=unavailable")
            devices.sortedBy { it.deviceName }.take(MAX_USB_DEVICES).forEachIndexed { index, device ->
                add("usbCandidate index=$index vendorId=${String.format(Locale.US, "%04x", device.vendorId)} " +
                    "productId=${String.format(Locale.US, "%04x", device.productId)} " +
                    "manufacturer=${field(runCatching { device.manufacturerName }.getOrNull())} " +
                    "product=${field(runCatching { device.productName }.getOrNull())} " +
                    "deviceRelease=${field(runCatching { device.version }.getOrNull())} " +
                    "busAddress=${field(device.deviceName)} interfaces=${device.interfaceCount}")
            }
        }
    }.getOrElse { listOf("usbMassStorage unavailable reason=${it.javaClass.simpleName}") }

    internal fun field(value: String?): String = value?.takeIf { it.isNotBlank() }?.let {
        "\"${DiagnosticText.sanitize(it.take(160)).replace('"', '\'')}\""
    } ?: "unavailable"
}
