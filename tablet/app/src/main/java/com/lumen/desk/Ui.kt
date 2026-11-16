package com.lumen.desk

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import java.util.Calendar
import kotlin.math.roundToInt
import kotlin.random.Random

// ---------- basic building blocks ----------

@Composable
fun T(
    text: String,
    size: TextUnit,
    color: Color = White,
    weight: FontWeight = FontWeight.Normal,
    family: FontFamily = Inter,
    modifier: Modifier = Modifier,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    lineHeight: TextUnit = TextUnit.Unspecified
) {
    BasicText(
        text,
        modifier,
        style = TextStyle(color = color, fontSize = size, fontWeight = weight, fontFamily = family, letterSpacing = letterSpacing, lineHeight = lineHeight),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
fun Glyph(name: String, size: Dp, color: Color, modifier: Modifier = Modifier) {
    Image(Icons.get(name), null, modifier.size(size), colorFilter = ColorFilter.tint(color))
}

/** Glass: a translucent layer with a highlight on top and a light rim. */
fun Modifier.glass(shape: Shape): Modifier = this
    .clip(shape)
    .background(Brush.verticalGradient(0f to White.copy(alpha = 0.10f), 0.4f to White.copy(alpha = 0.04f), 1f to White.copy(alpha = 0.05f)))
    .border(1.dp, Brush.verticalGradient(listOf(White.copy(alpha = 0.32f), White.copy(alpha = 0.10f), White.copy(alpha = 0.12f))), shape)

@Composable
fun haptic(): () -> Unit {
    val view = LocalView.current
    return { view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
}

// ---------- background: dark, three colored glows and grain ----------

@Composable
fun Background(tint: Color) {
    val grain = remember { noise() }
    Canvas(Modifier.fillMaxSize()) {
        val k = size.width / 1280f
        drawRect(Ink)
        fun blob(cx: Float, cy: Float, r: Float, a: Float) {
            val c = Offset(cx * k, cy * k)
            drawCircle(
                Brush.radialGradient(
                    0f to tint.copy(alpha = a), 0.4f to tint.copy(alpha = a * 0.55f), 0.72f to tint.copy(alpha = a * 0.16f), 1f to Color.Transparent,
                    center = c, radius = r * k
                ),
                r * k, c
            )
        }
        blob(170f, 130f, 560f, 0.85f)
        blob(1170f, 710f, 500f, 0.45f)
        blob(650f, 730f, 440f, 0.35f)
        drawRect(ShaderBrush(ImageShader(grain, TileMode.Repeated, TileMode.Repeated)), alpha = 0.07f)
    }
}

private fun noise(): ImageBitmap {
    val n = 160
    val px = IntArray(n * n) {
        val v = Random.nextInt(256)
        (255 shl 24) or (v shl 16) or (v shl 8) or v
    }
    return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888).asImageBitmap()
}

// ---------- the whole app ----------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun App(link: Link) {
    val cfg by link.config.collectAsState()
    val live by link.live.collectAsState()
    val status by link.status.collectAsState()
    var settings by remember { mutableStateOf(false) }
    var display by remember { mutableStateOf(false) }
    var phone by remember { mutableStateOf(false) }
    var touchpad by remember { mutableStateOf(false) }
    var presenter by remember { mutableStateOf(false) }
    var drawing by remember { mutableStateOf(false) }
    var claudeCard by remember { mutableStateOf(false) }
    var connectionCard by remember { mutableStateOf(false) }
    val saver by Hub.saver.collectAsState()
    var library by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        link.toasts.collect {
            toast = it
            delay(2600)
            toast = null
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Ink)) {
        CompositionLocalProvider(LocalScale provides Scale(maxWidth.value / 1280f, 1f)) {
            val tint by animateColorAsState(Tints[cfg?.theme?.tint] ?: Tints.getValue("Emerald"), tween(700), label = "tint")
            Background(tint)

            val c = cfg
            if (c == null || c.pages.isEmpty()) {
                Waiting(status) { settings = true }
            } else {
                val pager = rememberPagerState { c.pages.size }
                val scope = rememberCoroutineScope()
                val goTo: (String?) -> Unit = { id ->
                    val i = c.pages.indexOfFirst { it.id == id }
                    if (i >= 0) scope.launch { pager.animateScrollToPage(i) }
                }
                // A short, quick swipe is enough to change the page, and the page settles softly.
                val fling = PagerDefaults.flingBehavior(
                    pager,
                    pagerSnapDistance = PagerSnapDistance.atMost(1),
                    snapAnimationSpec = spring(dampingRatio = 0.86f, stiffness = 420f),
                    snapPositionalThreshold = 0.18f
                )
                HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, flingBehavior = fling, key = { c.pages.getOrNull(it)?.id ?: it }) { i ->
                    val page = c.pages.getOrNull(i) ?: return@HorizontalPager
                    val onTap: (Tile) -> Unit = { t ->
                        when (t.kind) {
                            "page" -> goTo(t.page)
                            "display" -> display = true
                            "touchpad" -> touchpad = true
                            "presenter" -> presenter = true
                            "draw" -> drawing = true
                            else -> link.tap(t)
                        }
                    }
                    // The neighbouring page shrinks a little while sliding, so the movement reads clearly.
                    // Read only while drawing, so sliding does not rebuild the pages on every frame.
                    Box(Modifier.fillMaxSize().graphicsLayer {
                        val away = kotlin.math.abs((pager.currentPage - i) + pager.currentPageOffsetFraction).coerceAtMost(1f)
                        scaleX = 1f - 0.05f * away
                        scaleY = 1f - 0.05f * away
                    }) {
                        when (page.layout) {
                            "home" -> HomePage(page, c, live, status, link, onTap, { library = true }, { apps = true }, { phone = true }, { touchpad = true }, { presenter = true }, { drawing = true }, { claudeCard = true }, { connectionCard = true }) { settings = true }
                            "sliders" -> SlidersPage(live, link)
                            "big" -> GridPage(page, c, live, onTap, cols = 3, rows = 2)
                            else -> GridPage(page, c, live, onTap, cols = 4, rows = 3)
                        }
                    }
                }
                if (c.pages.size > 1) Dots(c.pages.size, pager.currentPage, Modifier.align(Alignment.BottomCenter)) { i -> scope.launch { pager.animateScrollToPage(i) } }
                if (status !is Status.Connected && status !is Status.Pending && status !is Status.Refused) {
                    OfflineBanner(status, link, Modifier.align(Alignment.TopCenter).offset(y = 10.u))
                }
            }

            if (library) LibraryScreen(link, live.spotify, tint) { library = false }
            if (apps) AppsScreen(tint) { apps = false }
            if (display) DisplayScreen(link) { display = false }
            if (phone) PhoneScreen { phone = false }
            if (touchpad) TouchpadScreen(link) { touchpad = false }
            if (presenter) PresenterScreen(link) { presenter = false }
            if (drawing) DrawScreen(link) { drawing = false }
            Notices(link, Modifier.align(Alignment.TopEnd).padding(top = 20.u, end = 20.u))

            if (status is Status.Pending) {
                PairingCard(status as Status.Pending)
                // The code has to stay readable while the user walks to the PC.
                androidx.compose.runtime.DisposableEffect(Unit) {
                    Hub.awake(true)
                    onDispose { Hub.awake(false) }
                }
            }
            if (status is Status.Refused) RefusedCard((status as Status.Refused).pc, link)
            if (claudeCard) ClaudeCard(live.claude) { claudeCard = false }
            if (connectionCard) ConnectionCard(link, status, { settings = true }) { connectionCard = false }

            AnimatedVisibility(toast != null, Modifier.align(Alignment.BottomCenter).offset(y = (-36).u), enter = fadeIn(), exit = fadeOut()) {
                Pill(toast ?: "")
            }
            if (settings) LauncherSettings(link, cfg, status, tint) { settings = false }
            // Above everything; MainActivity closes it on the next touch without pressing anything underneath.
            if (saver) Screensaver(cfg, live)
        }
    }
}

