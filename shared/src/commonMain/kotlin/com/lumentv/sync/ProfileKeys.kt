package com.lumentv.sync

import com.lumentv.core.Crypto
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * What a device derives from a profile name and passphrase. Nothing here is sent as is: the server only ever sees
 * [id] and [token] (and stores a hash of the token); [encKey] never leaves the device. The same bytes on every
 * device (TV, phone, PC, iPhone): the cryptography is the platform's own ([Crypto]), checked by the tests.
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
            val dk = pbkdf2Sha256(passphrase.encodeToByteArray(), "lumentv-profile:$n".encodeToByteArray(), iterations, 64)
            val id = hex(sha256("lumentv-id:$n".encodeToByteArray()))
            val token = hex(sha256(dk.copyOfRange(32, 64)))
            return ProfileKeys(n, id, token, dk.copyOfRange(0, 32))
        }

        /** PBKDF2-HMAC-SHA256 (RFC 8018). */
        fun pbkdf2Sha256(password: ByteArray, salt: ByteArray, iterations: Int, length: Int): ByteArray =
            Crypto.pbkdf2Sha256(password, salt, iterations, length)

        fun sha256(b: ByteArray): ByteArray = Crypto.sha256(b)
        fun hex(b: ByteArray) = com.lumentv.core.hex(b)
    }
}

/** AES-256-GCM over the gzipped profile JSON; the profile id is bound as associated data. */
@OptIn(ExperimentalEncodingApi::class)
object ProfileCrypto {
    fun seal(keys: ProfileKeys, plain: String): String {
        val nonce = Crypto.randomBytes(12)
        return Base64.encode(nonce + Crypto.aesGcmSeal(keys.encKey, nonce, keys.id.encodeToByteArray(), Crypto.gzip(plain.encodeToByteArray())))
    }

    fun open(keys: ProfileKeys, sealed: String): String {
        val all = Base64.decode(sealed)
        val zipped = Crypto.aesGcmOpen(keys.encKey, all.copyOfRange(0, 12), keys.id.encodeToByteArray(), all.copyOfRange(12, all.size))
        return Crypto.gunzip(zipped).decodeToString()
    }
}
