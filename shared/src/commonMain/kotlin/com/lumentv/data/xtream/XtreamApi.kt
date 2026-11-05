package com.lumentv.data.xtream

import com.lumentv.core.FormatException
import com.lumentv.core.Http
import com.lumentv.core.HttpBody
import com.lumentv.core.HttpRequest
import com.lumentv.core.HttpResponse
import com.lumentv.core.HttpStatusException
import com.lumentv.core.XtreamAuthException
import com.lumentv.core.forEachJsonValue
import com.lumentv.core.urlDecode
import com.lumentv.core.urlEncode
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class XtreamCredentials(val server: String, val username: String, val password: String)

data class XtreamAccount(
    val status: String?,
    val expiresAt: Long?,
    val activeConnections: Int?,
    val maxConnections: Int?,
    val timeZone: String?,
) {
    fun summary(): String = buildList {
        status?.let { add(it) }
        expiresAt?.let {
            val d = Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.currentSystemDefault()).date
            add("expire le ${d.dayOfMonth.toString().padStart(2, '0')}/${d.monthNumber.toString().padStart(2, '0')}/${d.year}")
        }
        if (maxConnections != null) add("connexions ${activeConnections ?: 0}/$maxConnections")
    }.joinToString(" · ")
}

data class XLive(val streamId: Long, val name: String, val categoryId: String?, val icon: String?, val epgId: String?, val num: Int?, val archiveDays: Int)
data class XMovie(val streamId: Long, val name: String, val categoryId: String?, val icon: String?, val ext: String?, val rating: String?, val added: Long, val year: String?)
data class XSeries(val seriesId: Long, val name: String, val categoryId: String?, val cover: String?, val plot: String?, val rating: String?, val year: String?,
                   val tmdb: String? = null,
                   /** Last change on the provider's side (a new series, or new episodes), epoch ms; 0 when not given. */
                   val updated: Long = 0,
                   /** A wide picture of the series (backdrop), null when the provider has none. */
                   val backdrop: String? = null)
data class XEpisode(val id: Long, val title: String, val season: Int, val episode: Int, val ext: String?, val image: String?, val plot: String?, val durationSecs: Int)
data class XVodInfo(val plot: String?, val durationSecs: Int, val genre: String?, val cast: String?, val director: String?, val releaseDate: String?,
                    /** A wide picture of the film (backdrop), for the page and the banner; null when the provider has none. */
                    val backdrop: String? = null, val rating: String? = null,
                    /** The film's TMDB id when the provider gives it. */
                    val tmdb: String? = null)

/** Xtream Codes `player_api.php` client. Lists are read as a stream, element by element (shared with the iPhone). */
class XtreamApi(private val http: Http, private val creds: XtreamCredentials, private val userAgent: String?) {

    private val base = normalizeServer(creds.server)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun api(action: String?, extra: String = ""): String {
        val u = enc(creds.username)
        val p = enc(creds.password)
        return "$base/player_api.php?username=$u&password=$p" + (action?.let { "&action=$it" } ?: "") + extra
    }

    fun authenticate(): XtreamAccount {
        val root = open(api(null)).use { json.parseToJsonElement(it.text()) } as? JsonObject
            ?: throw FormatException("Réponse Xtream inattendue : ce serveur n'est peut-être pas un serveur Xtream Codes.")
        val user = root["user_info"] as? JsonObject
            ?: throw FormatException("Réponse Xtream sans « user_info » : vérifiez l'adresse du serveur.")
        val auth = user.str("auth")
        if (auth == "0") throw XtreamAuthException("Nom d'utilisateur ou mot de passe refusé par le serveur Xtream.")
        val status = user.str("status")
        if (status != null && !status.equals("Active", true)) {
            throw XtreamAuthException("Compte Xtream inactif (statut : $status).")
        }
        val server = root["server_info"] as? JsonObject
        return XtreamAccount(
            status = status,
            expiresAt = user.long("exp_date")?.takeIf { it > 0 }?.times(1000),
            activeConnections = user.long("active_cons")?.toInt(),
            maxConnections = user.long("max_connections")?.toInt(),
            timeZone = server?.str("timezone"),
        )
    }

    fun liveCategories(): Map<String, String> = categories("get_live_categories")
    fun vodCategories(): Map<String, String> = categories("get_vod_categories")
    fun seriesCategories(): Map<String, String> = categories("get_series_categories")

