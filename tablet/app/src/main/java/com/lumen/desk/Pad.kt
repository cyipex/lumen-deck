package com.lumen.desk

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp
import kotlin.math.abs

// ---------- touchpad and keyboard screen ----------

// Windows adds its own pointer acceleration on top, so the factors stay low.
private val SPEEDS = listOf("Slow" to 0.7f, "Normal" to 1.1f, "Fast" to 1.7f)

@Composable
fun TouchpadScreen(link: Link, onExit: () -> Unit) {
    val prefs = LocalContext.current.getSharedPreferences("pad", Context.MODE_PRIVATE)
    var speed by remember { mutableIntStateOf(prefs.getInt("speed", 1).coerceIn(0, SPEEDS.size - 1)) }
    var keyboard by remember { mutableStateOf(prefs.getBoolean("keyboard", false)) }
    val keys = remember { KeyState(link) }
    // Leaving the screen never leaves a mouse button or a locked Ctrl pressed on the PC.
    DisposableEffect(Unit) {
        onDispose {
            keys.releaseAll()
            link.pad("release")
        }
    }

    Box(Modifier.fillMaxSize().background(Ink).clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null) {}) {
        Column(Modifier.fillMaxSize().padding(16.u), verticalArrangement = Arrangement.spacedBy(12.u)) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Touchpad(
                    link, SPEEDS[speed].second, Modifier.fillMaxSize(),
                    if (keyboard) "Touchpad" else "Tap = click · 2 fingers = scroll, tap = right click, pinch = zoom · Double tap and hold = drag · 3 fingers = switch apps"
                )
                Row(Modifier.align(Alignment.TopStart).padding(14.u), verticalAlignment = Alignment.CenterVertically) {
                    CornerButton("close", onExit)
                }
                Row(Modifier.align(Alignment.TopEnd).padding(14.u), horizontalArrangement = Arrangement.spacedBy(10.u), verticalAlignment = Alignment.CenterVertically) {
                    SmallChip(SPEEDS[speed].first) {
                        speed = (speed + 1) % SPEEDS.size
                        prefs.edit().putInt("speed", speed).apply()
                    }
                    CornerButton("windows") { link.key("win") }
                    CornerButton(if (keyboard) "chevron-down" else "keyboard") {
                        keyboard = !keyboard
                        prefs.edit().putBoolean("keyboard", keyboard).apply()
                    }
                }
            }
            if (keyboard) Keyboard(keys, Modifier.fillMaxWidth())
            else ClickButtons(link, Modifier.fillMaxWidth().height(86.u))
        }
    }
}

@Composable
fun SmallChip(text: String, onClick: () -> Unit) {
    val click = haptic()
    Box(
        Modifier.glass(RoundedCornerShape(22.u)).clickable { click(); onClick() }.height(44.u).padding(horizontal = 16.u),
        contentAlignment = Alignment.Center
    ) {
        T(text, 14.us, White, FontWeight.Medium)
    }
}

