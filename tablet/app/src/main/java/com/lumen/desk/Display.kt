package com.lumen.desk

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewConfiguration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.hypot

// ---------- second monitor screen ----------

@Composable
fun DisplayScreen(link: Link, onExit: () -> Unit) {
    var error by remember { mutableStateOf<String?>(null) }
    var hint by remember { mutableStateOf(true) }
    val exit by rememberUpdatedState(onExit)
    LaunchedEffect(Unit) {
        delay(3500)
        hint = false
    }
    // With the second monitor the screen never dims, even when nobody touches it for a long time.
    androidx.compose.runtime.DisposableEffect(Unit) {
        Hub.awake(true)
        onDispose { Hub.awake(false) }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    var stream: VideoStream? = null
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) {
                            stream = VideoStream(link, h.surface, { msg -> post { error = msg } }).also { it.start() }
                        }

                        override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) {}

                        override fun surfaceDestroyed(h: SurfaceHolder) {
                            stream?.stop()
                            stream = null
                        }
                    })
                    setOnTouchListener(TouchMouse(link) { exit() })
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        AnimatedVisibility(hint && error == null, Modifier.align(Alignment.TopCenter).offset(y = 16.u), enter = fadeIn(), exit = fadeOut()) {
            Pill("Back to the panel: tap with three fingers")
        }

        error?.let { msg ->
            Box(Modifier.fillMaxSize().background(Ink.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
                Column(
                    Modifier.glass(RoundedCornerShape(40.u)).padding(40.u).widthIn(max = 640.u),
                    verticalArrangement = Arrangement.spacedBy(14.u)
                ) {
                    T("The second monitor is not working", 28.us, weight = FontWeight.SemiBold)
                    BasicText(msg, style = TextStyle(color = Grey1, fontSize = 17.us, fontFamily = Inter))
                    TextButton("Back to the panel", { exit() }, primary = true)
                }
            }
        }
    }
}

// ---------- receiving and decoding video ----------

/**
 * A separate connection to the PC just for video. The PC sends H.264 as individual NAL units
 * (with a 00 00 00 01 start code), the tablet's hardware decodes them straight onto the surface.
 */
class VideoStream(private val link: Link, private val surface: Surface, private val onError: (String) -> Unit) {
    private val queue = LinkedBlockingQueue<ByteArray>(600)
    @Volatile private var running = true
    private var socket: WebSocket? = null
    private val thread = Thread(::decodeLoop, "video")

    @Volatile private var resetCodec = false
    @Volatile private var fatal = false
    private val retry = Handler(Looper.getMainLooper())

    fun start() {
        thread.start()
        connect()
    }

    // An outage (server restart, Wi-Fi) is not an error: we reconnect in a moment.
    private fun connect() {
        if (!running) return
        link.displayTicket { url, key ->
            if (url == null || key == null || !running) reconnect() else open(url, key)
        }
    }