/**
 * A short outage shows only a small pill. When the PC is unreachable for more than a few seconds,
 * a clear "PC is offline" card appears with a button for an immediate retry.
 */
@Composable
fun OfflineBanner(status: Status, link: Link, modifier: Modifier) {
    var long by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(6000)
        long = true
    }
    if (!long) {
        Pill(if (status is Status.Connecting) "Connecting to PC…" else "Looking for PC…", modifier)
        return
    }
    Row(
        modifier.glass(RoundedCornerShape(28.u)).background(Ink.copy(alpha = 0.45f)).padding(start = 24.u, end = 12.u, top = 12.u, bottom = 12.u),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(10.u).clip(CircleShape).background(Color(0xFFFF8A65)))
        Spacer(Modifier.width(14.u))
        Column {
            T("PC is offline", 18.us, weight = FontWeight.SemiBold)
            T(
                if (status is Status.Connecting) "Trying ${status.host} · Check that Lumen Desk is running" else "Check that the PC is on and Lumen Desk is running",
                14.us, Grey1
            )
        }
        Spacer(Modifier.width(20.u))
        TextButton("Try again", { link.retry() }, primary = true)
    }
}

/** Notifications from the PC: top right, at most three at once, each disappears after 7 s (or when tapped). */
@Composable
fun Notices(link: Link, modifier: Modifier) {
    val list = remember { androidx.compose.runtime.mutableStateListOf<Notice>() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        link.notices.collect { n ->
            list.add(0, n)
            while (list.size > 3) list.removeAt(list.size - 1)
            scope.launch {
                delay(7000)
                list.remove(n)
            }
        }
    }
    Column(modifier.widthIn(max = 460.u), verticalArrangement = Arrangement.spacedBy(10.u), horizontalAlignment = Alignment.End) {
        for (n in list) {
            androidx.compose.runtime.key(n.id) {
                Row(
                    Modifier.glass(RoundedCornerShape(26.u)).background(Ink.copy(alpha = 0.55f))
                        .clickable(remember { MutableInteractionSource() }, null) { list.remove(n) }
                        .padding(18.u),
                    verticalAlignment = Alignment.Top
                ) {
                    Box(Modifier.size(40.u).clip(CircleShape).background(White.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                        T(n.app.take(1).uppercase(), 18.us, weight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.width(14.u))
                    Column(Modifier.widthIn(max = 370.u)) {
                        T(n.app, 13.us, Grey2)
                        Spacer(Modifier.height(2.u))
                        T(n.title, 17.us, weight = FontWeight.SemiBold)
                        if (n.body.isNotEmpty()) {
                            Spacer(Modifier.height(2.u))
                            BasicText(
                                n.body,
                                style = TextStyle(color = Grey0, fontSize = 15.us, fontFamily = Inter),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun Pill(text: String, modifier: Modifier = Modifier) {
    Box(modifier.glass(RoundedCornerShape(22.u)).background(Ink.copy(alpha = 0.35f)).padding(horizontal = 20.u, vertical = 10.u)) {
        T(text, 15.us, weight = FontWeight.Medium)
    }
}

@Composable
fun Dots(count: Int, current: Int, modifier: Modifier, onPick: (Int) -> Unit) {
    // Each dot has a finger-sized touch area: a tap jumps straight to that page.
    Row(modifier) {
        repeat(count) { i ->
            val a by animateFloatAsState(if (i == current) 0.95f else 0.28f, label = "dot")
            val w by animateFloatAsState(if (i == current) 18f else 6f, label = "dotw")
            Box(
                Modifier.size(width = 30.u, height = 24.u).clickable(remember { MutableInteractionSource() }, null) { onPick(i) },
                contentAlignment = Alignment.Center
            ) {
                Box(Modifier.size(width = w.u, height = 6.u).clip(CircleShape).background(White.copy(alpha = a)))
            }
        }
    }
}

// ---------- home page (layout A) ----------

@Composable
fun HomePage(page: Page, cfg: PanelConfig, live: LiveState, status: Status, link: Link, onTap: (Tile) -> Unit, onLibrary: () -> Unit, onApps: () -> Unit, onPhone: () -> Unit, onTouchpad: () -> Unit, onPresenter: () -> Unit, onDraw: () -> Unit, onClaude: () -> Unit, onConnection: () -> Unit, onSettings: () -> Unit) {
    Row(Modifier.fillMaxSize().padding(24.u), horizontalArrangement = Arrangement.spacedBy(18.u)) {
        val usb by link.viaUsb.collectAsState()
        ClockCard(cfg, live, status, usb, Modifier.weight(1.25f).fillMaxHeight(), onApps, onPhone, onTouchpad, onPresenter, onDraw, onClaude, onConnection, onSettings)
        Column(Modifier.weight(2f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(18.u)) {
            SpotifyCard(live.spotify, live.audio, cfg.theme.media, link, onLibrary, Modifier.weight(1f).fillMaxWidth())
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.u)) {
                Quad(page.tiles.subList(0, 4), cfg, live, onTap)
                Quad(page.tiles.subList(4, 8), cfg, live, onTap)
            }
        }
    }
}

@Composable
fun RowScope.Quad(ids: List<String?>, cfg: PanelConfig, live: LiveState, onTap: (Tile) -> Unit) {
    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.u)) {
        for (row in 0..1) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.u)) {
                for (col in 0..1) Slot(ids.getOrNull(row * 2 + col), cfg, live, onTap, small = true)
            }
        }
    }
}

@Composable
fun RowScope.Slot(id: String?, cfg: PanelConfig, live: LiveState, onTap: (Tile) -> Unit, small: Boolean) {
    val tile = id?.let { cfg.tiles[it] }
    val m = Modifier.weight(1f).fillMaxHeight()
    if (tile == null) Spacer(m) else TileView(tile, live.tiles[tile.id] == true, small, m) { onTap(tile) }
}

@Composable
fun TileView(tile: Tile, on: Boolean, small: Boolean, modifier: Modifier, big: Boolean = false, onTap: () -> Unit) {
    val click = haptic()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg by animateColorAsState(if (on) White.copy(alpha = 0.20f) else White.copy(alpha = 0.06f), label = "bg")
    val edge by animateColorAsState(if (on) White.copy(alpha = 0.92f) else White.copy(alpha = 0.11f), label = "edge")
    val fg by animateColorAsState(if (on) White else Grey1, label = "fg")
    val scale by animateFloatAsState(
        when { pressed -> 0.95f; on -> 1.03f; else -> 1f },
        spring(dampingRatio = 0.6f, stiffness = 500f), label = "scale"
    )
    val shape = RoundedCornerShape(32.u)
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(bg)
            .border(if (on) 2.dp else 1.dp, edge, shape)
            .clickable(interaction, indication = null) { click(); onTap() }
            .padding(if (small) 22.u else if (big) 32.u else 26.u)
    ) {
        Glyph(tile.icon, if (small) 36.u else if (big) 60.u else 44.u, fg)
        T(tile.label, if (small) 16.us else if (big) 26.us else 19.us, fg, FontWeight.Medium, modifier = Modifier.align(Alignment.BottomStart))
    }
}

// ---------- clock ----------

private val DAYS = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
private val MONTHS = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

@Composable
fun rememberNow(): Calendar {
    var now by remember { mutableStateOf(Calendar.getInstance()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Calendar.getInstance()
            delay(60_000 - System.currentTimeMillis() % 60_000 + 50)
        }
    }
    return now
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ClockCard(cfg: PanelConfig, live: LiveState, status: Status, usb: Boolean, modifier: Modifier, onApps: () -> Unit, onPhone: () -> Unit, onTouchpad: () -> Unit, onPresenter: () -> Unit, onDraw: () -> Unit, onClaude: () -> Unit, onConnection: () -> Unit, onSettings: () -> Unit) {
    val now = rememberNow()
    val h = now.get(Calendar.HOUR_OF_DAY)
    val greeting = when { h < 5 -> "Good night"; h < 12 -> "Good morning"; h < 18 -> "Good afternoon"; h < 22 -> "Good evening"; else -> "Good night" }
    val ready by Assets.ready.collectAsState()
    val font = remember(cfg.theme.clockFont, ready) { Fonts.get(cfg.theme.clockFont) }
    // Clock and info on the left, the shortcuts in one column on the right.
    Row(
        modifier
            .glass(RoundedCornerShape(40.u))
            .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onLongClick = onSettings) {}
            .padding(start = 32.u, top = 28.u, bottom = 28.u, end = 20.u)
    ) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
            Column(verticalArrangement = Arrangement.spacedBy(10.u)) {
                T(greeting, 18.us, Grey2)
                // A tap shows how the tablet is connected (or why not).
                val connected = status is Status.Connected
                Row(
                    Modifier.glass(RoundedCornerShape(18.u)).clickable(onClick = onConnection).height(34.u).padding(horizontal = 12.u),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(7.u).clip(CircleShape).background(if (connected) White else Color(0xFFFF8A65)))
                    Spacer(Modifier.width(8.u))
                    T(if (connected) live.pc.ifEmpty { "PC" } + (if (usb) " · USB" else " · Wi-Fi") else "Offline", 13.us, if (connected) Grey1 else Grey0)
                    Spacer(Modifier.width(6.u))
                    Glyph("chevron-right", 13.u, Grey2)
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val width = maxWidth
                Column {
                    Box(Modifier.offset(x = (-3).u)) {
                        FitText("%02d:%02d".format(h, now.get(Calendar.MINUTE)), font, font.size, White, width)
                    }
                    Spacer(Modifier.height(18.u))
                    T("${DAYS[now.get(Calendar.DAY_OF_WEEK) - 1]}, ${MONTHS[now.get(Calendar.MONTH)]} ${now.get(Calendar.DAY_OF_MONTH)}", 23.us, Grey0)
                    live.weather?.let { w ->
                        Spacer(Modifier.height(8.u))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Glyph("cloud", 19.u, Grey2)
                            Spacer(Modifier.width(9.u))
                            T("${w.city} · ${w.temp}° · ${w.text}", 16.us, Grey2)
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.u)) {
                Chip("CPU", live.cpu?.let { "$it %" } ?: "–")
                Chip("RAM", live.ram?.let { "$it %" } ?: "–")
                ClaudeChip(live.claude, onClaude)
            }
        }
        Spacer(Modifier.width(18.u))
        Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.u)) {
            // Tools: touchpad with keyboard, presentation remote, drawing tablet, the phone's screen, screensaver ...
            DockButton("touchpad", onTouchpad)
            DockButton("slides", onPresenter)
            DockButton("pen", onDraw)
            DockButton("phone", onPhone)
            DockButton("clock") { Hub.saver.value = true }
            Spacer(Modifier.weight(1f))
            // ... and the tablet itself: app catalog and settings.
            DockButton("grid", onApps)
            DockButton("gear", onSettings)
        }
    }
}

