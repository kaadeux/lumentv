package com.lumentv.core

/**
 * Encrypts playlist secrets (M3U URLs, Xtream passwords, EPG URLs) before they go to the database, with an AES key
 * kept by the system: the Android Keystore on the TV and the phone, the Keychain on the iPhone. Values are stored as
 * "k1:<base64(iv + ciphertext)>"; when the system store is broken the value is kept as "p:<text>" so the app still
 * works (the database itself stays private to the app).
 */
expect object SecretBox {
    fun encrypt(plain: String): String
    /** The text, or "" when it cannot be read back (key lost). */
    fun decrypt(stored: String): String
}
