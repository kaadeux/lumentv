package com.lumentv.sync

import java.security.MessageDigest
import java.security.SecureRandom
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * What a device derives from a profile name and passphrase. Nothing here is sent as is: the server only ever sees
 * [id] and [token] (and stores a hash of the token); [encKey] never leaves the device.
 */
class ProfileKeys(val name: String, val id: String, val token: String, val encKey: ByteArray) {
    companion object {
        const val ITERATIONS = 200_000
        const val MIN_PASSPHRASE = 10

        fun normalizeName(name: String) = name.trim().lowercase().replace(Regex("\\s+"), " ")

        /** Slow on purpose (PBKDF2, [ITERATIONS] rounds): a stolen copy of the server cannot be brute-forced cheaply. */
        fun derive(name: String, passphrase: String, iterations: Int = ITERATIONS): ProfileKeys {
            val n = normalizeName(name)
            require(n.isNotEmpty()) { "Nom de profil vide." }
            require(passphrase.length >= MIN_PASSPHRASE) { "La phrase secrète doit faire au moins $MIN_PASSPHRASE caractères." }
            val dk = pbkdf2Sha256(passphrase.toByteArray(), "lumentv-profile:$n".toByteArray(), iterations, 64)
            val id = hex(sha256("lumentv-id:$n".toByteArray()))
            val token = hex(sha256(dk.copyOfRange(32, 64)))
            return ProfileKeys(n, id, token, dk.copyOfRange(0, 32))
        }

        /** PBKDF2-HMAC-SHA256 (RFC 8018), written out because Android before 8.0 lacks it in SecretKeyFactory. */
        fun pbkdf2Sha256(password: ByteArray, salt: ByteArray, iterations: Int, length: Int): ByteArray {
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(password, "HmacSHA256")) }
            val out = ByteArray(length)
            var block = 1
            var pos = 0
            while (pos < length) {
                mac.update(salt)
                mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
                var u = mac.doFinal()
                val t = u.copyOf()
                repeat(iterations - 1) {
                    u = mac.doFinal(u)
                    for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
                }
                val n = minOf(t.size, length - pos)
                System.arraycopy(t, 0, out, pos, n)
                pos += n
                block++
            }
            return out
        }

        fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    }
}

/** AES-256-GCM over the gzipped profile JSON; the profile id is bound as associated data. */
object ProfileCrypto {
    private val random = SecureRandom()

    fun seal(keys: ProfileKeys, plain: String): String {
        val zipped = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(plain.toByteArray()) } }.toByteArray()
        val nonce = ByteArray(12).also { random.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.encKey, "AES"), GCMParameterSpec(128, nonce))
        c.updateAAD(keys.id.toByteArray())
        return Base64.getEncoder().encodeToString(nonce + c.doFinal(zipped))
    }

    fun open(keys: ProfileKeys, sealed: String): String {
        val all = Base64.getDecoder().decode(sealed)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(keys.encKey, "AES"), GCMParameterSpec(128, all, 0, 12))
        c.updateAAD(keys.id.toByteArray())
        val zipped = c.doFinal(all, 12, all.size - 12)
        return GZIPInputStream(ByteArrayInputStream(zipped)).use { it.readBytes() }.decodeToString()
    }
}