/** A shortcut in the clock card's column: a rounded square. */
@Composable
fun DockButton(icon: String, onClick: () -> Unit) {
    val click = haptic()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, spring(dampingRatio = 0.6f, stiffness = 600f), label = "dock")
    Box(
        Modifier.size(58.u)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .glass(RoundedCornerShape(18.u))
            .background(White.copy(alpha = if (pressed) 0.14f else 0.03f))
            .clickable(interaction, indication = null) { click(); onClick() },
        contentAlignment = Alignment.Center
    ) {
        Glyph(icon, 26.u, Grey0)
    }
}

@Composable
fun CornerButton(icon: String, onClick: () -> Unit) {
    val click = haptic()
    Box(
        Modifier.size(44.u).glass(CircleShape).clickable { click(); onClick() },
        contentAlignment = Alignment.Center
    ) {
        Glyph(icon, 20.u, Grey1)
    }
}

@Composable
fun Chip(label: String, value: String) {
    Row(
        Modifier.glass(RoundedCornerShape(20.u)).height(36.u).padding(horizontal = 12.u),
        verticalAlignment = Alignment.CenterVertically
    ) {
        T(label, 14.us, Grey1)
        Spacer(Modifier.width(8.u))
        T(value, 14.us, White, FontWeight.Medium)
    }
}

