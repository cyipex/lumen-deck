package com.lumen.desk

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Updating the tablet app over Wi-Fi: the PC offers the app that came with its Lumen Deck when it is newer,
 * the tablet downloads it over the encrypted connection, checks its SHA-256 and hands it to Android's installer
 * (Android asks once to allow installing apps from Lumen Deck).
 */
object Updater {
    sealed interface State {
        data object None : State
        data class Offered(val code: Int, val size: Int) : State
        data class Downloading(val done: Int, val of: Int) : State
        data class Ready(val file: File) : State
        data class Failed(val why: String) : State
    }

    val state = MutableStateFlow<State>(State.None)
    private var parts: Array<ByteArray?> = emptyArray()

    fun version(context: Context): Int = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionCode
    }.getOrDefault(0)

    fun offered(m: JSONObject, context: Context) {
        val code = m.optInt("code")
        if (code > version(context) && state.value !is State.Downloading) state.value = State.Offered(code, m.optInt("size"))
    }

    fun start(send: (JSONObject) -> Boolean) {
        parts = emptyArray()
        state.value = State.Downloading(0, 1)
        if (!send(JSONObject().put("t", "apk"))) state.value = State.Failed("The PC is not connected")
    }

    @Synchronized
    fun piece(m: JSONObject, context: Context) {
        if (m.has("error")) {
            state.value = State.Failed(m.optString("error"))
            return
        }
        val n = m.optInt("n").coerceIn(1, 400)
        val i = m.optInt("i")
        if (parts.size != n) parts = arrayOfNulls(n)
        if (i !in 0 until n) return
        parts[i] = android.util.Base64.decode(m.optString("data"), android.util.Base64.NO_WRAP)
        val done = parts.count { it != null }
        state.value = State.Downloading(done, n)
        if (done < n) return
        val all = java.io.ByteArrayOutputStream().apply { parts.forEach { write(it!!) } }.toByteArray()
        parts = emptyArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(all).joinToString("") { "%02x".format(it) }
        if (hash != m.optString("sha256")) {
            state.value = State.Failed("The download was damaged – try again")
            return
        }
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val file = File(dir, "lumen-desk.apk")
        file.writeBytes(all)
        state.value = State.Ready(file)
    }

    fun install(context: Context, file: File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/** The small "update ready" pill on the clock card. */
@Composable
fun UpdatePill(onClick: () -> Unit) {
    val st by Updater.state.collectAsState()
    if (st is Updater.State.None) return
    val click = haptic()
    Row(
        Modifier.clip(RoundedCornerShape(18.u)).background(White).clickable { click(); onClick() }.height(34.u).padding(horizontal = 12.u),
        verticalAlignment = Alignment.CenterVertically
    ) {
        T(
            when (st) {
                is Updater.State.Downloading -> "Downloading update…"
                is Updater.State.Ready -> "Install update"
                is Updater.State.Failed -> "Update failed"
                else -> "Update ready"
            }, 13.us, Ink, FontWeight.Medium
        )
    }
}

@Composable
fun UpdateCard(link: Link, onClose: () -> Unit) {
    val context = LocalContext.current
    val st by Updater.state.collectAsState()
    Box(
        Modifier.fillMaxSize().background(Ink.copy(alpha = 0.6f)).clickable(remember { MutableInteractionSource() }, null) { onClose() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.glass(RoundedCornerShape(40.u)).background(Ink.copy(alpha = 0.75f))
                .clickable(remember { MutableInteractionSource() }, null) {}
                .padding(36.u).widthIn(max = 620.u),
            verticalArrangement = Arrangement.spacedBy(16.u)
        ) {
            T("New version of Lumen Deck", 28.us, weight = FontWeight.SemiBold)
            val text = when (val s = st) {
                is Updater.State.Offered -> "Your PC has a newer tablet app (${s.size / 1024 / 1024 + 1} MB). It downloads over the encrypted connection; your layout and pairing stay."
                is Updater.State.Downloading -> "Downloading… ${s.done * 100 / s.of} %"
                is Updater.State.Ready -> "Downloaded and checked. Android now asks to install it – if it asks to allow apps from Lumen Deck, allow it once."
                is Updater.State.Failed -> s.why
                Updater.State.None -> "The tablet app is up to date."
            }
            BasicText(text, style = TextStyle(color = Grey1, fontSize = 17.us, fontFamily = Inter))
            (st as? Updater.State.Downloading)?.let { s ->
                Box(Modifier.fillMaxWidth().height(8.u).clip(CircleShape).background(White.copy(alpha = 0.12f))) {
                    Box(Modifier.fillMaxWidth(s.done.toFloat() / s.of).height(8.u).clip(CircleShape).background(White))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.u)) {
                when (val s = st) {
                    is Updater.State.Offered, is Updater.State.Failed -> TextButton("Download", { link.downloadUpdate() }, primary = true)
                    is Updater.State.Ready -> TextButton("Install", { Updater.install(context, s.file) }, primary = true)
                    else -> {}
                }
                TextButton("Later", onClose)
            }
        }
    }
}
