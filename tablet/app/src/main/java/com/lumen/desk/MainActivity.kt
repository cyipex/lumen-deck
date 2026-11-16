package com.lumen.desk

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicInteger

/** The connection to the PC lives as long as the process (survives rotation and returning home). */
@SuppressLint("StaticFieldLeak")
object Hub {
    private var link: Link? = null

    fun link(context: Context): Link = link ?: Link(context.applicationContext).also { link = it; it.start() }

    private val holds = AtomicInteger()

    /**
     * A screen you watch without touching is open (second monitor, phone, drawing, presentation, pairing):
     * the screen then neither dims nor shows the screensaver. Each screen calls awake(true) once and awake(false) when it closes.
     */
    fun awake(on: Boolean) {
        if (on) holds.incrementAndGet() else holds.updateAndGet { maxOf(0, it - 1) }
    }

    val keepBright get() = holds.get() > 0

    /** The clock screensaver is showing (after a while without touches, or from its button). */
    val saver = MutableStateFlow(false)

    /** The presentation remote is open: the volume buttons switch slides (true = volume up). */
    @Volatile var volumeKeys: ((Boolean) -> Unit)? = null
}

class MainActivity : ComponentActivity() {
    private val main = Handler(Looper.getMainLooper())
    private var lastTouch = SystemClock.uptimeMillis()
    private var dimmed = false
    // The rest of a touch that only woke the screen or closed the screensaver (it must not press anything underneath).
    private var swallowing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Icons.load(this)
        Fonts.load(this)
        Assets.init(this)
        val link = Hub.link(this)
        // This is the home screen, the Back button has nowhere to go.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })
        setContent { App(link) }
        immersive()
    }

    override fun onResume() {
        super.onResume()
        wake()
        main.post(idleCheck)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(idleCheck)
    }

    // ---------- idle: clock screensaver after 2 min, dimming after 3 min (both can be turned off) ----------

    private val idleCheck = object : Runnable {
        override fun run() {
            val idle = SystemClock.uptimeMillis() - lastTouch
            if (Hub.keepBright) {
                Hub.saver.value = false
            } else {
                if (!Hub.saver.value && Power.saverEnabled(this@MainActivity) && idle >= Power.SAVER_AFTER_MS) Hub.saver.value = true
                if (!dimmed && Power.dimEnabled(this@MainActivity) && idle >= Power.DIM_AFTER_MS) dim()
            }
            main.postDelayed(this, 5_000)
        }
    }

    // Under the screensaver the screen stays dark but the clock readable; otherwise almost off.
    private fun dim() {
        dimmed = true
        window.attributes = window.attributes.apply { screenBrightness = if (Hub.saver.value) 0.12f else 0.02f }
    }

    private fun wake() {
        lastTouch = SystemClock.uptimeMillis()
        if (!dimmed) return
        dimmed = false
        window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // One touch on a dimmed screen or on the screensaver wakes everything up and closes the screensaver,
        // but presses nothing: the whole gesture is swallowed (a tile under the finger could be Lock or Sleep PC).
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && (dimmed || Hub.saver.value)) {
            swallowing = true
            Hub.saver.value = false
            wake()
        }
        if (swallowing) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) swallowing = false
            return true
        }
        lastTouch = SystemClock.uptimeMillis()
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keys = Hub.volumeKeys
        if (keys != null && (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                wake()
                keys(event.keyCode == KeyEvent.KEYCODE_VOLUME_UP)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    @Suppress("DEPRECATION")
    private fun immersive() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }
}

/** Battery saving and screensaver settings (stored only on the tablet). */
object Power {
    const val SAVER_AFTER_MS = 2 * 60_000L
    const val DIM_AFTER_MS = 3 * 60_000L

    private fun prefs(c: Context) = c.getSharedPreferences("power", Context.MODE_PRIVATE)

    fun dimEnabled(c: Context) = prefs(c).getBoolean("dim", true)

    fun setDim(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean("dim", on).apply()
    }

    fun saverEnabled(c: Context) = prefs(c).getBoolean("saver", true)

    fun setSaver(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean("saver", on).apply()
    }
}