    private fun categories(action: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        each(api(action)) { o -> o.str("category_id")?.let { out[it] = o.str("category_name").orEmpty() } }
        return out
    }

    fun liveStreams(onItem: (XLive) -> Unit) = each(api("get_live_streams")) { o ->
        val id = o.long("stream_id") ?: return@each
        onItem(XLive(id, o.str("name").orEmpty(), o.str("category_id"), o.str("stream_icon"), o.str("epg_channel_id"),
            o.long("num")?.toInt(), if (o.str("tv_archive") == "1") (o.long("tv_archive_duration")?.toInt() ?: 0) else 0))
    }

    fun vodStreams(onItem: (XMovie) -> Unit) = each(api("get_vod_streams")) { o ->
        val id = o.long("stream_id") ?: return@each
        onItem(XMovie(id, o.str("name").orEmpty(), o.str("category_id"), o.str("stream_icon"), o.str("container_extension"),
            o.str("rating")?.takeIf { it != "0" && it.isNotBlank() }, (o.long("added") ?: 0) * 1000, o.str("year")))
    }

    fun series(onItem: (XSeries) -> Unit) = each(api("get_series")) { o ->
        val id = o.long("series_id") ?: return@each
        onItem(XSeries(id, o.str("name").orEmpty(), o.str("category_id"), o.str("cover"), o.str("plot"),
            o.str("rating")?.takeIf { it != "0" && it.isNotBlank() }, o.str("releaseDate")?.take(4) ?: o.str("year"),
            (o.str("tmdb_id") ?: o.str("tmdb"))?.takeIf { it.isNotBlank() && it != "0" }, (o.long("last_modified") ?: 0) * 1000, o.backdrop()))
    }

    fun seriesInfo(seriesId: Long): List<XEpisode> {
        val root = open(api("get_series_info", "&series_id=$seriesId")).use { json.parseToJsonElement(it.text()) }
        return parseSeriesEpisodes(root)
    }

    fun vodInfo(streamId: Long): XVodInfo? {
        val root = open(api("get_vod_info", "&vod_id=$streamId")).use { json.parseToJsonElement(it.text()) } as? JsonObject
        val info = root?.get("info") as? JsonObject ?: return null
        return XVodInfo(
            plot = info.str("plot") ?: info.str("description"),
            durationSecs = info.long("duration_secs")?.toInt() ?: parseDuration(info.str("duration")),
            genre = info.str("genre"), cast = info.str("cast") ?: info.str("actors"),
            director = info.str("director"), releaseDate = info.str("releasedate") ?: info.str("release_date"),
            backdrop = info.backdrop(), rating = info.str("rating")?.takeIf { it != "0" && it.isNotBlank() },
            tmdb = (info.str("tmdb_id") ?: info.str("tmdb"))?.takeIf { it.isNotBlank() && it != "0" },
        )
    }

    fun liveUrl(streamId: Long, format: String) = "$base/live/${enc(creds.username)}/${enc(creds.password)}/$streamId.$format"
    fun movieUrl(streamId: Long, ext: String?) = "$base/movie/${enc(creds.username)}/${enc(creds.password)}/$streamId.${ext ?: "mp4"}"
    fun episodeUrl(id: Long, ext: String?) = "$base/series/${enc(creds.username)}/${enc(creds.password)}/$id.${ext ?: "mp4"}"

    /** Catch-up (timeshift) URL; the start time is written in the server's time zone, as Xtream panels expect. */
    fun catchupUrl(streamId: Long, startMs: Long, durationMinutes: Int, serverZone: String?): String =
        catchupUrl(base, creds.username, creds.password, streamId, startMs, durationMinutes, serverZone)

    private fun open(url: String): HttpResponse {
        val headers = userAgent?.takeIf { it.isNotBlank() }?.let { mapOf("User-Agent" to it) } ?: emptyMap()
        val resp = http.execute(HttpRequest(url, headers = headers))
        if (!resp.isSuccessful) {
            resp.close()
            throw HttpStatusException(resp.code)
        }
        return resp
    }

    private fun each(url: String, block: (JsonObject) -> Unit) {
        open(url).use { r -> forEachObject(json, r.body, block) }
    }

