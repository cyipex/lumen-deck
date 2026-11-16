package com.lumen.desk

import android.content.Context
import android.net.wifi.WifiManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import kotlinx.coroutines.delay
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Calendar
import kotlin.random.Random

// ---------- clock screensaver ----------

/**
 * The full-screen clock, styled in the PC settings (Screensaver): black, the background color or an uploaded
 * photo; any clock font; one of nine positions; size and color. MainActivity shows it after 2 minutes without
 * touches (or the clock button does) and closes it on the next touch, which presses nothing else.
 * With "drift" it moves a little every minute so the screen does not keep the same image burned in.
 */
@Composable
fun Screensaver(cfg: PanelConfig?, live: LiveState) {
    val style = cfg?.saver ?: SaverStyle()
    val ready by Assets.ready.collectAsState()
    val font = Fonts.get(style.font.ifEmpty { cfg?.theme?.clockFont })
    val tint = Tints[cfg?.theme?.tint] ?: Tints.getValue("Emerald")
    var now by remember { mutableStateOf(Calendar.getInstance()) }
    LaunchedEffect(style.seconds) {
        while (true) {
            now = Calendar.getInstance()
            delay(if (style.seconds) 1000L - System.currentTimeMillis() % 1000 else 60_000L - System.currentTimeMillis() % 60_000 + 50)
        }
    }
    var shift by remember { mutableStateOf(0f to 0f) }
    LaunchedEffect(now.get(Calendar.MINUTE), style.drift) {
        shift = if (style.drift) (Random.nextFloat() - 0.5f) to (Random.nextFloat() - 0.5f) else 0f to 0f
    }
    val color = when (style.color) {
        "warm" -> androidx.compose.ui.graphics.Color(0xFFFFD9A8)
        "tint" -> androidx.compose.ui.graphics.lerp(tint, White, 0.55f)
        else -> White
    }
    val align = when (style.position) {
        "top-left" -> Alignment.TopStart; "top" -> Alignment.TopCenter; "top-right" -> Alignment.TopEnd
        "left" -> Alignment.CenterStart; "right" -> Alignment.CenterEnd
        "bottom-left" -> Alignment.BottomStart; "bottom" -> Alignment.BottomCenter; "bottom-right" -> Alignment.BottomEnd
        else -> Alignment.Center
    }
    val textAlign = when {
        style.position.endsWith("left") -> Alignment.Start
        style.position.endsWith("right") -> Alignment.End
        else -> Alignment.CenterHorizontally
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Ink).clickable(remember { MutableInteractionSource() }, null) {}) {
        when (style.background) {
            "photo" -> style.photo?.takeIf { it in ready }?.let { id ->
                Photo(id, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Ink.copy(alpha = style.dim / 100f)))
            }
            "tint" -> Background(tint)
        }
        val screenWidth = maxWidth
        Column(
            Modifier.align(align).padding(64.u)
                .offset(x = maxWidth * 0.05f * shift.first, y = maxHeight * 0.05f * shift.second),
            horizontalAlignment = textAlign
        ) {
            val pattern = if (style.seconds) "%02d:%02d:%02d" else "%02d:%02d"
            FitText(
                pattern.format(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), now.get(Calendar.SECOND)),
                font, font.size * 1.25f * style.size / 100f, color, screenWidth - 128.u
            )
            if (style.date) {
                Spacer(Modifier.height(14.u))
                T(dateLine(now), 26.us, color.copy(alpha = 0.85f))
            }
            if (style.weather) live.weather?.let { T("${it.city} · ${it.temp}° · ${it.text}", 20.us, color.copy(alpha = 0.65f)) }
            if (style.claude) live.claude?.let { c -> claudeLine(c)?.let { T(it, 18.us, color.copy(alpha = 0.65f)) } }
        }
    }
}