/** The surface itself: shows a light dot under each finger. */
@Composable
fun Touchpad(link: Link, gain: Float, modifier: Modifier, hint: String) {
    val scope = rememberCoroutineScope()
    val logic = remember { PadLogic(link, scope) }
    logic.gain = gain
    var fingers by remember { mutableStateOf<List<Offset>>(emptyList()) }
    val shape = RoundedCornerShape(36.u)
    Box(
        modifier
            .glass(shape)
            .pointerInput(Unit) { awaitEachGesture { with(logic) { gesture(viewConfiguration.touchSlop) { fingers = it } } } },
        contentAlignment = Alignment.Center
    ) {
        if (fingers.isEmpty()) {
            androidx.compose.foundation.text.BasicText(
                hint,
                Modifier.padding(horizontal = 60.u),
                style = androidx.compose.ui.text.TextStyle(
                    color = White.copy(alpha = 0.22f), fontSize = 16.us, fontFamily = Inter,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            )
        }
        val r = with(LocalDensity.current) { 34.u.toPx() }
        Canvas(Modifier.fillMaxSize()) {
            for (f in fingers) {
                drawCircle(White.copy(alpha = 0.10f), r, f)
                drawCircle(White.copy(alpha = 0.35f), r * 0.28f, f)
            }
        }
    }
}

/**
 * Turns touches into a laptop-style touchpad: one finger moves the pointer (faster when the finger moves fast),
 * tap = click, two fingers scroll (with a glide), pinch zooms (Ctrl + wheel), two- and three-finger taps
 * are right and middle click, double tap and hold drags, three and four finger swipes switch apps and desktops.
 */
private class PadLogic(private val link: Link, private val scope: CoroutineScope) {
    var gain = 1.6f
    private var restX = 0f
    private var restY = 0f
    private var restWheel = 0f
    private var restHWheel = 0f
    private var lastTapAt = 0L
    private var lastTapPos = Offset.Zero
    private var pendingClick: Job? = null
    private var glide: Job? = null

    suspend fun AwaitPointerEventScope.gesture(slop: Float, show: (List<Offset>) -> Unit) {
        val down = awaitFirstDown(requireUnconsumed = false)
        glide?.cancel()
        val startAt = down.uptimeMillis
        // A touch right after a tap: the button stays pressed while this finger moves (drag), or it is a double click.
        val second = pendingClick?.isActive == true && startAt - lastTapAt < 260 && (down.position - lastTapPos).getDistance() < 90f
        if (second) pendingClick?.cancel()
        var dragging = false
        var maxFingers = 1
        var mode = Mode.None
        var travel = 0f
        var count = 1
        var prev = down.position
        var lastAt = startAt
        var spread0 = 0f
        var prevSpread = 0f
        var twoTravel = 0f
        var zoomRest = 0f
        var swipe = Offset.Zero
        var swiped = false
        var velocity = Offset.Zero
        show(listOf(down.position))

        while (true) {
            val ev = awaitPointerEvent()
            val pts = ev.changes.filter { it.pressed }
            ev.changes.forEach { it.consume() }
            show(pts.map { it.position })
            if (pts.isEmpty()) break
            val n = pts.size
            if (n > maxFingers) maxFingers = n
            val c = Offset(pts.sumOf { it.position.x.toDouble() }.toFloat() / n, pts.sumOf { it.position.y.toDouble() }.toFloat() / n)
            val spread = if (n >= 2) (pts[0].position - pts[1].position).getDistance() else 0f
            // A finger was added or lifted: start measuring again, so the pointer does not jump.
            if (n != count) {
                count = n
                prev = c
                spread0 = spread
                prevSpread = spread
                twoTravel = 0f
                continue
            }
            val d = c - prev
            prev = c
            val now = ev.changes.first().uptimeMillis
            val dt = (now - lastAt).coerceAtLeast(1)
            lastAt = now
            travel += d.getDistance()

            when {
                n == 1 && maxFingers == 1 -> {
                    if (mode == Mode.None && travel > slop) mode = Mode.Move
                    if (mode == Mode.Move) {
                        if (second && !dragging) {
                            dragging = true
                            link.pad("down")
                        }
                        move(d, dt)
                    }
                }
                n == 2 && maxFingers == 2 -> {
                    twoTravel += d.getDistance()
                    if (mode == Mode.None || mode == Mode.Move) {
                        val pinch = abs(spread - spread0)
                        if (pinch > slop * 3 && pinch > twoTravel * 1.3f) {
                            mode = Mode.Zoom
                            link.hold("ctrl", true)
                        } else if (twoTravel > slop) mode = Mode.Scroll
                    }
                    when (mode) {
                        Mode.Scroll -> {
                            scroll(d)
                            velocity = velocity * 0.6f + (d / dt.toFloat()) * 0.4f
                        }
                        Mode.Zoom -> {
                            zoomRest += spread - prevSpread
                            val step = slop * 5
                            while (abs(zoomRest) >= step) {
                                link.pad("scroll", dy = if (zoomRest > 0) 120 else -120)
                                zoomRest -= if (zoomRest > 0) step else -step
                            }
                        }
                        else -> {}
                    }
                    prevSpread = spread
                }
                n >= 3 -> {
                    swipe += d
                    if (!swiped && swipe.getDistance() > 110f) {
                        swiped = true
                        mode = Mode.Swipe
                        val four = maxFingers >= 4
                        when {
                            abs(swipe.x) > abs(swipe.y) && swipe.x > 0 -> if (four) link.key("left", listOf("ctrl", "win")) else link.key("tab", listOf("alt", "shift"))
                            abs(swipe.x) > abs(swipe.y) -> if (four) link.key("right", listOf("ctrl", "win")) else link.key("tab", listOf("alt"))
                            swipe.y < 0 -> link.key("tab", listOf("win"))
                            else -> link.key("d", listOf("win"))
                        }
                    }
                }
            }
        }
        show(emptyList())

        val endAt = lastAt.coerceAtLeast(startAt)
        val tap = mode == Mode.None && travel < slop * 2 && endAt - startAt < 300
        when {
            dragging -> link.pad("up")
            mode == Mode.Zoom -> link.hold("ctrl", false)
            second && mode == Mode.None -> {
                // Double tap: both clicks now (the first one was waiting).
                link.pad("click")
                link.pad("click")
            }
            tap && maxFingers == 1 -> {
                // Wait a moment: a second touch may turn this into a drag.
                lastTapAt = endAt
                lastTapPos = down.position
                pendingClick = scope.launch {
                    delay(230)
                    link.pad("click")
                }
            }
            tap && maxFingers == 2 -> link.pad("click", b = "right")
            tap && maxFingers == 3 -> link.pad("click", b = "middle")
            mode == Mode.Scroll && velocity.getDistance() > 0.4f -> {
                var v = velocity * 16f
                glide = scope.launch {
                    while (v.getDistance() > 0.6f) {
                        scroll(v)
                        delay(16)
                        v *= 0.94f
                    }
                }
            }
        }
    }

    private fun move(d: Offset, dt: Long) {
        val speed = d.getDistance() / dt
        val k = gain * (0.6f + 0.9f * speed.coerceAtMost(3f))
        restX += d.x * k
        restY += d.y * k
        val ix = restX.toInt()
        val iy = restY.toInt()
        restX -= ix
        restY -= iy
        if (ix != 0 || iy != 0) link.pad("move", ix, iy)
    }

    /** Natural scrolling: the page follows the fingers. Mostly vertical moves scroll only vertically. */
    private fun scroll(d: Offset) {
        val x = if (abs(d.x) < abs(d.y) * 0.5f) 0f else d.x
        val y = if (abs(d.y) < abs(d.x) * 0.5f) 0f else d.y
        restWheel += y * 2.4f
        restHWheel -= x * 2.4f
        val w = restWheel.toInt()
        val h = restHWheel.toInt()
        restWheel -= w
        restHWheel -= h
        if (w != 0 || h != 0) link.pad("scroll", h, w)
    }

    private enum class Mode { None, Move, Scroll, Zoom, Swipe }
}

/** The two buttons under the touchpad, like on a laptop. Holding the left one and moving a finger on the pad drags. */
@Composable
private fun ClickButtons(link: Link, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.u)) {
        PadButton("left", link, Modifier.weight(1f))
        PadButton("right", link, Modifier.weight(1f))
    }
}