    companion object {
        fun normalizeServer(input: String): String {
            var s = input.trim()
            if (!s.contains("://")) s = "http://$s"
            s = s.substringBefore("/player_api.php").substringBefore("/get.php").substringBefore("/c/").trimEnd('/')
            return s
        }

        /** Recognises a "get.php?username=..&password=.." M3U link and returns its Xtream credentials. */
        fun fromM3uLink(url: String): XtreamCredentials? {
            if (!url.contains("get.php", true)) return null
            val query = url.substringAfter('?', "")
            val params = query.split('&').associate {
                it.substringBefore('=').lowercase() to urlDecode(it.substringAfter('=', ""))
            }
            val u = params["username"] ?: return null
            val p = params["password"] ?: return null
            return XtreamCredentials(normalizeServer(url), u, p)
        }

        fun xmltvUrl(c: XtreamCredentials) =
            "${normalizeServer(c.server)}/xmltv.php?username=${enc(c.username)}&password=${enc(c.password)}"

        fun catchupUrl(base: String, user: String, pass: String, streamId: Long, startMs: Long, durationMinutes: Int, serverZone: String?): String {
            // An unknown zone counts as GMT, as java.util.TimeZone did.
            val zone = serverZone?.let { runCatching { TimeZone.of(it) }.getOrElse { TimeZone.UTC } } ?: TimeZone.currentSystemDefault()
            val t = Instant.fromEpochMilliseconds(startMs).toLocalDateTime(zone)
            fun two(n: Int) = n.toString().padStart(2, '0')
            val stamp = "${t.year}-${two(t.monthNumber)}-${two(t.dayOfMonth)}:${two(t.hour)}-${two(t.minute)}"
            return "${normalizeServer(base)}/timeshift/${enc(user)}/${enc(pass)}/$durationMinutes/$stamp/$streamId.ts"
        }

        /** Each object of a list answer (an array, or objects one after another). */
        fun forEachObject(json: Json, body: HttpBody, block: (JsonObject) -> Unit) {
            forEachJsonValue(json, body) { el ->
                when (el) {
                    is JsonObject -> block(el)
                    is JsonArray -> el.forEach { (it as? JsonObject)?.let(block) }
                    else -> Unit
                }
            }
        }

        fun parseSeriesEpisodes(root: JsonElement): List<XEpisode> {
            val episodes = (root as? JsonObject)?.get("episodes") ?: return emptyList()
            val lists: List<JsonElement> = when (episodes) {
                is JsonObject -> episodes.values.toList()
                is JsonArray -> episodes.toList()
                else -> emptyList()
            }
            val out = ArrayList<XEpisode>()
            for (season in lists) {
                val items = (season as? JsonArray) ?: continue
                for (it in items) {
                    val o = it as? JsonObject ?: continue
                    val id = o.long("id") ?: continue
                    val info = o["info"] as? JsonObject
                    out += XEpisode(
                        id = id,
                        title = o.str("title").orEmpty(),
                        season = o.long("season")?.toInt() ?: 1,
                        episode = o.long("episode_num")?.toInt() ?: 0,
                        ext = o.str("container_extension"),
                        image = info?.str("movie_image"),
                        plot = info?.str("plot"),
                        durationSecs = info?.long("duration_secs")?.toInt() ?: parseDuration(info?.str("duration")),
                    )
                }
            }
            return out.sortedWith(compareBy({ it.season }, { it.episode }))
        }

        /** "01:42:00" -> 6120. */
        fun parseDuration(s: String?): Int {
            if (s.isNullOrBlank()) return 0
            val parts = s.split(':').mapNotNull { it.trim().toIntOrNull() }
            return when (parts.size) {
                3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
                2 -> parts[0] * 60 + parts[1]
                1 -> parts[0]
                else -> 0
            }
        }

        private fun enc(s: String) = urlEncode(s)
    }
}

/** "backdrop_path": a list of URLs or one URL, depending on the provider; the first usable one. */
internal fun JsonObject.backdrop(): String? = when (val b = this["backdrop_path"]) {
    is JsonArray -> b.firstNotNullOfOrNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { u -> u.startsWith("http") } }
    is JsonPrimitive -> b.contentOrNull?.takeIf { it.startsWith("http") }
    else -> null
}

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it != "null" }

internal fun JsonObject.long(key: String): Long? {
    val s = str(key)?.trim() ?: return null
    return s.toLongOrNull() ?: s.toDoubleOrNull()?.toLong()
}
