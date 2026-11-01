package com.lumen.desk

import org.json.JSONObject

data class Theme(val tint: String = "Emerald", val clockFont: String = "Fraunces", val media: String = "volume")

data class Page(val id: String, val name: String, val layout: String, val tiles: List<String?>)

data class Tile(
    val id: String,
    val label: String,
    val icon: String,
    val kind: String,
    val toggle: Boolean,
    val page: String?
) {
    val switchable get() = kind == "light" || (kind == "macro" && toggle)
}

data class PanelConfig(val theme: Theme, val pages: List<Page>, val tiles: Map<String, Tile>, val notify: Boolean = true)

data class Spotify(
    val active: Boolean = false,
    val playing: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val image: String = "",
    val progress: Long = 0,
    val duration: Long = 0,
    val volume: Int? = null,
    val receivedAt: Long = 0
)

/** A notification from the PC (Discord, WhatsApp, Windows…). */
data class Notice(val id: Long, val app: String, val title: String, val body: String)

/** One app in the PC volume mixer (key = process name). */
data class MixerApp(val key: String, val name: String, val volume: Int, val muted: Boolean)

/** PC volume and audio output. */
data class Audio(val volume: Int, val muted: Boolean, val output: String, val outputs: Int)

data class Weather(val city: String, val temp: Int, val text: String)

data class LiveState(
    val spotify: Spotify = Spotify(),
    val audio: Audio? = null,
    val mixer: List<MixerApp> = emptyList(),
    val cpu: Int? = null,
    val ram: Int? = null,
    val weather: Weather? = null,
    val tiles: Map<String, Boolean> = emptyMap(),
    val pc: String = ""
)

private fun JSONObject.str(key: String) = if (isNull(key)) null else optString(key, null)

fun parseConfig(o: JSONObject): PanelConfig {
    val th = o.optJSONObject("theme") ?: JSONObject()
    val tilesJ = o.optJSONObject("tiles") ?: JSONObject()
    val tiles = tilesJ.keys().asSequence().associateWith { id ->
        val t = tilesJ.getJSONObject(id)
        Tile(
            id = id,
            label = t.optString("label"),
            icon = t.optString("icon", "star"),
            kind = t.optString("kind", "macro"),
            toggle = t.optBoolean("toggle", false),
            page = t.str("page")
        )
    }
    val pagesJ = o.optJSONArray("pages")
    val pages = (0 until (pagesJ?.length() ?: 0)).map { i ->
        val p = pagesJ!!.getJSONObject(i)
        val ids = p.optJSONArray("tiles")
        Page(
            id = p.optString("id"),
            name = p.optString("name"),
            layout = p.optString("layout", "grid"),
            tiles = (0 until (ids?.length() ?: 0)).map { k -> if (ids!!.isNull(k)) null else ids.optString(k) }
        )
    }
    val notify = o.optJSONObject("notify")?.optBoolean("enabled", true) ?: true
    return PanelConfig(Theme(th.optString("tint", "Emerald"), th.optString("clockFont", "Fraunces"), th.optString("media", "volume")), pages, tiles, notify)
}

fun parseSpotify(o: JSONObject?): Spotify {
    if (o == null || !o.optBoolean("active")) return Spotify()
    return Spotify(
        active = true,
        playing = o.optBoolean("playing"),
        title = o.optString("title"),
        artist = o.optString("artist"),
        image = o.optString("image"),
        progress = o.optLong("progress"),
        duration = o.optLong("duration"),
        volume = if (o.isNull("volume") || !o.has("volume")) null else o.optInt("volume"),
        receivedAt = System.currentTimeMillis()
    )
}

fun parseAudio(o: JSONObject?): Audio? =
    o?.let { Audio(it.optInt("volume"), it.optBoolean("muted"), it.optString("output"), it.optInt("outputs")) }

fun parseMixer(a: org.json.JSONArray?): List<MixerApp> =
    (0 until (a?.length() ?: 0)).map { i ->
        val o = a!!.getJSONObject(i)
        MixerApp(o.optString("key"), o.optString("name"), o.optInt("volume"), o.optBoolean("muted"))
    }

fun parseWeather(o: JSONObject?): Weather? =
    o?.let { Weather(it.optString("city"), it.optInt("temp"), it.optString("text")) }

fun parseTileStates(o: JSONObject?): Map<String, Boolean> =
    o?.keys()?.asSequence()?.associateWith { o.optBoolean(it) } ?: emptyMap()
