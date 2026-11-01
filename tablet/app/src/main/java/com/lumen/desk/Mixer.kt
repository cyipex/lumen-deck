package com.lumen.desk

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import kotlin.math.roundToInt

/** Icon by app; unknown apps get their first letter. */
private fun mixerIcon(key: String): String? = when {
    key == "pc" -> "monitor"
    key == "system" -> "gear"
    key.contains("spotify", true) -> "music"
    key.contains("discord", true) -> "chat"
    key.contains("chrome", true) || key.contains("msedge", true) || key.contains("firefox", true) || key.contains("opera", true) || key.contains("brave", true) -> "globe"
    key.contains("steam", true) -> "gamepad"
    key.contains("whatsapp", true) || key.contains("teams", true) || key.contains("telegram", true) -> "chat"
    key.contains("obs", true) -> "record"
    else -> null
}

/** The "Sliders" page: the volume of the whole PC and of every app that is currently playing sound. */
@Composable
fun SlidersPage(live: LiveState, link: Link) {
    val audio = live.audio
    BoxWithConstraints(Modifier.fillMaxSize().padding(24.u)) {
        val count = live.mixer.size + 1
        val gap = 16.u
        val colW = max(150.u, (maxWidth - gap * (count - 1) - 2.dp) / count)
        LazyRow(Modifier.fillMaxSize(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(gap), contentPadding = PaddingValues(0.dp)) {
            item(key = "pc") {
                MixerColumn(
                    key = "pc", name = "PC", volume = audio?.volume ?: 0, muted = audio?.muted ?: false, master = true,
                    enabled = audio != null, modifier = Modifier.width(colW).fillMaxHeight(),
                    onVolume = { link.audio("volume", it) }, onMute = { link.audio("mute") }
                )
            }
            items(live.mixer, key = { it.key }) { app ->
                MixerColumn(
                    key = app.key, name = app.name, volume = app.volume, muted = app.muted, master = false,
                    enabled = true, modifier = Modifier.width(colW).fillMaxHeight(),
                    onVolume = { link.appAudio("appvol", app.key, it) },
                    onMute = { link.appAudio("appmute", app.key, if (app.muted) 0 else 1) }
                )
            }
        }
        if (live.mixer.isEmpty()) {
            BasicText(
                "More sliders appear when an app on the PC starts playing sound.",
                Modifier.align(Alignment.BottomCenter).padding(bottom = 8.u),
                style = TextStyle(color = Grey2, fontSize = 15.us, fontFamily = Inter, textAlign = TextAlign.Center)
            )
        }
    }
}

@Composable
private fun MixerColumn(
    key: String, name: String, volume: Int, muted: Boolean, master: Boolean, enabled: Boolean, modifier: Modifier,
    onVolume: (Int) -> Unit, onMute: () -> Unit
) {
    val click = haptic()
    var drag by remember { mutableStateOf<Float?>(null) }
    var sentAt by remember { mutableLongStateOf(0L) }
    val send by rememberUpdatedState(onVolume)
    val target = drag ?: if (muted) 0f else volume / 100f
    val fill by animateFloatAsState(target, if (drag != null) snap() else tween(200), label = "mix")
    val pct = (target * 100).roundToInt()
    // While dragging, at most ~12 values per second go to the PC, always the last one.
    val push: (Float, Boolean) -> Unit = { f, force ->
        val now = System.currentTimeMillis()
        if (force || now - sentAt > 80) {
            sentAt = now
            send((f * 100).roundToInt())
        }
    }
    val shape = RoundedCornerShape(36.u)
    Column(
        modifier.glass(shape).background(if (master) White.copy(alpha = 0.04f) else androidx.compose.ui.graphics.Color.Transparent).padding(vertical = 20.u, horizontal = 14.u),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.u)
    ) {
        Box(
            Modifier.size(52.u).clip(CircleShape).background(Brush.linearGradient(listOf(White.copy(alpha = if (master) 0.28f else 0.16f), White.copy(alpha = 0.05f)))),
            contentAlignment = Alignment.Center
        ) {
            val icon = mixerIcon(key)
            if (icon != null) Glyph(icon, 24.u, White)
            else T(name.take(1).uppercase(), 22.us, weight = FontWeight.SemiBold)
        }
        T(name, 15.us, if (master) White else Grey0, FontWeight.Medium)
        T(if (muted && drag == null) "Muted" else "$pct %", 20.us, weight = FontWeight.SemiBold)
        Box(
            Modifier.weight(1f).width(72.u)
                .clip(RoundedCornerShape(28.u))
                .background(White.copy(alpha = 0.07f))
                .border(1.dp, White.copy(alpha = 0.14f), RoundedCornerShape(28.u))
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures { push((1f - it.y / size.height).coerceIn(0f, 1f), true) }
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
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
        }
        Box(
            Modifier.fillMaxWidth().height(44.u).clip(RoundedCornerShape(22.u))
                .background(if (muted) White else White.copy(alpha = 0.07f))
                .border(1.dp, White.copy(alpha = if (muted) 1f else 0.14f), RoundedCornerShape(22.u))
                .clickable(enabled) { click(); onMute() },
            contentAlignment = Alignment.Center
        ) {
            Glyph("volume-x", 18.u, if (muted) Ink else Grey1)
        }
    }
}
