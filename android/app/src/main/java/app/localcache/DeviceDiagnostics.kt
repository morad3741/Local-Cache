package app.localcache

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Debug
import app.localcache.torrent.TorrentTuning

object DeviceDiagnostics {
    @Volatile private var context: Context? = null

    fun attach(context: Context) { this.context = context.applicationContext }

    fun tuning(): TorrentTuning {
        val memory = memoryInfo()
        return TorrentTuning(memory?.totalMem ?: 0, Runtime.getRuntime().maxMemory(), Runtime.getRuntime().availableProcessors())
    }

    fun snapshot(): String = runCatching {
        val runtime = Runtime.getRuntime()
        val memory = memoryInfo()
        val cm = context?.getSystemService(ConnectivityManager::class.java)
        val network = cm?.activeNetwork
        val caps = cm?.getNetworkCapabilities(network)
        val links = cm?.getLinkProperties(network)
        val transport = when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "ethernet"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "wifi"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true -> "vpn"
            else -> "other/disconnected"
        }
        "device=${Build.MANUFACTURER}/${Build.MODEL} sdk=${Build.VERSION.SDK_INT} " +
            "abis=${Build.SUPPORTED_ABIS.joinToString(",")} cores=${runtime.availableProcessors()} " +
            "ramTotal=${memory?.totalMem} ramAvailable=${memory?.availMem} lowMemory=${memory?.lowMemory} " +
            "heapUsed=${runtime.totalMemory() - runtime.freeMemory()} heapMax=${runtime.maxMemory()} " +
            "nativeHeap=${Debug.getNativeHeapAllocatedSize()} network=$transport " +
            "validated=${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)} " +
            "metered=${cm?.isActiveNetworkMetered} mtu=${links?.mtu} " +
            "dns=${links?.dnsServers?.joinToString(",") { it.hostAddress.orEmpty() }}"
    }.getOrElse { "deviceSnapshotError=${it.javaClass.simpleName}" }

    private fun memoryInfo(): ActivityManager.MemoryInfo? = runCatching {
        val manager = context?.getSystemService(ActivityManager::class.java) ?: return null
        ActivityManager.MemoryInfo().also { manager.getMemoryInfo(it) }
    }.getOrNull()
}
