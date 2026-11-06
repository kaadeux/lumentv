package com.lumentv.core

/**
 * The family access code, the same on every app: only this hash is built in (Android and the PC from local.properties,
 * the iPhone from the repository's secret), and an app built without one has no gate.
 */
object AccessCode {
    fun hash(code: String): String =
        Crypto.sha256("lumen-access:${code.trim()}".encodeToByteArray()).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