@Composable
private fun PadButton(button: String, link: Link, modifier: Modifier) {
    val click = haptic()
    var pressed by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(28.u)
    Box(
        modifier.fillMaxHeight().glass(shape)
            .background(White.copy(alpha = if (pressed) 0.16f else 0.0f))
            .pointerInput(button) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                    pressed = true
                    click()
                    link.pad("down", b = button)
                    waitForUpOrCancellation()
                    link.pad("up", b = button)
                    pressed = false
                }
            },
        contentAlignment = Alignment.Center
    ) {
        T(if (button == "left") "Left click" else "Right click", 15.us, Grey2)
    }
}

// ---------- keyboard ----------

/** A key: [name] is what the PC presses, [ch]/[up] are the characters typed without and with Shift. */
private class K(
    val name: String,
    val label: String = name,
    val w: Float = 1f,
    val ch: String? = null,
    val up: String? = null,
    val icon: String? = null,
    val mod: String? = null,
    val repeat: Boolean = false
)

private fun c(ch: String, up: String) = K(ch, ch, ch = ch, up = up)
private fun letters(s: String) = s.map { K(it.toString(), it.toString(), ch = it.toString(), up = it.uppercase()) }

/** Long press on a letter offers its accented forms (Slovak and Czech). */
private val ACCENTS = mapOf(
    "a" to listOf("á", "ä"), "c" to listOf("č"), "d" to listOf("ď"), "e" to listOf("é", "ě"), "i" to listOf("í"),
    "l" to listOf("ľ", "ĺ"), "n" to listOf("ň"), "o" to listOf("ó", "ô", "ö"), "r" to listOf("ŕ", "ř"), "s" to listOf("š"),
    "t" to listOf("ť"), "u" to listOf("ú", "ů", "ü"), "y" to listOf("ý"), "z" to listOf("ž")
)

