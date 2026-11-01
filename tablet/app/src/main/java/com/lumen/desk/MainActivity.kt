package com.lumen.desk

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent

/** The connection to the PC lives as long as the process (survives rotation and returning home). */
@SuppressLint("StaticFieldLeak")
object Hub {
    private var link: Link? = null

    fun link(context: Context): Link = link ?: Link(context.applicationContext).also { link = it; it.start() }

    /** The second monitor is open: the screen does not dim then, even when nobody touches it. */
    @Volatile var keepBright = false
}

class MainActivity : ComponentActivity() {
    private val main = Handler(Looper.getMainLooper())
    private var lastTouch = SystemClock.uptimeMillis()
    private var dimmed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Icons.load(this)
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

    // ---------- battery saving: dim the screen after inactivity ----------

    private val idleCheck = object : Runnable {
        override fun run() {
            val idle = SystemClock.uptimeMillis() - lastTouch
            if (!dimmed && Power.dimEnabled(this@MainActivity) && !Hub.keepBright && idle >= Power.DIM_AFTER_MS) dim()
            main.postDelayed(this, 10_000)
        }
    }

    private fun dim() {
        dimmed = true
        window.attributes = window.attributes.apply { screenBrightness = 0.02f }
    }

    private fun wake() {
        lastTouch = SystemClock.uptimeMillis()
        if (!dimmed) return
        dimmed = false
        window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // The first touch on a dimmed screen only wakes it up, it does not press anything.
        if (dimmed) {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) wake()
            return true
        }
        lastTouch = SystemClock.uptimeMillis()
        return super.dispatchTouchEvent(ev)
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

/** Battery saving setting (stored only on the tablet). */
object Power {
    const val DIM_AFTER_MS = 3 * 60_000L

    fun dimEnabled(c: Context) = c.getSharedPreferences("power", Context.MODE_PRIVATE).getBoolean("dim", true)

    fun setDim(c: Context, on: Boolean) {
        c.getSharedPreferences("power", Context.MODE_PRIVATE).edit().putBoolean("dim", on).apply()
    }
}