// ---------- Spotify ----------

private object ArtCache {
    private val map = object : LinkedHashMap<String, ImageBitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 120
    }

    @Synchronized fun get(url: String) = map[url]
    @Synchronized fun put(url: String, img: ImageBitmap) { map[url] = img }
}

@Composable
fun rememberArt(url: String): ImageBitmap? {
    var img by remember(url) { mutableStateOf(ArtCache.get(url)) }
    LaunchedEffect(url) {
        if (url.isEmpty() || img != null) return@LaunchedEffect
        img = withContext(Dispatchers.IO) {
            runCatching {
                URL(url).openStream().use { BitmapFactory.decodeStream(it) }?.asImageBitmap()?.also { ArtCache.put(url, it) }
            }.getOrNull()
        }
    }
    return img
}

private fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

/** The player on the home page. Style "compact" has a small slider, "volume" a big vertical one for PC volume. */
@Composable
fun SpotifyCard(sp: Spotify, audio: Audio?, style: String, link: Link, onLibrary: () -> Unit, modifier: Modifier) {
    val tint = Tints.getValue("Emerald")
    val big = style == "volume" && audio != null
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(sp.playing, sp.receivedAt) {
        while (sp.playing) {
            tick = System.currentTimeMillis()
            delay(500)
        }
    }
    val pos = Link.position(sp, maxOf(tick, sp.receivedAt))
    val art = rememberArt(sp.image)

    Row(
        modifier.glass(RoundedCornerShape(40.u)).padding(if (big) 26.u else 28.u),
        horizontalArrangement = Arrangement.spacedBy(if (big) 26.u else 28.u),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.fillMaxHeight().widthIn(max = if (big) 200.u else 220.u).aspectSquare()
                .clip(RoundedCornerShape(28.u))
                .background(Brush.linearGradient(listOf(tint, Color(0xFF121214))))
                .border(1.dp, White.copy(alpha = 0.14f), RoundedCornerShape(28.u)),
            contentAlignment = Alignment.Center
        ) {
            if (art != null) Image(art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Glyph("music", 56.u, White.copy(alpha = 0.75f))
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.u).clip(CircleShape).background(if (sp.playing) White else White.copy(alpha = 0.3f)))
                    Spacer(Modifier.width(8.u))
                    T(
                        when { !sp.active -> "Spotify · nothing playing"; sp.playing -> "Spotify · playing on PC"; else -> "Spotify · paused" },
                        13.us, Grey2, modifier = Modifier.weight(1f)
                    )
                    LibraryChip(onLibrary)
                }
                Spacer(Modifier.height(6.u))
                T(if (sp.active) sp.title else "Play music on the PC", 28.us, weight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.u))
                T(if (sp.active) sp.artist else "The buttons control the player on the PC", 17.us, Grey1)
            }
            Column {
                Bar(if (sp.duration > 0) pos.toFloat() / sp.duration else 0f, White, true, Modifier.fillMaxWidth()) { f ->
                    if (sp.duration > 0) link.spotify("seek", (f * sp.duration).toLong())
                }
                Row(Modifier.fillMaxWidth()) {
                    T(fmt(pos), 13.us, Grey2, modifier = Modifier.weight(1f))
                    T(fmt(sp.duration), 13.us, Grey2)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoundButton("prev", 52.u, null, White) { link.spotify("prev") }
                Spacer(Modifier.width(10.u))
                RoundButton(if (sp.playing) "pause" else "play", 68.u, White, Ink) { link.spotify("toggle") }
                Spacer(Modifier.width(10.u))
                RoundButton("next", 52.u, null, White) { link.spotify("next") }
                Spacer(Modifier.weight(1f))
                if (big) OutputChip(audio!!) { link.audio("output") }
                else if (audio != null) {
                    Glyph(if (audio.muted) "volume-x" else "volume", 20.u, Grey1)
                    Spacer(Modifier.width(10.u))
                    Bar(if (audio.muted) 0f else audio.volume / 100f, Grey1, false, Modifier.width(130.u)) { f -> link.audio("volume", (f * 100).roundToInt()) }
                } else if (sp.volume != null) {
                    Glyph("volume", 20.u, Grey1)
                    Spacer(Modifier.width(10.u))
                    Bar(sp.volume / 100f, Grey1, false, Modifier.width(130.u)) { f -> link.spotify("volume", (f * 100).toInt()) }
                }
            }
        }
        if (big) VolumeColumn(audio!!, link, Modifier.width(92.u).fillMaxHeight())
    }
}

