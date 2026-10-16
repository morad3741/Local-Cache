package app.localcache

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory + on-disk log the viewer can copy from `/logs` on their phone.
 *
 * Off by default. When the settings toggle is on, torrent/download/playback events
 * are kept so a stuck 0% download can be diagnosed without adb.
 */
object DiagLog {
    private const val TAG = "LocalCacheDiag"
    private const val MAX_LINES = 900
    private const val MAX_FILE_BYTES = 1024 * 1024L

    @Volatile
    var enabled: Boolean = false
        private set

    private val lines = ArrayDeque<String>(MAX_LINES)
    private val lock = Any()
    @Volatile private var file: File? = null
    private var header: String = ""
    private val pending = ArrayBlockingQueue<Pair<Long, String>>(256)
    private val generation = AtomicLong()
    private val dropped = AtomicLong()
    private val writeFailures = AtomicLong()
    private var writtenGeneration = 0L // Writer thread only.
    @Volatile private var previousSession = ""
    private var loadedPath: String? = null // Writer thread only.
    private val writer = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "cache-diagnostic-writer").apply { isDaemon = true }
    }.apply { scheduleWithFixedDelay({ persist() }, 1, 1, TimeUnit.SECONDS) }

    fun attach(context: Context) {
        enabled = Prefs.debugLog(context)
        DeviceDiagnostics.attach(context)
        header = buildString {
            append("v").append(BuildConfig.VERSION_NAME)
            append(" sdk=").append(Build.VERSION.SDK_INT)
            append(" ").append(Build.MANUFACTURER)
            append(" ").append(Build.MODEL)
        }
        // Never compete with payload IO or block the alert/UI thread on an unhealthy USB.
        file = File(context.filesDir, "local-cache-debug.log")
        if (enabled) i("debug log attached $header ${DeviceDiagnostics.snapshot()}")
    }

    fun setEnabled(context: Context, on: Boolean) {
        val was = enabled
        Prefs.setDebugLog(context, on)
        if (on && !was) {
            clear()
            enabled = true
            attach(context)
            i("debug log turned ON")
        } else if (!on && was) {
            i("debug log turned OFF")
            enabled = false
        } else {
            enabled = on
            if (on) attach(context)
        }
    }

    fun i(msg: String) = add("I", msg)

    fun w(msg: String) = add("W", msg)

    fun e(msg: String) = add("E", msg)

    /** Torrent/engine detail — stored only when the settings toggle is on. */
    fun t(msg: String) {
        if (enabled) add("T", msg)
    }

    fun dump(): String = synchronized(lock) {
        buildString {
            appendLine("Local Cache diagnostic log")
            appendLine(header)
            appendLine("enabled=$enabled lines=${lines.size} queued=${pending.size} dropped=${dropped.get()} writeFailures=${writeFailures.get()}")
            file?.let { appendLine("file=${it.absolutePath}") }
            appendLine("---")
            if (previousSession.isNotEmpty()) {
                appendLine("Previous process (persisted tail):")
                appendLine(previousSession)
                appendLine("Current process:")
            }
            lines.forEach { appendLine(it) }
        }
    }

    fun clear() {
        synchronized(lock) { lines.clear() }
        generation.incrementAndGet()
        pending.clear()
        previousSession = ""
    }

    private fun add(level: String, msg: String) {
        val safe = DiagnosticText.sanitize(msg)
        Log.i(TAG, safe)
        if (!enabled) return
        val line = "${ts()} monoMs=${System.nanoTime() / 1_000_000L} thread=${Thread.currentThread().name} $level $safe"
        synchronized(lock) {
            if (lines.size >= MAX_LINES) lines.removeFirst()
            lines.addLast(line)
        }
        if (!pending.offer(generation.get() to line)) dropped.incrementAndGet()
    }

    private fun persist() {
        val dest = file ?: return
        runCatching {
            val backup = File(dest.parentFile, "${dest.name}.previous")
            if (loadedPath != dest.absolutePath) {
                // Only the background writer reads old logs; keep the export memory bounded.
                previousSession = listOf(backup, dest).filter { it.isFile && it.length() <= MAX_FILE_BYTES + 128 * 4096 }
                    .joinToString("\n") { it.readText().takeLast(128 * 1024) }.takeLast(256 * 1024)
                loadedPath = dest.absolutePath
            }
            val currentGeneration = generation.get()
            if (writtenGeneration != currentGeneration) {
                dest.writeText("")
                backup.delete()
                writtenGeneration = currentGeneration
            }
            val batch = ArrayList<Pair<Long, String>>(128)
            pending.drainTo(batch, 128)
            val text = batch.filter { it.first == currentGeneration }.joinToString("") { it.second + "\n" }
            if (text.isNotEmpty()) {
                if (dest.length() + text.toByteArray(Charsets.UTF_8).size > MAX_FILE_BYTES) {
                    check(!backup.exists() || backup.delete()) { "Cannot rotate diagnostic backup" }
                    check(!dest.exists() || dest.renameTo(backup)) { "Cannot rotate diagnostic log" }
                }
                dest.appendText(text)
            }
        }.onFailure { writeFailures.incrementAndGet() }
    }

    private fun ts(): String {
        val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        fmt.timeZone = TimeZone.getDefault()
        return fmt.format(Date())
    }
}
