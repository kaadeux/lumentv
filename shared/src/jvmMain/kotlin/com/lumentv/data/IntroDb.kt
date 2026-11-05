package com.lumentv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Where the recap, the intro and the credits are in an episode (ms); null when unknown. */
data class Segments(val recap: LongRange?, val intro: LongRange?, val credits: LongRange?) {
    val known get() = recap != null || intro != null

    /** This one, its missing parts taken from [other]. */
    fun fill(other: Segments) = Segments(recap ?: other.recap, intro ?: other.intro, credits ?: other.credits).sane()

    /**
     * Community entries are sometimes wrong: an "intro" lying over the recap (the recap timed twice) is dropped, so
     * another database can give the real one (From S2E7: recap, a scene, then the intro).
     */
    fun sane(): Segments = if (recap != null && intro != null && intro.first < recap.last - 3_000) copy(intro = null) else this
}

/**
 * Recap, intro and credits of an episode from the free community databases, so the players can offer "Passer le
 * récap", "Passer l'intro" and "Épisode suivant" at the right moments: IntroDB first (the most complete), then
 * TheIntroDB and SkipDB for the parts it lacks. IntroDB and SkipDB want the IMDb id, found once per series from the
 * TMDB id on Wikidata. A few small requests per episode at most, kept in small bounded caches (the TV has little memory).
 */
class IntroDb(http: OkHttpClient) {
    private val http = http.newBuilder().callTimeout(5, TimeUnit.SECONDS).build()
    private val episodes = Lru<String, Segments>(300)
    /** TMDB id → IMDb id, "" when Wikidata has none. */
    private val imdbIds = Lru<String, String>(100)

    suspend fun segments(tmdb: String, season: Int, episode: Int): Segments? = withContext(Dispatchers.IO) {
        val key = "$tmdb/$season/$episode"
        episodes[key]?.let { return@withContext it }
        val imdb = imdbOf(tmdb)
        val sources: List<() -> Pair<Segments, Set<String>>?> = listOf(
            { imdb?.let { id -> get(INTRODB.toHttpUrl().newBuilder().addQueryParameter("imdb_id", id).season(season, episode))?.let(::parseIntroDb) } },
            { get(THEINTRODB.toHttpUrl().newBuilder().addQueryParameter("tmdb_id", tmdb).season(season, episode))?.let { parse(it)?.to(emptySet()) } },
            { imdb?.let { id -> get(SKIPDB.toHttpUrl().newBuilder().addQueryParameter("imdb_id", id).season(season, episode))?.let(::parseSkipDb) } },
        )
        var merged: Segments? = null
        // Parts a database confirms the episode does not have ("no recap"): no need to look further for them.
        val absent = HashSet<String>()
        for (source in sources) {
            val m = merged
            if (m != null && (m.recap != null || "recap" in absent) && (m.intro != null || "intro" in absent) && m.credits != null) break
            val (raw, none) = runCatching { source() }.getOrNull() ?: continue
            val found = raw.sane()
            merged = m?.fill(found) ?: found
            absent += none
        }
        merged?.also { episodes[key] = it }
    }

    private fun imdbOf(tmdb: String): String? {
        imdbIds[tmdb]?.let { return it.ifEmpty { null } }
        val query = "SELECT ?imdb WHERE { ?s wdt:P4983 \"$tmdb\"; wdt:P345 ?imdb } LIMIT 1"
        val body = get(WIKIDATA.toHttpUrl().newBuilder().addQueryParameter("query", query).addQueryParameter("format", "json")) ?: return null
        val id = Regex("\"(tt\\d+)\"").find(body)?.groupValues?.get(1).orEmpty()
        imdbIds[tmdb] = id
        return id.ifEmpty { null }
    }

    private fun okhttp3.HttpUrl.Builder.season(season: Int, episode: Int) =
        addQueryParameter("season", season.toString()).addQueryParameter("episode", episode.toString())

    /** The body of a successful answer, null on any failure (nothing is cached then). */
    private fun get(url: okhttp3.HttpUrl.Builder): String? = runCatching {
        http.newCall(Request.Builder().url(url.build()).header("User-Agent", "LumenTV/1.0 (lecteur IPTV familial)").build()).execute()
            .use { r -> if (r.isSuccessful) r.body?.string() else null }
    }.getOrNull()

    /** A small map that forgets the least recently used entries beyond [max]. */
    private class Lru<K, V>(private val max: Int) {
        private val map = object : LinkedHashMap<K, V>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>) = size > max
        }
        @Synchronized operator fun get(k: K): V? = map[k]
        @Synchronized operator fun set(k: K, v: V) { map[k] = v }
    }

    companion object {
        private const val INTRODB = "https://api.introdb.app/segments"
        private const val THEINTRODB = "https://api.theintrodb.org/v3/media"
        private const val SKIPDB = "https://api.skipdb.tv/api/segments"
        private const val WIKIDATA = "https://query.wikidata.org/sparql"

        /** A recap or intro needs a real end of at least 5 s; credits may run to the end of the episode. */
        private fun range(o: JsonObject?, openEnd: Boolean): LongRange? {
            o ?: return null
            val start = (o["start_ms"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return null
            val end = (o["end_ms"] as? JsonPrimitive)?.content?.toLongOrNull() ?: if (openEnd) Long.MAX_VALUE else return null
            return if (end - start >= 5_000) start until end else null
        }

        private fun JsonElement?.obj() = this as? JsonObject

        /** TheIntroDB: each part is a list of ranges. */
        fun parse(body: String): Segments? {
            val o = Json.parseToJsonElement(body).obj() ?: return null
            fun seg(name: String, openEnd: Boolean) = range((o[name] as? JsonArray)?.firstOrNull().obj(), openEnd)
            return Segments(seg("recap", false), seg("intro", false), seg("credits", true))
        }

        /** IntroDB: one range per part, and "no_recap" / "no_intro" when the community confirmed there is none. */
        fun parseIntroDb(body: String): Pair<Segments, Set<String>>? {
            val o = Json.parseToJsonElement(body).obj() ?: return null
            if (o["error"] != null) return null
            val none = buildSet {
                if ((o["no_recap"] as? JsonPrimitive)?.content == "true") add("recap")
                if ((o["no_intro"] as? JsonPrimitive)?.content == "true") add("intro")
            }
            return Segments(range(o["recap"].obj(), false), range(o["intro"].obj(), false), range(o["outro"].obj(), true)) to none
        }

        /** SkipDB: the parts sit under "segments". */
        fun parseSkipDb(body: String): Pair<Segments, Set<String>>? {
            val s = Json.parseToJsonElement(body).obj()?.get("segments").obj() ?: return null
            return Segments(range(s["recap"].obj(), false), range(s["intro"].obj(), false), range(s["outro"].obj(), true)) to emptySet()
        }
    }
}
