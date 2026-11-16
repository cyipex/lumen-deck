package com.lumen.desk

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The PC monitor the drawing tablet maps onto: number [i] of [n], its size in pixels. */
data class DrawScreen(val i: Int, val n: Int, val w: Int, val h: Int)

// ---------- drawing tablet ----------

/**
 * The drawing area has the shape of one PC monitor and covers all of it: where the finger touches,
 * the mouse is on the PC, and while it touches, the left button is held (draws in Paint, Photoshop, Krita…).
 * No picture is sent back; the finger's line shows briefly on the tablet so you see what you drew.
 * Only the first finger draws, so a resting palm or a second finger does nothing.
 */
@Composable
fun DrawScreen(link: Link, onExit: () -> Unit) {
    val prefs = LocalContext.current.getSharedPreferences("draw", Context.MODE_PRIVATE)
    val screen by link.drawScreen.collectAsState()
    var hover by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        Hub.awake(true)
        link.drawOn(prefs.getInt("screen", 0))
        onDispose { Hub.awake(false) }
    }

    Row(Modifier.fillMaxSize().background(Ink).clickable(remember { MutableInteractionSource() }, null) {}.padding(16.u)) {
        Column(Modifier.width(64.u).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.u), horizontalAlignment = Alignment.CenterHorizontally) {
            CornerButton("close", onExit)
            Spacer(Modifier.height(10.u))
            ToolButton("pen", "Draw", !hover) { hover = false }
            ToolButton("pointer", "Move", hover) { hover = true }
            Spacer(Modifier.height(10.u))
            ToolButton("undo", "Undo", false) { link.key("z", listOf("ctrl")) }
            ToolButton("redo", "Redo", false) { link.key("y", listOf("ctrl")) }
            Spacer(Modifier.weight(1f))
            screen?.takeIf { it.n > 1 }?.let { s ->
                ToolButton("monitor", "${s.i + 1} / ${s.n}", false) {
                    val next = (s.i + 1) % s.n
                    prefs.edit().putInt("screen", next).apply()
                    link.drawOn(next)
                }
            }
        }
        Spacer(Modifier.width(16.u))
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            val s = screen
            if (s == null) {
                Pill("Connecting to the PC…")
                return@BoxWithConstraints
            }
            // As big as possible with the monitor's proportions, so a circle stays a circle.
            val ratio = s.w.toFloat() / s.h
            val w = if (maxWidth / maxHeight > ratio) maxHeight * ratio else maxWidth
            val h = w / ratio
            DrawArea(link, hover, Modifier.size(w, h))
        }
    }
}

@Composable
private fun ToolButton(icon: String, label: String, selected: Boolean, onClick: () -> Unit) {
    val click = haptic()
    Column(
        Modifier.width(64.u).clip(RoundedCornerShape(18.u))
            .background(if (selected) White else White.copy(alpha = 0.06f))
            .clickable { click(); onClick() }
            .padding(vertical = 10.u),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Glyph(icon, 22.u, if (selected) Ink else Grey0)
        Spacer(Modifier.height(4.u))
        T(label, 11.us, if (selected) Ink else Grey1, FontWeight.Medium)
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun DrawArea(link: Link, hover: Boolean, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    val current = remember { mutableStateListOf<Offset>() }
    var touching by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(20.u)
    Box(
        modifier
            .glass(shape)
            .pointerInput(hover) {
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false)
                    val id = first.id
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    fun at(a: String, p: Offset) = link.draw(a, (p.x / w).coerceIn(0f, 1f), (p.y / h).coerceIn(0f, 1f))
                    first.consume()
                    touching = true
                    current.clear()
                    current.add(first.position)
                    at("hover", first.position)
                    if (!hover) at("down", first.position)
                    var last = first.position
                    while (true) {
                        val ev = awaitPointerEvent()
                        ev.changes.forEach { it.consume() }
                        val c = ev.changes.firstOrNull { it.id == id } ?: continue
                        // The in-between samples make fast lines smooth instead of jagged.
                        for (hp in c.historical) {
                            at(if (hover) "hover" else "move", hp.position)
                            current.add(hp.position)
                        }
                        if (!c.pressed) break
                        if (c.position != last) {
                            at(if (hover) "hover" else "move", c.position)
                            current.add(c.position)
                            last = c.position
                        }
                    }
                    if (!hover) at("up", last)
                    touching = false
                    if (!hover && current.size > 0) {
                        val done = current.toList()
                        strokes.add(done)
                        scope.launch {
                            delay(1400)
                            strokes.remove(done)
                        }
                    }
                    current.clear()
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (strokes.isEmpty() && !touching) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Glyph(if (hover) "pointer" else "pen", 34.u, White.copy(alpha = 0.2f))
                Spacer(Modifier.height(10.u))
                T(if (hover) "Move the pointer without drawing" else "This area is the whole PC screen · draw with one finger", 15.us, White.copy(alpha = 0.25f))
            }
        }
        Canvas(Modifier.fillMaxSize().clip(shape)) {
            val ink = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            fun line(pts: List<Offset>, alpha: Float) {
                if (pts.size == 1) drawCircle(White.copy(alpha = alpha), 2.dp.toPx(), pts[0])
                if (pts.size < 2) return
                val p = Path().apply {
                    moveTo(pts[0].x, pts[0].y)
                    for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
                }
                drawPath(p, White.copy(alpha = alpha), style = ink)
            }
            for (s in strokes) line(s, 0.35f)
            if (hover) current.lastOrNull()?.let { drawCircle(White.copy(alpha = 0.5f), 6.dp.toPx(), it) }
            else line(current, 0.85f)
        }
    }
}