    // Everything on the video connection is encrypted with the ticket's key; JSON messages start with "{".
    private fun open(url: String, key: ByteArray) {
        val session = Secure.Session(null, key)
        socket = link.http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val data = runCatching { session.open(bytes.toByteArray()) }.getOrNull() ?: return webSocket.cancel()
                if (data.isNotEmpty() && data[0] == '{'.code.toByte()) {
                    val m = runCatching { JSONObject(String(data)) }.getOrNull() ?: return
                    if (m.optString("t") == "error") {
                        fatal = true
                        fail(m.optString("text"))
                    }
                    return
                }
                if (!queue.offer(data)) queue.clear()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = dropped(webSocket)
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped(webSocket)
        })
    }

    private fun dropped(ws: WebSocket) {
        if (ws !== socket || fatal) return
        reconnect()
    }

    private fun reconnect() {
        queue.clear()
        resetCodec = true
        retry.postDelayed(::connect, 1500)
    }

    private fun fail(msg: String) {
        if (running) onError(msg)
    }

    fun stop() {
        running = false
        retry.removeCallbacksAndMessages(null)
        socket?.cancel()
        thread.interrupt()
    }

    private fun decodeLoop() {
        var codec: MediaCodec? = null
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        val info = MediaCodec.BufferInfo()
        val t0 = System.nanoTime()
        try {
            while (running) {
                // New connection = new stream from the PC, the decoder starts over.
                if (resetCodec) {
                    resetCodec = false
                    runCatching { codec?.stop() }
                    runCatching { codec?.release() }
                    codec = null
                    sps = null
                    pps = null
                }
                val nal = queue.poll(10, TimeUnit.MILLISECONDS)
                if (nal != null && nal.size > 4) {
                    val type = nal[4].toInt() and 0x1f
                    val c = codec
                    if (c == null) {
                        // The decoder starts when the stream description (SPS and PPS) arrives before the first keyframe.
                        if (type == 7) sps = nal
                        if (type == 8) pps = nal
                        if (sps != null && pps != null) {
                            val f = MediaFormat.createVideoFormat("video/avc", 1280, 800)
                            f.setByteBuffer("csd-0", ByteBuffer.wrap(sps))
                            f.setByteBuffer("csd-1", ByteBuffer.wrap(pps))
                            codec = MediaCodec.createDecoderByType("video/avc").apply {
                                configure(f, surface, null, 0)
                                start()
                            }
                        }
                    } else if (type != 7 && type != 8) {
                        val idx = c.dequeueInputBuffer(30_000)
                        if (idx >= 0) {
                            val b = c.getInputBuffer(idx)!!
                            b.clear()
                            b.put(nal)
                            c.queueInputBuffer(idx, 0, nal.size, (System.nanoTime() - t0) / 1000, 0)
                        }
                    }
                }
                // Finished frames go straight to the screen, without waiting for their timestamp.
                val c = codec ?: continue
                while (true) {
                    val out = c.dequeueOutputBuffer(info, 0)
                    if (out >= 0) c.releaseOutputBuffer(out, true)
                    else if (out == MediaCodec.INFO_TRY_AGAIN_LATER) break
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            fail("The video cannot be played: ${e.message}")
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
        }
    }
}

// ---------- touches -> mouse on the PC ----------

/**
 * Tap = click, drag = drag with the button held, hold or two-finger tap
 * = right click, two fingers up/down = mouse wheel, three fingers = back to the panel.
 */
class TouchMouse(private val link: Link, private val exit: () -> Unit) : View.OnTouchListener {
    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var done = false
    private var multi = false
    private var scrollY = 0f
    private var scrolled = false
    private var lastMove = 0L
    private var slop = 16f

    private val longPress = Runnable {
        if (!dragging && !multi) {
            done = true
            view?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            send("rclick", downX, downY)
        }
    }

    override fun onTouch(v: View, e: MotionEvent): Boolean {
        view = v
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                slop = ViewConfiguration.get(v.context).scaledTouchSlop.toFloat()
                downX = e.x
                downY = e.y
                dragging = false
                done = false
                multi = false
                send("move", e.x, e.y)
                main.postDelayed(longPress, 550)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                main.removeCallbacks(longPress)
                if (dragging) send("up", e.getX(0), e.getY(0))
                dragging = false
                done = true
                if (e.pointerCount >= 3) {
                    exit()
                    return true
                }
                multi = true
                scrolled = false
                scrollY = avgY(e)
            }
            MotionEvent.ACTION_MOVE -> {
                if (multi) {
                    if (e.pointerCount >= 2) {
                        val y = avgY(e)
                        val dy = y - scrollY
                        if (abs(dy) > 10f) {
                            send("wheel", e.getX(0), e.getY(0), (dy * 4).toInt())
                            scrollY = y
                            scrolled = true
                        }
                    }
                } else if (!done) {
                    if (!dragging && hypot(e.x - downX, e.y - downY) > slop) {
                        dragging = true
                        main.removeCallbacks(longPress)
                        send("down", downX, downY)
                    }
                    val now = SystemClock.uptimeMillis()
                    if (dragging && now - lastMove >= 12) {
                        lastMove = now
                        send("move", e.x, e.y)
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (multi && !scrolled && e.pointerCount == 2) {
                    scrolled = true
                    send("rclick", e.getX(0), e.getY(0))
                }
            }
            MotionEvent.ACTION_UP -> {
                main.removeCallbacks(longPress)
                if (dragging) send("up", e.x, e.y)
                else if (!done && !multi) send("click", downX, downY)
                dragging = false
            }
            MotionEvent.ACTION_CANCEL -> {
                main.removeCallbacks(longPress)
                if (dragging) send("up", e.x, e.y)
                dragging = false
            }
        }
        return true
    }

    private fun avgY(e: MotionEvent): Float {
        var s = 0f
        for (i in 0 until e.pointerCount) s += e.getY(i)
        return s / e.pointerCount
    }

    private fun send(a: String, x: Float, y: Float, d: Int = 0) {
        val v = view ?: return
        link.pointer(a, (x / v.width).coerceIn(0f, 1f), (y / v.height).coerceIn(0f, 1f), d)
    }
}
