package app.localcache.net

import app.localcache.DiagLog
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy

/** Connection events copy scalar fields only; credentials and full URLs are never logged. */
data class DownloadTrace(val attempt: String, val cacheKey: String)

class DownloadNetworkLog(private val call: Call) : EventListener() {
    private val started = System.nanoTime()
    private fun event(details: String) {
        if (!DiagLog.enabled) return
        val trace = call.request().tag(DownloadTrace::class.java)
        DiagLog.t("http attempt=${trace?.attempt} key=${trace?.cacheKey} " +
            "elapsedMs=${(System.nanoTime() - started) / 1_000_000} $details")
    }

    override fun callStart(call: Call) = event("event=start host=${call.request().url.host} port=${call.request().url.port}")
    override fun dnsStart(call: Call, domainName: String) = event("event=dns-start host=$domainName")
    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) =
        event("event=dns-end host=$domainName addresses=${inetAddressList.joinToString(",") { it.hostAddress.orEmpty() }}")
    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) =
        event("event=connect-start endpoint=$inetSocketAddress proxy=${proxy.type()}")
    override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) =
        event("event=connect-end endpoint=$inetSocketAddress protocol=$protocol")
    override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?, ioe: IOException) =
        event("event=connect-failed endpoint=$inetSocketAddress exception=${ioe.javaClass.simpleName} error=${ioe.message}")
    override fun secureConnectStart(call: Call) = event("event=tls-start")
    override fun secureConnectEnd(call: Call, handshake: Handshake?) =
        event("event=tls-end version=${handshake?.tlsVersion} cipher=${handshake?.cipherSuite}")
    override fun connectionAcquired(call: Call, connection: Connection) = event("event=connection-acquired protocol=${connection.protocol()}")
    override fun responseHeadersEnd(call: Call, response: Response) = event(
        "event=headers code=${response.code} host=${response.request.url.host} " +
            "length=${response.header("Content-Length")} range=${response.header("Content-Range")} type=${response.header("Content-Type")}",
    )
    override fun responseBodyEnd(call: Call, byteCount: Long) = event("event=body-end bytes=$byteCount")
    override fun callEnd(call: Call) = event("event=end")
    override fun callFailed(call: Call, ioe: IOException) = event("event=failed exception=${ioe.javaClass.simpleName} error=${ioe.message}")
}
