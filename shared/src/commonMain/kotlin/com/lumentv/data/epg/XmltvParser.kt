package com.lumentv.data.epg

import com.lumentv.core.FormatException
import com.lumentv.core.XmlReader
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

data class XmltvChannel(val id: String, val names: List<String>)
data class XmltvProgram(val channel: String, val start: Long, val stop: Long, val title: String, val description: String?)

/**
 * Streaming XMLTV reader. The caller supplies the reader already set to the input (Android's parser on the TV,
 * kxml2 on the PC, [com.lumentv.core.KotlinXmlReader] on the iPhone). Nothing is kept in memory: channels and
 * programmes go to the callbacks.
 */
object XmltvParser {

    fun parse(
        parser: XmlReader,
        onChannel: (XmltvChannel) -> Unit,
        onProgram: (XmltvProgram) -> Unit,
    ): Int {
        var programs = 0
        var sawTv = false
        var event = parser.eventType
        while (event != XmlReader.END_DOCUMENT) {
            if (event == XmlReader.START_TAG) {
                when (parser.name) {
                    "tv" -> sawTv = true
                    "channel" -> readChannel(parser)?.let(onChannel)
                    "programme" -> readProgramme(parser)?.let { onProgram(it); programs++ }
                    "html", "HTML" -> throw FormatException("Le serveur a renvoyé une page web au lieu d'un guide XMLTV.")
                }
            }
            event = parser.next()
        }
        if (!sawTv) throw FormatException("Ce fichier n'est pas un guide XMLTV (balise <tv> absente).")
        return programs
    }

    private fun readChannel(p: XmlReader): XmltvChannel? {
        val id = p.getAttributeValue(null, "id")?.trim()
        val names = ArrayList<String>(2)
        val depth = p.depth
        while (true) {
            val ev = p.next()
            if (ev == XmlReader.END_DOCUMENT) break
            if (ev == XmlReader.END_TAG && p.depth == depth) break
            if (ev == XmlReader.START_TAG && p.name == "display-name") readText(p).trim().takeIf { it.isNotEmpty() }?.let(names::add)
        }
        return if (id.isNullOrEmpty()) null else XmltvChannel(id, names)
    }

    private fun readProgramme(p: XmlReader): XmltvProgram? {
        val channel = p.getAttributeValue(null, "channel")?.trim()
        val start = parseTime(p.getAttributeValue(null, "start"))
        val stop = parseTime(p.getAttributeValue(null, "stop"))
        var title: String? = null
        var desc: String? = null
        val depth = p.depth
        while (true) {
            val ev = p.next()
            if (ev == XmlReader.END_DOCUMENT) break
            if (ev == XmlReader.END_TAG && p.depth == depth) break
            if (ev == XmlReader.START_TAG) when (p.name) {
                "title" -> { val t = readText(p); if (title == null) title = t }
                "desc" -> { val d = readText(p); if (desc == null) desc = d }
            }
        }
        if (channel.isNullOrEmpty() || start == null) return null
        val end = stop?.takeIf { it > start } ?: (start + 30 * 60_000L)
        return XmltvProgram(channel, start, end, title?.trim().orEmpty().ifEmpty { "Sans titre" }, desc?.trim()?.takeIf { it.isNotEmpty() })
    }

    /** Reads the text of the current element and leaves the parser on its END_TAG. */
    private fun readText(p: XmlReader): String {
        val sb = StringBuilder()
        val depth = p.depth
        while (true) {
            val ev = p.next()
            if (ev == XmlReader.TEXT || ev == XmlReader.CDSECT || ev == XmlReader.ENTITY_REF) sb.append(p.text ?: "")
            if (ev == XmlReader.END_DOCUMENT) break
            if (ev == XmlReader.END_TAG && p.depth == depth) break
        }
        return sb.toString()
    }

    /** "20261027203000 +0100" -> epoch ms. Without offset the time is UTC, as XMLTV says. */
    fun parseTime(value: String?): Long? {
        val v = value?.trim() ?: return null
        if (v.length < 12 || !v.substring(0, 12).all { it.isDigit() }) return null
        // Lenient, as java.util.Calendar was: "240000" is midnight the next day, day 32 the 1st of the next month
        // (some providers write them so).
        val sec = if (v.length >= 14 && v[12].isDigit() && v[13].isDigit()) v.substring(12, 14).toInt() else 0
        val day = runCatching {
            LocalDate(v.substring(0, 4).toInt(), 1, 1)
                .plus(DatePeriod(months = v.substring(4, 6).toInt() - 1, days = v.substring(6, 8).toInt() - 1))
        }.getOrNull() ?: return null
        var ms = day.toEpochDays() * 86_400_000L +
            (v.substring(8, 10).toLong() * 3600 + v.substring(10, 12).toLong() * 60 + sec) * 1000
        val tz = Regex("([+-])(\\d{2}):?(\\d{2})\\s*$").find(v)
        if (tz != null) {
            val sign = if (tz.groupValues[1] == "-") -1 else 1
            val offset = (tz.groupValues[2].toInt() * 60 + tz.groupValues[3].toInt()) * 60_000L
            ms -= sign * offset
        }
        return ms
    }
}
