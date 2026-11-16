package com.lumen.mirror

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

/**
 * Carries out what the user does on the tablet: touches become gestures on the phone's screen,
 * plus the system buttons and typing into the focused text field. Everything runs on the main thread.
 */
class TouchService : AccessibilityService() {
    companion object {
        @Volatile var instance: TouchService? = null
            private set

        /** Set by MainActivity when it starts mirroring by itself (after a restart): the capture prompt is confirmed here. */
        @Volatile var confirmUntil = 0L
    }

    override fun onServiceConnected() {
        instance = this
        MirrorService.onChange?.invoke()
        // After the phone restarts, mirroring starts again without anyone touching the (broken) phone screen.
        if (Prefs(this).autoStart && !MirrorService.running) restartMirroring()
        listenForTablet()
    }

    /**
     * While mirroring is off (e.g. Android ended it when the phone left DeX), the paired tablet can ask
     * for it over UDP: the phone wakes up and starts mirroring again by itself.
     */
    @Volatile private var listening = false
    private var startSocket: DatagramSocket? = null

    private fun listenForTablet() {
        if (listening) return
        listening = true
        Thread({
            try {
                DatagramSocket(null).use { s ->
                    startSocket = s
                    s.reuseAddress = true
                    s.bind(InetSocketAddress(Proto.START_PORT))
                    val buf = ByteArray(512)
                    while (listening) {
                        val p = DatagramPacket(buf, buf.size)
                        s.receive(p)
                        val m = runCatching { JSONObject(String(p.data, 0, p.length)) }.getOrNull() ?: continue
                        val key = Prefs(this).key
                        if (m.optString("t") != "start" || key == null || m.optString("key") != key) continue
                        Handler(mainLooper).post { if (!MirrorService.running && !starting()) wakeAndStart() }
                    }
                }
            } catch (_: Exception) {
            }
            listening = false
        }, "start-listener").start()
    }

    private var lastStart = 0L
    private fun starting() = SystemClock.uptimeMillis() - lastStart < 20_000

