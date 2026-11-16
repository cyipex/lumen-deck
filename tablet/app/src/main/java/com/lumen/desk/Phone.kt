package com.lumen.desk

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

// ---------- the phone's screen (Lumen Mirror on the phone) ----------

private const val PHONE_PORT = 47811
private const val PHONE_BEACON = 47812
private const val PHONE_START = 47813

sealed class PhoneState {
    data object Looking : PhoneState()
    data class Connected(val phone: String, val control: Boolean, val rotate: Boolean) : PhoneState()
    data class Problem(val text: String) : PhoneState()
}

@Composable
fun PhoneScreen(onExit: () -> Unit) {
    val ctx = LocalContext.current
    var state by remember { mutableStateOf<PhoneState>(PhoneState.Looking) }
    var aspect by remember { mutableStateOf(9f / 19.5f) }
    val main = remember { Handler(Looper.getMainLooper()) }
    val link = remember {
        PhoneLink(ctx.applicationContext, { s -> main.post { state = s } }, { w, h -> main.post { aspect = w.toFloat() / h } })
    }
    val keyboard = remember { mutableStateOf<EditText?>(null) }
    DisposableEffect(Unit) {
        Hub.awake(true)
        onDispose {
            Hub.awake(false)
            link.stop()
        }
    }

    Row(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Box(Modifier.aspectRatio(aspect, matchHeightConstraintsFirst = aspect < 1.6f)) {
                AndroidView(
                    factory = { c ->
                        SurfaceView(c).apply {
                            holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(h: SurfaceHolder) = link.start(h.surface)
                                override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) {}
                                override fun surfaceDestroyed(h: SurfaceHolder) = link.pause()
                            })
                            setOnTouchListener(PhoneTouch(link))
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
            // An invisible text field: what is typed on the tablet's keyboard goes to the phone.
            AndroidView(factory = { c -> PhoneKeys(c, link).also { keyboard.value = it } }, modifier = Modifier.size(1.u))

            when (val s = state) {
                is PhoneState.Looking -> Column(
                    Modifier.glass(RoundedCornerShape(40.u)).background(Ink.copy(alpha = 0.6f)).padding(36.u).widthIn(max = 620.u),
                    verticalArrangement = Arrangement.spacedBy(12.u)
                ) {
                    T("Looking for the phone…", 26.us, weight = FontWeight.SemiBold)
                    BasicText(
                        "Start Lumen Mirror on the phone. The tablet must be on the same Wi-Fi as the phone, or connected to the phone's hotspot.",
                        style = TextStyle(color = Grey1, fontSize = 17.us, fontFamily = Inter)
                    )
                }
                is PhoneState.Problem -> Column(
                    Modifier.glass(RoundedCornerShape(40.u)).background(Ink.copy(alpha = 0.6f)).padding(36.u).widthIn(max = 620.u),
                    verticalArrangement = Arrangement.spacedBy(14.u)
                ) {
                    T("The phone needs attention", 26.us, weight = FontWeight.SemiBold)
                    BasicText(s.text, style = TextStyle(color = Grey1, fontSize = 17.us, fontFamily = Inter))
                    TextButton("Back to the panel", onExit, primary = true)
                }
                is PhoneState.Connected -> {}
            }
        }

        // The phone's buttons, on the side so they never cover the picture.
        val connected = state as? PhoneState.Connected
        Column(
            Modifier.fillMaxHeight().padding(vertical = 20.u, horizontal = 14.u),
            verticalArrangement = Arrangement.spacedBy(14.u, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CornerButton("close", onExit)
            Box(Modifier.size(1.u, 20.u))
            CornerButton("back") { link.button("back") }
            CornerButton("home") { link.button("home") }
            CornerButton("recents") { link.button("recents") }
            CornerButton("bell") { link.button("notifications") }
            CornerButton("keyboard") { keyboard.value?.let { toggleKeyboard(it) } }
            if (connected?.rotate == true) CornerButton("rotate") { link.send(JSONObject().put("t", "rotate")) }
        }
    }
}

private fun toggleKeyboard(e: EditText) {
    val imm = e.context.getSystemService(InputMethodManager::class.java)
    if (e.hasFocus()) {
        imm.hideSoftInputFromWindow(e.windowToken, 0)
        e.clearFocus()
    } else {
        e.requestFocus()
        imm.showSoftInput(e, InputMethodManager.SHOW_FORCED)
    }
}

/** Every typed character is sent at once and the field is emptied again. */
private class PhoneKeys(context: Context, private val link: PhoneLink) : EditText(context) {
    init {
        alpha = 0f
        isFocusableInTouchMode = true
        // No suggestions: the keyboard commits each letter right away instead of holding a word.
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) {
                if (s.isEmpty()) return
                link.send(JSONObject().put("t", "text").put("s", s.toString()))
                s.clear()
            }
        })
        setOnEditorActionListener { _, _, _ ->
            link.send(JSONObject().put("t", "key").put("k", "enter"))
            true
        }
        setOnKeyListener { _, code, e ->
            if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (code) {
                KeyEvent.KEYCODE_DEL -> link.send(JSONObject().put("t", "key").put("k", "backspace"))
                KeyEvent.KEYCODE_ENTER -> link.send(JSONObject().put("t", "key").put("k", "enter"))
                else -> return@setOnKeyListener false
            }
            true
        }
    }
}

