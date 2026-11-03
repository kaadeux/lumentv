package com.lumentv.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * TMDB's lists (what is trending this week, popular in France, best rated) and its recommendations for a title, to
 * put forward the films and series of the playlist that people watch, and more of what the family watched. Lists are
 * kept 6 hours, recommendations for the session. Needs the free key, like [TitleLogos].
 */
class Tmdb(http: OkHttpClient, private val key: String) {
    /** A film or series of TMDB, with what is needed to find it in the playlist. */
    class Item(val id: String, val title: String, val original: String, val year: Int?, val rating: Double, val votes: Int)

    private val http = http.newBuilder().callTimeout(10, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val lists = HashMap<String, Pair<Long, List<Item>>>()
    private val ids = HashMap<String, String?>()

    val enabled get() = key.isNotBlank()

    /** [path]: "trending/movie/week", "movie/popular", "tv/top_rated"… [pages] pages of 20. Blocking. */
    fun list(path: String, pages: Int = 1, vararg params: Pair<String, String>): List<Item> {
        if (!enabled) return emptyList()
        val k = path + params.joinToString { "${it.first}=${it.second}" } + "/$pages"
        synchronized(lists) { lists[k]?.let { (t, l) -> if (System.currentTimeMillis() - t < 6 * 3_600_000L) return l } }
        val out = (1..pages).flatMap { p -> items(get("https://api.themoviedb.org/3/$path", "page" to "$p", "language" to "fr-FR", *params) ?: return@flatMap emptyList()) }
            .distinctBy { it.id }
        if (out.isNotEmpty()) synchronized(lists) { lists[k] = System.currentTimeMillis() to out }
        return out
    }

    /** Titles TMDB recommends to who liked this one (its users' viewing), best first. */
    fun recommendations(series: Boolean, id: String): List<Item> = list((if (series) "tv" else "movie") + "/$id/recommendations")

    /** The TMDB id of a title from the playlist, found by its name and year (kept, the absence too). */
    fun idOf(series: Boolean, title: String, year: Int?): String? {
        if (!enabled) return null
        val k = (if (series) "tv|" else "mv|") + title + "|" + year
        synchronized(ids) { if (ids.containsKey(k)) return ids[k] }
        val kind = if (series) "tv" else "movie"
        val params = listOfNotNull("query" to title, "language" to "fr-FR", year?.let { (if (series) "first_air_date_year" else "year") to it.toString() })
        val found = runCatching { items(get("https://api.themoviedb.org/3/search/$kind", *params.toTypedArray()) ?: return@runCatching null).firstOrNull()?.id }.getOrNull()
        synchronized(ids) { ids[k] = found }
        return found
    }

    private fun items(o: JsonObject): List<Item> = (o["results"] as? JsonArray).orEmpty().mapNotNull { e ->
        val r = e as? JsonObject ?: return@mapNotNull null
        Item(
            id = r.s("id") ?: return@mapNotNull null,
            title = r.s("title") ?: r.s("name") ?: return@mapNotNull null,
            original = r.s("original_title") ?: r.s("original_name").orEmpty(),
            year = (r.s("release_date") ?: r.s("first_air_date"))?.take(4)?.toIntOrNull(),
            rating = r.s("vote_average")?.toDoubleOrNull() ?: 0.0,
            votes = r.s("vote_count")?.toIntOrNull() ?: 0,
        )
    }

    private fun get(url: String, vararg params: Pair<String, String>): JsonObject? = runCatching {
        val u = url.toHttpUrl().newBuilder().addQueryParameter("api_key", key).apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        http.newCall(Request.Builder().url(u).build()).execute().use { r -> if (!r.isSuccessful) null else json.parseToJsonElement(r.body!!.string()) as? JsonObject }
    }.getOrNull()

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
}
