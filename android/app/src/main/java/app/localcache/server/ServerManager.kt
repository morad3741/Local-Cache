package app.localcache.server

import android.content.Context
import android.util.Log
import app.localcache.DiagLog
import app.localcache.Prefs
import java.io.IOException

/** One HTTP server for the whole app — picks a free port if needed. */
object ServerManager {
    private const val TAG = "ServerManager"

    @Volatile
    private var server: LocalHttpServer? = null

    data class StartResult(
        val server: LocalHttpServer,
        val port: Int,
        val portNote: String?,
    )

    @Synchronized
    @Throws(IOException::class)
    fun start(context: Context): StartResult {
        server?.takeIf { it.isAlive }?.let { return StartResult(it, it.listeningPort, null) }
        stopQuietly()

        val lanHost = Prefs.lanHost(context)
        DiagLog.attach(context)
        val portsToTry = buildList {
            add(Prefs.serverPort(context))
            addAll(PortFinder.candidates)
        }.distinct()

        var lastError: IOException? = null

        for (port in portsToTry) {
            var instance: LocalHttpServer? = null
            try {
                instance = LocalHttpServer(context.applicationContext, lanHost, port)
                instance.startServer()
                server = instance
                Prefs.setServerPort(context, port)

                val note = if (port != PortFinder.candidates.first()) {
                    "Using port $port (${app.localcache.AppVariant.defaultPort} not available on this TV)"
                } else {
                    null
                }

                Log.i(TAG, "Started on http://$lanHost:$port")
                return StartResult(instance, port, note)
            } catch (e: IOException) {
                lastError = e
                Log.w(TAG, "Port $port failed: ${e.message}")
                try {
                    instance?.stop()
                } catch (_: Exception) {
                }
                stopQuietly()
            }
        }

        throw lastError ?: IOException("Could not bind any port")
    }

    @Synchronized
    fun stop() {
        stopQuietly()
    }

    fun isRunning(): Boolean {
        // A UI status read must never wait behind USB scans or server shutdown.
        return server?.isAlive == true
    }

    fun activePort(): Int? = server?.listeningPort

    private fun stopQuietly() {
        val current = server
        server = null
        if (current == null) return
        try {
            current.stop()
            Log.i(TAG, "Stopped previous server")
        } catch (e: Exception) {
            Log.w(TAG, "Stop failed: ${e.message}")
        }
    }
}