/** Opens the Spotify library (playlists, liked songs, search). */
@Composable
fun LibraryChip(onClick: () -> Unit) {
    val click = haptic()
    Row(
        Modifier.glass(RoundedCornerShape(20.u)).clickable { click(); onClick() }.height(40.u).padding(horizontal = 14.u),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Glyph("list", 16.u, Grey1)
        Spacer(Modifier.width(8.u))
        T("Library", 14.us, White, FontWeight.Medium)
    }
}

private val HEADPHONES = Regex("headphone|headset|airpods|buds|arctis|hyperx", RegexOption.IGNORE_CASE)

/** Current audio output; a tap switches to the next one. */
@Composable
fun OutputChip(audio: Audio, onClick: () -> Unit) {
    val click = haptic()
    Row(
        Modifier.glass(RoundedCornerShape(22.u)).clickable { click(); onClick() }.height(44.u).padding(horizontal = 14.u).widthIn(max = 170.u),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Glyph(if (HEADPHONES.containsMatchIn(audio.output)) "headphones" else "speaker", 18.u, Grey1)
        Spacer(Modifier.width(8.u))
        T(audio.output.substringBefore(" (").ifEmpty { "Output" }, 14.us, White, FontWeight.Medium)
    }
}

/** Vertical PC volume slider: tap or drag, with mute below it. */
@Composable
fun VolumeColumn(audio: Audio, link: Link, modifier: Modifier) {
    val click = haptic()
    var drag by remember { mutableStateOf<Float?>(null) }
    var sentAt by remember { mutableLongStateOf(0L) }
    val target = drag ?: if (audio.muted) 0f else audio.volume / 100f
    val fill by animateFloatAsState(target, if (drag != null) snap() else tween(200), label = "vol")
    val pct = (target * 100).roundToInt()
    // While dragging, at most ~14 values per second go to the PC, always the last one.
    val push: (Float, Boolean) -> Unit = { f, force ->
        val now = System.currentTimeMillis()
        if (force || now - sentAt > 70) {
            sentAt = now
            link.audio("volume", (f * 100).roundToInt())
        }
    }
    val shape = RoundedCornerShape(30.u)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.u)) {
        T(if (audio.muted && drag == null) "Muted" else "$pct %", 20.us, weight = FontWeight.SemiBold)
        Box(
            Modifier.weight(1f).width(76.u)
                .clip(shape)
                .background(White.copy(alpha = 0.07f))
                .border(1.dp, White.copy(alpha = 0.14f), shape)
                .pointerInput(Unit) { detectTapGestures { push((1f - it.y / size.height).coerceIn(0f, 1f), true) } }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { drag = (1f - it.y / size.height).coerceIn(0f, 1f) },
                        onDragEnd = { drag?.let { push(it, true) }; drag = null },
                        onDragCancel = { drag = null }
                    ) { c, _ ->
                        c.consume()
                        val f = (1f - c.position.y / size.height).coerceIn(0f, 1f)
                        drag = f
                        push(f, false)
                    }
                }
        ) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(fill).background(Brush.verticalGradient(listOf(White, Grey0))))
            Glyph(
                when { pct == 0 -> "volume-x"; pct < 50 -> "volume-low"; else -> "volume" },
                26.u, if (fill > 0.12f) Ink else Grey1,
                Modifier.align(Alignment.BottomCenter).padding(bottom = 18.u)
            )
        }
        Box(
            Modifier.width(76.u).height(44.u).clip(RoundedCornerShape(22.u))
                .background(if (audio.muted) White else White.copy(alpha = 0.07f))
                .border(1.dp, White.copy(alpha = if (audio.muted) 1f else 0.14f), RoundedCornerShape(22.u))
                .clickable { click(); link.audio("mute") },
            contentAlignment = Alignment.Center
        ) {
            Glyph("volume-x", 18.u, if (audio.muted) Ink else Grey1)
        }
    }
}