private val ROWS: List<List<K?>> = listOf(
    listOf(K("esc", "Esc", 1.5f)) + (1..12).map { K("f$it", "F$it") } + K("delete", "Del", 1.5f, repeat = true),
    listOf(c("`", "~"), c("1", "!"), c("2", "@"), c("3", "#"), c("4", "$"), c("5", "%"), c("6", "^"), c("7", "&"), c("8", "*"), c("9", "("), c("0", ")"), c("-", "_"), c("=", "+"),
        K("backspace", "", 2f, icon = "backspace", repeat = true)),
    listOf(K("tab", "Tab", 1.5f)) + letters("qwertyuiop") + c("[", "{") + c("]", "}") + K("\\", "\\", 1.5f, ch = "\\", up = "|"),
    listOf(K("caps", "Caps", 1.75f)) + letters("asdfghjkl") + c(";", ":") + c("'", "\"") + K("enter", "Enter", 2.25f, icon = "enter"),
    listOf(K("shift", "Shift", 2.25f, icon = "shift", mod = "shift")) + letters("zxcvbnm") + c(",", "<") + c(".", ">") + c("/", "?") +
        K("shift", "", 0.75f, icon = "shift", mod = "shift") + K("up", "", icon = "chevron-up", repeat = true) + null,
    listOf(
        K("ctrl", "Ctrl", 1.5f, mod = "ctrl"), K("win", "", 1.25f, icon = "windows", mod = "win"), K("alt", "Alt", 1.25f, mod = "alt"),
        K("space", "", 6.75f, ch = " ", up = " "), K("ctrl", "Ctrl", 1.25f, mod = "ctrl"),
        K("left", "", icon = "chevron-left", repeat = true), K("down", "", icon = "chevron-down", repeat = true), K("right", "", icon = "chevron-right", repeat = true)
    )
)

private enum class Mod { Off, Once, Locked }

private class Accents(val bounds: Rect, val options: List<String>, val selected: Int)

/**
 * Modifiers work like on a phone: a tap uses them for the next key only, a second tap (or a long press) locks them,
 * a third tap releases them. A locked modifier is held down on the PC, so e.g. Alt stays down while Tab is pressed again.
 * Win tapped twice opens the Start menu.
 */
private class KeyState(private val link: Link) {
    val mods = mutableStateMapOf("shift" to Mod.Off, "ctrl" to Mod.Off, "alt" to Mod.Off, "win" to Mod.Off)
    var caps by mutableStateOf(false)
    var accents by mutableStateOf<Accents?>(null)

    val shift get() = mods["shift"] != Mod.Off

    fun tapMod(m: String) {
        when (mods[m]) {
            Mod.Off -> mods[m] = Mod.Once
            Mod.Once -> if (m == "win") {
                mods[m] = Mod.Off
                link.key("win")
            } else lock(m)
            Mod.Locked -> unlock(m)
            null -> {}
        }
    }

    fun holdMod(m: String) = if (mods[m] == Mod.Locked) unlock(m) else lock(m)

    private fun lock(m: String) {
        mods[m] = Mod.Locked
        link.hold(m, true)
    }

    private fun unlock(m: String) {
        mods[m] = Mod.Off
        link.hold(m, false)
    }

    /** [text]: an accented letter picked by a long press instead of the key's own character. */
    fun press(k: K, text: String? = null) {
        if (k.name == "caps") {
            caps = !caps
            return
        }
        val once = mods.filterValues { it == Mod.Once }.keys.toList()
        // With Ctrl, Alt or Win the key is a shortcut (Ctrl+C), otherwise it types its character.
        val command = mods.any { (m, v) -> m != "shift" && v != Mod.Off }
        val ch = text ?: k.ch
        if (ch != null && (!command || text != null)) {
            val letter = ch.length == 1 && ch[0].isLetter()
            val upper = shift != (caps && letter)
            link.type(
                when {
                    text != null || letter -> if (upper) ch.uppercase() else ch
                    shift -> k.up ?: ch
                    else -> ch
                }
            )
        } else {
            link.key(k.name, once)
        }
        for (m in once) mods[m] = Mod.Off
    }

    fun releaseAll() {
        for ((m, v) in mods.toMap()) if (v == Mod.Locked) link.hold(m, false)
        for (m in mods.keys.toList()) mods[m] = Mod.Off
    }
}

