package com.lumen.desk

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ---------- app catalog ----------

class AppEntry(val label: String, val component: ComponentName, val icon: ImageBitmap)

private fun Drawable.toImage(size: Int): ImageBitmap {
    if (this is BitmapDrawable && bitmap != null) return Bitmap.createScaledBitmap(bitmap, size, size, true).asImageBitmap()
    val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = AndroidCanvas(b)
    setBounds(0, 0, size, size)
    draw(c)
    return b.asImageBitmap()
}

/** All apps that can be launched (except this launcher), sorted by name. */
fun loadApps(context: Context): List<AppEntry> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .filter { it.activityInfo.packageName != context.packageName }
        .map {
            AppEntry(
                it.loadLabel(pm).toString(),
                ComponentName(it.activityInfo.packageName, it.activityInfo.name),
                it.loadIcon(pm).toImage(144)
            )
        }
        .sortedBy { it.label.lowercase() }
}

private object AppCache {
    var apps: List<AppEntry>? = null
}

@Composable
fun AppsScreen(tint: Color, onClose: () -> Unit) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf(AppCache.apps) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        // The list is refreshed on every open (new / uninstalled apps); until then the old one is shown.
        val fresh = withContext(Dispatchers.IO) { loadApps(context) }
        AppCache.apps = fresh
        apps = fresh
    }
    val shown = apps.orEmpty().filter { query.isBlank() || it.label.contains(query.trim(), ignoreCase = true) }

    Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null) {}) {
        Background(tint)
        Column(Modifier.fillMaxSize().padding(24.u).glass(RoundedCornerShape(40.u)).padding(top = 24.u, start = 28.u, end = 28.u)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T("Apps", 30.us, weight = FontWeight.SemiBold)
                Spacer(Modifier.width(24.u))
                SearchField(query, "Search apps", Modifier.weight(1f)) { query = it }
                Spacer(Modifier.width(16.u))
                RoundButton("close", 48.u, White.copy(alpha = 0.10f), White) { onClose() }
            }
            Spacer(Modifier.height(18.u))
            if (apps == null) T("Loading…", 16.us, Grey2)
            LazyVerticalGrid(
                GridCells.Fixed(8),
                Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 24.u),
                verticalArrangement = Arrangement.spacedBy(10.u),
                horizontalArrangement = Arrangement.spacedBy(10.u)
            ) {
                items(shown, key = { it.component.flattenToString() }) { app ->
                    val click = haptic()
                    Column(
                        Modifier.clip(RoundedCornerShape(22.u)).clickable {
                            click()
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                                        .setComponent(app.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                                )
                            }
                            onClose()
                        }.padding(vertical = 14.u, horizontal = 6.u),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Image(app.icon, null, Modifier.size(72.u))
                        Spacer(Modifier.height(10.u))
                        BasicText(
                            app.label,
                            style = TextStyle(color = Grey0, fontSize = 14.us, fontFamily = Inter, textAlign = TextAlign.Center),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SearchField(value: String, hint: String, modifier: Modifier, onChange: (String) -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(22.u)).background(White.copy(alpha = 0.08f))
            .border(1.dp, White.copy(alpha = 0.16f), RoundedCornerShape(22.u)).padding(horizontal = 16.u, vertical = 12.u),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Glyph("search", 18.u, Grey2)
        Spacer(Modifier.width(10.u))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) T(hint, 16.us, Grey2)
            BasicTextField(
                value, onChange,
                singleLine = true,
                textStyle = TextStyle(color = White, fontSize = 16.us, fontFamily = Inter),
                cursorBrush = SolidColor(White),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

// ---------- launcher settings ----------

/** Is this launcher the default home screen? */
fun isDefaultHome(context: Context): Boolean {
    val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    val r = context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
    return r?.activityInfo?.packageName == context.packageName
}

/** "Exit launcher": opens another launcher on the tablet (e.g. the stock one). */
fun openOtherLauncher(context: Context): Boolean {
    val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    // The tablet's own (system) launcher first; never the old Liquid Control, which shows its old layout.
    val skip = setOf(context.packageName, "com.android.settings", "com.liquid.control")
    val others = context.packageManager.queryIntentActivities(home, 0).filter { it.activityInfo.packageName !in skip }
    val other = others.firstOrNull { it.activityInfo.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0 }
        ?: others.firstOrNull()
        ?: return false
    context.startActivity(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .setComponent(ComponentName(other.activityInfo.packageName, other.activityInfo.name))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
    return true
}

/** "Set as default launcher": the system home screen picker. */
fun openHomeSettings(context: Context) {
    val tries = listOf(
        Intent(Settings.ACTION_HOME_SETTINGS),
        Intent("com.android.settings.PREFERRED_SETTINGS"),
        Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
    )
    for (i in tries) {
        if (runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
    }
}

private val MEDIA_STYLES = listOf("compact" to "Compact", "volume" to "With volume")

@Composable
fun LauncherSettings(link: Link, cfg: PanelConfig?, status: Status, tint: Color, onClose: () -> Unit) {
    val context = LocalContext.current
    val connected = status is Status.Connected
    val usb by link.viaUsb.collectAsState()
    var host by remember { mutableStateOf(link.manualHost ?: "") }
    var isDefault by remember { mutableStateOf(isDefaultHome(context)) }

    Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null) {}) {
        Background(tint)
        Row(Modifier.fillMaxSize().padding(24.u), horizontalArrangement = Arrangement.spacedBy(18.u)) {
            // ---- appearance (saved on the PC, applies to the tablet and the PC settings) ----
            Column(
                Modifier.weight(1.35f).glass(RoundedCornerShape(40.u)).verticalScroll(rememberScrollState()).padding(32.u),
                verticalArrangement = Arrangement.spacedBy(14.u)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T("Settings", 32.us, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    RoundButton("close", 48.u, White.copy(alpha = 0.10f), White) { onClose() }
                }
                if (!connected) T("The appearance is saved on the PC – connect the tablet to the PC to change it.", 15.us, Grey2)

                Label("Background color")
                Row(horizontalArrangement = Arrangement.spacedBy(10.u)) {
                    for ((name, color) in Tints) {
                        val sel = cfg?.theme?.tint == name
                        Box(
                            Modifier.size(52.u).clip(CircleShape).background(color)
                                .border(if (sel) 3.dp else 1.dp, if (sel) White else White.copy(alpha = 0.25f), CircleShape)
                                .clickable(enabled = connected) { link.setting(listOf("theme", "tint"), name) }
                        )
                    }
                }
                T(cfg?.theme?.tint ?: "", 14.us, Grey2)

                Label("Clock font")
                // 20 fonts: a row to swipe through (uploading your own font is in the PC settings → Appearance).
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.u)) {
                    for ((name, f) in ClockFonts) {
                        val sel = cfg?.theme?.clockFont == name
                        Column(
                            Modifier.width(150.u).clip(RoundedCornerShape(22.u))
                                .background(if (sel) White.copy(alpha = 0.16f) else White.copy(alpha = 0.05f))
                                .border(1.dp, if (sel) White else White.copy(alpha = 0.12f), RoundedCornerShape(22.u))
                                .clickable(enabled = connected) { link.setting(listOf("theme", "clockFont"), name) }
                                .padding(vertical = 14.u),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            T("21:14", (44f * 150f / f.size).us, family = f.family, letterSpacing = f.trackingEm.em)
                            T(name, 13.us, Grey2)
                        }
                    }
                }

                Label("Media tile")
                Row(horizontalArrangement = Arrangement.spacedBy(10.u)) {
                    for ((id, name) in MEDIA_STYLES) {
                        Choice(name, cfg?.theme?.media == id, connected, Modifier.weight(1f)) { link.setting(listOf("theme", "media"), id) }
                    }
                }

                Label("Battery saving")
                var dim by remember { mutableStateOf(Power.dimEnabled(context)) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.u)) {
                    Choice("Dim after 3 min", dim, true, Modifier.weight(1f)) { dim = true; Power.setDim(context, true) }
                    Choice("Never dim", !dim, true, Modifier.weight(1f)) { dim = false; Power.setDim(context, false) }
                }

                Label("Clock screensaver")
                var saver by remember { mutableStateOf(Power.saverEnabled(context)) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.u)) {
                    Choice("After 2 min", saver, true, Modifier.weight(1f)) { saver = true; Power.setSaver(context, true) }
                    Choice("Only from its button", !saver, true, Modifier.weight(1f)) { saver = false; Power.setSaver(context, false) }
                }

                Label("Notifications from the PC")
                Row(horizontalArrangement = Arrangement.spacedBy(10.u)) {
                    Choice("Show", cfg?.notify != false, connected, Modifier.weight(1f)) { link.setting(listOf("notify", "enabled"), true) }
                    Choice("Off", cfg?.notify == false, connected, Modifier.weight(1f)) { link.setting(listOf("notify", "enabled"), false) }
                }
                T("Tiles, macros and pages are edited in Lumen Desk on the PC.", 14.us, Grey2)
            }

            // ---- connection and launcher ----
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(18.u)) {
                Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(40.u)).padding(28.u), verticalArrangement = Arrangement.spacedBy(12.u)) {
                    T("Connection to PC", 22.us, weight = FontWeight.SemiBold)
                    T(
                        when (status) {
                            is Status.Connected -> "Connected to ${status.pc}" + if (usb) " over USB cable" else " over Wi-Fi"
                            is Status.Pending -> "Waiting for approval on the PC"
                            is Status.Connecting -> "Connecting to ${status.host}"
                            Status.Searching -> "Looking for the PC on the network"
                            is Status.Refused -> "${status.pc} does not accept this tablet any more"
                        }, 15.us, Grey1
                    )
                    BasicText(
                        "The tablet finds the PC by itself (over USB cable or Wi-Fi). If that fails, enter the PC's IP address, e.g. 192.168.1.20.",
                        style = TextStyle(color = Grey2, fontSize = 14.us, fontFamily = Inter)
                    )
                    BasicTextField(
                        host, { host = it.trim() },
                        singleLine = true,
                        textStyle = TextStyle(color = White, fontSize = 18.us, fontFamily = Inter),
                        cursorBrush = SolidColor(White),
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.u)).background(White.copy(alpha = 0.08f))
                            .border(1.dp, White.copy(alpha = 0.2f), RoundedCornerShape(16.u)).padding(14.u)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.u)) {
                        TextButton("Save", {
                            link.manualHost = host.takeIf { it.isNotBlank() }?.let { if (':' in it) it else "$it:47801" }
                        }, primary = true)
                        TextButton("Automatic", { host = ""; link.manualHost = null })
                    }
                }
                Column(Modifier.fillMaxWidth().weight(1f).glass(RoundedCornerShape(40.u)).padding(28.u), verticalArrangement = Arrangement.spacedBy(12.u)) {
                    T("Launcher", 22.us, weight = FontWeight.SemiBold)
                    T(if (isDefault) "Lumen Desk is the default home screen." else "Another launcher is the default home screen.", 15.us, Grey1)
                    Spacer(Modifier.weight(1f))
                    WideButton("Set as default launcher", "home", primary = !isDefault) {
                        openHomeSettings(context)
                        isDefault = isDefaultHome(context)
                    }
                    WideButton("Exit launcher", "power", primary = false) {
                        if (!openOtherLauncher(context)) link.toasts.tryEmit("There is no other launcher on this tablet")
                        onClose()
                    }
                }
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Spacer(Modifier.height(6.u))
    T(text, 15.us, Grey1, FontWeight.Medium)
}

@Composable
private fun Choice(text: String, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(52.u).clip(RoundedCornerShape(26.u))
            .background(if (selected) White else White.copy(alpha = 0.06f))
            .border(1.dp, if (selected) White else White.copy(alpha = 0.14f), RoundedCornerShape(26.u))
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        T(text, 16.us, if (selected) Ink else White, FontWeight.Medium)
    }
}

@Composable
private fun WideButton(text: String, icon: String, primary: Boolean, onClick: () -> Unit) {
    val click = haptic()
    Row(
        Modifier.fillMaxWidth().height(60.u).clip(RoundedCornerShape(30.u))
            .background(if (primary) White else White.copy(alpha = 0.08f))
            .border(1.dp, White.copy(alpha = if (primary) 1f else 0.18f), RoundedCornerShape(30.u))
            .clickable { click(); onClick() }
            .padding(horizontal = 22.u),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Glyph(icon, 22.u, if (primary) Ink else White)
        Spacer(Modifier.width(14.u))
        T(text, 17.us, if (primary) Ink else White, FontWeight.Medium)
    }
}
