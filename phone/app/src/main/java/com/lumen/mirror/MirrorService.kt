package com.lumen.mirror

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Display
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Captures the phone's screen, encodes it as H.264 and sends it to the paired tablet.
 * Runs as a foreground service (Android requires that for screen capture).
 */
class MirrorService : Service() {
    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "stop"

        @Volatile var running = false
            private set
        /** Shown in the phone app: "Waiting for the tablet" / the tablet's name. */
        @Volatile var connectedTo: String? = null
            private set
        var onChange: (() -> Unit)? = null

        // The longer side of the video. The tablets this is made for have 1280 or 1920 pixels.
        private const val MAX_SIDE = 1600
    }

    private lateinit var prefs: Prefs
    private lateinit var displays: DisplayManager
    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var pipeline: Pipeline? = null
    private var client: Client? = null
    private var server: ServerSocket? = null
    private var beacon: Thread? = null
    private var wake: PowerManager.WakeLock? = null
    private var savedBrightness: Pair<Int, Int>? = null
    private var landscape = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        displays = getSystemService(DisplayManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            prefs.autoStart = false
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundNow()
        if (projection != null) return START_NOT_STICKY
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data = intent?.let { if (Build.VERSION.SDK_INT >= 33) it.getParcelableExtra(EXTRA_DATA, Intent::class.java) else @Suppress("DEPRECATION") it.getParcelableExtra(EXTRA_DATA) }
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val mp = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
        if (mp == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                // Android can end the capture itself (e.g. when the phone leaves DeX). Start it again by itself.
                if (running && prefs.autoStart) TouchService.instance?.restartMirroring()
                stopSelf()
            }
        }, main)
        val size = videoSize()
        display = mp.createVirtualDisplay("Lumen Mirror", size.x, size.y, dpi(size), DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, null, null, main)
        displays.registerDisplayListener(displayListener, main)
        running = true
        prefs.autoStart = true
        keepAlive()
        startServer()
        startBeacon()
        changed()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        displays.unregisterDisplayListener(displayListener)
        client?.close()
        client = null
        pipeline?.stop()
        pipeline = null
        runCatching { server?.close() }
        beacon?.interrupt()
        display?.release()
        display = null
        projection?.stop()
        projection = null
        releaseScreen()
        runCatching { cpu?.release() }
        runCatching { wifiLock?.release() }
        connectedTo = null
        changed()
        super.onDestroy()
    }

    private fun changed() = main.post { onChange?.invoke() }

    private fun startForegroundNow() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("mirror", "Mirroring", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, MirrorService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "mirror")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Lumen Mirror")
            .setContentText(connectedTo?.let { "Showing the screen on $it" } ?: "Waiting for the tablet")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)
    }

    // ---------- screen size and rotation ----------

    private fun realSize(): Point {
        val p = Point()
        @Suppress("DEPRECATION")
        displays.getDisplay(Display.DEFAULT_DISPLAY).getRealSize(p)
        return p
    }

    /** The screen scaled down so the longer side fits MAX_SIDE; both sides a multiple of 16 for the encoder. */
    private fun videoSize(): Point {
        val r = realSize()
        val scale = minOf(1f, MAX_SIDE.toFloat() / max(r.x, r.y))
        fun fit(v: Int) = max(16, ((v * scale) / 16f).roundToInt() * 16)
        return Point(fit(r.x), fit(r.y))
    }

    private fun dpi(video: Point): Int {
        val r = realSize()
        return max(120, (resources.displayMetrics.densityDpi * video.x.toFloat() / r.x).roundToInt())
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) {}
        override fun onDisplayRemoved(id: Int) {}
        override fun onDisplayChanged(id: Int) {
            if (id != Display.DEFAULT_DISPLAY) return
            val r = realSize()
            val now = r.x > r.y
            if (now != landscape && client != null) restartPipeline()
        }
    }

    // ---------- video ----------

    private fun restartPipeline() {
        display?.surface = null
        pipeline?.stop()
        pipeline = null
        val c = client ?: return
        val size = videoSize()
        landscape = size.x > size.y
        display?.resize(size.x, size.y, dpi(size))
        pipeline = Pipeline(size, c).also {
            display?.surface = it.surface
        }
    }

    /** One encoder for one screen size; recreated when the phone rotates. */
    private inner class Pipeline(size: Point, private val client: Client) {
        private val codec = MediaCodec.createEncoderByType("video/avc")
        val surface: android.view.Surface
        @Volatile private var alive = true
        private val thread: Thread

        init {
            val f = MediaFormat.createVideoFormat("video/avc", size.x, size.y)
            f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            f.setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
            f.setInteger(MediaFormat.KEY_FRAME_RATE, 60)
            f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10)
            // Even a still screen keeps sending frames, the tablet's decoder needs them to show the last picture.
            f.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000)
            f.setInteger(MediaFormat.KEY_PRIORITY, 0)
            codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = codec.createInputSurface()
            codec.start()
            client.send(Proto.SIZE, JSONObject().put("w", size.x).put("h", size.y).toString().toByteArray())
            thread = Thread(::drain, "encoder").also { it.start() }
        }

        fun keyframe() {
            runCatching { codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
        }

        private fun drain() {
            val info = MediaCodec.BufferInfo()
            try {
                while (alive) {
                    val i = codec.dequeueOutputBuffer(info, 100_000)
                    if (i < 0) continue
                    val buf = codec.getOutputBuffer(i)
                    if (buf != null && info.size > 0) {
                        val b = ByteArray(info.size)
                        buf.position(info.offset)
                        buf.get(b)
                        val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        if (config) client.config(b) else if (!client.frame(b, key)) keyframe()
                    }
                    codec.releaseOutputBuffer(i, false)
                }
            } catch (_: Exception) {
            }
        }

        fun stop() {
            alive = false
            thread.join(500)
            runCatching { codec.stop() }
            runCatching { codec.release() }
            surface.release()
        }
    }

    // ---------- the tablet connection ----------

    private fun startServer() {
        Thread({
            try {
                val s = ServerSocket(Proto.PORT).also { server = it }
                while (running) {
                    val sock = s.accept()
                    Thread({ handshake(sock) }, "client").start()
                }
            } catch (_: Exception) {
            }
        }, "server").start()
    }

    private fun handshake(sock: Socket) {
        try {
            sock.tcpNoDelay = true
            sock.soTimeout = 8000
            val input = DataInputStream(BufferedInputStream(sock.getInputStream()))
            val out = DataOutputStream(BufferedOutputStream(sock.getOutputStream(), 1 shl 16))
            val (type, payload) = Proto.read(input) ?: return sock.close()
            val hello = JSONObject(String(payload))
            if (type != Proto.JSON || hello.optString("t") != "hello") return sock.close()
            val name = hello.optString("name", "Tablet").take(40)
            val known = prefs.key
            if (known == null) {
                // The first tablet is paired. Others are refused until the user forgets it in the phone app.
                val k = prefs.newKey()
                prefs.key = k
                prefs.tabletName = name
                Proto.write(out, Proto.JSON, JSONObject().put("t", "paired").put("key", k).toString().toByteArray())
            } else if (hello.optString("key") != known) {
                Proto.write(out, Proto.JSON, JSONObject().put("t", "denied").put("text", "This phone is paired with another tablet. Open Lumen Mirror on the phone and tap \"Forget the tablet\".").toString().toByteArray())
                return sock.close()
            }
            sock.soTimeout = 0
            val c = Client(sock, input, out, name)
            main.post { attach(c) }
            c.readLoop()
        } catch (_: Exception) {
            runCatching { sock.close() }
        }
    }

    private fun attach(c: Client) {
        if (!running) return c.close()
        client?.close()
        client = c
        connectedTo = c.name
        c.json(JSONObject()
            .put("t", "ok")
            .put("name", Build.MODEL)
            .put("control", TouchService.instance != null)
            .put("rotate", Settings.System.canWrite(this)))
        holdScreen()
        restartPipeline()
        startForegroundNow()
        changed()
    }

    private fun detach(c: Client) = main.post {
        if (client !== c) return@post
        client = null
        display?.surface = null
        pipeline?.stop()
        pipeline = null
        connectedTo = null
        releaseScreen()
        if (running) startForegroundNow()
        changed()
    }

    private inner class Client(val sock: Socket, val input: DataInputStream, val out: DataOutputStream, val name: String) {
        private val queue = LinkedBlockingQueue<Pair<Int, ByteArray>>(90)
        @Volatile private var closed = false
        @Volatile private var waitKey = false
        private var lastConfig: ByteArray? = null
        private var lastSize: ByteArray? = null

        private val writer = Thread({
            try {
                while (!closed) {
                    val (t, b) = queue.take()
                    Proto.write(out, t, b)
                }
            } catch (_: Exception) {
                close()
            }
        }, "writer").also { it.start() }

        fun send(type: Int, b: ByteArray) {
            if (type == Proto.SIZE) lastSize = b
            queue.offer(type to b)
        }

        fun json(j: JSONObject) {
            queue.offer(Proto.JSON to j.toString().toByteArray())
        }

        fun config(b: ByteArray) {
            lastConfig = b
            queue.offer(Proto.CONFIG to b)
        }

        /** False when the network is too slow: the old frames are dropped and we wait for a new keyframe. */
        fun frame(b: ByteArray, key: Boolean): Boolean {
            if (waitKey && !key) return true
            waitKey = false
            if (queue.offer(Proto.FRAME to b)) return true
            queue.clear()
            lastSize?.let { queue.offer(Proto.SIZE to it) }
            lastConfig?.let { queue.offer(Proto.CONFIG to it) }
            waitKey = true
            return false
        }

        fun readLoop() {
            try {
                while (!closed) {
                    val (type, payload) = Proto.read(input) ?: break
                    if (type != Proto.JSON) continue
                    val m = JSONObject(String(payload))
                    main.post { control(m) }
                }
            } catch (_: Exception) {
            }
            close()
        }

        fun close() {
            if (closed) return
            closed = true
            runCatching { sock.close() }
            writer.interrupt()
            detach(this)
        }
    }

    private fun control(m: JSONObject) {
        // Any touch from the tablet turns the phone's screen on, e.g. after it locked itself.
        if (m.optString("a") == "down" || m.optString("t") == "button") wakeUp()
        if (m.optString("t") == "rotate") return rotate()
        if (m.optString("t") == "keyframe") return pipeline?.keyframe() ?: Unit
        val touch = TouchService.instance
        if (touch == null) {
            client?.json(JSONObject().put("t", "error").put("text", "Control is off on the phone. Open Lumen Mirror on the phone and turn on \"Control from the tablet\"."))
            return
        }
        when (m.optString("t")) {
            "touch" -> {
                val r = realSize()
                touch.touch(m.optString("a"), (m.optDouble("x") * r.x).toFloat(), (m.optDouble("y") * r.y).toFloat())
            }
            "button" -> touch.button(m.optString("a"))
            "text" -> touch.type(m.optString("s"))
            "key" -> touch.key(m.optString("k"))
        }
    }

    /** Switches the phone between portrait and landscape (needs "Modify system settings"). */
    private fun rotate() {
        if (!Settings.System.canWrite(this)) return
        val r = realSize()
        runCatching {
            Settings.System.putInt(contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0)
            Settings.System.putInt(contentResolver, Settings.System.USER_ROTATION, if (r.x > r.y) 0 else 1)
        }
    }

    // ---------- keeping the phone awake ----------

    private var cpu: PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    /** With the screen off the phone would put the CPU and Wi-Fi to sleep, and the tablet could not reach it. */
    @Suppress("DEPRECATION")
    private fun keepAlive() {
        cpu = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lumen:server").apply { acquire() }
        wifiLock = applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
            ?.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "lumen:server")?.apply { acquire() }
    }

    /** A screen lock or the power button ends the screen wake lock; a new one turns the screen on again. */
    private fun wakeUp() {
        if (client == null || getSystemService(PowerManager::class.java).isInteractive) return
        runCatching { wake?.release() }
        wake = null
        holdScreen()
    }

    @Suppress("DEPRECATION")
    private fun holdScreen() {
        if (wake == null) {
            val pm = getSystemService(PowerManager::class.java)
            wake = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "lumen:mirror").apply { acquire() }
        }
        if (prefs.dimPhone && savedBrightness == null && Settings.System.canWrite(this)) {
            runCatching {
                val cr = contentResolver
                savedBrightness = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) to Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, 0)
            }
        }
    }

    private fun releaseScreen() {
        runCatching { wake?.release() }
        wake = null
        savedBrightness?.let { (mode, level) ->
            runCatching {
                Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, level)
                Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, mode)
            }
        }
        savedBrightness = null
    }

    // ---------- "I am here" for the tablet ----------

    private fun startBeacon() {
        beacon = Thread({
            try {
                DatagramSocket().use { s ->
                    s.broadcast = true
                    while (running) {
                        val msg = JSONObject().put("app", "lumen-mirror").put("name", Build.MODEL).put("port", Proto.PORT).toString().toByteArray()
                        // Every network the phone is on: Wi-Fi and its own hotspot.
                        val targets = runCatching {
                            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                                .flatMap { it.interfaceAddresses }.mapNotNull { it.broadcast }
                        }.getOrDefault(emptyList()) + InetAddress.getByName("255.255.255.255")
                        for (t in targets.distinct()) runCatching { s.send(DatagramPacket(msg, msg.size, t, Proto.BEACON_PORT)) }
                        Thread.sleep(1000)
                    }
                }
            } catch (_: InterruptedException) {
            } catch (_: Exception) {
            }
        }, "beacon").also { it.start() }
    }
}