@Composable
private fun Keyboard(keys: KeyState, modifier: Modifier) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    Box(modifier.onGloballyPositioned { origin = it.boundsInRoot().topLeft }) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.u)) {
            ROWS.forEachIndexed { i, row ->
                Row(Modifier.fillMaxWidth().height(if (i == 0) 46.u else 66.u), horizontalArrangement = Arrangement.spacedBy(8.u)) {
                    for (k in row) {
                        if (k == null) Spacer(Modifier.weight(1f)) else KeyView(k, keys)
                    }
                }
            }
        }
        // The accented letters above the held key: the first one is right above the finger, sliding picks another.
        keys.accents?.let { a ->
            val b = a.bounds.translate(-origin)
            with(density) {
                Row(
                    Modifier.offset((b.left - b.width).toDp(), (b.top - b.height - 10.u.toPx()).toDp())
                        .clip(RoundedCornerShape(16.u)).background(Color(0xFF26262A)).border(1.dp, White.copy(alpha = 0.2f), RoundedCornerShape(16.u))
                ) {
                    a.options.forEachIndexed { i, o ->
                        Box(
                            Modifier.width(b.width.toDp()).height(b.height.toDp()).padding(4.u)
                                .background(if (i == a.selected) White else Color.Transparent, RoundedCornerShape(12.u)),
                            contentAlignment = Alignment.Center
                        ) {
                            T(if (keys.shift != keys.caps) o.uppercase() else o, 24.us, if (i == a.selected) Ink else White, FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.KeyView(k: K, keys: KeyState) {
    val click = haptic()
    val scope = rememberCoroutineScope()
    var pressed by remember { mutableStateOf(false) }
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val accents = ACCENTS[k.name]
    val mod = k.mod?.let { keys.mods[it] } ?: if (k.name == "caps" && keys.caps) Mod.Locked else Mod.Off
    val shape = RoundedCornerShape(14.u)
    val bg = when {
        mod == Mod.Locked -> White
        pressed -> White.copy(alpha = 0.24f)
        mod == Mod.Once -> White.copy(alpha = 0.20f)
        k.mod != null || k.icon != null || k.ch == null -> White.copy(alpha = 0.045f)
        else -> White.copy(alpha = 0.085f)
    }
    val fg = if (mod == Mod.Locked) Ink else White

    Box(
        Modifier.weight(k.w).fillMaxHeight()
            .onGloballyPositioned { bounds = it.boundsInRoot() }
            .clip(shape)
            .background(bg)
            .border(1.dp, White.copy(alpha = if (mod == Mod.Once) 0.6f else 0.08f), shape)
            .pointerInput(k) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    pressed = true
                    click()
                    if (k.repeat) {
                        // Backspace, Delete and arrows: right away, then repeated while held.
                        keys.press(k)
                        val again = scope.launch {
                            delay(420)
                            while (true) {
                                keys.press(k)
                                delay(45)
                            }
                        }
                        waitForUpOrCancellation()
                        again.cancel()
                    } else {
                        var held = true
                        withTimeoutOrNull(380) {
                            waitForUpOrCancellation()
                            held = false
                        }
                        when {
                            !held -> if (k.mod != null) keys.tapMod(k.mod) else keys.press(k)
                            k.mod != null -> {
                                keys.holdMod(k.mod)
                                waitForUpOrCancellation()
                            }
                            accents != null -> {
                                val options = listOf(k.ch!!) + accents
                                keys.accents = Accents(bounds, options, 1)
                                var pick = 1
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val p = ev.changes.firstOrNull() ?: break
                                    p.consume()
                                    if (!p.pressed) break
                                    pick = (kotlin.math.floor(p.position.x / size.width).toInt() + 1).coerceIn(0, options.size - 1)
                                    keys.accents = Accents(bounds, options, pick)
                                }
                                keys.accents = null
                                keys.press(k, options[pick])
                            }
                            else -> {
                                waitForUpOrCancellation()
                                keys.press(k)
                            }
                        }
                    }
                    pressed = false
                }
            },
        contentAlignment = Alignment.Center
    ) {
        when {
            k.icon != null -> Glyph(k.icon, 22.u, fg)
            k.ch != null && k.up != null && !k.ch[0].isLetter() && k.name != "space" -> {
                // Symbol keys show both characters, the one Shift gives on top.
                T(k.up, 13.us, if (keys.shift) fg else Grey2, modifier = Modifier.align(Alignment.TopStart).padding(start = 10.u, top = 6.u))
                T(k.ch, 20.us, if (keys.shift) Grey2 else fg, FontWeight.Medium, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.u, bottom = 8.u))
            }
            k.ch != null && k.ch[0].isLetter() -> {
                T(if (keys.shift != keys.caps) k.up ?: k.ch else k.ch, 22.us, fg, FontWeight.Medium)
                if (accents != null) T(accents[0], 11.us, Grey2, modifier = Modifier.align(Alignment.TopEnd).padding(end = 8.u, top = 4.u))
            }
            else -> T(k.label, if (k.label.length > 3) 15.us else 16.us, fg, FontWeight.Medium)
        }
    }
}
