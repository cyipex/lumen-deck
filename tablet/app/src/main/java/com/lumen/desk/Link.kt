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
import okio.ByteString
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.TimeUnit

sealed interface Status {
    data object Searching : Status
    data class Connecting(val host: String) : Status
    /** Waiting for Allow on the PC. [code] must match the one the PC shows; [known]: this tablet was paired before. */
    data class Pending(val pc: String, val code: String, val known: Boolean) : Status
    data class Connected(val pc: String) : Status
    /** The PC refused this tablet's pairing key (it was paired again elsewhere, or removed): pair again. */
    data class Refused(val pc: String) : Status
}

/**
 * Connection to the PC. The PC announces its address over UDP (port 47802), the tablet connects
 * over WebSocket and keeps the connection; after an outage it reconnects by itself.
 * Everything after the first key exchange is encrypted (Secure.kt); the PC trusts the tablet only through
 * the pairing key it gave it when the user allowed it, never through the tablet's id alone.
 */
class Link(context: Context) {
    private val app = context.applicationContext
    private val prefs = context.getSharedPreferences("link", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    val http: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .connectTimeout(3, TimeUnit.SECONDS)
        .build()

    val status = MutableStateFlow<Status>(Status.Searching)
    // The layout of the PC used last; each PC has its own (a second PC must never replace the first one's).
    val config = MutableStateFlow(
        (prefs.getString("lastPc", null)?.let { prefs.getString("config:$it", null) } ?: prefs.getString("config", null))
            ?.let { runCatching { parseConfig(JSONObject(it)) }.getOrNull() }
    )
    val live = MutableStateFlow(LiveState())
    val toasts = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val notices = MutableSharedFlow<Notice>(extraBufferCapacity = 8)

    private val deviceId = prefs.getString("deviceId", null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString("deviceId", it).apply()
    }

    // ---------- pairing keys, one per PC ----------
    // Each PC that allowed this tablet made its own secret; they are kept by the PC's id, so pairing with a
    // second PC never destroys the first pairing. "pairKey" is a key from before PCs had ids: it belongs to
    // whichever PC accepts it first and is then filed under that PC's id.

    private fun dec(s: String) = runCatching { android.util.Base64.decode(s, android.util.Base64.NO_WRAP) }.getOrNull()
    private fun enc(b: ByteArray) = android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP)
    private fun keys() = runCatching { JSONObject(prefs.getString("keys", "{}") ?: "{}") }.getOrDefault(JSONObject())
    private fun keyFor(pc: String?) = pc?.let { keys().optString(it) }?.takeIf { it.isNotEmpty() }?.let(::dec)
    private val legacyKey get() = prefs.getString("pairKey", null)?.let(::dec)

    private fun saveKey(pc: String, key: ByteArray?) {
        val k = keys()
        if (key == null) k.remove(pc) else k.put(pc, enc(key))
        prefs.edit().putString("keys", k.toString()).apply()
    }

    @Volatile private var serverPc: String? = null
    @Volatile private var usedLegacy = false
    @Volatile private var refused: String? = null

    var manualHost: String?
        get() = prefs.getString("manualHost", null)
        set(v) {
            prefs.edit().putString("manualHost", v?.takeIf { it.isNotBlank() }).apply()
            reconnectSoon(0)
        }

    @Volatile private var discovered: String? = prefs.getString("lastHost", null)
    // PCs heard on the network: id -> (address, when). The PC used last is preferred, so the tablet
    // never hops to another PC just because that one announced itself more recently.
    private val heard = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var session: Secure.Session? = null
    private var pairCode = ""
    @Volatile private var running = false
    private var connectedHost: String? = null

    /** For the connection card: the PC address in use (or being tried) and the last one the PC announced. */
    val currentHost get() = connectedHost
    val announcedHost get() = discovered
    val isManual get() = manualHost != null
    private var discovery: Thread? = null
    @Volatile private var usb = false

    /** The connection goes over the USB cable (adb reverse), not Wi-Fi. */
    val viaUsb = MutableStateFlow(false)

