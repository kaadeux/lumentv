package com.lumentv.core

/**
 * The cryptography of the online profile (see [com.lumentv.sync.ProfileKeys]), given by each platform: the JVM's
 * (javax.crypto: TV, phone, PC) and Apple's CommonCrypto on the iPhone. Every device must produce exactly the same
 * bytes, or the profile could not be read across them: the tests check the standard test vectors on each platform.
 */
expect object Crypto {
    fun sha256(data: ByteArray): ByteArray
    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray
    /** PBKDF2-HMAC-SHA256 (RFC 8018). */
    fun pbkdf2Sha256(password: ByteArray, salt: ByteArray, iterations: Int, length: Int): ByteArray
    /** Cryptographically secure random bytes. */
    fun randomBytes(size: Int): ByteArray
    /** AES-256-GCM: the ciphertext followed by its 16-byte tag. */
    fun aesGcmSeal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray
    /** Opens [aesGcmSeal]'s output; throws when the key, the data or the tag do not match. */
    fun aesGcmOpen(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray
    fun gzip(data: ByteArray): ByteArray
    fun gunzip(data: ByteArray): ByteArray
}

/** Lower-case hexadecimal: "0a1f…". */
fun hex(bytes: ByteArray): String {
    val digits = "0123456789abcdef"
    val sb = StringBuilder(bytes.size * 2)
    for (b in bytes) { val v = b.toInt() and 0xff; sb.append(digits[v ushr 4]).append(digits[v and 0x0f]) }
    return sb.toString()
}

/** A wrong key, altered data or a wrong tag: the sealed data cannot be opened. */
class CryptoException(message: String) : Exception(message)
