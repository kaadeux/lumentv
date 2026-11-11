package com.lumentv.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.lumentv.core.Http
import com.lumentv.core.HttpRequest
import com.lumentv.core.InFlight
import com.lumentv.core.Lru
import com.lumentv.core.query

/**
 * TMDB (free personal key): lists (trending, popular, best rated, by genre, by streaming service in France), a
 * title's details (genres, cast, directors, keywords, collection, recommendations, similar titles, other titles) and
 * search. Shared with the iPhone; the client given ([http]) keeps the answers (TV / PC: TmdbHttp's disk cache).
 * Every call is blocking: run them off the main thread.
 */
class Tmdb(private val http: Http, private val key: String) {
    /** A film or series of a TMDB list. */
    class Item(val id: String, val title: String, val original: String, val year: Int?, val rating: Double, val votes: Int,
               val popularity: Double, val genres: List<Int>) {
        /** The English title, when the list was asked with English titles too (see [list]). */
        var english: String = ""
    }

    /** What makes a title like another: for the recommendations. */
    class Details(
        val id: String, val title: String, val original: String, val year: Int?, val rating: Double, val votes: Int, val popularity: Double,
        val genres: List<Int>, val cast: List<Int>, val directors: List<Int>, val keywords: List<Int>, val collection: Int?,
        val recommendations: List<Item>, val similar: List<Item>, val otherTitles: List<String>,
        val names: Map<Int, String>,
    )

    private val json = Json { ignoreUnknownKeys = true }
    /** Calls that got no usable answer (no network, TMDB busy…): whoever needs them can ask again. */
    @kotlin.concurrent.Volatile var failures = 0; private set
    /** Title of the playlist → TMDB id (null: TMDB does not know it), for the session. */
    private val ids = Lru<String, String?>(5000)

    val enabled get() = key.isNotBlank()

    /** [path]: "trending/movie/week", "movie/popular", "discover/movie"… [pages] pages of 20, merged. */
    fun list(path: String, pages: Int = 1, vararg params: Pair<String, String>): List<Item> = list(path, pages, false, *params)

    /**
     * [english]: the same pages asked in English too, so each item knows its English title ([Item.english]): a list
     * names a Swedish series "A Couple of Lies", TMDB in French "Trouble échange" (original "Bytet").
     */
    fun list(path: String, pages: Int, english: Boolean, vararg params: Pair<String, String>): List<Item> {
        if (!enabled) return emptyList()
        fun page(p: Int, lang: String) = get("https://api.themoviedb.org/3/$path", "page" to "$p", "language" to lang, *params)?.let { items(it) }
        val all = (1..pages).flatMap { p -> page(p, "fr-FR") ?: return@flatMap emptyList() }.distinctBy { it.id }
        if (english && all.isNotEmpty()) {
            val names = (1..pages).flatMap { p -> page(p, "en-US").orEmpty() }.associate { it.id to it.title }
            all.forEach { it.english = names[it.id].orEmpty() }
        }
        return all
    }

    /** The films of a saga (TMDB collection), in their order of release: "Harry Potter", "Mission Impossible"… */
    fun collection(id: Int): List<Item> {
        if (!enabled) return emptyList()
        val o = get("https://api.themoviedb.org/3/collection/$id", "language" to "fr-FR") ?: return emptyList()
        return (o["parts"] as? JsonArray).orEmpty().mapNotNull { p -> (p as? JsonObject)?.let { it to item(it) } }
            .filter { (o, it) -> it != null && !o.s("release_date").isNullOrBlank() }
            .sortedBy { (o, _) -> o.s("release_date") }.mapNotNull { it.second }
    }

    /** Titles TMDB recommends to who liked this one (its users' viewing), best first. */
    fun recommendations(series: Boolean, id: String): List<Item> = list((if (series) "tv" else "movie") + "/$id/recommendations")

