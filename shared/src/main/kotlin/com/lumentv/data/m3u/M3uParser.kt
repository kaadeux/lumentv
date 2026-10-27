package com.lumentv.data.m3u

import com.lumentv.core.FormatException
import java.io.BufferedReader

data class M3uHeader(val epgUrl: String?)

data class M3uEntry(
    val name: String,
    val url: String,
    val attrs: Map<String, String>,
    val group: String,
    val userAgent: String?,
    val referer: String?,
) {
    val tvgId: String? get() = attrs["tvg-id"]?.takeIf { it.isNotBlank() }
    val logo: String? get() = attrs["tvg-logo"]?.takeIf { it.isNotBlank() }
    val catchupDays: Int get() = (attrs["catchup-days"] ?: attrs["timeshift"])?.trim()?.toIntOrNull() ?: 0
    val catchupSource: String? get() = attrs["catchup-source"]?.takeIf { it.isNotBlank() }
}

/**
 * Streaming M3U / M3U8 (extended) parser. Reads line by line, so a 50 000-entry playlist never sits in memory
 * as one string. Understands #EXTINF attributes, #EXTGRP, #EXTVLCOPT http-user-agent / http-referrer,
 * #KODIPROP stream headers and Kodi-style "url|User-Agent=..." suffixes.
 */
object M3uParser {

    /** @return the number of entries emitted. Throws [FormatException] when the text is not a playlist. */
    fun parse(reader: BufferedReader, onHeader: (M3uHeader) -> Unit = {}, onEntry: (M3uEntry) -> Unit): Int {
        var count = 0
        var sawHeader = false
        var lineNo = 0
        var pendingInfo: String? = null
        var pendingGroup: String? = null
        var ua: String? = null
        var referer: String? = null

        while (true) {
            val raw = reader.readLine() ?: break
            lineNo++
            var line = raw.trim()
            if (lineNo == 1) line = line.removePrefix("\uFEFF")
            if (line.isEmpty()) continue

            if (lineNo <= 3 && (line.startsWith("<!DOCTYPE", true) || line.startsWith("<html", true))) {
                throw FormatException("Le serveur a renvoyé une page web au lieu d'une playlist M3U.")
            }
            if (line.startsWith("<?xml") || line.startsWith("<tv")) {
                throw FormatException("Ce fichier est un guide XMLTV, pas une playlist. Ajoutez-le comme guide TV.")
            }

            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    sawHeader = true
                    val attrs = parseAttributes(line.substring(7))
                    val epg = (attrs["url-tvg"] ?: attrs["x-tvg-url"] ?: attrs["tvg-url"])
                        ?.split(',')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                    onHeader(M3uHeader(epg))
                }
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    pendingInfo = line
                    pendingGroup = null
                    ua = null
                    referer = null
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> pendingGroup = line.substring(8).trim()
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val opt = line.substring(11).trim()
                    val key = opt.substringBefore('=').trim().lowercase()
                    val value = opt.substringAfter('=', "").trim()
                    when (key) {
                        "http-user-agent" -> ua = value
                        "http-referrer", "http-referer" -> referer = value
                    }
                }
                line.startsWith("#KODIPROP:", ignoreCase = true) -> {
                    val prop = line.substring(10)
                    if (prop.contains("stream_headers", true)) {
                        val headers = parseHeaderString(prop.substringAfter('=', ""))
                        headers["user-agent"]?.let { ua = it }
                        headers["referer"]?.let { referer = it }
                    }
                }
                line.startsWith("#") -> Unit
                else -> {
                    val info = pendingInfo
                    var url = line
                    var lineUa = ua
                    var lineRef = referer
                    val pipe = url.indexOf('|')
                    if (pipe > 0) {
                        val headers = parseHeaderString(url.substring(pipe + 1))
                        headers["user-agent"]?.let { lineUa = it }
                        headers["referer"]?.let { lineRef = it }
                        url = url.substring(0, pipe)
                    }
                    if (looksLikeUrl(url)) {
                        val (attrs, title) = if (info != null) splitExtInf(info) else emptyMap<String, String>() to ""
                        val name = title.ifBlank { attrs["tvg-name"].orEmpty() }.ifBlank { url.substringAfterLast('/') }
                        val group = attrs["group-title"]?.takeIf { it.isNotBlank() } ?: pendingGroup ?: ""
                        onEntry(M3uEntry(name.trim(), url, attrs, group.trim(), lineUa, lineRef))
                        count++
                    }
                    pendingInfo = null
                    pendingGroup = null
                    ua = null
                    referer = null
                }
            }
        }
        if (count == 0 && !sawHeader) {
            throw FormatException("Ce contenu n'est pas une playlist M3U (en-tête #EXTM3U absent).")
        }
        return count
    }

    private fun looksLikeUrl(s: String): Boolean {
        val i = s.indexOf("://")
        return i in 2..10 || s.startsWith("/") || s.startsWith("rtmp", true)
    }

    /** "#EXTINF:-1 tvg-id="a" group-title="b, c",Name, with comma" -> attrs + "Name, with comma". */
    internal fun splitExtInf(line: String): Pair<Map<String, String>, String> {
        val body = line.substringAfter(':', "")
        var inQuote = false
        var commaAt = -1
        for (i in body.indices) {
            val c = body[i]
            if (c == '"') inQuote = !inQuote
            else if (c == ',' && !inQuote) { commaAt = i; break }
        }
        val attrPart = if (commaAt >= 0) body.substring(0, commaAt) else body
        val title = if (commaAt >= 0) body.substring(commaAt + 1) else ""
        return parseAttributes(attrPart) to title.trim()
    }

    /**
     * key="value" and key=value pairs in one pass, without regular expressions: this runs once per entry,
     * and on a TV's CPU the regex version took most of the import time of a 50 000-entry list.
     */
    internal fun parseAttributes(text: String): Map<String, String> {
        val out = HashMap<String, String>(8)
        val n = text.length
        var i = 0
        while (i < n) {
            while (i < n && !isKeyChar(text[i])) {
                // Skip a quoted value that has no key in front of it.
                if (text[i] == '"') i = text.indexOf('"', i + 1).let { if (it < 0) n else it }
                i++
            }
            val keyStart = i
            while (i < n && isKeyChar(text[i])) i++
            if (i == keyStart) break
            val keyEnd = i
            while (i < n && text[i] == ' ') i++
            if (i >= n || text[i] != '=') continue
            i++
            while (i < n && text[i] == ' ') i++
            val value: String
            if (i < n && text[i] == '"') {
                val end = text.indexOf('"', i + 1).let { if (it < 0) n else it }
                value = text.substring(i + 1, end)
                i = end + 1
            } else {
                val start = i
                while (i < n && text[i] != ' ' && text[i] != '"') i++
                value = text.substring(start, i)
            }
            val key = text.substring(keyStart, keyEnd).lowercase()
            if (key !in out) out[key] = value
        }
        return out
    }

    private fun isKeyChar(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' || c == '_'

    /** "User-Agent=VLC&Referer=http://x" -> lower-cased keys. */
    private fun parseHeaderString(text: String): Map<String, String> =
        text.split('&').mapNotNull { part ->
            val k = part.substringBefore('=', "").trim()
            if (k.isEmpty()) null else k.lowercase() to java.net.URLDecoder.decode(part.substringAfter('='), "UTF-8").trim()
        }.toMap()
}

