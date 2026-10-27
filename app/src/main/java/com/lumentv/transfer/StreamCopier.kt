package com.lumentv.transfer

import com.lumentv.core.FormatException
import com.lumentv.core.HttpStatusException
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

data class HlsSegment(val sequence: Long, val uri: String, val key: HlsKey?, val map: String?)
data class HlsKey(val method: String, val uri: String?, val iv: ByteArray?)
data class HlsMedia(val targetDurationSec: Int, val segments: List<HlsSegment>, val ended: Boolean)

/** Minimal HLS playlist reading: enough to download an HLS movie. */
object Hls {
    fun isMaster(text: String) = text.contains("#EXT-X-STREAM-INF")

    /** The variant with the highest BANDWIDTH, resolved against the playlist URL. */
    fun bestVariant(master: String, base: String): String? {
        var best: Pair<Long, String>? = null
        var pendingBw: Long? = null
        for (raw in master.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                pendingBw = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0
            } else if (pendingBw != null && line.isNotEmpty() && !line.startsWith("#")) {
                if (best == null || pendingBw > best.first) best = pendingBw to resolve(base, line)
                pendingBw = null
            }
        }
        return best?.second
    }

    fun parseMedia(text: String, base: String): HlsMedia {
        var target = 6
        var seq = 0L
        var key: HlsKey? = null
        var map: String? = null
        val out = ArrayList<HlsSegment>()
        var pendingSegment = false
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-TARGETDURATION:") -> target = line.substringAfter(':').trim().toDoubleOrNull()?.toInt() ?: target
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> seq = line.substringAfter(':').trim().toLongOrNull() ?: seq
                line.startsWith("#EXT-X-KEY:") -> {
                    val attrs = line.substringAfter(':')
                    val method = attr(attrs, "METHOD") ?: "NONE"
                    key = if (method == "NONE") null else HlsKey(method, attr(attrs, "URI")?.let { resolve(base, it) },
                        attr(attrs, "IV")?.removePrefix("0x")?.removePrefix("0X")?.let(::hex))
                }
                line.startsWith("#EXT-X-MAP:") -> map = attr(line.substringAfter(':'), "URI")?.let { resolve(base, it) }
                line.startsWith("#EXTINF") -> pendingSegment = true
                line.isNotEmpty() && !line.startsWith("#") && pendingSegment -> {
                    out += HlsSegment(seq++, resolve(base, line), key, map)
                    pendingSegment = false
                }
            }
        }
        return HlsMedia(target, out, text.contains("#EXT-X-ENDLIST"))
    }

    private fun attr(attrs: String, name: String): String? =
        Regex("(?:^|,)$name=(\"[^\"]*\"|[^,]*)").find(attrs)?.groupValues?.get(1)?.trim('"')

    fun resolve(base: String, ref: String): String = runCatching { URI(base).resolve(ref.trim()).toString() }.getOrDefault(ref)

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    /** IV of a segment: the one in the key, else its media sequence number as 16 big-endian bytes. */
    fun ivFor(seg: HlsSegment): ByteArray = seg.key?.iv ?: ByteArray(16).also { b ->
        var v = seg.sequence
        for (i in 15 downTo 8) { b[i] = (v and 0xff).toByte(); v = v shr 8 }
    }
}

/**
 * Downloads a movie or an episode to a file: a plain file with resume, or an HLS stream segment by segment.
 * Runs on a background thread.
 */
class StreamCopier(
    private val http: OkHttpClient,
    private val userAgent: String?,
    private val referer: String?,
    private val cancelled: () -> Boolean,
    private val onProgress: (bytes: Long, total: Long) -> Unit,
) {
    private var written = 0L

    private fun request(url: String, range: Long = 0): Request = Request.Builder().url(url).apply {
        userAgent?.let { header("User-Agent", it) }
        referer?.let { header("Referer", it) }
        if (range > 0) header("Range", "bytes=$range-")
    }.build()

    private fun text(url: String): String = http.newCall(request(url)).execute().use { r ->
        if (!r.isSuccessful) throw HttpStatusException(r.code)
        r.body?.string() ?: ""
    }

    private fun bytes(url: String): ByteArray = http.newCall(request(url)).execute().use { r ->
        if (!r.isSuccessful) throw HttpStatusException(r.code)
        r.body?.bytes() ?: ByteArray(0)
    }

    private fun recordHls(url: String, first: String, out: OutputStream, untilMs: Long) {
        val mediaUrl = if (Hls.isMaster(first)) Hls.bestVariant(first, url) ?: throw FormatException("Playlist HLS sans variante lisible.") else url
        var lastSeq = -1L
        var mapWritten: String? = null
        val keys = HashMap<String, ByteArray>()
        var failures = 0
        while (!cancelled() && System.currentTimeMillis() < untilMs) {
            val media = try { Hls.parseMedia(text(mediaUrl), mediaUrl).also { failures = 0 } } catch (e: IOException) {
                if (++failures > 20) throw e
                Thread.sleep(2000); continue
            }
            // On a live list, start with the newest segments, as a TV starts "now", not minutes in the past.
            if (lastSeq < 0 && !media.ended && media.segments.size > 3) lastSeq = media.segments[media.segments.size - 3].sequence
            for (seg in media.segments) {
                if (seg.sequence <= lastSeq) continue
                if (cancelled() || System.currentTimeMillis() >= untilMs) return
                if (seg.map != null && seg.map != mapWritten) { out.write(bytes(seg.map)); mapWritten = seg.map }
                var data = runCatching { bytes(seg.uri) }.getOrNull() ?: continue
                seg.key?.let { k ->
                    if (k.method != "AES-128" || k.uri == null) throw FormatException("Chiffrement ${k.method} non pris en charge pour l'enregistrement.")
                    val keyBytes = keys.getOrPut(k.uri) { bytes(k.uri) }
                    data = Cipher.getInstance("AES/CBC/PKCS5Padding").run {
                        init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(Hls.ivFor(seg)))
                        doFinal(data)
                    }
                }
                out.write(data)
                written += data.size
                lastSeq = seg.sequence
                onProgress(written, 0)
            }
            if (media.ended) return
            Thread.sleep((media.targetDurationSec * 500L).coerceIn(1000, 5000))
        }
    }

    /** Downloads an on-demand file, resuming a partial one. HLS movies are fetched segment by segment. */
    fun download(url: String, file: File): Long {
        file.parentFile?.mkdirs()
        if (url.substringBefore('?').lowercase().endsWith(".m3u8")) {
            FileOutputStream(file, false).use { recordHls(url, text(url), it, Long.MAX_VALUE) }
            return written
        }
        val have = if (file.exists()) file.length() else 0L
        http.newCall(request(url, have)).execute().use { r ->
            if (r.code == 416) { written = have; return have }
            if (!r.isSuccessful) throw HttpStatusException(r.code)
            val append = r.code == 206 && have > 0
            val body = r.body ?: throw FormatException("Réponse vide.")
            val total = (if (append) have else 0) + body.contentLength().coerceAtLeast(0)
            written = if (append) have else 0
            FileOutputStream(file, append).use { out ->
                val input = body.byteStream()
                val buf = ByteArray(64 * 1024)
                var lastReport = 0L
                while (true) {
                    if (cancelled()) throw InterruptedException()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    written += n
                    if (written - lastReport > 2_000_000) { onProgress(written, total); lastReport = written }
                }
            }
            onProgress(written, total)
        }
        return written
    }
}