    /** One request for everything the recommendations need about a title. */
    fun details(series: Boolean, id: String): Details? {
        if (!enabled) return null
        val kind = if (series) "tv" else "movie"
        val d = get("https://api.themoviedb.org/3/$kind/$id", "language" to "fr-FR",
            "append_to_response" to "credits,keywords,recommendations,similar,alternative_titles") ?: return null
        val names = HashMap<Int, String>()
        val credits = d["credits"] as? JsonObject
        val cast = (credits?.get("cast") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.take(8).mapNotNull { c ->
            c.s("id")?.toIntOrNull()?.also { names[it] = c.s("name").orEmpty() }
        }
        val directors = if (series) (d["created_by"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { c -> c.s("id")?.toIntOrNull()?.also { names[it] = c.s("name").orEmpty() } }
        else (credits?.get("crew") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.filter { it.s("job") == "Director" }
            .mapNotNull { c -> c.s("id")?.toIntOrNull()?.also { names[it] = c.s("name").orEmpty() } }
        val kw = (d["keywords"] as? JsonObject)?.let { (it["keywords"] ?: it["results"]) as? JsonArray }.orEmpty().mapNotNull { (it as? JsonObject)?.s("id")?.toIntOrNull() }
        val alt = (d["alternative_titles"] as? JsonObject)?.let { (it["titles"] ?: it["results"]) as? JsonArray }.orEmpty()
            .mapNotNull { (it as? JsonObject)?.s("title") }
        return Details(
            id = id, title = d.s("title") ?: d.s("name").orEmpty(), original = d.s("original_title") ?: d.s("original_name").orEmpty(),
            year = (d.s("release_date") ?: d.s("first_air_date"))?.take(4)?.toIntOrNull(),
            rating = d.s("vote_average")?.toDoubleOrNull() ?: 0.0, votes = d.s("vote_count")?.toIntOrNull() ?: 0,
            popularity = d.s("popularity")?.toDoubleOrNull() ?: 0.0,
            genres = (d["genres"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.s("id")?.toIntOrNull() },
            cast = cast, directors = directors, keywords = kw,
            collection = (d["belongs_to_collection"] as? JsonObject)?.s("id")?.toIntOrNull(),
            recommendations = (d["recommendations"] as? JsonObject)?.let { items(it) }.orEmpty(),
            similar = (d["similar"] as? JsonObject)?.let { items(it) }.orEmpty(),
            otherTitles = alt, names = names,
        )
    }

    /** The films or series a person played in or directed, most popular first. */
    fun credits(series: Boolean, person: Int): List<Item> {
        val o = get("https://api.themoviedb.org/3/person/$person/" + (if (series) "tv_credits" else "movie_credits"), "language" to "fr-FR") ?: return emptyList()
        return (listOf(o["cast"], o["crew"])).flatMap { (it as? JsonArray).orEmpty() }.mapNotNull { (it as? JsonObject)?.let(::item) }
            .distinctBy { it.id }.sortedByDescending { it.popularity }
    }

    /** A person TMDB knows: [department] is what they are known for ("Acting", "Directing", "Writing"…). */
    class Person(val id: Int, val name: String, val department: String, val popularity: Double)

    /** A film or series of a person: [made] when they directed or created it, else they play in it. */
    class Work(val series: Boolean, val item: Item, val made: Boolean)

    /**
     * The people whose name answers [query] ("tom holland", "nolan"), most known first: every word of the query starts
     * a word of their name, so a film's title ("avatar") does not bring up strangers.
     */
    fun people(query: String): List<Person> {
        val o = get("https://api.themoviedb.org/3/search/person", "query" to query, "language" to "fr-FR") ?: return emptyList()
        return (o["results"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { r ->
            val id = r.s("id")?.toIntOrNull() ?: return@mapNotNull null
            Person(id, r.s("name") ?: return@mapNotNull null, r.s("known_for_department").orEmpty(), r.s("popularity")?.toDoubleOrNull() ?: 0.0)
        }.filter { com.lumentv.core.SmartSearch.nameAnswers(query, it.name) }.sortedByDescending { it.popularity }
            // "tom holland" typed in full: Tom Holland, not also Tom Hollander.
            .let { all -> all.filter { com.lumentv.core.SmartSearch.nameIs(query, it.name) }.ifEmpty { all } }
    }

    /** Everything [person] played in, directed or created (films and series), most popular first. */
    fun works(person: Int): List<Work> {
        val o = get("https://api.themoviedb.org/3/person/$person/combined_credits", "language" to "fr-FR") ?: return emptyList()
        fun list(key: String, made: Boolean) = (o[key] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .filter { !made || it.s("job") in MAKERS }
            .mapNotNull { r -> item(r)?.let { Work(r.s("media_type") == "tv", it, made) } }
        return (list("crew", true) + list("cast", false)).distinctBy { "${it.series}${it.item.id}${it.made}" }.sortedByDescending { it.item.popularity }
    }

    /** Free search, films or series, French titles and original ones. */
    fun search(series: Boolean, query: String): List<Item> {
        val o = get("https://api.themoviedb.org/3/search/" + (if (series) "tv" else "movie"), "query" to query, "language" to "fr-FR") ?: return emptyList()
        return items(o)
    }

    /** The TMDB id of a title from the playlist, found by its name and year (kept, the absence too). */
    fun idOf(series: Boolean, name: String, year: Int?): String? {
        if (!enabled) return null
        // "Les Misérables - 2012", "Dune 4K": searched as "Les Misérables" (2012), "Dune".
        val title = com.lumentv.core.Featured.searchTitle(name)
        val k = (if (series) "tv|" else "mv|") + title + "|" + year
        if (k in ids) return ids[k]
        val kind = if (series) "tv" else "movie"
        // Only a result that is this title, never just the first one (see TmdbMatch).
        val found = TmdbMatch.find(title, year, { y ->
            val params = listOfNotNull("query" to title, "language" to "fr-FR", y?.let { (if (series) "first_air_date_year" else "year") to it.toString() })
            get("https://api.themoviedb.org/3/search/$kind", *params.toTypedArray())?.let { items(it) }.orEmpty()
        }, { listOf(it.title, it.original) })?.id
        ids[k] = found
        return found
    }

    private fun items(o: JsonObject): List<Item> = (o["results"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::item) }

    private fun item(r: JsonObject): Item? {
        val id = r.s("id") ?: return null
        val title = r.s("title") ?: r.s("name") ?: return null
        return Item(
        id = id,
        title = title,
        original = r.s("original_title") ?: r.s("original_name").orEmpty(),
        year = (r.s("release_date") ?: r.s("first_air_date"))?.take(4)?.toIntOrNull(),
        rating = r.s("vote_average")?.toDoubleOrNull() ?: 0.0,
        votes = r.s("vote_count")?.toIntOrNull() ?: 0,
        popularity = r.s("popularity")?.toDoubleOrNull() ?: 0.0,
        genres = (r["genre_ids"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.toIntOrNull() },
        )
    }

    /** Same request asked twice at once (two rows, the banner and a page): one call to TMDB, shared. */
    private val inFlight = InFlight<String, JsonObject?>()

    private fun get(url: String, vararg params: Pair<String, String>): JsonObject? = runCatching {
        val u = "$url?${query("api_key" to key, *params)}"
        inFlight.get(u) {
            http.execute(HttpRequest(u, timeoutMs = 10_000)).use { r ->
                // An unknown title (404) is an answer; a busy or failing server is not.
                if (r.code == 429 || r.code >= 500) failures++
                if (!r.isSuccessful) null else json.parseToJsonElement(r.text()) as? JsonObject
            }
        }
    }.onFailure { failures++ }.getOrNull()

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

    companion object {
        /** TMDB's answers kept on disk. */
        const val CACHE_BYTES = 20L * 1024 * 1024
        /** Posters, backdrops and logos kept on disk by the apps' image loaders (Coil): TV, phone and PC. */
        const val IMAGE_CACHE_BYTES = 60L * 1024 * 1024
        /** The jobs that make a person the author of a title: directing a film, creating a series. */
        val MAKERS = setOf("Director", "Creator")

        /** Streaming services in France (TMDB / JustWatch ids). */
        val PLATFORMS = listOf("Netflix" to 8, "Prime Video" to 119, "Disney+" to 337, "Max" to 1899, "Apple TV+" to 350, "Paramount+" to 531, "Canal+" to 381)

        /** Genre rows of the Films home (TMDB movie genres). */
        val MOVIE_GENRES = listOf(
            "Action" to 28, "Aventure" to 12, "Science-fiction" to 878, "Fantastique" to 14, "Comédie" to 35, "Thriller" to 53,
            "Horreur" to 27, "Romance" to 10749, "Policier" to 80, "Animation" to 16, "Documentaire" to 99, "Films familiaux" to 10751,
        )

        /** Genre rows of the Séries home (TMDB TV genres). */
        val TV_GENRES = listOf(
            "Action et aventure" to 10759, "Science-fiction et fantastique" to 10765, "Comédie" to 35, "Policier" to 80, "Drame" to 18,
            "Mystère" to 9648, "Animation" to 16, "Documentaire" to 99, "Familial" to 10751,
        )

        /** Names of the genres, both lists (for "Dans vos genres préférés"). */
        val GENRE_NAMES: Map<Int, String> = (MOVIE_GENRES + TV_GENRES).associate { it.second to it.first } + mapOf(18 to "Drame", 36 to "Histoire", 10752 to "Guerre", 37 to "Western", 9648 to "Mystère")
    }
}
