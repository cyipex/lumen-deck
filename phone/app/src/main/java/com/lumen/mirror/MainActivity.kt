package com.lumen.mirror

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.Inet4Address
import java.net.NetworkInterface

/** Setup on the phone (best done once with the phone in a DeX dock or on a working screen). */
class MainActivity : Activity() {
    companion object {
        const val EXTRA_AUTO = "auto"
        private const val REQ_CAPTURE = 1
    }

    private lateinit var prefs: Prefs
    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(32), dp(24), dp(32))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(BG)
            addView(root)
        })
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        if (intent.getBooleanExtra(EXTRA_AUTO, false)) {
            // Started by itself: show up even over the lock screen, so the capture prompt can be confirmed.
            if (Build.VERSION.SDK_INT >= 27) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            }
            // A lock screen without a PIN (or one Smart Lock keeps open) goes away by itself; then the prompt appears.
            val km = getSystemService(android.app.KeyguardManager::class.java)
            if (km.isKeyguardLocked) {
                km.requestDismissKeyguard(this, object : android.app.KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() = startCapture(auto = true)
                    override fun onDismissCancelled() = startCapture(auto = true)
                    override fun onDismissError() = startCapture(auto = true)
                })
            } else startCapture(auto = true)
        }
    }

    override fun onResume() {
        super.onResume()
        MirrorService.onChange = { render() }
        render()
    }

    override fun onPause() {
        MirrorService.onChange = null
        super.onPause()
    }

    // ---------- starting the capture ----------

    private fun startCapture(auto: Boolean) {
        if (auto) TouchService.confirmUntil = SystemClock.uptimeMillis() + 15_000
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), if (auto) REQ_CAPTURE + 100 else REQ_CAPTURE)
    }

    @Deprecated("Activity result API is not needed for one call")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        TouchService.confirmUntil = 0
        if (requestCode != REQ_CAPTURE && requestCode != REQ_CAPTURE + 100) return
        if (resultCode == RESULT_OK && data != null) {
            startForegroundService(Intent(this, MirrorService::class.java)
                .putExtra(MirrorService.EXTRA_CODE, resultCode)
                .putExtra(MirrorService.EXTRA_DATA, data))
        }
        if (requestCode == REQ_CAPTURE + 100) moveTaskToBack(true)
    }

    // ---------- the screen ----------

    private fun render() {
        root.removeAllViews()
        text("Lumen Mirror", 30f, Color.WHITE, bold = true)
        text("Use your tablet as the screen and touch panel of this phone.", 16f, GREY, top = 6)

        val control = TouchService.instance != null
        val settings = Settings.System.canWrite(this)
        val battery = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

        section("1 · Control from the tablet", if (control) "On" else "Off – needed for touches, buttons and typing", control) {
            text("In the list open Installed apps (or Downloaded apps) → Lumen Mirror → On.", 14f, GREY, top = 6)
            button(if (control) "Open settings" else "Turn on", !control) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        section("2 · Landscape and brightness", if (settings) "Allowed" else "Optional – lets the tablet rotate the phone and dim its screen", settings) {
            button(if (settings) "Open settings" else "Allow", false) {
                startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
            }
        }
        section("3 · Keep running", if (battery) "Battery optimization is off" else "Recommended – otherwise the phone may stop mirroring in the background", battery) {
            if (!battery) button("Allow", false) {
                runCatching { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
            }
        }

        val on = MirrorService.running
        section("4 · Mirroring", when {
            !on -> "Stopped"
            MirrorService.connectedTo != null -> "Showing the screen on ${MirrorService.connectedTo}"
            else -> "Waiting for the tablet"
        }, on && MirrorService.connectedTo != null) {
            if (on) {
                button("Stop", false) { startService(Intent(this, MirrorService::class.java).setAction(MirrorService.ACTION_STOP)) }
            } else {
                button("Start mirroring", true) { startCapture(auto = false) }
            }
            text("On the tablet: open Lumen Desk and tap the phone button. The tablet must be on the same Wi-Fi as the phone or on the phone's hotspot. After a restart the phone starts mirroring by itself.", 14f, GREY, top = 10)
            addresses().takeIf { it.isNotEmpty() }?.let { text("Phone address: ${it.joinToString(", ")}", 14f, GREY, top = 6) }
        }

        section("Options", null, null) {
            root.addView(CheckBox(this).apply {
                text = "Dim the phone's own screen while the tablet is connected"
                setTextColor(Color.WHITE)
                textSize = 15f
                isChecked = prefs.dimPhone
                setOnCheckedChangeListener { _, v -> prefs.dimPhone = v }
            })
            val paired = prefs.tabletName
            if (prefs.key != null) {
                text("Paired with ${paired ?: "a tablet"}. Other tablets are refused.", 14f, GREY, top = 6)
                button("Forget the tablet", false) {
                    prefs.key = null
                    prefs.tabletName = null
                    render()
                }
            } else {
                text("Not paired yet: the first tablet that connects is paired.", 14f, GREY, top = 6)
            }
        }
    }

    private fun addresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().map { it.hostAddress ?: "" }.filter { it.isNotEmpty() }
    }.getOrDefault(emptyList())

    // ---------- small building blocks ----------

    private fun section(title: String, status: String?, ok: Boolean?, body: () -> Unit) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(CARD)
                setStroke(dp(1), RIM)
            }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) }
        }
        root.addView(card)
        val outer = root
        root = card
        text(title, 18f, Color.WHITE, bold = true)
        if (status != null) text((if (ok == true) "✓ " else "") + status, 14f, if (ok == true) GREEN else GREY, top = 4)
        body()
        root = outer
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false, top: Int = 0) {
        root.addView(TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
        })
    }

    private fun button(label: String, primary: Boolean, onClick: () -> Unit) {
        root.addView(Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            setTextColor(if (primary) BG else Color.WHITE)
            stateListAnimator = null
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(if (primary) Color.WHITE else 0x14FFFFFF)
                setStroke(dp(1), if (primary) Color.WHITE else RIM)
            }
            gravity = Gravity.CENTER
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(-2, dp(48)).apply { topMargin = dp(12) }
            setPadding(dp(22), 0, dp(22), 0)
            textAlignment = View.TEXT_ALIGNMENT_CENTER
        })
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

private const val BG = 0xFF050505.toInt()
private const val CARD = 0xFF141416.toInt()
private const val RIM = 0x33FFFFFF
private const val GREY = 0xFFA8A8A8.toInt()
private const val GREEN = 0xFF8FD6A8.toInt()
