package com.lumentv.data.sports

import com.lumentv.core.Http
import com.lumentv.core.HttpBody
import com.lumentv.core.HttpRequest
import com.lumentv.core.IoDispatcher
import com.lumentv.core.TextFile
import com.lumentv.core.XmlReader
import com.lumentv.core.gunzip
import com.lumentv.core.normalize
import com.lumentv.core.nowMillis
import com.lumentv.data.epg.XmltvParser
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** One programme of a French sports channel: which channel ("Ligue 1+ 2", "Canal+ Sport 360"), when, what (normalised). */
@Serializable
data class Listing(val channel: String, val start: Long, val title: String)

/**
 * Which French channel shows a game, days ahead: the free XMLTV France guide (xmltvfr.fr, about 390 channels over
 * 5 days) read at most twice a day, keeping only the sports channels. The small result is kept in [cacheFile].
 * [newReader] reads XML from a body: the platform's pull parser on the TV and the PC (xmlPullReaders), the shared
 * KotlinXmlReader on the iPhone.
 */
class FrenchTvGuide(private val http: Http, private val cacheFile: TextFile, private val newReader: (HttpBody) -> XmlReader) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    @kotlin.concurrent.Volatile private var mem: Cache? = null

    @Serializable private class Cache(val fetchedAt: Long, val items: List<Listing>)

    /** One background refresh at a time. */
    private val refreshLock = SynchronizedObject()
    private var refreshing = false
    private val background = CoroutineScope(SupervisorJob() + IoDispatcher)

    /**
     * The listings at once: an older copy (up to 2 days) is answered immediately and refreshed in the background, so
     * the sports screen never waits for the 9 MB download; only the very first time does.
     */
    suspend fun listings(): List<Listing> {
        val now = nowMillis()
        val cached = withContext(IoDispatcher) { cached() }
        if (cached != null && now - cached.fetchedAt < MAX_AGE) return cached.items
        if (cached != null && now - cached.fetchedAt < 4 * MAX_AGE) {
            val start = synchronized(refreshLock) { if (refreshing) false else { refreshing = true; true } }
            if (start) background.launch { try { refresh() } finally { synchronized(refreshLock) { refreshing = false } } }
            return cached.items
        }
        return refresh()
    }

    /** Downloads the guide again when the copy is older than 12 h (at start-up, in the background). */
    suspend fun refresh(): List<Listing> = lock.withLock {
        withContext(IoDispatcher) {
            val now = nowMillis()
            val cached = cached()
            if (cached != null && now - cached.fetchedAt < MAX_AGE) return@withContext cached.items
            runCatching { fetch(now) }.onSuccess { items ->
                val c = Cache(now, items); mem = c
                cacheFile.write(json.encodeToString(c))
            }.getOrNull() ?: cached?.items.orEmpty()
        }
    }

    private fun cached(): Cache? = mem ?: cacheFile.read()?.let { runCatching { json.decodeFromString<Cache>(it) }.getOrNull() }?.also { mem = it }

    private fun fetch(now: Long): List<Listing> {
        // 9 MB compressed: 3 minutes at most for the whole download.
        http.execute(HttpRequest(URL, headers = mapOf("User-Agent" to "LumenTV"), timeoutMs = 180_000)).use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            val names = HashMap<String, String>()
            val out = ArrayList<Listing>()
            XmltvParser.parse(newReader(gunzip(r.body)), onChannel = { ch -> ch.names.firstOrNull()?.takeIf { sportsChannel.containsMatchIn(it) }?.let { names[ch.id] = it } }) { p ->
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
        /** A copy younger than this is used as is; older ones are refreshed. */
        private const val MAX_AGE = 12 * 3_600_000L
        private val sportsChannel = Regex("(?i)canal\\+|bein|ligue 1|rmc sport|eurosport|dazn|^france [2-5]$|^tf1$|^m6$|^w9$|tmc|tfx|l.equipe|" +
            "infosport|sport en france|prime video|amazon|rugby")

        /**
         * The first broadcast naming both teams of [f], within about a day of the official kick-off (the official time
         * may be off; later broadcasts are replays). @return the listing, or null.
         */
        fun broadcast(f: Fixture, listings: List<Listing>): Listing? = broadcasts(f, listings).firstOrNull()

        /**
         * The live broadcasts of [f]: the programmes naming both teams that start around the official kick-off (from an
         * hour before, for the build-up, to 45 minutes after), nearest first; Canal+ and Canal+ Live 1 can both show it.
         * Programmes earlier or later the same day are replays and only count when there is no live one (the official
         * time can be wrong), the nearest to kick-off first.
         */
        fun broadcasts(f: Fixture, listings: List<Listing>): List<Listing> {
            if (f.home == null || f.away == null) return emptyList()
            val named = listings.filter { it.start in (f.start - 30 * 3_600_000L)..(f.start + 30 * 3_600_000L) }
                .filter { l -> val t = " ${l.title} "; listOf(f.home, f.away).all { team -> team.aliases.any { a -> t.contains(" $a ") || (a.length >= 6 && t.contains(a)) } } }
            val live = named.filter { it.start in (f.start - 60 * 60_000L)..(f.start + 45 * 60_000L) }
            return if (live.isNotEmpty()) live.sortedBy { kotlin.math.abs(it.start - f.start) }
            else named.sortedBy { kotlin.math.abs(it.start - f.start) }.take(1)
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

        /**
         * A channel in French, for the Sports panel: no country prefix but FR ("AR | beIN", "UK: Sky" are not), no foreign
         * country or language in its name or group ("SPORT AR", "DAZN ARABIC", "beIN SPORTS ES"), no Arabic script.
         */
        fun french(name: String, group: String): Boolean {
            if (!frenchOrPlain(name) || !frenchOrPlain(group)) return false
            if ((name + group).any { it in '؀'..'ۿ' }) return false
            val words = (com.lumentv.core.normalize(name) + " " + com.lumentv.core.normalize(group)).split(' ').toSet()
            // "Borussia Dortmund v Real Madrid": the English way of writing a game (French channels write "-" or "/").
            return words.none { it in foreign } && !englishGame.containsMatchIn(name)
        }

        // Made once: built at each call, they took seconds over the 56 000 channels of a big list.
        private val englishGame = Regex("\\S \\bv\\b \\S")
        private val countryPrefix = Regex("^\\s*([A-Za-z0-9]{2,3})\\s*[|:]")

        private val foreign = setOf("ar", "arab", "arabic", "arabe", "uk", "us", "usa", "es", "esp", "espana", "spain", "it", "ita", "italia",
            "de", "ger", "germany", "deutsch", "pt", "portugal", "br", "brasil", "nl", "tr", "turk", "turkey", "pl", "ru", "en", "eng", "english",
            "latino", "latam", "mx", "mena", "stan", "au", "aus", "australia", "nz", "optus", "kayo", "ca", "canada", "afrika", "africa", "india", "pk", "gr", "ro", "al", "ex yu", "exyu", "kurd", "persian", "iran")

        /** A playlist channel the viewer can use for a French broadcast: no country prefix, or FR. */
        fun frenchOrPlain(name: String): Boolean {
            val m = countryPrefix.find(name) ?: return true
            return m.groupValues[1].equals("FR", true)
        }
    }
}
