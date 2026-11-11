package com.lumentv.desktop

import com.lumentv.core.Featured
import com.lumentv.core.asHttp
import com.lumentv.data.Tmdb
import com.lumentv.data.discover.TitleIndex
import com.lumentv.data.discover.VodLite
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import okhttp3.OkHttpClient
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Temporary probe: every TMDB list the homes are made of, against the PC's copy of the list (LUMEN_CATALOG). For each
 * TMDB title the row would not show, looks whether the list has it anyway (another name, another year, a copy left out)
 * and writes what it finds to LUMEN_REPORT.
 */
class RowsProbe {
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @Test fun probe() {
        val path = System.getenv("LUMEN_CATALOG") ?: return
        val report = File(System.getenv("LUMEN_REPORT") ?: return)
        val c = GZIPInputStream(File(path).inputStream().buffered()).use { Json { ignoreUnknownKeys = true }.decodeFromStream<Catalog>(it) }
        val movies = c.movies.mapIndexed { i, m -> VodLite(i.toLong(), m.uid, m.name, m.category, m.year, m.rating) }
        val series = c.series.mapIndexed { i, s -> VodLite(i.toLong(), s.uid, s.name, s.category, s.year, s.rating) }
        val idx = TitleIndex(movies, series)
        val tmdb = Tmdb(OkHttpClient().asHttp(), BuildInfo.TMDB_KEY)
        // Every copy, kept or not, by its key; and the keys, for a looser look.
        val allByKey = mapOf(false to movies.groupBy { TitleIndex.key(it.name) }, true to series.groupBy { TitleIndex.key(it.name) })
        val today = java.time.LocalDate.now().toString()
        val since = java.time.LocalDate.now().minusDays(60).toString()
        val out = StringBuilder()
        val totals = HashMap<String, Int>()

        fun check(label: String, isSeries: Boolean, items: List<Tmdb.Item>) {
            // Titles found only through their English name (a list naming foreign films in English).
            val byEnglish = items.filter { it.english.isNotBlank() && idx.find(isSeries, it.title, it.original, it.year) == null && idx.find(isSeries, it) != null }
            if (byEnglish.isNotEmpty()) out.append("$label : ${byEnglish.size} trouvés grâce au titre anglais : " +
                byEnglish.joinToString { e -> "${e.title} = ${idx.find(isSeries, e)?.name}" } + "\n")
            totals["english"] = (totals["english"] ?: 0) + byEnglish.size
            var shown = 0
            val lines = ArrayList<String>()
            for (it in items) {
                if (idx.find(isSeries, it) != null) { shown++; continue }
                val names = listOf(it.title, it.original, it.english).filter { n -> n.isNotBlank() }.distinct()
                val keys = names.map { n -> TitleIndex.key(n) }.filter { k -> k.length >= 2 }
                val exact = keys.flatMap { k -> allByKey.getValue(isSeries)[k].orEmpty() }.distinctBy { v -> v.id }
                val why = when {
                    exact.isNotEmpty() -> {
                        val kept = exact.filter { v -> !Featured.foreign(v.name, v.category) && !Featured.otherLanguage(v.name) }
                        if (kept.isEmpty()) "copies écartées (langue) : " + exact.take(3).joinToString { v -> v.name }
                        else "année ${it.year} ≠ " + kept.take(3).joinToString { v -> "${v.name} [${v.year}]" }
                    }
                    else -> {
                        // Looser: the list's title starts with TMDB's (or the reverse), long enough to mean something.
                        val loose = keys.filter { k -> k.length >= 6 }.flatMap { k ->
                            allByKey.getValue(isSeries).entries.filter { (lk, _) -> lk.length >= 6 && (lk.startsWith(k) || k.startsWith(lk)) }.flatMap { e -> e.value }
                        }.distinctBy { v -> v.id }.take(3)
                        if (loose.isEmpty()) null else "autre nom : " + loose.joinToString { v -> "${v.name} [${v.year}]" }
                    }
                }
                if (why != null) lines += "  - ${names.joinToString(" / ")} (${it.year}) → $why"
            }
            totals["missed"] = (totals["missed"] ?: 0) + lines.size
            totals["shown"] = (totals["shown"] ?: 0) + shown
            out.append("$label : ${items.size} titres TMDB, $shown dans la liste, ${lines.size} présents mais manqués\n")
            lines.forEach { l -> out.append(l).append('\n') }
        }

        for (isSeries in listOf(false, true)) {
            val type = if (isSeries) "tv" else "movie"
            val region = if (isSeries) emptyArray() else arrayOf("region" to "FR")
            val kind = if (isSeries) "Séries" else "Films"
            val release = if (isSeries) "first_air_date" else "primary_release_date"
            check("$kind · Tendances", isSeries, tmdb.list("trending/$type/week", 5, true))
            check("$kind · Nouveautés", isSeries, tmdb.list(if (isSeries) "tv/on_the_air" else "movie/now_playing", 5, true, *region))
            check("$kind · Populaires", isSeries, tmdb.list("$type/popular", 5, true, *region))
            check("$kind · Mieux notés", isSeries, tmdb.list("$type/top_rated", 6, true))
            (if (isSeries) Tmdb.TV_GENRES else Tmdb.MOVIE_GENRES).forEach { (name, id) ->
                check("$kind · $name", isSeries, tmdb.list("discover/$type", 4, true, "with_genres" to "$id", "sort_by" to "popularity.desc", "vote_count.gte" to "50"))
            }
            Tmdb.PLATFORMS.forEach { (name, id) ->
                val where = arrayOf("with_watch_providers" to "$id", "watch_region" to "FR", "with_watch_monetization_types" to "flatrate", "vote_count.gte" to "10")
                check("$kind · $name", isSeries, if (isSeries) tmdb.list("discover/tv", 5, true, *where, "air_date.gte" to since, "air_date.lte" to today,
                    "sort_by" to "popularity.desc", "without_genres" to "10763,10764,10766,10767")
                else tmdb.list("discover/movie", 5, true, *where, "sort_by" to "$release.desc", "$release.lte" to today))
            }
        }
        report.writeText("Total : ${totals["shown"]} affichés, ${totals["english"]} grâce au titre anglais, ${totals["missed"]} présents dans la liste mais manqués\n\n$out")
        println("PROBE ${totals["english"]} grâce à l'anglais, ${totals["shown"]} affichés, ${totals["missed"]} manqués")
    }
}
