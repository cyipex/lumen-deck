package com.lumen.desk

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import org.json.JSONObject

val Ink = Color(0xFF050505)
val White = Color.White
val Grey1 = Color(0xFFBDBDBD)
val Grey2 = Color(0xFF9A9A9A)
val Grey0 = Color(0xFFE6E6E6)

val Tints = mapOf(
    "Graphite" to Color(0xFF3A3A3D), "Ocean" to Color(0xFF2F4A8A), "Indigo" to Color(0xFF3A3478),
    "Lavender" to Color(0xFF5E4B8B), "Rose" to Color(0xFF7A4458), "Terracotta" to Color(0xFF7A3E2E),
    "Amber" to Color(0xFF8A5A2B), "Sand" to Color(0xFF7A6A4A), "Sage" to Color(0xFF4E6B5C),
    "Emerald" to Color(0xFF1F5E4E)
)

// Android 7 cannot use variable fonts, so fixed weights are bundled (static instances of the variable fonts).
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold)
)

data class ClockFont(val family: FontFamily, val size: Float, val trackingEm: Float)

/**
 * Clock fonts: the 20 bundled ones (shared/fonts/fonts.json, the same files the PC settings show) and fonts the
 * user uploaded on the PC ("custom:<id>", files that Assets brings over). [size] makes them look equally big.
 */
object Fonts {
    private var builtin: Map<String, ClockFont> = emptyMap()
    private val custom = HashMap<String, ClockFont>()

    fun load(context: Context) {
        if (builtin.isNotEmpty()) return
        val list = org.json.JSONArray(context.assets.open("fonts/fonts.json").bufferedReader().use { it.readText() })
        builtin = (0 until list.length()).associate { i ->
            val f = list.getJSONObject(i)
            f.getString("name") to ClockFont(
                FontFamily(Font("fonts/" + f.getString("file"), context.assets)),
                f.getDouble("size").toFloat(), f.getDouble("tracking").toFloat()
            )
        }
    }

    val names get() = builtin.keys

    /** A font by its setting value; an uploaded font that has not arrived yet (or a name we do not know) falls back to Fraunces. */
    fun get(key: String?): ClockFont {
        builtin[key]?.let { return it }
        val id = key?.removePrefix("custom:")?.takeIf { key.startsWith("custom:") }
        if (id != null) {
            custom[id]?.let { return it }
            Assets.file(id)?.let { f ->
                return ClockFont(FontFamily(Font(f)), 140f, 0f).also { custom[id] = it }
            }
        }
        return builtin["Fraunces"] ?: ClockFont(FontFamily.Serif, 150f, 0f)
    }
}

val ClockFonts: Map<String, ClockFont> get() = Fonts.names.associateWith { Fonts.get(it) }

/**
 * The layout is designed for 1280×800 (like the mockup). All sizes are in "design points"
 * and are scaled to the real screen width.
 */
class Scale(val unit: Float, val fontScale: Float)

val LocalScale = compositionLocalOf { Scale(1f, 1f) }

/**
 * What kind of screen the app runs on. Tablets keep the 1280x800 layout; phones (shorter side under
 * 600 dp) get their own layouts, held sideways or upright, with bigger text than a shrunk tablet layout.
 */
data class Form(val phone: Boolean, val portrait: Boolean)

val LocalForm = compositionLocalOf { Form(phone = false, portrait = false) }

val Number.u: Dp
    @Composable get() = (toFloat() * LocalScale.current.unit).dp

val Number.us: TextUnit
    @Composable get() = with(LocalDensity.current) { (toFloat() * LocalScale.current.unit).dp.toSp() }

object Icons {
    private val cache = HashMap<String, ImageVector>()
    private var paths: Map<String, String> = emptyMap()
    private val filled = setOf("play", "pause", "prev", "next")

    fun load(context: Context) {
        if (paths.isNotEmpty()) return
        val j = JSONObject(context.assets.open("icons.json").bufferedReader().use { it.readText() })
        paths = j.keys().asSequence().associateWith { j.getString(it) }
    }

    fun get(name: String): ImageVector = cache.getOrPut(name) {
        val d = paths[name] ?: paths["star"] ?: ""
        val fill = name in filled
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).addPath(
            pathData = PathParser().parsePathString(d).toNodes(),
            fill = if (fill) SolidColor(Color.White) else null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = 1.6f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ).build()
    }
}

/** Remembers the value the user is dragging right now (so the PC state does not overwrite it). */
class Dragging<T>(initial: T?) {
    var value by mutableStateOf(initial)
}

@Composable
fun <T> rememberDragging() = remember { Dragging<T>(null) }
