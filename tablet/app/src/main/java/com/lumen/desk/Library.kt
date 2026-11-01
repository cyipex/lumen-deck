package com.lumen.desk

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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.json.JSONObject

data class LibSource(val id: String, val name: String, val image: String, val count: Int)
data class LibTrack(val uri: String, val name: String, val artist: String, val image: String, val duration: Long)

private fun parseTracks(o: JSONObject?): List<LibTrack> {
    val a = o?.optJSONArray("items") ?: return emptyList()
    return (0 until a.length()).map { i ->
        val t = a.getJSONObject(i)
        LibTrack(t.optString("uri"), t.optString("name"), t.optString("artist"), t.optString("image"), t.optLong("duration"))
    }
}

private fun mmss(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

/** Spotify library: liked songs and playlists on the left, tracks on the right. A tap plays the track on the PC. */
@Composable
fun LibraryScreen(link: Link, playing: Spotify, tint: Color, onClose: () -> Unit) {
    val sources = remember { mutableStateListOf<LibSource>() }
    val tracks = remember { mutableStateListOf<LibTrack>() }
    var selected by remember { mutableStateOf<LibSource?>(null) }
    var total by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var forbidden by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val list = rememberLazyListState()

    fun load(src: LibSource, offset: Int) {
        loading = true
        link.lib("tracks", JSONObject().put("source", src.id).put("offset", offset)) { r ->
            loading = false
            if (selected?.id != src.id || searching) return@lib
            r.optString("error").takeIf { it.isNotEmpty() }?.let { error = it; return@lib }
            val d = r.optJSONObject("data")
            if (offset == 0) tracks.clear()
            forbidden = d?.optBoolean("forbidden") == true
            tracks.addAll(parseTracks(d))
            total = d?.optInt("total") ?: tracks.size
        }
    }

    fun open(src: LibSource) {
        selected = src
        searching = false
        forbidden = false
        error = null
        tracks.clear()
        total = 0
        load(src, 0)
    }

    fun search() {
        val q = query.trim()
        if (q.isEmpty()) return
        keyboard?.hide()
        searching = true
        selected = null
        error = null
        loading = true
        tracks.clear()
        link.lib("search", JSONObject().put("q", q)) { r ->
            loading = false
            r.optString("error").takeIf { it.isNotEmpty() }?.let { error = it; return@lib }
            tracks.addAll(parseTracks(r.optJSONObject("data")))
            total = tracks.size
        }
    }

    LaunchedEffect(Unit) {
        link.lib("sources") { r ->
            r.optString("error").takeIf { it.isNotEmpty() }?.let { error = it; return@lib }
            val a = r.optJSONArray("data") ?: return@lib
            sources.clear()
            for (i in 0 until a.length()) {
                val s = a.getJSONObject(i)
                sources += LibSource(s.optString("id"), s.optString("name"), s.optString("image"), s.optInt("count"))
            }
            sources.firstOrNull()?.let { open(it) }
        }
    }

    // At the end of the list we load the next 50 tracks.
    val nearEnd by remember { derivedStateOf { (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= tracks.size - 8 } }
    LaunchedEffect(nearEnd, tracks.size) {
        val src = selected
        if (nearEnd && !loading && !searching && src != null && tracks.size in 1 until total) load(src, tracks.size)
    }

    Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null) {}) {
        // Its own background, so the home screen does not show through the glass.
        Background(tint)
        Row(Modifier.fillMaxSize().padding(24.u), horizontalArrangement = Arrangement.spacedBy(18.u)) {
            // ---- sources ----
            Column(Modifier.width(360.u).fillMaxHeight().glass(RoundedCornerShape(40.u)).padding(24.u), verticalArrangement = Arrangement.spacedBy(14.u)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T("Library", 30.us, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    RoundButton("close", 44.u, White.copy(alpha = 0.10f), White) { onClose() }
                }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(22.u)).background(White.copy(alpha = 0.08f))
                        .border(1.dp, White.copy(alpha = 0.16f), RoundedCornerShape(22.u)).padding(horizontal = 16.u, vertical = 12.u),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Glyph("search", 18.u, Grey2)
                    Spacer(Modifier.width(10.u))
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) T("Search tracks", 16.us, Grey2)
                        BasicTextField(
                            query, { query = it },
                            singleLine = true,
                            textStyle = TextStyle(color = White, fontSize = 16.us, fontFamily = Inter),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(White),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { search() }),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.u)) {
                    itemsIndexed(sources, key = { _, s -> s.id }) { _, s ->
                        val sel = !searching && selected?.id == s.id
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.u))
                                .background(if (sel) White.copy(alpha = 0.14f) else Color.Transparent)
                                .clickable { open(s) }.padding(8.u),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Cover(s.image, 52.u, liked = s.id == "liked")
                            Spacer(Modifier.width(14.u))
                            Column(Modifier.weight(1f)) {
                                T(s.name, 16.us, if (sel) White else Grey0, FontWeight.Medium)
                                T("${s.count} skladieb", 13.us, Grey2)
                            }
                        }
                    }
                }
            }

            // ---- tracks ----
            Column(Modifier.weight(1f).fillMaxHeight().glass(RoundedCornerShape(40.u)).padding(top = 24.u, start = 24.u, end = 24.u)) {
                val src = selected
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        T(if (searching) "Results: $query" else src?.name ?: "", 26.us, weight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.u))
                        T(
                            when {
                                loading && tracks.isEmpty() -> "Loading…"
                                searching -> "${tracks.size} skladieb"
                                forbidden -> "${src?.count ?: 0} skladieb"
                                else -> "$total tracks · tap to play on PC"
                            }, 14.us, Grey2
                        )
                    }
                    // The whole playlist at once, even when Spotify does not allow showing its tracks.
                    if (!searching && src != null) {
                        TextButton("Play", { link.libPlay(src.id, null, tracks.map { it.uri }, false) }, primary = true)
                        Spacer(Modifier.width(10.u))
                        TextButton("Shuffle", { link.libPlay(src.id, null, tracks.map { it.uri }, true) })
                    }
                }
                Spacer(Modifier.height(14.u))
                if (forbidden) {
                    BasicText(
                        "This playlist is not yours (you only follow it), so Spotify does not allow showing its tracks. You can still play it – press Play or Shuffle.",
                        style = TextStyle(color = Grey1, fontSize = 17.us, fontFamily = Inter)
                    )
                }
                error?.let {
                    BasicText(it, style = TextStyle(color = Grey0, fontSize = 17.us, fontFamily = Inter))
                }
                LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(bottom = 24.u)) {
                    itemsIndexed(tracks, key = { i, t -> "$i:${t.uri}" }) { i, t ->
                        val now = playing.active && playing.title == t.name && playing.artist == t.artist
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.u))
                                .background(if (now) White.copy(alpha = 0.14f) else Color.Transparent)
                                .clickable {
                                    val src = if (searching) "search" else selected?.id ?: "search"
                                    link.libPlay(src, t.uri, tracks.map { it.uri })
                                }
                                .padding(horizontal = 10.u, vertical = 8.u),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            T("${i + 1}", 14.us, Grey2, modifier = Modifier.width(36.u))
                            Cover(t.image, 48.u)
                            Spacer(Modifier.width(14.u))
                            Column(Modifier.weight(1f)) {
                                T(t.name, 17.us, if (now) White else Grey0, FontWeight.Medium)
                                T(t.artist, 14.us, Grey2)
                            }
                            if (now) {
                                Glyph(if (playing.playing) "volume" else "pause", 18.u, White)
                                Spacer(Modifier.width(14.u))
                            }
                            T(mmss(t.duration), 14.us, Grey2)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Cover(url: String, size: Dp, liked: Boolean = false) {
    val img = rememberArt(url)
    Box(
        Modifier.size(size).clip(RoundedCornerShape(10.u))
            .background(Brush.linearGradient(listOf(if (liked) Color(0xFF4A3FB8) else White.copy(alpha = 0.12f), Color(0xFF121214)))),
        contentAlignment = Alignment.Center
    ) {
        if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Glyph(if (liked) "star" else "music", size * 0.42f, White.copy(alpha = 0.7f))
    }
}
