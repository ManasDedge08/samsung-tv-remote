package com.manas.tvremote

import android.annotation.SuppressLint
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** What the TV reports about itself on http://<ip>:8001/api/v2/. */
data class TvInfo(
    val ip: String,
    val name: String,
    val model: String,
    val mac: String,
    val powerState: String, // "on" or "standby"
)

enum class Conn { Idle, Connecting, NeedsApproval, Connected, Denied, TimedOut, Untrusted, Unreachable }

sealed interface TvEvent {
    data object KeyboardOpened : TvEvent
    data object KeyboardClosed : TvEvent
}

/**
 * Talks to a 2016+ Samsung Tizen TV:
 * - keys and text over the secure WebSocket on port 8002 (token-paired),
 * - app launch and status over the REST API on port 8001,
 * - power-on with Wake-on-LAN.
 */
class SamsungTv(
    private val scope: CoroutineScope,
    private val onToken: (String) -> Unit,
    private val pinnedCert: () -> String?,
    private val onCertPinned: (String) -> Unit,
) {

    private val _conn = MutableStateFlow(Conn.Idle)
    val conn: StateFlow<Conn> = _conn

    private val _events = MutableSharedFlow<TvEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<TvEvent> = _events

    private var ws: WebSocket? = null
    private var host: String? = null
    private var token: String? = null
    private var wantConnected = false
    private var retryJob: Job? = null
    private var retryDelayMs = 1000L

    /** Commands sent while the socket is (re)connecting; flushed on connect. */
    private val pending = ArrayDeque<String>()

    fun configure(host: String, token: String?) {
        if (this.host != host || this.token != token) {
            this.host = host
            this.token = token
            if (wantConnected) reconnectNow()
        }
    }

    fun start() {
        wantConnected = true
        if (_conn.value != Conn.Connected && _conn.value != Conn.Connecting &&
            _conn.value != Conn.NeedsApproval
        ) reconnectNow()
    }

    fun stop() {
        wantConnected = false
        retryJob?.cancel()
        ws?.close(1000, null)
        ws = null
        pending.clear()
        _conn.value = Conn.Idle
    }

    private fun reconnectNow() {
        retryJob?.cancel()
        ws?.cancel()
        ws = null
        open()
    }

    private fun open() {
        val h = host ?: return
        val name = Base64.encodeToString(DEVICE_NAME.toByteArray(), Base64.NO_WRAP)
        val tokenPart = token?.let { "&token=$it" } ?: ""
        val url = "wss://$h:8002/api/v2/channels/samsung.remote.control?name=$name$tokenPart"
        _conn.value = if (token == null) Conn.NeedsApproval else Conn.Connecting
        openedAt = System.currentTimeMillis()
        Log.d(TAG, "open $h token=${token != null}")
        ws = wsClient.newWebSocket(Request.Builder().url(url).build(), Listener())
    }

    private var openedAt = 0L
    private fun since() = "${System.currentTimeMillis() - openedAt}ms"

    /**
     * OkHttp calls back on its own reader thread. Every callback hops to [scope] (main) so the
     * socket, queue and flags are only ever touched from one thread.
     */
    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "socket open after ${since()}")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            scope.launch { handleMessage(webSocket, text) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.d(TAG, "failure after ${since()}: $t")
            val certChanged = generateSequence(t) { it.cause }.any { it.message == CERT_CHANGED }
            scope.launch { handleFailure(webSocket, certChanged) }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            scope.launch { handleClosed(webSocket) }
        }
    }

    private fun handleMessage(webSocket: WebSocket, text: String) {
            if (webSocket !== ws) return
            val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
            Log.d(TAG, "event ${msg.optString("event")} after ${since()}")
            when (msg.optString("event")) {
                "ms.channel.connect" -> {
                    msg.optJSONObject("data")?.optString("token")?.takeIf { it.isNotBlank() }?.let {
                        token = it
                        onToken(it)
                    }
                    retryDelayMs = 1000L
                    _conn.value = Conn.Connected
                    while (pending.isNotEmpty()) webSocket.send(pending.removeFirst())
                }
                "ms.channel.unauthorized" -> {
                    _conn.value = Conn.Denied
                    wantConnected = false
                    ws = null
                    webSocket.close(1000, null)
                }
                "ms.channel.timeOut" -> {
                    // Nobody answered the Allow prompt on the TV in time.
                    _conn.value = Conn.TimedOut
                    wantConnected = false
                    ws = null
                    webSocket.close(1000, null)
                }
                "ms.remote.imeStart" -> _events.tryEmit(TvEvent.KeyboardOpened)
                "ms.remote.imeEnd" -> _events.tryEmit(TvEvent.KeyboardClosed)
            }
    }

    private fun handleFailure(webSocket: WebSocket, certChanged: Boolean) {
        if (webSocket !== ws) return
        ws = null
        if (certChanged) {
            _conn.value = Conn.Untrusted
            wantConnected = false
            return
        }
        if (_conn.value != Conn.Denied && _conn.value != Conn.TimedOut) _conn.value = Conn.Unreachable
        scheduleRetry()
    }

    /**
     * The TV serves port 8002 with a self-signed certificate, so no CA can vouch for it. Instead the
     * first certificate seen for this TV is pinned (trust on first use) and every later connection
     * must present the same one. Hostname checks are skipped because the certificate doesn't name
     * the TV's IP address; the pin is what identifies the TV.
     */
    @SuppressLint("CustomX509TrustManager") // Deliberate: pins the TV's self-signed certificate, see above.
    private val pinningTrust = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            throw CertificateException("This app never acts as a TLS server")
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("TV sent no certificate")
            val fingerprint = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
                .joinToString("") { "%02x".format(it) }
            when (pinnedCert()) {
                null -> onCertPinned(fingerprint)
                fingerprint -> Unit
                else -> throw CertificateException(CERT_CHANGED)
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    private val wsClient: OkHttpClient by lazy {
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(pinningTrust), SecureRandom()) }
        OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, pinningTrust)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    private fun handleClosed(webSocket: WebSocket) {
        if (webSocket !== ws) return
        ws = null
        if (_conn.value == Conn.Connected) _conn.value = Conn.Connecting
        scheduleRetry()
    }

    private fun scheduleRetry() {
        if (!wantConnected) return
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(retryDelayMs)
            retryDelayMs = (retryDelayMs * 2).coerceAtMost(8000L)
            if (wantConnected && ws == null) open()
        }
    }

    private fun send(json: JSONObject) {
        val text = json.toString()
        val socket = ws
        if (_conn.value == Conn.Connected && socket != null && socket.send(text)) return
        if (pending.size >= 16) pending.removeFirst()
        pending.addLast(text)
        if (ws == null && host != null) {
            wantConnected = true
            retryDelayMs = 1000L
            reconnectNow()
        }
    }

    fun key(code: String) = send(
        JSONObject()
            .put("method", "ms.remote.control")
            .put(
                "params", JSONObject()
                    .put("Cmd", "Click")
                    .put("DataOfCmd", code)
                    .put("Option", "false")
                    .put("TypeOfRemote", "SendRemoteKey")
            )
    )

    /** Fills the TV's system keyboard (Browser, Settings, Wi-Fi password) and submits it. */
    fun text(value: String) {
        val b64 = Base64.encodeToString(value.toByteArray(), Base64.NO_WRAP)
        send(
            JSONObject().put("method", "ms.remote.control").put(
                "params", JSONObject()
                    .put("Cmd", b64)
                    .put("DataOfCmd", "base64")
                    .put("TypeOfRemote", "SendInputString")
            )
        )
        send(
            JSONObject().put("method", "ms.remote.control")
                .put("params", JSONObject().put("TypeOfRemote", "SendInputEnd"))
        )
    }

    /** REST launch: verified on the Q60B, where the WebSocket ed.apps.launch is ignored. */
    suspend fun launch(appId: String): Boolean = withContext(Dispatchers.IO) {
        val h = host ?: return@withContext false
        runCatching {
            val req = Request.Builder()
                .url("http://$h:8001/api/v2/applications/$appId")
                .post(ByteArray(0).toRequestBody("application/json".toMediaType()))
                .build()
            httpClient.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    /** Returns the subset of [apps] installed on the TV, in the same order. */
    suspend fun installed(apps: List<TvApp>): List<TvApp> = withContext(Dispatchers.IO) {
        val h = host ?: return@withContext emptyList()
        apps.map { app ->
            async {
                runCatching {
                    val req = Request.Builder().url("http://$h:8001/api/v2/applications/${app.id}").build()
                    httpClient.newCall(req).execute().use { it.isSuccessful }
                }.getOrDefault(false).let { if (it) app else null }
            }
        }.awaitAll().filterNotNull()
    }

    /**
     * Power button. On: power key. Light standby (API still answers): power key wakes it.
     * Deep sleep (unreachable): Wake-on-LAN, then wait for it to come up.
     * Returns the power state afterwards ("on", "standby" or "off").
     */
    suspend fun togglePower(mac: String?): String {
        val h = host ?: return "off"
        val info = info(h)
        if (info != null) {
            if (info.powerState == "on") {
                key("KEY_POWER")
                return "standby"
            }
            key("KEY_POWER")
            return waitFor(h, 10_000) ?: "standby"
        }
        if (mac.isNullOrBlank()) return "off"
        wakeOnLan(mac, h)
        return waitFor(h, 20_000) ?: "off"
    }

    private suspend fun waitFor(h: String, timeoutMs: Long): String? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val s = info(h)?.powerState
            if (s == "on") return s
            delay(800)
        }
        return info(h)?.powerState
    }

    private suspend fun wakeOnLan(mac: String, h: String) = withContext(Dispatchers.IO) {
        val parts = mac.split(":", "-").map { it.toIntOrNull(16) }
        if (parts.size != 6 || parts.any { it == null || it !in 0..255 }) return@withContext
        val macBytes = parts.map { it!!.toByte() }.toByteArray()
        val packet = ByteArray(6) { 0xFF.toByte() } + (1..16).flatMap { macBytes.toList() }
        val subnetBroadcast = h.substringBeforeLast('.') + ".255"
        runCatching {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                repeat(3) {
                    for (dst in listOf("255.255.255.255", subnetBroadcast, h)) {
                        for (port in listOf(9, 7)) {
                            runCatching {
                                socket.send(DatagramPacket(packet, packet.size, InetAddress.getByName(dst), port))
                            }
                        }
                    }
                    Thread.sleep(150)
                }
            }
        }
    }

    suspend fun powerState(): String {
        val h = host ?: return "off"
        return info(h)?.powerState ?: "off"
    }

    companion object {
        /** Shown on the TV's "Allow" prompt and in its device list. */
        const val DEVICE_NAME = "Phone Remote"
        private const val TAG = "TvRemote"

        private const val CERT_CHANGED = "TV certificate changed since pairing"

        private val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .build()
        }

        private val probeClient: OkHttpClient by lazy {
            httpClient.newBuilder()
                .connectTimeout(700, TimeUnit.MILLISECONDS)
                .readTimeout(1500, TimeUnit.MILLISECONDS)
                .build()
        }

        suspend fun info(ip: String, fast: Boolean = false): TvInfo? = withContext(Dispatchers.IO) {
            runCatching {
                val client = if (fast) probeClient else httpClient
                client.newCall(Request.Builder().url("http://$ip:8001/api/v2/").build()).execute().use { r ->
                    if (!r.isSuccessful) return@use null
                    val d = JSONObject(r.body!!.string()).getJSONObject("device")
                    if (d.optString("OS") != "Tizen" && !d.optString("type").contains("Samsung")) return@use null
                    TvInfo(
                        ip = ip,
                        name = d.optString("name").ifBlank { "Samsung TV" },
                        model = d.optString("modelName"),
                        mac = d.optString("wifiMac"),
                        powerState = d.optString("PowerState", "on"),
                    )
                }
            }.getOrNull()
        }
    }
}