/** A square sized by the available height (album cover). */
private fun Modifier.aspectSquare() = layout { measurable, constraints ->
    val side = minOf(constraints.maxHeight, constraints.maxWidth)
    val p = measurable.measure(Constraints.fixed(side, side))
    layout(side, side) { p.place(0, 0) }
}

@Composable
fun RoundButton(icon: String, size: Dp, fill: Color?, fg: Color, onClick: () -> Unit) {
    val click = haptic()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, spring(dampingRatio = 0.6f, stiffness = 600f), label = "btn")
    Box(
        Modifier.size(size)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(fill ?: Color.Transparent)
            .clickable(interaction, indication = null) { click(); onClick() },
        contentAlignment = Alignment.Center
    ) {
        Glyph(icon, size * 0.4f, fg, if (icon == "play") Modifier.offset(x = 2.u) else Modifier)
    }
}

/** Slider: a tap or drag changes the value (track position, volume). */
@Composable
fun Bar(fraction: Float, color: Color, knob: Boolean, modifier: Modifier, onChange: (Float) -> Unit) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val change by rememberUpdatedState(onChange)
    val shown = (drag ?: fraction).coerceIn(0f, 1f)
    val track = 4.u
    val knobR = 6.u
    Box(
        modifier.height(28.u)
            .pointerInput(Unit) { detectTapGestures { change((it.x / size.width).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { drag = (it.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = { drag?.let { change(it) }; drag = null },
                    onDragCancel = { drag = null }
                ) { c, _ ->
                    c.consume()
                    drag = (c.position.x / size.width).coerceIn(0f, 1f)
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Canvas(Modifier.fillMaxWidth().height(12.u)) {
            val t = track.toPx()
            val y = size.height / 2
            val r = CornerRadius(t / 2)
            drawRoundRect(White.copy(alpha = 0.14f), Offset(0f, y - t / 2), Size(size.width, t), r)
            drawRoundRect(color, Offset(0f, y - t / 2), Size(size.width * shown, t), r)
            if (knob) drawCircle(color, knobR.toPx(), Offset(size.width * shown, y))
        }
    }
}

// ---------- other pages: grid ----------

@Composable
fun GridPage(page: Page, cfg: PanelConfig, live: LiveState, onTap: (Tile) -> Unit, cols: Int, rows: Int) {
    val gap = if (cols == 3) 18.u else 14.u
    Column(Modifier.fillMaxSize().padding(24.u), verticalArrangement = Arrangement.spacedBy(gap)) {
        for (row in 0 until rows) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                for (col in 0 until cols) {
                    val id = page.tiles.getOrNull(row * cols + col)
                    val tile = id?.let { cfg.tiles[it] }
                    val m = Modifier.weight(1f).fillMaxHeight()
                    if (tile == null) Spacer(m)
                    else TileView(tile, live.tiles[tile.id] == true, small = false, modifier = m, big = cols == 3) { onTap(tile) }
                }
            }
        }
    }
}

// ---------- connection states and settings ----------

@Composable
fun Waiting(status: Status, onSettings: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.glass(RoundedCornerShape(40.u)).padding(40.u).widthIn(max = 620.u),
            verticalArrangement = Arrangement.spacedBy(14.u)
        ) {
            T("Lumen Desk", 44.us, family = ClockFonts.getValue("Fraunces").family, letterSpacing = (-0.03f).em)
            T(if (status is Status.Connecting) "Connecting to PC…" else "Looking for the PC on the network…", 20.us, Grey0)
            BasicText(
                "Start Lumen Desk on the PC. The tablet and the PC must be on the same Wi-Fi (or connected by USB cable).",
                style = TextStyle(color = Grey2, fontSize = 16.us, fontFamily = Inter)
            )
            Spacer(Modifier.height(6.u))
            TextButton("Enter PC address manually", onSettings)
        }
    }
}

