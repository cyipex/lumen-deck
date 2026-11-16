package com.lumen.desk

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * Photos and fonts uploaded in the PC settings. The PC sends each in pieces over the encrypted connection;
 * the file is kept by its id (the first 32 hex digits of its SHA-256), which is checked before it is saved,
 * so a damaged or wrong file is never used.
 */
object Assets {
    private lateinit var dir: File
    private val parts = HashMap<String, Array<ByteArray?>>()
    private val asked = HashMap<String, Long>()

    /** Ids of the files the tablet has; composables read it so a file that arrives shows up by itself. */
    val ready = MutableStateFlow<Set<String>>(emptySet())

    fun init(context: Context) {
        dir = File(context.filesDir, "assets").apply { mkdirs() }
        ready.value = dir.listFiles().orEmpty().map { it.name.substringBefore('.') }.toSet()
    }

    fun file(id: String): File? = if (::dir.isInitialized) dir.listFiles()?.firstOrNull { it.name.startsWith("$id.") } else null

    /** Asks the PC for a file we do not have (at most once a minute per file). */
    @Synchronized
    fun want(id: String, ask: () -> Unit) {
        if (file(id) != null) return
        val now = System.currentTimeMillis()
        if (now - (asked[id] ?: 0) < 60_000) return
        asked[id] = now
        ask()
    }

    @Synchronized
    fun piece(m: JSONObject) {
        val id = m.optString("id")
        if (!Regex("[a-f0-9]{32}").matches(id) || m.has("error")) return
        val n = m.optInt("n").coerceIn(1, 200)
        val i = m.optInt("i")
        if (i !in 0 until n) return
        val got = parts.getOrPut(id) { arrayOfNulls(n) }
        if (got.size != n) {
            parts.remove(id)
            return
        }
        got[i] = android.util.Base64.decode(m.optString("data"), android.util.Base64.NO_WRAP)
        if (got.any { it == null }) return
        parts.remove(id)
        val all = ByteArrayOutputStream().apply { got.forEach { write(it!!) } }.toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(all).joinToString("") { "%02x".format(it) }.take(32)
        if (hash != id) return
        val ext = m.optString("ext").takeIf { it in setOf("jpg", "png", "webp", "ttf", "otf") } ?: return
        val tmp = File(dir, "$id.part")
        tmp.writeBytes(all)
        tmp.renameTo(File(dir, "$id.$ext"))
        ready.value = ready.value + id
    }
}
