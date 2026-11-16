package com.lumen.mirror

import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.SecureRandom

/**
 * The phone <-> tablet protocol: one TCP connection, messages framed as [type: 1 byte][length: 4 bytes][payload].
 *   phone -> tablet: SIZE (JSON {w,h}), CONFIG (H.264 SPS+PPS), FRAME (H.264 access unit), JSON (status)
 *   tablet -> phone: JSON (hello, touch, buttons, typing)
 * The phone also announces itself on UDP 47812 so the tablet finds it without typing an address.
 */
object Proto {
    const val PORT = 47811
    const val BEACON_PORT = 47812
    /** The tablet asks a phone that is not mirroring to start (UDP, with the pairing key). */
    const val START_PORT = 47813

    const val SIZE = 1
    const val CONFIG = 2
    const val FRAME = 3
    const val JSON = 10

    fun write(out: DataOutputStream, type: Int, payload: ByteArray) {
        synchronized(out) {
            out.writeByte(type)
            out.writeInt(payload.size)
            out.write(payload)
            out.flush()
        }
    }

    /** Returns null at the end of the stream. */
    fun read(input: DataInputStream): Pair<Int, ByteArray>? {
        val type = input.read()
        if (type < 0) return null
        val len = input.readInt()
        if (len < 0 || len > 1 shl 20) throw IllegalStateException("bad message")
        val b = ByteArray(len)
        input.readFully(b)
        return type to b
    }
}

/** What the phone remembers: the paired tablet and the user's choices. */
class Prefs(context: Context) {
    private val p = context.getSharedPreferences("mirror", Context.MODE_PRIVATE)

    /** The secret only the paired tablet knows. The first tablet that connects gets it. */
    var key: String?
        get() = p.getString("key", null)
        set(v) = p.edit().putString("key", v).apply()

    var tabletName: String?
        get() = p.getString("tablet", null)
        set(v) = p.edit().putString("tablet", v).apply()

    /** Start mirroring by itself after the phone restarts. */
    var autoStart: Boolean
        get() = p.getBoolean("autoStart", false)
        set(v) = p.edit().putBoolean("autoStart", v).apply()

    /** Turn the phone's own screen to the lowest brightness while a tablet is connected. */
    var dimPhone: Boolean
        get() = p.getBoolean("dimPhone", true)
        set(v) = p.edit().putBoolean("dimPhone", v).apply()

    fun newKey(): String {
        val b = ByteArray(18)
        SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }
}