/** A photo from the PC, decoded only as big as the screen needs (photos from phones are much bigger). */
@Composable
private fun Photo(id: String, modifier: Modifier) {
    var img by remember(id) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(id) {
        img = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val f = Assets.file(id) ?: return@runCatching null
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(f.path, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= 1280 && bounds.outHeight / (sample * 2) >= 800) sample *= 2
                android.graphics.BitmapFactory.decodeFile(f.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
                    ?.asImageBitmap()
            }.getOrNull()
        }
    }
    img?.let { androidx.compose.foundation.Image(it, null, modifier, contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
}

/**
 * The clock at [size] (design points) in [font], made smaller when it would not fit into [maxWidth]
 * (wide fonts, seconds, a big size setting).
 */
@Composable
fun FitText(text: String, font: ClockFont, size: Float, color: androidx.compose.ui.graphics.Color, maxWidth: androidx.compose.ui.unit.Dp) {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val unit = LocalScale.current.unit
    val style = TextStyle(fontFamily = font.family, letterSpacing = font.trackingEm.em, color = color)
    val wanted = with(density) { (size * unit).dp.toSp() }
    val width = remember(text.length, font, wanted) {
        measurer.measure(androidx.compose.ui.text.AnnotatedString(text.replace(Regex("[0-9]"), "0")), style.copy(fontSize = wanted), maxLines = 1).size.width
    }
    val limit = with(density) { maxWidth.toPx() }
    val k = if (width > limit && width > 0) limit / width else 1f
    BasicText(text, style = style.copy(fontSize = wanted * k, lineHeight = wanted * k * 1.05f), maxLines = 1, softWrap = false)
}

private val DAY_NAMES = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
private val MONTH_NAMES = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

private fun dateLine(c: Calendar) = "${DAY_NAMES[c.get(Calendar.DAY_OF_WEEK) - 1]}, ${MONTH_NAMES[c.get(Calendar.MONTH)]} ${c.get(Calendar.DAY_OF_MONTH)}"

// ---------- Claude usage ----------

/** Windows whose reset time has passed are no longer shown (their numbers are not true any more). */
private fun ClaudeUsage.live(nowSec: Long = System.currentTimeMillis() / 1000) =
    copy(five = five?.takeIf { it.resets > nowSec }, week = week?.takeIf { it.resets > nowSec })

private fun claudeLine(c: ClaudeUsage): String? {
    val l = c.live()
    val parts = listOfNotNull(l.five?.let { "5 h ${it.pct} %" }, l.week?.let { "week ${it.pct} %" })
    return if (parts.isEmpty()) null else "Claude · " + parts.joinToString(" · ")
}

/** The chip on the clock card: the 5-hour window, or the weekly one. */
@Composable
fun ClaudeChip(usage: ClaudeUsage?, onClick: () -> Unit) {
    val l = usage?.live()
    val w = l?.five ?: l?.week
    val click = haptic()
    Row(
        Modifier.glass(RoundedCornerShape(20.u)).clickable { click(); onClick() }.height(36.u).padding(horizontal = 12.u),
        verticalAlignment = Alignment.CenterVertically
    ) {
        T("Claude", 14.us, Grey1)
        Spacer(Modifier.width(8.u))
        T(w?.let { "${it.pct} %" } ?: "–", 14.us, if (w != null && w.pct >= 80) Color.Warn else White, FontWeight.Medium)
    }
}

private object Color {
    val Warn = androidx.compose.ui.graphics.Color(0xFFFFB074)
}

@Composable
fun ClaudeCard(usage: ClaudeUsage?, onClose: () -> Unit) {
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            tick = System.currentTimeMillis()
        }
    }
    val l = usage?.live(tick / 1000)
    Overlay(onClose) {
        T("Claude usage", 28.us, weight = FontWeight.SemiBold)
        if (l == null || (l.five == null && l.week == null)) {
            Text(
                "Nothing yet. The numbers come from Claude Code on the PC: they appear after you use Claude Code in a terminal " +
                    "(its status line passes them to Lumen Desk). The Claude desktop app does not send them."
            )
        } else {
            l.five?.let { UsageBar("Current 5-hour window", it, tick) }
            l.week?.let { UsageBar("This week", it, tick) }
            val age = ((tick - l.at) / 60_000).coerceAtLeast(0)
            Text(
                (if (age < 1) "Updated just now" else "Updated $age min ago") +
                    " – from Claude Code on the PC, it refreshes while you use Claude Code in a terminal."
            )
        }
        TextButton("Close", onClose)
    }
}

