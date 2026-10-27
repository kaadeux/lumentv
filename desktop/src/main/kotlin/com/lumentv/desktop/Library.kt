package com.lumentv.desktop

import com.lumentv.core.FormatException
import com.lumentv.core.HttpStatusException
import com.lumentv.core.channelKeyName
import com.lumentv.core.normalize
import com.lumentv.data.epg.EpgMatcher
import com.lumentv.data.epg.XmltvParser
import com.lumentv.data.m3u.EntryKind
import com.lumentv.data.m3u.M3uClassifier
import com.lumentv.data.m3u.M3uParser
import com.lumentv.data.xtream.XLive
import com.lumentv.data.xtream.XMovie
import com.lumentv.data.xtream.XSeries
import com.lumentv.data.xtream.XtreamApi
import com.lumentv.data.xtream.XtreamCredentials
import com.lumentv.sync.SyncPlaylist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

// The uids follow the TV app's rules ("x:<id>" for Xtream, "m:<name>" for M3U), so favourites and history match.

@Serializable data class Ch(val uid: String, val name: String, val group: String, val logo: String? = null, val url: String = "",
                            val streamId: Long = 0, val epgId: String? = null, val num: Int = 0) { val norm by lazy { normalize(name) } }
@Serializable data class Mv(val uid: String, val name: String, val category: String, val poster: String? = null, val url: String = "",
                            val streamId: Long = 0, val ext: String? = null, val year: String? = null, val rating: String? = null, val added: Long = 0) { val norm by lazy { normalize(name) } }
@Serializable data class Se(val uid: String, val name: String, val category: String, val cover: String? = null, val seriesId: Long = 0,
                            val plot: String? = null, val year: String? = null, val rating: String? = null) { val norm by lazy { normalize(name) } }
@Serializable data class Ep(val uid: String, val title: String, val season: Int, val episode: Int, val url: String = "", val streamId: Long = 0,
                            val ext: String? = null, val image: String? = null, val plot: String? = null, val durationSecs: Int = 0, val rating: String? = null)

/** What a series / film page shows beyond the list entry (Xtream only). */
data class Details(val plot: String? = null, val genre: String? = null, val cast: String? = null, val director: String? = null,
                   val rating: String? = null, val release: String? = null, val backdrop: String? = null, val durationSecs: Int = 0)

@Serializable
data class Catalog(
    val loadedAt: Long,
    val channels: List<Ch> = emptyList(),
    val movies: List<Mv> = emptyList(),
    val series: List<Se> = emptyList(),
    /** M3U only: episodes come with the playlist. Xtream episodes are fetched per series. */
    val episodes: Map<String, List<Ep>> = emptyMap(),
    val epgUrl: String? = null,
)

data class Prog(val start: Long, val stop: Long, val title: String, val description: String?)

const val NO_GROUP = "Sans catégorie"

/** Downloads playlists and guides. */
class Library(private val http: OkHttpClient) {
    private fun xtream(p: SyncPlaylist) = XtreamApi(http, XtreamCredentials(p.source, p.username, p.password), p.userAgent.ifBlank { null })

    suspend fun load(p: SyncPlaylist): Catalog = withContext(Dispatchers.IO) { if (p.type == "xtream") loadXtream(p) else loadM3u(p) }

    private fun loadXtream(p: SyncPlaylist): Catalog {
        val api = xtream(p)
        api.authenticate()
        val liveCats = runCatching { api.liveCategories() }.getOrDefault(emptyMap())
        val vodCats = runCatching { api.vodCategories() }.getOrDefault(emptyMap())
        val serCats = runCatching { api.seriesCategories() }.getOrDefault(emptyMap())
        val live = ArrayList<XLive>(); api.liveStreams { live += it }
        val vod = ArrayList<XMovie>(); runCatching { api.vodStreams { vod += it } }
        val ser = ArrayList<XSeries>(); runCatching { api.series { ser += it } }
        return Catalog(
            loadedAt = System.currentTimeMillis(),
            channels = live.mapIndexed { i, x -> Ch("x:${x.streamId}", x.name, liveCats[x.categoryId] ?: NO_GROUP, x.icon, streamId = x.streamId, epgId = x.epgId, num = x.num ?: (i + 1)) },
            movies = vod.map { x -> Mv("x:${x.streamId}", x.name, vodCats[x.categoryId] ?: NO_GROUP, x.icon, streamId = x.streamId, ext = x.ext, year = x.year, rating = x.rating, added = x.added) },
            series = ser.map { x -> Se("x:${x.seriesId}", x.name, serCats[x.categoryId] ?: NO_GROUP, x.cover, x.seriesId, x.plot, x.year, x.rating) },
            epgUrl = p.epgUrl.ifBlank { XtreamApi.xmltvUrl(XtreamCredentials(XtreamApi.normalizeServer(p.source), p.username, p.password)) },
        )
    }

