package com.lumen.desk

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The tablet's side of the encrypted connection (the PC's side and the full description are in pc/lib/secure.js):
 * a fresh P-256 key exchange for every connection, the pairing key mixed in once the tablet is paired,
 * and AES-256-GCM messages with a counter.
 */
object Secure {
    private val random = SecureRandom()

    fun randomBytes(n: Int) = ByteArray(n).also { random.nextBytes(it) }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }

    /** HKDF-SHA256 (RFC 5869), the same as Node's crypto.hkdfSync. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: String, len: Int): ByteArray {
        val prk = hmac(salt, ikm)
        val out = java.io.ByteArrayOutputStream()
        var t = ByteArray(0)
        var i = 1
        while (out.size() < len) {
            t = hmac(prk, t + info.toByteArray() + byteArrayOf(i.toByte()))
            out.write(t)
            i++
        }
        return out.toByteArray().copyOf(len)
    }

    /** One connection's key exchange, started before the hello is sent. */
    class Handshake(private val deviceId: String) {
        private val pair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }
        private val pub = pair.public as ECPublicKey

        /** Our public point, uncompressed (65 bytes), and our nonce. */
        val publicKey: ByteArray = byteArrayOf(4) + fixed(pub.w.affineX) + fixed(pub.w.affineY)
        val nonce: ByteArray = randomBytes(16)

        class Result(val session: Session, val code: String)

        /** [pairKey]: the stored pairing key when the PC answered "paired", otherwise null. */
        fun finish(serverKey: ByteArray, serverNonce: ByteArray, pairKey: ByteArray?): Result {
            require(serverKey.size == 65 && serverKey[0] == 4.toByte() && serverNonce.size == 16) { "bad handshake" }
            val point = ECPoint(BigInteger(1, serverKey.copyOfRange(1, 33)), BigInteger(1, serverKey.copyOfRange(33, 65)))
            val theirs = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, pub.params))
            val shared = KeyAgreement.getInstance("ECDH").run {
                init(pair.private)
                doPhase(theirs, true)
                generateSecret()
            }
            val salt = MessageDigest.getInstance("SHA-256").digest(nonce + serverNonce + publicKey + serverKey + deviceId.toByteArray())
            val ikm = if (pairKey != null) shared + pairKey else shared
            val c = hkdf(ikm, salt, "lumen-desk code", 4)
            val n = ((c[0].toLong() and 0xff) shl 24) or ((c[1].toLong() and 0xff) shl 16) or ((c[2].toLong() and 0xff) shl 8) or (c[3].toLong() and 0xff)
            return Result(
                Session(send = hkdf(ikm, salt, "lumen-desk c2s", 32), receive = hkdf(ikm, salt, "lumen-desk s2c", 32)),
                "%06d".format(n % 1_000_000)
            )
        }

        private fun fixed(v: BigInteger): ByteArray {
            val b = v.toByteArray()
            return when {
                b.size == 32 -> b
                b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
                else -> ByteArray(32 - b.size) + b
            }
        }
    }

    /** Messages are [counter: 8 bytes][ciphertext + 16-byte tag]; each side counts from 0 and accepts only the next number. */
    class Session(send: ByteArray?, receive: ByteArray) {
        private val tx = send?.let { SecretKeySpec(it, "AES") }
        private val rx = SecretKeySpec(receive, "AES")
        private var txN = 0L
        private var rxN = 0L
        private val enc = Cipher.getInstance("AES/GCM/NoPadding")
        private val dec = Cipher.getInstance("AES/GCM/NoPadding")

        private fun iv(n: Long) = ByteArray(12).also { for (i in 0 until 8) it[4 + i] = (n ushr (56 - 8 * i)).toByte() }

        /** Encrypts and hands the result to [out] under one lock, so messages leave in counter order. */
        @Synchronized
        fun seal(data: ByteArray, out: (ByteArray) -> Boolean): Boolean {
            val n = txN++
            enc.init(Cipher.ENCRYPT_MODE, tx, GCMParameterSpec(128, iv(n)))
            val ct = enc.doFinal(data)
            val head = iv(n).copyOfRange(4, 12)
            return out(head + ct)
        }

        /** Throws when the message was changed, replayed or not made with our key. */
        @Synchronized
        fun open(m: ByteArray): ByteArray {
            require(m.size >= 24) { "bad message" }
            var n = 0L
            for (i in 0 until 8) n = (n shl 8) or (m[i].toLong() and 0xff)
            require(n == rxN) { "bad message order" }
            dec.init(Cipher.DECRYPT_MODE, rx, GCMParameterSpec(128, iv(n)))
            val pt = dec.doFinal(m, 8, m.size - 8)
            rxN++
            return pt
        }
    }
}