@Composable
private fun UsageBar(label: String, w: ClaudeWindow, nowMs: Long) {
    Column(verticalArrangement = Arrangement.spacedBy(8.u)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T(label, 17.us, Grey1, modifier = Modifier.weight(1f))
            T("${w.pct} %", 22.us, weight = FontWeight.SemiBold)
        }
        Box(Modifier.fillMaxWidth().height(10.u).clip(CircleShape).background(White.copy(alpha = 0.12f))) {
            Box(Modifier.fillMaxWidth(w.pct / 100f).height(10.u).clip(CircleShape).background(if (w.pct >= 80) Color.Warn else White))
        }
        T("Resets ${resetText(w.resets, nowMs)}", 14.us, Grey2)
    }
}

private fun resetText(resetsSec: Long, nowMs: Long): String {
    val mins = ((resetsSec * 1000 - nowMs) / 60_000).coerceAtLeast(0)
    val at = Calendar.getInstance().apply { timeInMillis = resetsSec * 1000 }
    val clock = "%02d:%02d".format(at.get(Calendar.HOUR_OF_DAY), at.get(Calendar.MINUTE))
    return when {
        mins < 60 -> "in $mins min ($clock)"
        mins < 24 * 60 -> "in ${mins / 60} h ${mins % 60} min ($clock)"
        else -> "${DAY_NAMES[at.get(Calendar.DAY_OF_WEEK) - 1]} at $clock"
    }
}

// ---------- connection details ----------

/**
 * What the tablet itself knows about the connection – it works even when the PC cannot be reached,
 * which is exactly when it is needed. The PC's own addresses are in the PC settings → Tablet.
 */
@Composable
fun ConnectionCard(link: Link, status: Status, onSettings: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val usb by link.viaUsb.collectAsState()
    val (ssid, ip) = remember(status) { tabletNetwork(context) }
    Overlay(onClose) {
        T("Connection", 28.us, weight = FontWeight.SemiBold)
        Fact(
            "Status", when (status) {
                is Status.Connected -> "Connected to ${status.pc}" + if (usb) " by USB cable" else " over Wi-Fi"
                is Status.Pending -> "Waiting for approval on the PC (code ${status.code})"
                is Status.Connecting -> "Trying to reach the PC"
                Status.Searching -> "Looking for the PC"
                is Status.Refused -> "${status.pc.ifEmpty { "The PC" }} does not accept this tablet's key"
            }
        )
        Fact("PC address", (if (usb) "USB cable" else link.currentHost ?: "–") + if (link.isManual) " (entered by hand)" else "")
        link.announcedHost?.let { if (it != link.currentHost) Fact("Last announced by the PC", it) }
        Fact("This tablet", listOfNotNull(ip, ssid?.let { "Wi-Fi \"$it\"" }).joinToString(" · ").ifEmpty { "Not on Wi-Fi" })
        Text(
            "The PC must be on the same Wi-Fi, or connected by USB cable. The PC's addresses are shown on the PC: " +
                "Lumen Desk settings → Tablet → Connect the tablet."
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.u)) {
            TextButton("Try again", { link.retry() }, primary = true)
            TextButton("Enter address", { onClose(); onSettings() })
            TextButton("Close", onClose)
        }
    }
}

@Suppress("DEPRECATION")
private fun tabletNetwork(context: Context): Pair<String?, String?> {
    val ssid = runCatching {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wm.connectionInfo?.ssid?.trim('"')?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" }
    }.getOrNull()
    val ip = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().map { it.hostAddress }.firstOrNull()
    }.getOrNull()
    return ssid to ip
}

@Composable
private fun Fact(label: String, value: String) {
    Column {
        T(label, 13.us, Grey2)
        T(value, 18.us, White)
    }
}

@Composable
private fun Text(s: String) {
    BasicText(s, style = TextStyle(color = Grey2, fontSize = 15.us, fontFamily = Inter))
}

/** A card over the dimmed home screen; a tap outside closes it. */
@Composable
private fun Overlay(onClose: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Ink.copy(alpha = 0.6f)).clickable(remember { MutableInteractionSource() }, null) { onClose() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.glass(RoundedCornerShape(40.u)).background(Ink.copy(alpha = 0.5f))
                .clickable(remember { MutableInteractionSource() }, null) {}
                .padding(36.u).widthIn(max = 640.u),
            verticalArrangement = Arrangement.spacedBy(16.u)
        ) { content() }
    }
}
