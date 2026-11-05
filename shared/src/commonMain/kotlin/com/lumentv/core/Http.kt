package com.lumentv.core

import kotlinx.coroutines.CoroutineDispatcher

/**
 * The network as the shared code sees it: one request, one answer read as a stream. Each platform gives its own
 * client: OkHttp on the TV, the phone and the PC (with its retries, encrypted DNS and caches: [okHttp] in jvmMain),
 * NSURLSession on the iPhone. Calls are blocking: run them off the main thread ([IoDispatcher]).
 */
interface Http {
    /** Throws [IOException] when there is no answer (no network, timeout…); an HTTP error status is an answer. */
    fun execute(request: HttpRequest): HttpResponse
}

class HttpRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    /** The request's body (PUT / POST), with its [contentType]. */
    val body: ByteArray? = null,
    val contentType: String? = null,
    /** The whole call at most this long (default: the client's). */
    val timeoutMs: Long? = null,
) {
    fun header(name: String, value: String) = HttpRequest(url, method, headers + (name to value), body, contentType, timeoutMs)
}

/** An answer: its status, headers and body. Close it (use { }) once read. */
class HttpResponse(val code: Int, private val headerOf: (String) -> String?, val body: HttpBody) : AutoCloseable {
    val isSuccessful get() = code in 200..299
    fun header(name: String): String? = headerOf(name)
    fun bytes(): ByteArray = body.readAll()
    fun text(): String = bytes().decodeToString()
    override fun close() = body.close()
}

/** A body read piece by piece (a 9 MB guide never sits whole in memory on the TV). */
interface HttpBody : AutoCloseable {
    /** Fills [buffer] from its start; the number of bytes read, or -1 at the end. */
    fun read(buffer: ByteArray): Int

    fun readAll(): ByteArray {
        val chunks = ArrayList<ByteArray>()
        val buf = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val n = read(buf)
            if (n < 0) break
            if (n > 0) { chunks += buf.copyOf(n); total += n }
        }
        val out = ByteArray(total)
        var pos = 0
        for (c in chunks) { c.copyInto(out, pos); pos += c.size }
        return out
    }

    companion object {
        /** A body already in memory (the iPhone's answers; tests). */
        fun of(bytes: ByteArray): HttpBody = object : HttpBody {
            private var pos = 0
            override fun read(buffer: ByteArray): Int {
                if (pos >= bytes.size) return -1
                val n = minOf(buffer.size, bytes.size - pos)
                bytes.copyInto(buffer, 0, pos, pos + n)
                pos += n
                return n
            }
            override fun close() {}
        }
    }
}

/** Where blocking network and file work runs: Dispatchers.IO on the JVM, its equivalent on the iPhone. */
expect val IoDispatcher: CoroutineDispatcher

/** Encodes a value for a URL's query, as java.net.URLEncoder does ("a b&c" → "a+b%26c"). */
fun urlEncode(text: String): String {
    val sb = StringBuilder(text.length + 8)
    for (b in text.encodeToByteArray()) {
        val c = (b.toInt() and 0xff)
        when {
            c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code || c == '.'.code || c == '-'.code || c == '*'.code || c == '_'.code ->
                sb.append(c.toChar())
            c == ' '.code -> sb.append('+')
            else -> sb.append('%').append("0123456789ABCDEF"[c ushr 4]).append("0123456789ABCDEF"[c and 0x0f])
        }
    }
    return sb.toString()
}

/** A query string from its parameters, encoded: "a=1&b=x+y". */
fun query(vararg params: Pair<String, String>): String = params.joinToString("&") { (k, v) -> urlEncode(k) + "=" + urlEncode(v) }