enum class EntryKind { LIVE, MOVIE, EPISODE }

data class Classified(val kind: EntryKind, val seriesName: String? = null, val season: Int = 0, val episode: Int = 0)

/** Decides whether an M3U entry is a live channel, a movie or a series episode. */
object M3uClassifier {
    private val sxe = Regex("(?i)^(.*?)[\\s._-]*S(\\d{1,2})[\\s._-]*E(\\d{1,4})")
    private val xForm = Regex("(?i)^(.*?)[\\s._-]+(\\d{1,2})x(\\d{1,3})\\b")
    private val vodExt = setOf("mp4", "mkv", "avi", "mov", "m4v", "wmv", "flv", "webm", "mpg", "mpeg")

    fun classify(e: M3uEntry): Classified {
        val path = e.url.substringBefore('?').lowercase()
        val ext = path.substringAfterLast('.', "").takeIf { it.length in 2..4 }
        // The episode patterns only run on entries that are already on demand: live channels skip them.
        fun episodeMatch() = sxe.find(e.name) ?: xForm.find(e.name)
        val type = e.attrs["type"]?.lowercase()
        return when {
            path.contains("/series/") || type == "series" ->
                episode(e, episodeMatch())
            path.contains("/movie/") || path.contains("/movies/") || type == "movie" ->
                Classified(EntryKind.MOVIE)
            ext != null && ext in vodExt ->
                episodeMatch()?.let { episode(e, it) } ?: Classified(EntryKind.MOVIE)
            else -> Classified(EntryKind.LIVE)
        }
    }

    private fun episode(e: M3uEntry, m: MatchResult?): Classified {
        if (m == null) return Classified(EntryKind.EPISODE, e.group.ifBlank { e.name }, 1, 0)
        val series = m.groupValues[1].trim().trimEnd('-', '_', '.', ' ').ifBlank { e.group.ifBlank { e.name } }
        return Classified(EntryKind.EPISODE, series, m.groupValues[2].toInt(), m.groupValues[3].toInt())
    }
}