/** One finger is passed through as it is: down, moves, up. The phone turns it into real touches. */
private class PhoneTouch(private val link: PhoneLink) : android.view.View.OnTouchListener {
    private var lastMove = 0L

    override fun onTouch(v: android.view.View, e: MotionEvent): Boolean {
        val a = when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> "down"
            MotionEvent.ACTION_MOVE -> {
                val now = SystemClock.uptimeMillis()
                if (now - lastMove < 16) return true
                lastMove = now
                "move"
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> "up"
            else -> return true
        }
        val x = (e.getX(0) / v.width).coerceIn(0f, 1f)
        val y = (e.getY(0) / v.height).coerceIn(0f, 1f)
        link.send(JSONObject().put("t", "touch").put("a", a).put("x", x.toDouble()).put("y", y.toDouble()))
        return true
    }
}

// ---------- connection to the phone ----------

/**
 * Finds the phone (its UDP announcement, the last address, or the hotspot's gateway), pairs with it
 * and plays its screen. Reconnects by itself when the Wi-Fi drops out.
 */
class PhoneLink(
    private val context: Context,
    private val onState: (PhoneState) -> Unit,
    private val onSize: (Int, Int) -> Unit
) {
    private val prefs = context.getSharedPreferences("phone", Context.MODE_PRIVATE)
    private val wifi = context.getSystemService(WifiManager::class.java)
    private val lock = wifi?.createMulticastLock("lumen-phone")?.apply { setReferenceCounted(false) }
    private val writer = Executors.newSingleThreadExecutor()
    private val queue = LinkedBlockingQueue<Pair<Int, ByteArray>>(240)

    @Volatile private var running = false
    @Volatile private var surface: Surface? = null
    @Volatile private var out: DataOutputStream? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var heard: String? = null
    @Volatile private var heardAt = 0L
    private var threads = emptyList<Thread>()

    fun start(s: Surface) {
        surface = s
        if (running) return
        running = true
        lock?.acquire()
        threads = listOf(Thread(::listen, "phone-beacon"), Thread(::connectLoop, "phone-link"), Thread(::decodeLoop, "phone-video"))
        threads.forEach { it.start() }
    }

    /** The surface is gone (screen turned off): stop everything, start() is called again with a new one. */
    fun pause() = stop()

    fun stop() {
        running = false
        runCatching { socket?.close() }
        threads.forEach { it.interrupt() }
        runCatching { lock?.release() }
    }

    fun send(j: JSONObject) {
        val o = out ?: return
        val b = j.toString().toByteArray()
        writer.execute {
            runCatching { writeMsg(o, 10, b) }
        }
    }

    fun button(a: String) = send(JSONObject().put("t", "button").put("a", a))

    private fun writeMsg(o: DataOutputStream, type: Int, b: ByteArray) = synchronized(o) {
        o.writeByte(type)
        o.writeInt(b.size)
        o.write(b)
        o.flush()
    }

    private fun listen() {
        try {
            DatagramSocket(null).use { s ->
                s.reuseAddress = true
                s.soTimeout = 1000
                s.bind(InetSocketAddress(PHONE_BEACON))
                val buf = ByteArray(1024)
                while (running) {
                    val p = DatagramPacket(buf, buf.size)
                    try {
                        s.receive(p)
                    } catch (_: java.net.SocketTimeoutException) {
                        continue
                    }
                    val m = runCatching { JSONObject(String(p.data, 0, p.length)) }.getOrNull() ?: continue
                    if (m.optString("app") != "lumen-mirror") continue
                    heard = p.address.hostAddress
                    heardAt = SystemClock.uptimeMillis()
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun candidates(): List<String> {
        val list = ArrayList<String>()
        heard?.takeIf { SystemClock.uptimeMillis() - heardAt < 5000 }?.let { list.add(it) }
        prefs.getString("host", null)?.let { list.add(it) }
        // On the phone's hotspot the phone is the gateway.
        @Suppress("DEPRECATION")
        val g = wifi?.dhcpInfo?.gateway ?: 0
        if (g != 0) list.add("${g and 0xff}.${g shr 8 and 0xff}.${g shr 16 and 0xff}.${g shr 24 and 0xff}")
        return list.distinct()
    }

    private fun connectLoop() {
        var shownLooking = false
        while (running) {
            var connected = false
            for (host in candidates()) {
                if (!running) return
                try {
                    session(host)
                    connected = true
                } catch (_: Fatal) {
                    return
                } catch (_: Exception) {
                }
                if (connected) break
            }
            if (!running) return
            if (!connected) askToStart()
            if (!shownLooking || connected) {
                onState(PhoneState.Looking)
                shownLooking = true
            }
            try {
                Thread.sleep(if (connected) 500 else 1500)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    /** A phone that stopped mirroring (e.g. after leaving DeX) still listens for its paired tablet and starts again. */
    private fun askToStart() {
        val key = prefs.getString("key", null) ?: return
        val msg = JSONObject().put("t", "start").put("key", key).toString().toByteArray()
        runCatching {
            DatagramSocket().use { s ->
                s.broadcast = true
                val targets = candidates() + "255.255.255.255"
                for (t in targets) runCatching { s.send(DatagramPacket(msg, msg.size, java.net.InetAddress.getByName(t), PHONE_START)) }
            }
        }
    }

    private class Fatal : Exception()

    /** One connection; returns when it ends. Throws when the phone could not be reached at all. */
    private fun session(host: String) {
        val s = Socket()
        s.connect(InetSocketAddress(host, PHONE_PORT), 1500)
        s.tcpNoDelay = true
        socket = s
        try {
            val input = DataInputStream(BufferedInputStream(s.getInputStream(), 1 shl 16))
            val o = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
            writeMsg(o, 10, JSONObject().put("t", "hello").put("name", Build.MODEL).put("key", prefs.getString("key", "")).toString().toByteArray())
            out = o
            queue.clear()
            queue.offer(-1 to ByteArray(0))
            prefs.edit().putString("host", host).apply()
            while (running) {
                val type = input.read()
                if (type < 0) break
                val len = input.readInt()
                if (len < 0 || len > 8 shl 20) break
                val b = ByteArray(len)
                input.readFully(b)
                if (type == 10) {
                    val m = JSONObject(String(b))
                    when (m.optString("t")) {
                        "paired" -> prefs.edit().putString("key", m.optString("key")).apply()
                        "ok" -> onState(PhoneState.Connected(m.optString("name"), m.optBoolean("control"), m.optBoolean("rotate")))
                        "denied" -> {
                            onState(PhoneState.Problem(m.optString("text")))
                            throw Fatal()
                        }
                        "error" -> onState(PhoneState.Problem(m.optString("text")))
                    }
                } else if (!queue.offer(type to b)) {
                    // The tablet cannot keep up: start again from the next keyframe.
                    queue.clear()
                    send(JSONObject().put("t", "keyframe"))
                }
            }
        } finally {
            out = null
            runCatching { s.close() }
        }
    }

    // ---------- decoding ----------

    private fun decodeLoop() {
        var codec: MediaCodec? = null
        var width = 0
        var height = 0
        val info = MediaCodec.BufferInfo()
        val t0 = System.nanoTime()
        fun release() {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            codec = null
        }
        try {
            while (running) {
                val msg = queue.poll(10, TimeUnit.MILLISECONDS)
                if (msg != null) {
                    val (type, b) = msg
                    when (type) {
                        -1 -> release() // a new connection
                        1 -> {
                            release()
                            val j = JSONObject(String(b))
                            width = j.getInt("w")
                            height = j.getInt("h")
                            onSize(width, height)
                        }
                        2 -> {
                            release()
                            val sf = surface
                            if (width > 0 && sf != null) {
                                val f = MediaFormat.createVideoFormat("video/avc", width, height)
                                val (sps, pps) = splitConfig(b)
                                f.setByteBuffer("csd-0", ByteBuffer.wrap(sps))
                                if (pps != null) f.setByteBuffer("csd-1", ByteBuffer.wrap(pps))
                                codec = MediaCodec.createDecoderByType("video/avc").apply {
                                    configure(f, sf, null, 0)
                                    start()
                                }
                            }
                        }
                        3 -> codec?.let { c ->
                            val idx = c.dequeueInputBuffer(30_000)
                            if (idx >= 0) {
                                val ib = c.getInputBuffer(idx)!!
                                ib.clear()
                                if (b.size <= ib.capacity()) {
                                    ib.put(b)
                                    c.queueInputBuffer(idx, 0, b.size, (System.nanoTime() - t0) / 1000, 0)
                                } else c.queueInputBuffer(idx, 0, 0, 0, 0)
                            }
                        }
                    }
                }
                val c = codec ?: continue
                while (true) {
                    val o = c.dequeueOutputBuffer(info, 0)
                    if (o >= 0) c.releaseOutputBuffer(o, true)
                    else if (o == MediaCodec.INFO_TRY_AGAIN_LATER) break
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            if (running) onState(PhoneState.Problem("The phone's picture cannot be played: ${e.message}"))
        } finally {
            release()
        }
    }

    /** The encoder sends SPS and PPS together; the decoder wants them separately. */
    private fun splitConfig(b: ByteArray): Pair<ByteArray, ByteArray?> {
        for (i in 4 until b.size - 4) {
            if (b[i].toInt() == 0 && b[i + 1].toInt() == 0 && b[i + 2].toInt() == 0 && b[i + 3].toInt() == 1) {
                return b.copyOfRange(0, i) to b.copyOfRange(i, b.size)
            }
        }
        return b to null
    }
}