    /** The monitor the drawing tablet draws on (answer to drawScreen). */
    val drawScreen = MutableStateFlow<DrawScreen?>(null)
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    fun start() {
        if (running) return
        running = true
        multicastLock = wifi.createMulticastLock("lumen").apply { setReferenceCounted(false) }
        // Without this, Wi-Fi power saving delays or drops packets (Huawei especially): late taps and lost connections.
        @Suppress("DEPRECATION")
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "lumen").apply { setReferenceCounted(false); acquire() }
        searching(true)
        discovery = Thread(::listenForBeacons, "beacon").apply { isDaemon = true; start() }
        Thread(::watchUsb, "usb").apply { isDaemon = true; start() }
        // The first connection is made by the cable check (so we don't go over Wi-Fi first and switch right away);
        // this is only a fallback in case the check takes long.
        reconnectSoon(2500)
    }

    fun stop() {
        running = false
        wifiLock?.let { if (it.isHeld) it.release() }
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
        var fails = 0
        var wins = 0
        while (running) {
            val ok = runCatching {
                java.net.Socket().use { s ->
                    s.connect(InetSocketAddress("127.0.0.1", 47801), 400)
                    s.soTimeout = 1200
                    s.getOutputStream().write("GET /ping HTTP/1.0\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
                    s.getInputStream().bufferedReader().readLine()?.startsWith("HTTP/1") == true
                }
            }.getOrDefault(false)
            // One slow answer (PC busy, e.g. encoding video) must not drop a working cable connection:
            // switching needs two answers in a row, and a live connection over the cable is never dropped by the check.
            if (ok) { wins++; fails = 0 } else { fails++; wins = 0 }
            val liveOnUsb = usb && socket != null && status.value is Status.Connected
            val change = when {
                first -> true
                ok && !usb -> wins >= 2
                !ok && usb -> fails >= 2 && !liveOnUsb
                else -> false
            }
            if (change) {
                usb = ok
                if (manualHost == null || first) reconnectSoon(0)
                first = false
            }
            Thread.sleep(2000)
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
                        val now = System.currentTimeMillis()
                        heard[j.optString("id").ifEmpty { host }] = host to now
                        val mine = prefs.getString("lastPc", null)?.let { heard[it] }?.takeIf { now - it.second < 15_000 }?.first
                        val pick = mine ?: heard.values.filter { now - it.second < 15_000 }.maxByOrNull { it.second }?.first ?: host
                        if (pick != discovered) {
                            discovered = pick
                            prefs.edit().putString("lastHost", pick).apply()
                            val busy = status.value is Status.Connected || status.value is Status.Pending
                            if (manualHost == null && !usb && pick != connectedHost && !busy) reconnectSoon(0)
                        }
                    }
                }
            } catch (_: Exception) {
                Thread.sleep(2000)
            }
        }
    }

    fun downloadUpdate() = Updater.start(::send)

    /** A new attempt right away (the "Try again" button). */
    fun retry() {
        main.post { reconnectSoon(0) }
    }

    /**
     * The PC refused our key: forget it for that PC and connect again, so the PC shows this tablet with a code
     * to allow. Keys for other PCs stay.
     */
    fun pairAgain() {
        main.post {
            val pc = serverPc ?: prefs.getString("lastPc", null)
            if (pc != null) saveKey(pc, null)
            prefs.edit().remove("pairKey").apply()
            reconnectSoon(0)
        }
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
        session = null
        serverPc = null
        usedLegacy = false
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
        val hs = Secure.Handshake(deviceId)
        val b64 = { b: ByteArray -> android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP) }
        socket = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                ws.send(
                    JSONObject().put("t", "hello2").put("deviceId", deviceId).put("name", "${Build.MANUFACTURER} ${Build.MODEL}")
                        .put("epk", b64(hs.publicKey)).put("cn", b64(hs.nonce))
                        // Which PCs we hold keys for (their ids are not secret); "paired" is for PCs from before ids.
                        .put("pcs", org.json.JSONArray(keys().keys().asSequence().toList()))
                        .put("legacy", legacyKey != null)
                        .put("paired", keys().length() > 0 || legacyKey != null).toString()
                )
            }

            // Only the PC's half of the key exchange comes unencrypted.
            override fun onMessage(ws: WebSocket, text: String) {
                if (ws !== socket) return
                if (session != null) return ws.cancel()
                val ok = runCatching {
                    val m = JSONObject(text)
                    require(m.optString("t") == "hello2")
                    serverPc = m.optString("pcId").ifEmpty { null }
                    refused = m.optString("pc")
                    val own = keyFor(serverPc)
                    val key = own ?: legacyKey
                    usedLegacy = own == null && key != null
                    // The PC uses the pairing key only if it still knows this tablet; otherwise we must be approved again.
                    val paired = m.optString("mode") == "paired"
                    require(!paired || key != null)
                    val dec = { s: String -> android.util.Base64.decode(s, android.util.Base64.NO_WRAP) }
                    val r = hs.finish(dec(m.getString("spk")), dec(m.getString("sn")), if (paired) key else null)
                    pairCode = r.code
                    session = r.session
                    // The first encrypted message proves to the PC that we have the key.
                    send(JSONObject().put("t", "ready"))
                }.isSuccess
                if (!ok) ws.cancel()
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                if (ws !== socket) return
                val s = session ?: return ws.cancel()
                // A message we cannot open was not made by our PC: drop the connection.
                val m = runCatching { JSONObject(String(s.open(bytes.toByteArray()))) }.getOrNull() ?: return ws.cancel()
                runCatching { handle(m) }
            }

            // 4401: the PC does not accept our key. Retrying every second would only fill its log; ask the user instead.
            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                if (ws !== socket) return
                if (code == 4401) {
                    socket = null
                    session = null
                    connectedHost = null
                    status.value = Status.Refused(refused ?: "")
                    reconnectSoon(30_000)
                    return
                }
                ws.close(1000, null)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) = dropped(ws)
            override fun onClosed(ws: WebSocket, code: Int, reason: String) = dropped(ws)
        })
    }

    private fun dropped(ws: WebSocket) {
        if (ws !== socket) return
        socket = null
        session = null
        connectedHost = null
        status.value = Status.Searching
        searching(true)
        reconnectSoon(1500)
    }

    private fun handle(m: JSONObject) {
        when (m.optString("t")) {
            "pair" -> {
                val pc = serverPc
                if (m.optString("status") == "ok") {
                    // The key comes once, when the user pressed Allow on the PC; it is filed under that PC.
                    val fresh = m.optString("key").takeIf { it.isNotEmpty() }?.let(::dec)
                    if (pc != null) {
                        if (fresh != null) saveKey(pc, fresh)
                        else if (usedLegacy) legacyKey?.let { saveKey(pc, it) }
                        if (fresh != null || usedLegacy) prefs.edit().remove("pairKey").apply()
                        if (prefs.getString("lastPc", null) != pc) {
                            prefs.edit().putString("lastPc", pc).apply()
                            // This PC's own layout right away (the PC sends the current one in a moment).
                            prefs.getString("config:$pc", null)?.let { runCatching { config.value = parseConfig(JSONObject(it)) } }
                        }
                    } else if (fresh != null) {
                        prefs.edit().putString("pairKey", enc(fresh)).apply()
                    }
                    status.value = Status.Connected(live.value.pc)
                    searching(false)
                    // Which tablet app this is; the PC answers with an update when it has a newer one.
                    send(JSONObject().put("t", "version").put("code", Updater.version(app)))
                } else {
                    // The code is our own result of the key exchange, not a number the other side sent.
                    status.value = Status.Pending(m.optString("pc"), pairCode, known = keyFor(pc) != null || legacyKey != null)
                }
            }
            "config" -> {
                val c = m.getJSONObject("config")
                val parsed = parseConfig(c)
                config.value = parsed
                // The photo and uploaded fonts this layout uses, if the tablet does not have them yet.
                listOfNotNull(parsed.saver.photo, parsed.theme.clockFont, parsed.saver.font)
                    .map { it.removePrefix("custom:") }.filter { Regex("[a-f0-9]{32}").matches(it) }
                    .forEach { id -> Assets.want(id) { send(JSONObject().put("t", "asset").put("id", id).put("req", 0)) } }
                prefs.edit().putString(serverPc?.let { "config:$it" } ?: "config", c.toString()).apply()
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
                    claude = parseClaude(m.optJSONObject("claude")),
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
            "lib", "display" -> libWaiting.remove(m.optInt("req"))?.let { cb -> main.post { cb(m) } }
            "asset" -> Assets.piece(m)
            "update" -> Updater.offered(m, app)
            "apk" -> Updater.piece(m, app)
            // "Show it on the tablet now" in the PC settings.
            "saver" -> Hub.saver.value = true
            "drawscreen" -> drawScreen.value = DrawScreen(m.optInt("i"), m.optInt("n", 1), m.optInt("w", 16), m.optInt("h", 9))
        }
    }

    private fun send(o: JSONObject): Boolean {
        val ws = socket ?: return false
        val s = session ?: return false
        return s.seal(o.toString().toByteArray()) { ws.send(ByteString.of(*it)) }
    }

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

    /**
     * The second-monitor video: asks the PC for a one-time ticket and the video's key (over the encrypted connection),
     * then [onReady] gets the address and the key; null when the PC is not connected.
     */
    fun displayTicket(onReady: (String?, ByteArray?) -> Unit) {
        val host = connectedHost?.takeIf { status.value is Status.Connected } ?: return onReady(null, null)
        val id = libIds.incrementAndGet()
        libWaiting[id] = { m ->
            val token = m.optString("token")
            val key = runCatching { android.util.Base64.decode(m.optString("key"), android.util.Base64.NO_WRAP) }.getOrNull()
            if (token.isEmpty() || key == null || key.size != 32) onReady(null, null)
            else onReady("ws://$host/ws?role=display&token=$token", key)
        }
        if (!send(JSONObject().put("t", "display").put("req", id))) {
            libWaiting.remove(id)
            onReady(null, null)
        }
    }

    /** A touch on the second monitor; x, y are 0..1. */
    fun pointer(a: String, x: Float, y: Float, d: Int = 0) {
        send(JSONObject().put("t", "ptr").put("a", a).put("x", x.toDouble()).put("y", y.toDouble()).put("d", d))
    }

    /** Touchpad: relative movement in PC pixels, mouse buttons ("left", "right", "middle") and scrolling (120 = one notch). */
    fun pad(a: String, dx: Int = 0, dy: Int = 0, b: String = "left") {
        send(JSONObject().put("t", "pad").put("a", a).put("dx", dx).put("dy", dy).put("b", b))
    }

    /** One key on the PC (e.g. "enter", "f5", "c") with the modifiers pressed just for it. */
    fun key(k: String, mods: List<String> = emptyList()) {
        if (!send(JSONObject().put("t", "key").put("k", k).put("mods", org.json.JSONArray(mods)))) toasts.tryEmit("PC is not connected")
    }

    /** Text typed as characters, so the PC's keyboard layout does not matter. */
    fun type(text: String) {
        if (!send(JSONObject().put("t", "type").put("text", text))) toasts.tryEmit("PC is not connected")
    }

    /** A modifier (ctrl, shift, alt, win) that stays pressed on the PC until released. */
    fun hold(k: String, down: Boolean) {
        send(JSONObject().put("t", "hold").put("k", k).put("down", down))
    }

    /** Drawing tablet: picks monitor number [i]; the PC answers with its size. */
    fun drawOn(i: Int) {
        if (!send(JSONObject().put("t", "drawscreen").put("i", i))) toasts.tryEmit("PC is not connected")
    }

    /** Drawing tablet: "hover", "down", "move" or "up" at x, y (0..1 on the monitor). */
    fun draw(a: String, x: Float, y: Float) {
        send(JSONObject().put("t", "draw").put("a", a).put("x", x.toDouble()).put("y", y.toDouble()))
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