    @Suppress("DEPRECATION")
    private fun wakeAndStart() {
        lastStart = SystemClock.uptimeMillis()
        getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "lumen:start")
            .acquire(15_000)
        restartMirroring(300)
    }

    /** Asks for the screen capture again; the system prompt is confirmed in onAccessibilityEvent. */
    fun restartMirroring(delay: Long = 1500) {
        lastStart = SystemClock.uptimeMillis()
        Handler(mainLooper).postDelayed({
            if (!MirrorService.running) {
                startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_AUTO, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }, delay)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        MirrorService.onChange?.invoke()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        listening = false
        runCatching { startSocket?.close() }
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (SystemClock.uptimeMillis() > confirmUntil) return
        if (event.packageName != "com.android.systemui") return
        // Only the system's screen capture prompt that names this app.
        val roots = (windows.mapNotNull { it.root } + listOfNotNull(rootInActiveWindow)).filter { it.packageName == "com.android.systemui" }
        for (root in roots) {
            if (root.findAccessibilityNodeInfosByText("Lumen Mirror").isEmpty()) continue
            val start = root.findAccessibilityNodeInfosByViewId("android:id/button1").firstOrNull() ?: continue
            if (start.performAction(AccessibilityNodeInfo.ACTION_CLICK)) confirmUntil = 0
            return
        }
    }

    // ---------- touches ----------

    // A finger that only taps is sent as one short gesture. When it moves or stays down,
    // the gesture is sent live in short pieces (continued strokes), so scrolling and long presses work as on the phone.
    private var downAt = 0L
    private var downX = 0f
    private var downY = 0f
    private var live: GestureDescription.StrokeDescription? = null
    private var lastX = 0f
    private var lastY = 0f
    private var lastAt = 0L
    private var busy = false
    private var ended = false
    private val pending = ArrayList<FloatArray>()
    private val path = ArrayList<FloatArray>()
    private var liveBroken = false
    private val main by lazy { Handler(mainLooper) }
    private val goLive = Runnable { if (live == null && !ended) startLive() }

    fun touch(a: String, x: Float, y: Float) {
        when (a) {
            "down" -> {
                main.removeCallbacks(goLive)
                live = null
                busy = false
                ended = false
                pending.clear()
                path.clear()
                downAt = SystemClock.uptimeMillis()
                downX = x
                downY = y
                path.add(floatArrayOf(x, y, 0f))
                if (!liveBroken) main.postDelayed(goLive, 180)
            }
            "move" -> {
                path.add(floatArrayOf(x, y, (SystemClock.uptimeMillis() - downAt).toFloat()))
                if (liveBroken) return
                if (live == null) {
                    if (Math.hypot((x - downX).toDouble(), (y - downY).toDouble()) > 12) {
                        main.removeCallbacks(goLive)
                        pending.add(floatArrayOf(x, y))
                        startLive()
                    }
                } else {
                    pending.add(floatArrayOf(x, y))
                    pump()
                }
            }
            "up" -> {
                main.removeCallbacks(goLive)
                ended = true
                val held = SystemClock.uptimeMillis() - downAt
                if (live != null) {
                    pending.add(floatArrayOf(x, y))
                    pump()
                } else if (liveBroken && path.size > 1) {
                    replay(held)
                } else {
                    tap(downX, downY, held.coerceIn(1, 3000))
                }
            }
        }
    }

    private fun tap(x: Float, y: Float, ms: Long) {
        val p = Path().apply { moveTo(x, y) }
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, ms)).build(), null, null)
    }

    /** Fallback for phones without working continued strokes: the whole movement afterwards. */
    private fun replay(held: Long) {
        val p = Path().apply {
            moveTo(path[0][0], path[0][1])
            for (i in 1 until path.size) lineTo(path[i][0], path[i][1])
        }
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, held.coerceIn(10, 10_000))).build(), null, null)
    }

    private fun startLive() {
        val p = Path().apply { moveTo(downX, downY) }
        val s = GestureDescription.StrokeDescription(p, 0, 16, true)
        live = s
        lastX = downX
        lastY = downY
        lastAt = SystemClock.uptimeMillis()
        send(s)
    }

    /** The next piece starts where the last one ended, after the previous one has finished. */
    private fun pump() {
        val prev = live ?: return
        if (busy || pending.isEmpty() && !ended) return
        val p = Path().apply {
            moveTo(lastX, lastY)
            for (pt in pending) lineTo(pt[0], pt[1])
        }
        pending.lastOrNull()?.let {
            lastX = it[0]
            lastY = it[1]
        }
        pending.clear()
        val now = SystemClock.uptimeMillis()
        val ms = (now - lastAt).coerceIn(8, 120)
        lastAt = now
        val last = ended
        val s = prev.continueStroke(p, 0, ms, !last)
        live = if (last) null else s
        send(s)
    }

    private fun send(s: GestureDescription.StrokeDescription) {
        busy = true
        val ok = dispatchGesture(GestureDescription.Builder().addStroke(s).build(), object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription) {
                busy = false
                pump()
            }

            override fun onCancelled(g: GestureDescription) {
                busy = false
                if (live != null) liveBroken = true
                live = null
                pending.clear()
            }
        }, null)
        if (!ok) {
            busy = false
            live = null
            liveBroken = true
        }
    }

    // ---------- buttons ----------

    fun button(a: String) {
        when (a) {
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            "quick" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            "lock" -> if (Build.VERSION.SDK_INT >= 28) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        }
    }

    // ---------- typing ----------

    private fun field(): AccessibilityNodeInfo? = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }

    private fun current(n: AccessibilityNodeInfo): String =
        if (n.isShowingHintText) "" else n.text?.toString() ?: ""

    private fun setText(n: AccessibilityNodeInfo, text: String, cursor: Int) {
        n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        })
        n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
        })
    }

    private fun selection(n: AccessibilityNodeInfo, len: Int): Pair<Int, Int> {
        var s = n.textSelectionStart
        var e = n.textSelectionEnd
        if (s < 0 || e < 0 || s > len || e > len) {
            s = len
            e = len
        }
        return minOf(s, e) to maxOf(s, e)
    }

    fun type(add: String) {
        if (add.isEmpty()) return
        val n = field() ?: return
        val t = current(n)
        val (s, e) = selection(n, t.length)
        setText(n, t.substring(0, s) + add + t.substring(e), s + add.length)
    }

    fun key(k: String) {
        val n = field() ?: return
        val t = current(n)
        val (s, e) = selection(n, t.length)
        when (k) {
            "backspace" -> {
                if (s != e) setText(n, t.substring(0, s) + t.substring(e), s)
                else if (s > 0) {
                    // Do not split an emoji (surrogate pair) in half.
                    val from = if (s >= 2 && Character.isLowSurrogate(t[s - 1]) && Character.isHighSurrogate(t[s - 2])) s - 2 else s - 1
                    setText(n, t.substring(0, from) + t.substring(s), from)
                }
            }
            "enter" -> {
                if (Build.VERSION.SDK_INT >= 30 && !n.isMultiLine) {
                    n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                } else if (n.isMultiLine) {
                    setText(n, t.substring(0, s) + "\n" + t.substring(e), s + 1)
                }
            }
        }
    }
}
