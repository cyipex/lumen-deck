package com.lumen.desk

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import kotlinx.coroutines.delay
import java.util.Calendar

// ---------- presentation remote ----------

/**
 * Big Back / Next buttons for slides (arrow keys work in PowerPoint, Google Slides, Keynote and PDF viewers),
 * a timer, a pad that moves the mouse (with Ctrl+L PowerPoint turns it into a laser pointer)
 * and the tablet's volume buttons as a clicker: up = next, down = back.
 */
@Composable
fun PresenterScreen(link: Link, onExit: () -> Unit) {
    var elapsed by remember { mutableLongStateOf(0L) }
    var running by remember { mutableStateOf(false) }
    LaunchedEffect(running) {
        var last = System.currentTimeMillis()
        while (running) {
            delay(250)
            val now = System.currentTimeMillis()
            elapsed += now - last
            last = now
        }
    }
    val next = {
        link.key("right")
        if (!running && elapsed == 0L) running = true
    }
    val back = { link.key("left") }
    val onNext by rememberUpdatedState(next)
    val onBack by rememberUpdatedState(back)
    DisposableEffect(Unit) {
        Hub.awake(true)
        Hub.volumeKeys = { up -> if (up) onNext() else onBack() }
        onDispose {
            Hub.awake(false)
            Hub.volumeKeys = null
        }
    }

    Box(Modifier.fillMaxSize().background(Ink).clickable(remember { MutableInteractionSource() }, null) {}) {
        Column(Modifier.fillMaxSize().padding(24.u), verticalArrangement = Arrangement.spacedBy(18.u)) {
            Row(Modifier.fillMaxWidth().height(64.u), verticalAlignment = Alignment.CenterVertically) {
                CornerButton("close", onExit)
                Spacer(Modifier.width(18.u))
                Column(Modifier.weight(1f)) {
                    T("Presentation", 24.us, weight = FontWeight.SemiBold)
                    T("Volume buttons: up = next slide, down = back", 14.us, Grey2)
                }
                Timer(elapsed, running, { running = !running }) {
                    running = false
                    elapsed = 0L
                }
                Spacer(Modifier.width(14.u))
                val now = rememberNow()
                T("%02d:%02d".format(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE)), 22.us, Grey1)
            }
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.u)) {
                SlideButton("arrow-left", "Back", Modifier.weight(0.9f), back)
                Column(Modifier.weight(1.1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.u)) {
                    Touchpad(link, 1.0f, Modifier.weight(1f).fillMaxWidth(), "Pointer\nDrag to move the mouse · tap = click")
                }
                SlideButton("arrow-right", "Next", Modifier.weight(1.6f), next)
            }
            Row(Modifier.fillMaxWidth().height(72.u), horizontalArrangement = Arrangement.spacedBy(12.u)) {
                Action("play", "Start", Modifier.weight(1f)) {
                    link.key("f5")
                    running = true
                }
                Action("play", "From this slide", Modifier.weight(1.3f)) {
                    link.key("f5", listOf("shift"))
                    running = true
                }
                Action("monitor", "Black screen", Modifier.weight(1.2f)) { link.key("b") }
                Action("pointer", "Laser", Modifier.weight(1f)) { link.key("l", listOf("ctrl")) }
                Action("close", "End", Modifier.weight(1f)) {
                    link.key("esc")
                    running = false
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Timer(ms: Long, running: Boolean, onToggle: () -> Unit, onReset: () -> Unit) {
    val click = haptic()
    val s = ms / 1000
    Column(
        Modifier.glass(RoundedCornerShape(24.u))
            .combinedClickable(remember { MutableInteractionSource() }, null, onLongClick = { click(); onReset() }) { click(); onToggle() }
            .padding(horizontal = 22.u, vertical = 8.u),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        T("%d:%02d".format(s / 60, s % 60), 28.us, if (running) White else Grey1, FontWeight.SemiBold, letterSpacing = 0.02f.em)
        T(if (running) "tap = pause · hold = reset" else if (ms > 0) "paused · hold = reset" else "starts with the first slide", 11.us, Grey2)
    }
}

@Composable
private fun SlideButton(icon: String, label: String, modifier: Modifier, onClick: () -> Unit) {
    val click = haptic()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, spring(dampingRatio = 0.6f, stiffness = 700f), label = "slide")
    Box(
        modifier.fillMaxHeight()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .glass(RoundedCornerShape(40.u))
            .background(White.copy(alpha = if (pressed) 0.14f else 0f))
            .clickable(interaction, null) { click(); onClick() },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Glyph(icon, 72.u, White)
            Spacer(Modifier.height(14.u))
            T(label, 24.us, Grey0, FontWeight.Medium)
        }
    }
}

@Composable
private fun Action(icon: String, label: String, modifier: Modifier, onClick: () -> Unit) {
    val click = haptic()
    Row(
        modifier.fillMaxHeight().glass(RoundedCornerShape(24.u)).clickable { click(); onClick() }.padding(horizontal = 18.u),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Glyph(icon, 20.u, Grey1)
        Spacer(Modifier.width(10.u))
        T(label, 16.us, White, FontWeight.Medium)
    }
}
