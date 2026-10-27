package com.lumentv.data.sports

import com.lumentv.core.normalize
import com.lumentv.data.epg.XmltvParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/** One programme of a French sports channel: which channel ("Ligue 1+ 2", "Canal+ Sport 360"), when, what (normalised). */
@Serializable
data class Listing(val channel: String, val start: Long, val title: String)

/**
 * Which French channel shows a game, days ahead: the free XMLTV France guide (xmltvfr.fr, about 390 channels over
 * 5 days) read at most twice a day, keeping only the sports channels. The small result is kept in [cacheFile].
 * [newParser] gives the platform's XML pull parser (built into Android, kxml2 on Windows).
 */
class FrenchTvGuide(http: OkHttpClient, private val cacheFile: File, private val newParser: () -> XmlPullParser) {
    private val http = http.newBuilder().readTimeout(120, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    @Volatile private var mem: Cache? = null

    @Serializable private class Cache(val fetchedAt: Long, val items: List<Listing>)

    suspend fun listings(): List<Listing> = lock.withLock {
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val cached = mem ?: runCatching { json.decodeFromString<Cache>(cacheFile.readText()) }.getOrNull()?.also { mem = it }
            if (cached != null && now - cached.fetchedAt < 12 * 3_600_000L) return@withContext cached.items
            runCatching { fetch(now) }.onSuccess { items ->
                val c = Cache(now, items); mem = c
                runCatching { cacheFile.parentFile?.mkdirs(); cacheFile.writeText(json.encodeToString(c)) }
            }.getOrNull() ?: cached?.items.orEmpty()
        }
    }

    private fun fetch(now: Long): List<Listing> {
        val req = Request.Builder().url(URL).header("User-Agent", "LumenTV").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            val names = HashMap<String, String>()
            val out = ArrayList<Listing>()
            val parser = newParser().apply { setInput(GZIPInputStream(r.body!!.byteStream()), "UTF-8") }
            XmltvParser.parse(parser, onChannel = { ch -> ch.names.firstOrNull()?.takeIf { sportsChannel.containsMatchIn(it) }?.let { names[ch.id] = it } }) { p ->
                val name = names[p.channel] ?: return@parse
                if (p.start < now - 3 * 3_600_000L || p.start > now + 6 * 86_400_000L) return@parse
                // Games are titled "A / B" or "Sport : Compétition | A / B": keep titles with two sides only.
                if (!p.title.contains(" / ") && !p.title.contains(" - ") && !p.title.contains(" vs", true)) return@parse
                out += Listing(name, p.start, normalize(p.title))
            }
            return out
        }
    }

    companion object {
        const val URL = "https://xmltvfr.fr/xmltv/xmltv_fr.xml.gz"
        private val sportsChannel = Regex("(?i)canal\\+|bein|ligue 1|rmc sport|eurosport|dazn|^france [2-5]$|^tf1$|^m6$|^w9$|tmc|tfx|l.equipe|" +
            "infosport|sport en france|prime video|amazon|rugby")

        /**
         * The first broadcast naming both teams of [f], within about a day of the official kick-off (the official time
         * may be off; later broadcasts are replays). @return the listing, or null.
         */
        fun broadcast(f: Fixture, listings: List<Listing>): Listing? {
            if (f.home == null || f.away == null) return null
            return listings.asSequence()
                .filter { it.start in (f.start - 30 * 3_600_000L)..(f.start + 30 * 3_600_000L) }
                .filter { l -> val t = " ${l.title} "; listOf(f.home, f.away).all { team -> team.aliases.any { a -> t.contains(" $a ") || (a.length >= 6 && t.contains(a)) } } }
                .sortedBy { it.start }.firstOrNull()
        }

        /**
         * Compact comparison key for channel names: "CANAL+ SPORT360 HD" and "Canal+ Sport 360" → "canal+sport360"
         * ("+" kept, so "Canal+ Sport+" stays another channel).
         */
        fun channelKey(name: String): String =
            com.lumentv.core.channelKeyName(name.replace("+", " plus ")).replace(" ", "").replace("[h265]", "").replace("plus", "+")

        /** Of the playlist's copies of one channel, the two best: FHD, then HD, then the rest. */
        fun <T> best(copies: List<T>, name: (T) -> String): List<T> = copies.sortedBy { c ->
            val n = name(c).uppercase()
            when { "FHD" in n -> 0; "HD" in n && "UHD" !in n -> 1; "H265" in n || "HEVC" in n -> 3; "SD" in n -> 4; else -> 2 }
        }.take(2)

        /** A playlist channel the viewer can use for a French broadcast: no country prefix, or FR. */
        fun frenchOrPlain(name: String): Boolean {
            val m = Regex("^\\s*([A-Za-z0-9]{2,3})\\s*[|:]").find(name) ?: return true
            return m.groupValues[1].equals("FR", true)
        }
    }
}
