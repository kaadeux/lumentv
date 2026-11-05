package com.lumentv.core

/** A small text file of the app (a cache): read whole, written whole. JVM: java.io.File; iPhone: Foundation. */
expect class TextFile(path: String) {
    /** The content, or null when the file does not exist or cannot be read. */
    fun read(): String?
    /** Writes [text], creating the folder if needed; false when it cannot. */
    fun write(text: String): Boolean
    /** Removes the file (nothing when it does not exist). */
    fun delete()
}

/** The body decompressed (gzip) as it is read: a 9 MB guide never sits whole in memory. */
expect fun gunzip(body: HttpBody): HttpBody

/** A local file read piece by piece (a playlist or a guide picked on the device). Throws [IOException] if missing. */
expect fun openFile(path: String): HttpBody

/** The body's UTF-8 lines, one per call, null at the end (an M3U playlist of 50 MB is never whole in memory). */
expect fun HttpBody.lineReader(): () -> String?

/** The body as it is, or decompressed when it is gzip (starts with 1f 8b): playlists and guides come both ways. */
fun HttpBody.ungzipped(): HttpBody {
    val buf = ByteArray(64 * 1024)
    var head = ByteArray(0)
    // At least 2 bytes to decide (a read may give fewer).
    while (head.size < 2) {
        val r = read(buf)
        if (r < 0) break
        head += buf.copyOf(r)
    }
    val start = PrefixedBody(head, this)
    return if (head.size >= 2 && head[0] == 0x1f.toByte() && head[1] == 0x8b.toByte()) gunzip(start) else start
}

/** [prefix] (already read) then the rest of [rest]. */
private class PrefixedBody(private val prefix: ByteArray, private val rest: HttpBody) : HttpBody {
    private var pos = 0
    override fun read(buffer: ByteArray): Int {
        if (pos < prefix.size) {
            val n = minOf(buffer.size, prefix.size - pos)
            prefix.copyInto(buffer, 0, pos, pos + n)
            pos += n
            return n
        }
        return rest.read(buffer)
    }
    override fun close() = rest.close()
}
