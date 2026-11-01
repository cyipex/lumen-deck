package com.lumen.desk

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.TimeUnit

sealed interface Status {
    data object Searching : Status
    data class Connecting(val host: String) : Status
    data class Pending(val pc: String) : Status
    data class Connected(val pc: String) : Status
}

/**
 * Connection to the PC. The PC announces its address over UDP (port 47802), the tablet connects
 * over WebSocket and keeps the connection; after an outage it reconnects by itself.
 */
class Link(context: Context) {
    private val prefs = context.getSharedPreferences("link", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    val http: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .connectTimeout(3, TimeUnit.SECONDS)
        .build()

    val status = MutableStateFlow<Status>(Status.Searching)
    val config = MutableStateFlow(prefs.getString("config", null)?.let { runCatching { parseConfig(JSONObject(it)) }.getOrNull() })
    val live = MutableStateFlow(LiveState())
    val toasts = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val notices = MutableSharedFlow<Notice>(extraBufferCapacity = 8)

    private val deviceId = prefs.getString("deviceId", null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString("deviceId", it).apply()
    }

    var manualHost: String?
        get() = prefs.getString("manualHost", null)
        set(v) {
            prefs.edit().putString("manualHost", v?.takeIf { it.isNotBlank() }).apply()
            reconnectSoon(0)
        }

    @Volatile private var discovered: String? = prefs.getString("lastHost", null)
    @Volatile private var socket: WebSocket? = null
    @Volatile private var running = false
    private var connectedHost: String? = null
    private var discovery: Thread? = null
    @Volatile private var usb = false

    /** The connection goes over the USB cable (adb reverse), not Wi-Fi. */
    val viaUsb = MutableStateFlow(false)
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start() {
        if (running) return
        running = true
        multicastLock = wifi.createMulticastLock("lumen").apply { setReferenceCounted(false) }
        searching(true)
        discovery = Thread(::listenForBeacons, "beacon").apply { isDaemon = true; start() }
        Thread(::watchUsb, "usb").apply { isDaemon = true; start() }
        // The first connection is made by the cable check (so we don't go over Wi-Fi first and switch right away);
        // this is only a fallback in case the check takes long.
        reconnectSoon(2500)
    }

    fun stop() {
        running = false
        socket?.close(1000, null)
        socket = null
        searching(false)
    }

    // The cable wins over Wi-Fi; a manually entered address wins over both.
    /**
     * Receiving the PC's announcements keeps Wi-Fi in a more demanding mode (multicast). It is on only
     * while we are looking for the PC; during a connection Wi-Fi can save battery.
     */
    private fun searching(on: Boolean) {
        val lock = multicastLock ?: return
        if (on && !lock.isHeld) lock.acquire()
        if (!on && lock.isHeld) lock.release()
    }

    private fun target(): String? = manualHost ?: (if (usb) USB_HOST else null) ?: discovered

    /**
     * When the tablet is connected by cable and the PC set up adb reverse, the server is reachable at 127.0.0.1.
     * A connection alone is not enough (adb listens even without the server), so we wait for a real server answer.
     */
    private fun watchUsb() {
        var first = true
        while (running) {
            val ok = runCatching {
                java.net.Socket().use { s ->
                    s.connect(InetSocketAddress("127.0.0.1", 47801), 400)
                    s.soTimeout = 1200
                    s.getOutputStream().write("GET /ping HTTP/1.0\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
                    s.getInputStream().bufferedReader().readLine()?.startsWith("HTTP/1") == true
                }
            }.getOrDefault(false)
            if (ok != usb || first) {
                usb = ok
                if (manualHost == null || first) reconnectSoon(0)
                first = false
            }
            Thread.sleep(3000)
        }
    }

    private fun listenForBeacons() {
        while (running) {
            try {
                DatagramSocket(null).use { s ->
                    s.reuseAddress = true
                    s.broadcast = true
                    s.bind(InetSocketAddress(47802))
                    val buf = ByteArray(1024)
                    while (running) {
                        val p = DatagramPacket(buf, buf.size)
                        s.receive(p)
                        val j = runCatching { JSONObject(String(p.data, 0, p.length)) }.getOrNull() ?: continue
                        if (j.optString("app") != "lumen-desk") continue
                        val host = "${p.address.hostAddress}:${j.optInt("port", 47801)}"
                        if (host != discovered) {
                            discovered = host
                            prefs.edit().putString("lastHost", host).apply()
                            if (manualHost == null && !usb && host != connectedHost) reconnectSoon(0)
                        }
                    }
                }
            } catch (_: Exception) {
                Thread.sleep(2000)
            }
        }
    }

    /** A new attempt right away (the "Try again" button). */
    fun retry() {
        main.post { reconnectSoon(0) }
    }

    private fun reconnectSoon(delayMs: Long) {
        main.removeCallbacks(connectRunnable)
        main.postDelayed(connectRunnable, delayMs)
    }

    private val connectRunnable = Runnable { connect() }

    private fun connect() {
        if (!running) return
        socket?.cancel()
        socket = null
        val host = target()
        if (host == null) {
            status.value = Status.Searching
            reconnectSoon(1500)
            return
        }
        status.value = Status.Connecting(host)
        connectedHost = host
        viaUsb.value = host == USB_HOST
        val req = Request.Builder().url("ws://$host/ws").build()
        socket = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                ws.send(JSONObject().put("t", "hello").put("deviceId", deviceId).put("name", "${Build.MANUFACTURER} ${Build.MODEL}").toString())
            }

            override fun onMessage(ws: WebSocket, text: String) {
                if (ws !== socket) return
                runCatching { handle(JSONObject(text)) }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) = dropped(ws)
            override fun onClosed(ws: WebSocket, code: Int, reason: String) = dropped(ws)
        })
    }

    private fun dropped(ws: WebSocket) {
        if (ws !== socket) return
        socket = null
        connectedHost = null
        status.value = Status.Searching
        searching(true)
        reconnectSoon(1500)
    }

    private fun handle(m: JSONObject) {
        when (m.optString("t")) {
            "pair" -> {
                status.value = if (m.optString("status") == "ok") Status.Connected(live.value.pc) else Status.Pending(m.optString("pc"))
                if (status.value is Status.Connected) searching(false)
            }
            "config" -> {
                val c = m.getJSONObject("config")
                config.value = parseConfig(c)
                prefs.edit().putString("config", c.toString()).apply()
            }
            "state" -> {
                val stats = m.optJSONObject("stats")
                live.value = LiveState(
                    spotify = parseSpotify(m.optJSONObject("spotify")),
                    // For a moment after a change on the tablet we keep our own value, so the slider does not jump back.
                    audio = if (System.currentTimeMillis() < audioHold) live.value.audio else parseAudio(m.optJSONObject("audio")),
                    mixer = if (System.currentTimeMillis() < mixerHold) live.value.mixer else parseMixer(m.optJSONArray("mixer")),
                    cpu = stats?.optInt("cpu"),
                    ram = stats?.optInt("ram"),
                    weather = parseWeather(m.optJSONObject("weather")),
                    tiles = parseTileStates(m.optJSONObject("tiles")),
                    pc = m.optString("pc")
                )
                val s = status.value
                if (s is Status.Connected && s.pc != live.value.pc) status.value = Status.Connected(live.value.pc)
            }
            "stats" -> {
                val s = m.optJSONObject("stats") ?: return
                live.value = live.value.copy(cpu = s.optInt("cpu"), ram = s.optInt("ram"))
            }
            "toast" -> toasts.tryEmit(m.optString("text"))
            "notify" -> notices.tryEmit(Notice(m.optLong("id"), m.optString("app"), m.optString("title"), m.optString("body")))
            "lib" -> libWaiting.remove(m.optInt("req"))?.let { cb -> main.post { cb(m) } }
        }
    }

    private fun send(o: JSONObject): Boolean = socket?.send(o.toString()) ?: false

    fun tap(tile: Tile) {
        // Instant feedback: the tile switches right away, the PC then sends the real state.
        if (tile.switchable) {
            val cur = live.value.tiles[tile.id] ?: false
            live.value = live.value.copy(tiles = live.value.tiles + (tile.id to !cur))
        }
        if (!send(JSONObject().put("t", "tap").put("tile", tile.id))) toasts.tryEmit("PC is not connected")
    }

    fun spotify(cmd: String, value: Number? = null) {
        val sp = live.value.spotify
        when (cmd) {
            "toggle" -> live.value = live.value.copy(spotify = sp.copy(playing = !sp.playing, progress = position(sp), receivedAt = System.currentTimeMillis()))
            "seek" -> live.value = live.value.copy(spotify = sp.copy(progress = value!!.toLong(), receivedAt = System.currentTimeMillis()))
            "volume" -> live.value = live.value.copy(spotify = sp.copy(volume = value!!.toInt()))
        }
        val o = JSONObject().put("t", "spotify").put("cmd", cmd)
        if (value != null) o.put("value", value)
        if (!send(o)) toasts.tryEmit("PC is not connected")
    }

    // ---------- Spotify library (question -> answer with the same number) ----------

    private val libWaiting = java.util.concurrent.ConcurrentHashMap<Int, (JSONObject) -> Unit>()
    private val libIds = java.util.concurrent.atomic.AtomicInteger()

    fun lib(op: String, params: JSONObject = JSONObject(), onReply: (JSONObject) -> Unit) {
        val id = libIds.incrementAndGet()
        libWaiting[id] = onReply
        if (!send(params.put("t", "lib").put("op", op).put("req", id))) {
            libWaiting.remove(id)
            onReply(JSONObject().put("error", "PC is not connected"))
        }
    }

    fun libPlay(source: String, uri: String?, uris: List<String>, shuffle: Boolean? = null) {
        val o = JSONObject().put("t", "libplay").put("source", source).put("uris", org.json.JSONArray(uris))
        if (uri != null) o.put("uri", uri)
        if (shuffle != null) o.put("shuffle", shuffle)
        if (!send(o)) toasts.tryEmit("PC is not connected")
    }

    /** A setting changed on the tablet (appearance, notifications); saved on the PC, which then sends the new configuration. */
    fun setting(path: List<String>, value: Any) {
        val op = JSONObject().put("path", org.json.JSONArray(path)).put("value", value)
        if (!send(JSONObject().put("t", "set").put("ops", org.json.JSONArray().put(op)))) toasts.tryEmit("PC is not connected")
    }

    /** Connection address for the second-monitor video. */
    fun displayUrl(): String? = connectedHost?.takeIf { status.value is Status.Connected }?.let { "ws://$it/ws?role=display&device=$deviceId" }

    /** A touch on the second monitor; x, y are 0..1. */
    fun pointer(a: String, x: Float, y: Float, d: Int = 0) {
        send(JSONObject().put("t", "ptr").put("a", a).put("x", x.toDouble()).put("y", y.toDouble()).put("d", d))
    }

    private var audioHold = 0L
    private var mixerHold = 0L

    /** Mixer: volume ("appvol", 0..100) or mute ("appmute", 1/0) of one app on the PC. */
    fun appAudio(cmd: String, key: String, value: Int) {
        live.value = live.value.copy(mixer = live.value.mixer.map {
            if (it.key != key) it
            else if (cmd == "appvol") it.copy(volume = value, muted = false)
            else it.copy(muted = value == 1)
        })
        mixerHold = System.currentTimeMillis() + 2500
        val o = JSONObject().put("t", "audio").put("cmd", cmd).put("key", key).put("value", value)
        if (!send(o)) toasts.tryEmit("PC is not connected")
    }

    fun audio(cmd: String, value: Int? = null) {
        val a = live.value.audio
        if (a != null) {
            when (cmd) {
                "volume" -> live.value = live.value.copy(audio = a.copy(volume = value!!, muted = false))
                "mute" -> live.value = live.value.copy(audio = a.copy(muted = !a.muted))
            }
            if (cmd != "output") audioHold = System.currentTimeMillis() + 1500
        }
        val o = JSONObject().put("t", "audio").put("cmd", cmd)
        if (value != null) o.put("value", value)
        if (!send(o)) toasts.tryEmit("PC is not connected")
    }

    companion object {
        private const val USB_HOST = "127.0.0.1:47801"

        fun position(sp: Spotify, now: Long = System.currentTimeMillis()): Long {
            val p = if (sp.playing) sp.progress + (now - sp.receivedAt) else sp.progress
            return if (sp.duration > 0) p.coerceIn(0, sp.duration) else p.coerceAtLeast(0)
        }
    }
}
