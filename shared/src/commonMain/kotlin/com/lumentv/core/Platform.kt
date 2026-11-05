package com.lumentv.core

/**
 * The few things the shared code needs from the system, given by each platform (jvmMain: TV, phone, PC; iosMain:
 * iPhone). Everything else is plain Kotlin.
 */

/** Network and file errors: java.io.IOException on the JVM (OkHttp's errors are of this kind). */
expect open class IOException(message: String?) : Exception

/** Unicode canonical decomposition (NFD): "é" becomes "e" + its accent, which [normalize] then drops. */
internal expect fun decomposeUnicode(text: String): String

/** Now, in milliseconds since 1970. */
fun nowMillis(): Long = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()

/** "7,3": one decimal, with a comma as written in French. */
fun oneDecimal(value: Double): String {
    val tenths = kotlin.math.round(value * 10).toLong()
    val sign = if (tenths < 0) "-" else ""
    val abs = kotlin.math.abs(tenths)
    return "$sign${abs / 10},${abs % 10}"
}

/** Decodes "%C3%A9" and "+" in a URL's query part (UTF-8), as java.net.URLDecoder does. */
fun urlDecode(text: String): String {
    if ('%' !in text && '+' !in text) return text
    val out = ArrayList<Byte>(text.length)
    var i = 0
    while (i < text.length) {
        val c = text[i]
        val hex = if (c == '%' && i + 2 < text.length) text.substring(i + 1, i + 3).toIntOrNull(16) else null
        when {
            c == '+' -> { out += ' '.code.toByte(); i++ }
            hex != null -> { out += hex.toByte(); i += 3 }
            else -> {
                // One character as UTF-8 (both halves of an emoji together).
                val n = if (c.isHighSurrogate() && i + 1 < text.length) 2 else 1
                text.substring(i, i + n).encodeToByteArray().forEach { out += it }
                i += n
            }
        }
    }
    return out.toByteArray().decodeToString()
}