    private fun loadM3u(p: SyncPlaylist): Catalog {
        val channels = ArrayList<Ch>(); val movies = ArrayList<Mv>()
        val series = LinkedHashMap<String, Se>(); val episodes = HashMap<String, MutableList<Ep>>()
        val seen = HashMap<String, Int>()
        fun unique(base: String): String { val n = (seen[base] ?: 0) + 1; seen[base] = n; return if (n == 1) base else "$base#$n" }
        val seenUrls = HashSet<String>()
        var epgUrl: String? = null
        open(p.source, p.userAgent).use { input ->
            M3uParser.parse(BufferedReader(InputStreamReader(input, Charsets.UTF_8)), onHeader = { h -> if (epgUrl == null) epgUrl = h.epgUrl }) { e ->
                if (p.dedupe && !seenUrls.add(e.url)) return@parse
                val group = e.group.ifBlank { NO_GROUP }
                val c = M3uClassifier.classify(e)
                val norm = normalize(e.name)
                val ext = e.url.substringBefore('?').substringAfterLast('.', "").takeIf { it.length in 2..4 }
                when (c.kind) {
                    EntryKind.LIVE -> channels += Ch(unique("m:" + norm + "|" + (e.tvgId?.lowercase() ?: "")), e.name, group, e.logo, e.url, epgId = e.tvgId, num = channels.size + 1)
                    EntryKind.MOVIE -> movies += Mv(unique("m:$norm"), e.name, group, e.logo, e.url, ext = ext,
                        year = Regex("\\((19|20)\\d{2}\\)").find(e.name)?.value?.trim('(', ')'))
                    EntryKind.EPISODE -> {
                        val name = c.seriesName ?: e.name
                        val sUid = "m:" + normalize(name)
                        series.getOrPut(sUid) { Se(sUid, name, group, e.logo) }
                        episodes.getOrPut(sUid) { ArrayList() } += Ep(unique("$sUid:s${c.season}e${c.episode}"), e.name, c.season, c.episode, e.url, ext = ext, image = e.logo)
                    }
                }
            }
        }
        if (channels.isEmpty() && movies.isEmpty() && series.isEmpty()) throw FormatException("La playlist est vide ou illisible.")
        return Catalog(System.currentTimeMillis(), channels, movies, series.values.toList(), episodes, p.epgUrl.ifBlank { epgUrl.orEmpty() }.ifBlank { null })
    }

    suspend fun episodes(p: SyncPlaylist, c: Catalog, s: Se): List<Ep> = seriesDetails(p, c, s).second

    private val seriesCache = HashMap<String, Pair<Details?, List<Ep>>>()

    /** Series page: details and episodes (with their rating), kept for the session. */
    suspend fun seriesDetails(p: SyncPlaylist, c: Catalog, s: Se): Pair<Details?, List<Ep>> = withContext(Dispatchers.IO) {
        if (p.type != "xtream") return@withContext null to c.episodes[s.uid].orEmpty().sortedWith(compareBy({ it.season }, { it.episode }))
        seriesCache[s.uid]?.let { return@withContext it }
        val root = getJson(p, "get_series_info&series_id=${s.seriesId}")
        val info = (root as? kotlinx.serialization.json.JsonObject)?.get("info") as? kotlinx.serialization.json.JsonObject
        val ratings = HashMap<String, String>()
        ((root as? kotlinx.serialization.json.JsonObject)?.get("episodes"))?.let { eps ->
            val lists = when (eps) { is kotlinx.serialization.json.JsonObject -> eps.values; is kotlinx.serialization.json.JsonArray -> eps; else -> emptyList() }
            lists.forEach { l -> (l as? kotlinx.serialization.json.JsonArray)?.forEach { e ->
                val o = e as? kotlinx.serialization.json.JsonObject ?: return@forEach
                val id = o.s("id") ?: return@forEach
                ((o["info"] as? kotlinx.serialization.json.JsonObject)?.s("rating"))?.takeIf { it != "0" && it.isNotBlank() }?.let { ratings[id] = it }
            } }
        }
        val episodes = XtreamApi.parseSeriesEpisodes(root).map {
            Ep("x:${it.id}", it.title, it.season, it.episode, streamId = it.id, ext = it.ext, image = it.image, plot = it.plot, durationSecs = it.durationSecs,
                rating = ratings[it.id.toString()]?.toDoubleOrNull()?.let { r -> "%.1f".format(r).replace(',', '.') })
        }.sortedWith(compareBy({ it.season }, { it.episode }))
        val d = info?.let { Details(it.s("plot"), it.s("genre"), it.s("cast"), it.s("director"), it.s("rating"), it.s("releaseDate") ?: it.s("release_date"),
            (it["backdrop_path"] as? kotlinx.serialization.json.JsonArray)?.firstOrNull()?.let { b -> (b as? kotlinx.serialization.json.JsonPrimitive)?.content }) }
        (d to episodes).also { seriesCache[s.uid] = it }
    }

