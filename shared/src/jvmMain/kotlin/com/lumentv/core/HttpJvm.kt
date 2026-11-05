package com.lumentv.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

actual val IoDispatcher: CoroutineDispatcher get() = Dispatchers.IO

/**
 * TV, phone and PC: the shared code's [Http] over an OkHttp client, so everything set on that client still applies
 * (RetryInterceptor, PrivateNet's encrypted DNS and HTTPS, TMDB's disk cache, NetStats).
 */
fun OkHttpClient.asHttp(): Http = OkHttp(this)

private class OkHttp(private val client: OkHttpClient) : Http {
    /** One client per timeout asked for (they share the connection pool and the interceptors). */
    private val timed = ConcurrentHashMap<Long, OkHttpClient>()

    override fun execute(request: HttpRequest): HttpResponse {
        val c = request.timeoutMs?.let { ms -> timed.getOrPut(ms) { client.newBuilder().callTimeout(ms, TimeUnit.MILLISECONDS).build() } } ?: client
        val needsBody = request.method == "PUT" || request.method == "POST"
        val body = (request.body ?: if (needsBody) ByteArray(0) else null)?.toRequestBody(request.contentType?.toMediaTypeOrNull())
        val r = c.newCall(Request.Builder().url(request.url).apply {
            request.headers.forEach { (k, v) -> header(k, v) }
            method(request.method, body)
        }.build()).execute()
        return HttpResponse(r.code, { r.header(it) }, StreamBody(r.body?.byteStream()) { r.close() })
    }
}

/** A body read from a JVM stream; [stream] is handed as is to the JVM's streaming readers ([inputStream]). */
class StreamBody(val stream: InputStream?, private val onClose: () -> Unit = { stream?.close() }) : HttpBody {
    override fun read(buffer: ByteArray): Int = stream?.read(buffer) ?: -1
    override fun close() = onClose()
}

/** The body as an InputStream, for kotlinx JSON sequences, XmlPullParser, GZIP: the OkHttp stream itself, not a copy. */
fun HttpBody.inputStream(): InputStream = (this as? StreamBody)?.stream ?: object : InputStream() {
    private val one = ByteArray(1)
    override fun read(): Int = if (this@inputStream.read(one) <= 0) -1 else one[0].toInt() and 0xff
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val tmp = if (off == 0 && len == b.size) b else ByteArray(len)
        val n = this@inputStream.read(tmp)
        if (n > 0 && tmp !== b) tmp.copyInto(b, off, 0, n)
        return n
    }
}