@Composable
fun PairingCard(p: Status.Pending) {
    Box(Modifier.fillMaxSize().background(Ink.copy(alpha = 0.55f)).clickable(remember { MutableInteractionSource() }, null) {}, contentAlignment = Alignment.Center) {
        Column(Modifier.glass(RoundedCornerShape(40.u)).background(Ink.copy(alpha = 0.75f)).padding(40.u).widthIn(max = 660.u), verticalArrangement = Arrangement.spacedBy(12.u)) {
            T(if (p.known) "The PC does not know this tablet any more" else "Allow the tablet on the PC", 30.us, weight = FontWeight.SemiBold)
            BasicText(
                "On the PC${if (p.pc.isNotEmpty()) " ${p.pc}" else ""} open Lumen Desk → Tablet. Press Allow only if it shows this same code:",
                style = TextStyle(color = Grey1, fontSize = 18.us, fontFamily = Inter)
            )
            T(p.code.chunked(3).joinToString(" "), 54.us, weight = FontWeight.SemiBold, letterSpacing = 0.08f.em)
            BasicText(
                if (p.known) "This tablet was allowed before. If you did not remove it on this PC and the code is not on its screen, do not allow it."
                else "A different code means another device is in between – do not allow it then. You only need to do this once.",
                style = TextStyle(color = Grey2, fontSize = 15.us, fontFamily = Inter)
            )
        }
    }
}