    /** Film page: plot, genre, cast, director, duration (Xtream only). */
    suspend fun movieDetails(p: SyncPlaylist, m: Mv): Details? = withContext(Dispatchers.IO) {
        if (p.type != "xtream") return@withContext null
        val root = getJson(p, "get_vod_info&vod_id=${m.streamId}") as? kotlinx.serialization.json.JsonObject
        val info = root?.get("info") as? kotlinx.serialization.json.JsonObject ?: return@withContext null
        Details(info.s("plot") ?: info.s("description"), info.s("genre"), info.s("cast") ?: info.s("actors"), info.s("director"), info.s("rating"),
            info.s("releasedate") ?: info.s("release_date"),
            (info["backdrop_path"] as? kotlinx.serialization.json.JsonArray)?.firstOrNull()?.let { b -> (b as? kotlinx.serialization.json.JsonPrimitive)?.content },
            info.s("duration_secs")?.toIntOrNull() ?: XtreamApi.parseDuration(info.s("duration")))
    }

    private fun getJson(p: SyncPlaylist, action: String): kotlinx.serialization.json.JsonElement {
        fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
        val url = XtreamApi.normalizeServer(p.source) + "/player_api.php?username=${enc(p.username)}&password=${enc(p.password)}&action=$action"
        open(url, p.userAgent).use { return kotlinx.serialization.json.Json.parseToJsonElement(it.reader().readText()) }
    }

    private fun kotlinx.serialization.json.JsonObject.s(k: String): String? =
        (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }

    fun channelUrl(p: SyncPlaylist, c: Ch) = if (p.type == "xtream") xtream(p).liveUrl(c.streamId, p.liveFormat) else c.url
    fun movieUrl(p: SyncPlaylist, m: Mv) = if (p.type == "xtream") xtream(p).movieUrl(m.streamId, m.ext) else m.url
    fun episodeUrl(p: SyncPlaylist, e: Ep) = if (p.type == "xtream" && e.streamId > 0) xtream(p).episodeUrl(e.streamId, e.ext) else e.url

    /** Programmes from 6 h ago to 2 days ahead, by channel uid. */
    suspend fun guide(c: Catalog, offsetMinutes: Int, userAgent: String): Map<String, List<Prog>> = withContext(Dispatchers.IO) {
        val url = c.epgUrl ?: return@withContext emptyMap()
        val from = System.currentTimeMillis() - 6 * 3_600_000L
        val to = System.currentTimeMillis() + 48 * 3_600_000L
        val shift = offsetMinutes * 60_000L
        val names = HashMap<String, String>() // channelKeyName(display name) -> lowercase XMLTV id
        val progs = HashMap<String, MutableList<Prog>>()
        open(url, userAgent).use { raw ->
            val input = java.io.BufferedInputStream(raw).let { b -> b.mark(2); val gz = b.read() == 0x1f && b.read() == 0x8b; b.reset(); if (gz) GZIPInputStream(b) else b }
            val parser = org.kxml2.io.KXmlParser().apply { setInput(input, null) }
            XmltvParser.parse(parser, onChannel = { ch -> ch.names.forEach { n -> names.putIfAbsent(channelKeyName(n), ch.id.lowercase()) } }) { pr ->
                if (pr.stop + shift < from || pr.start + shift > to) return@parse
                progs.getOrPut(pr.channel.lowercase()) { ArrayList() } += Prog(pr.start + shift, pr.stop + shift, pr.title, pr.description)
            }
        }
        val matcher = EpgMatcher(progs.keys, names)
        val out = HashMap<String, List<Prog>>()
        for (ch in c.channels) {
            val key = matcher.keyFor(null, ch.epgId, ch.name) ?: continue
            progs[key]?.let { out[ch.uid] = it.sortedBy { p -> p.start } }
        }
        out
    }

    private fun open(url: String, userAgent: String): InputStream {
        if (!url.startsWith("http")) return java.io.File(url.removePrefix("file:")).inputStream()
        val r = http.newCall(Request.Builder().url(url).header("User-Agent", userAgent.ifBlank { "Lumen TV" }).build()).execute()
        if (!r.isSuccessful) { r.close(); throw HttpStatusException(r.code) }
        return r.body!!.byteStream()
    }
}
