package com.lumentv.desktop

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * A small HTTP relay on 127.0.0.1 between VLC and the provider. Every stream goes through it, so through the app's
 * private client (encrypted DNS, HTTPS when the server allows it) rather than VLC's own network code. For films and
 * episodes it also rides over stalls: VLC waits for ever on a
 * connection the server stops feeding; the relay notices after a few seconds without data and reopens it at the
 * same byte (Range), while VLC still plays from its buffer: nothing shows. The TV's player does the same by itself.
 * One upstream connection at a time per request (the subscription allows one screen); VLC's seeks are passed on.
 */
object StreamRelay {
    /** Without a byte for this long, the download is reopened where it stopped. */
    private const val SILENCE_MS = 6_000L
    private const val MAX_RESUMES = 20

    private val http = with(com.lumentv.core.PrivateNet) { OkHttpClient.Builder().private() }.connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(SILENCE_MS, TimeUnit.MILLISECONDS).retryOnConnectionFailure(true).build()
    /** Live channels: no byte to resume at; a longer silence is allowed before the player's own reconnection. */
    private val liveHttp = http.newBuilder().readTimeout(15, TimeUnit.SECONDS).build()
    private class Target(val url: String, val userAgent: String, val live: Boolean)
    private val targets = object : LinkedHashMap<String, Target>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Target>) = size > 8
    }
    private val server: ServerSocket by lazy {
        ServerSocket(0, 16, InetAddress.getLoopbackAddress()).also { s ->
            Thread({ while (true) { val c = runCatching { s.accept() }.getOrNull() ?: continue; Thread({ serve(c) }, "relay").apply { isDaemon = true }.start() } },
                "relay-accept").apply { isDaemon = true }.start()
        }
    }

    /** The address VLC should open instead of [url]. */
    fun wrap(url: String, userAgent: String, live: Boolean = false): String {
        val token = java.util.UUID.randomUUID().toString().replace("-", "")
        synchronized(targets) { targets[token] = Target(url, userAgent, live) }
        // The file name is kept so VLC still recognises the container (.mkv, .mp4…).
        return "http://127.0.0.1:${server.localPort}/$token/${url.substringBefore('?').substringAfterLast('/')}"
    }

    private fun serve(client: Socket) = client.use { c ->
        c.soTimeout = 30_000
        val reader = BufferedReader(InputStreamReader(c.getInputStream(), Charsets.ISO_8859_1))
        val requestLine = reader.readLine() ?: return
        val headers = generateSequence { reader.readLine()?.takeIf { it.isNotEmpty() } }.toList()
        val method = requestLine.substringBefore(' ')
        val token = requestLine.split(' ').getOrNull(1)?.trim('/')?.substringBefore('/') ?: return
        val target = synchronized(targets) { targets[token] } ?: return c.getOutputStream().write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
        val range = headers.firstOrNull { it.startsWith("Range:", true) }?.substringAfter(':')?.trim()
        val out = c.getOutputStream()
        var upstream = open(target, method, range) ?: return out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".toByteArray())
        try {
            // Status and the headers VLC needs, as the provider sent them.
            val head = StringBuilder("HTTP/1.1 ${upstream.code} ${upstream.message.ifBlank { "OK" }}\r\n")
            for (h in listOf("Content-Type", "Content-Length", "Content-Range", "Accept-Ranges", "Last-Modified")) upstream.header(h)?.let { head.append("$h: $it\r\n") }
            head.append("Connection: close\r\n\r\n")
            out.write(head.toString().toByteArray(Charsets.ISO_8859_1)); out.flush()
            if (method == "HEAD" || !upstream.isSuccessful) return
            // Where this answer starts and ends in the file, to resume at the right byte.
            val first = upstream.header("Content-Range")?.let { Regex("bytes (\\d+)-").find(it)?.groupValues?.get(1)?.toLongOrNull() } ?: 0L
            val length = upstream.header("Content-Length")?.toLongOrNull()
            val last = length?.let { first + it - 1 }
            val canResume = !target.live && (upstream.code == 206 || upstream.header("Accept-Ranges")?.contains("bytes") == true)
            val at = longArrayOf(first) // next byte to send, kept even when the copy fails half-way
            var resumes = 0
            while (true) {
                val error = runCatching { copy(upstream, out, at) }.exceptionOrNull()
                if (error is DownstreamClosed) return // VLC closed or seeks elsewhere
                val pos = at[0]
                val complete = last != null && pos > last
                if (complete || (error == null && last == null)) return
                // The server went silent or cut early: reopen at the same byte.
                if (!canResume || ++resumes > MAX_RESUMES) return
                upstream.close()
                PlaybackLog.line("relais : reprise du téléchargement à ${pos / 1_048_576} Mo (${if (error == null) "coupure" else "silence du serveur"}, n°$resumes)")
                upstream = open(target, "GET", "bytes=$pos-${last ?: ""}")?.takeIf { it.code == 206 } ?: return
            }
        } finally {
            upstream.close()
        }
    }

    private class DownstreamClosed : Exception()

    /** Copies the body to VLC, advancing [at]; upstream silence (read timeout) or a cut ends it with an error / early. */
    private fun copy(upstream: Response, out: OutputStream, at: LongArray) {
        val input = upstream.body?.byteStream() ?: return
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) return
            try { out.write(buf, 0, n) } catch (e: java.io.IOException) { throw DownstreamClosed() }
            at[0] += n
        }
    }

    private fun open(t: Target, method: String, range: String?): Response? = runCatching {
        val b = Request.Builder().url(t.url).method(if (method == "HEAD") "HEAD" else "GET", null)
        if (t.userAgent.isNotBlank()) b.header("User-Agent", t.userAgent)
        b.header("Accept-Encoding", "identity") // the bytes as they are: sizes and Range positions must match the file
        if (range != null) b.header("Range", range)
        (if (t.live) liveHttp else http).newCall(b.build()).execute()
    }.getOrNull()
}