/**
 * The PC closed the connection because it does not accept this tablet's key – usually because the tablet was
 * paired with another PC (each PC makes its own key) or removed on this PC. Pairing again shows a code to allow.
 */
@Composable
fun RefusedCard(pc: String, link: Link) {
    Box(Modifier.fillMaxSize().background(Ink.copy(alpha = 0.55f)).clickable(remember { MutableInteractionSource() }, null) {}, contentAlignment = Alignment.Center) {
        Column(Modifier.glass(RoundedCornerShape(40.u)).background(Ink.copy(alpha = 0.75f)).padding(40.u).widthIn(max = 660.u), verticalArrangement = Arrangement.spacedBy(14.u)) {
            T("${pc.ifEmpty { "The PC" }} does not accept this tablet", 30.us, weight = FontWeight.SemiBold)
            BasicText(
                "The tablet's key for this PC is not the one the PC knows – it was probably paired with another PC with Lumen Desk, " +
                    "or removed on this PC. Pair again: the tablet then shows a code, and you allow it on the PC (Lumen Desk → Tablet). " +
                    "Your pages and tiles are kept on the PC.",
                style = TextStyle(color = Grey1, fontSize = 17.us, fontFamily = Inter)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.u)) {
                TextButton("Pair again", { link.pairAgain() }, primary = true)
                TextButton("Try again", { link.retry() })
            }
        }
    }
}

@Composable
fun TextButton(text: String, onClick: () -> Unit, primary: Boolean = false) {
    val click = haptic()
    Box(
        Modifier.clip(RoundedCornerShape(18.u))
            .background(if (primary) White else White.copy(alpha = 0.08f))
            .border(1.dp, White.copy(alpha = if (primary) 1f else 0.18f), RoundedCornerShape(18.u))
            .clickable { click(); onClick() }
            .padding(horizontal = 22.u, vertical = 14.u)
    ) {
        T(text, 16.us, if (primary) Ink else White, FontWeight.Medium)
    }
}

@Composable
fun SettingsCard(link: Link, status: Status, onClose: () -> Unit) {
    var host by remember { mutableStateOf(link.manualHost ?: "") }
    Box(Modifier.fillMaxSize().background(Ink.copy(alpha = 0.6f)).clickable(remember { MutableInteractionSource() }, null) { onClose() }, contentAlignment = Alignment.Center) {
        Column(
            Modifier.glass(RoundedCornerShape(40.u)).clickable(remember { MutableInteractionSource() }, null) {}.padding(36.u).widthIn(max = 640.u),
            verticalArrangement = Arrangement.spacedBy(14.u)
        ) {
            T("Connection to PC", 28.us, weight = FontWeight.SemiBold)
            T(
                when (status) {
                    is Status.Connected -> "Connected to ${status.pc}"
                    is Status.Pending -> "Waiting for approval on the PC"
                    is Status.Connecting -> "Connecting to ${status.host}"
                    Status.Searching -> "Looking for the PC on the network"
                    is Status.Refused -> "The PC does not accept this tablet any more"
                }, 17.us, Grey1
            )
            BasicText(
                "The tablet finds the PC by itself. If that fails (e.g. a different network), enter the PC's IP address, for example 192.168.1.20:47801.",
                style = TextStyle(color = Grey2, fontSize = 15.us, fontFamily = Inter)
            )
            BasicTextField(
                host, { host = it.trim() },
                singleLine = true,
                textStyle = TextStyle(color = White, fontSize = 20.us, fontFamily = Inter),
                cursorBrush = SolidColor(White),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.u)).background(White.copy(alpha = 0.08f))
                    .border(1.dp, White.copy(alpha = 0.2f), RoundedCornerShape(16.u)).padding(16.u)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.u)) {
                TextButton("Save", {
                    link.manualHost = host.takeIf { it.isNotBlank() }?.let { if (':' in it) it else "$it:47801" }
                    onClose()
                }, primary = true)
                TextButton("Search automatically", { link.manualHost = null; onClose() })
                TextButton("Close", onClose)
            }
        }
    }
}
