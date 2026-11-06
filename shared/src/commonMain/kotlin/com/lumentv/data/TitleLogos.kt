package com.lumentv.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.lumentv.core.Http
import com.lumentv.core.HttpRequest
import com.lumentv.core.Lru
import com.lumentv.core.query

/**
 * The title of a film or series as its poster writes it (the logo: "SUPERMAN", "STRANGER THINGS"), from TMDB with a
 * free personal key. French logo first, then English, then one without words. Found by the TMDB id when the provider
 * gives it, else by the title and the year. Kept for the session, the absence of a logo too.
 */
class TitleLogos(private val http: Http, private val key: String) {
    private val json = Json { ignoreUnknownKeys = true }
    /**
     * What TMDB says of a title: its logo, its rating (out of 10, from its users' votes), its summary in French (null
     * when TMDB has none in French), its main actors and who made it (directors of a film, creators of a series), and
     * its poster (French when there is one): shown when the provider's is missing or does not load.
     */
    class Info(val logo: String?, val rating: Double?, val votes: Int,
               val overview: String? = null, val cast: List<String> = emptyList(), val makers: List<String> = emptyList(),
               val poster: String? = null)

    private val cache = Lru<String, Info?>(600)

    val enabled get() = key.isNotBlank()

    fun cached(series: Boolean, tmdb: String?, title: String, year: Int?): Info? = cache.peek(keyOf(series, tmdb, title, year))

    /** Logo, rating, summary and cast (one request: the details with their images and credits), null when TMDB does not know the title. Blocking. */
    fun info(series: Boolean, tmdb: String?, title: String, year: Int?): Info? {
        if (!enabled) return null
        val k = keyOf(series, tmdb, title, year)
        if (k in cache) return cache[k]
        val found = runCatching {
            val kind = if (series) "tv" else "movie"
            val id = tmdb ?: search(kind, clean(title), year ?: com.lumentv.core.Featured.yearOf(null, title)) ?: return@runCatching null
            val d = get("https://api.themoviedb.org/3/$kind/$id", "language" to "fr-FR", "append_to_response" to "images,credits",
                "include_image_language" to "fr,en,null") ?: return@runCatching null
            val logos = ((d["images"] as? JsonObject)?.get("logos") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            val best = listOf("fr", "en", null).firstNotNullOfOrNull { lang -> logos.firstOrNull { it.s("iso_639_1") == lang } }
            val votes = d.s("vote_count")?.toIntOrNull() ?: 0
            val credits = d["credits"] as? JsonObject
            val cast = (credits?.get("cast") as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.s("name") }.take(CAST)
            val makers = if (series) (d["created_by"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.s("name") }
            else (credits?.get("crew") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.filter { it.s("job") == "Director" }.mapNotNull { it.s("name") }
            Info(best?.s("file_path")?.let { "https://image.tmdb.org/t/p/w500$it" }, d.s("vote_average")?.toDoubleOrNull()?.takeIf { votes >= MIN_VOTES && it > 0 }, votes,
                d.s("overview")?.trim()?.takeIf { it.isNotEmpty() }, cast, makers.distinct().take(3),
                d.s("poster_path")?.let { "https://image.tmdb.org/t/p/w342$it" })
        }.getOrNull()
        cache[k] = found
        return found
    }

    companion object {
        /** Fewer votes and the rating says little (an unknown film "10/10"): not shown. */
        const val MIN_VOTES = 20
        /** Actors shown: the first ones TMDB lists (by billing). */
        const val CAST = 6
    }

    /** The title's TMDB id: only a result that is this title (see [TmdbMatch]), never just the first one. */
    private fun search(kind: String, title: String, year: Int?): String? {
        val found = TmdbMatch.find(title, year, { y ->
            val params = listOfNotNull("query" to title, "language" to "fr-FR", y?.let { (if (kind == "tv") "first_air_date_year" else "year") to it.toString() })
            (get("https://api.themoviedb.org/3/search/$kind", *params.toTypedArray())?.get("results") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        }, { r -> listOfNotNull(r.s("title"), r.s("name"), r.s("original_title"), r.s("original_name")) })
        return found?.s("id")
    }

    private fun get(url: String, vararg params: Pair<String, String>): JsonObject? {
        // 8 s at most: a slow answer only costs the logo, the page shows the title in text meanwhile.
        return http.execute(HttpRequest("$url?${query("api_key" to key, *params)}", timeoutMs = 8_000)).use { r ->
            if (!r.isSuccessful) null else json.parseToJsonElement(r.text()) as? JsonObject
        }
    }

    private fun keyOf(series: Boolean, tmdb: String?, title: String, year: Int?) = (if (series) "tv" else "mv") + "|" + (tmdb ?: "${clean(title)}|${year ?: com.lumentv.core.Featured.yearOf(null, title)}")

    /** The provider's title as TMDB knows it ("Les Misérables SD" → "Les Misérables"). */
    private fun clean(title: String) = com.lumentv.core.Featured.searchTitle(title)
    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
}
