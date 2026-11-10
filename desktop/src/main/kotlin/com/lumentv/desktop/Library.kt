package com.lumentv.desktop

import com.lumentv.core.asXmlReader
import com.lumentv.core.asHttp
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
import com.lumentv.data.Segments
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
                            val streamId: Long = 0, val epgId: String? = null, val num: Int = 0,
                            /** Days of catch-up, and the M3U address template for it. */
                            val catchupDays: Int = 0, val catchupSource: String? = null) { val norm by lazy { normalize(name) } }
@Serializable data class Mv(val uid: String, val name: String, val category: String, val poster: String? = null, val url: String = "",
                            val streamId: Long = 0, val ext: String? = null, val year: String? = null, val rating: String? = null, val added: Long = 0) { val norm by lazy { normalize(name) } }
@Serializable data class Se(val uid: String, val name: String, val category: String, val cover: String? = null, val seriesId: Long = 0,
                            val plot: String? = null, val year: String? = null, val rating: String? = null, val tmdb: String? = null,
                            /** Last change on the provider's side (new series or new episodes), epoch ms; 0 = unknown. */
                            val updated: Long = 0,
                            /** A wide picture of the series, for the home's banner. */
                            val backdrop: String? = null) { val norm by lazy { normalize(name) } }
@Serializable data class Ep(val uid: String, val title: String, val season: Int, val episode: Int, val url: String = "", val streamId: Long = 0,
                            val ext: String? = null, val image: String? = null, val plot: String? = null, val durationSecs: Int = 0, val rating: String? = null)

/** What a series / film page shows beyond the list entry (Xtream only). The cast comes from TMDB. */
data class Details(val plot: String? = null, val genre: String? = null,
                   val rating: String? = null, val release: String? = null, val backdrop: String? = null, val durationSecs: Int = 0)

/** Bumped when the cached catalog lacks something new (v2: TMDB ids of series); an older cache is reloaded. */
const val CATALOG_VERSION = 5 // 3: series' last change date; 4: series' backdrop; 5: channels' catch-up

@Serializable
data class Catalog(
    val loadedAt: Long,
    val version: Int = 1,
    val channels: List<Ch> = emptyList(),
    val movies: List<Mv> = emptyList(),
    val series: List<Se> = emptyList(),
    /** M3U only: episodes come with the playlist. Xtream episodes are fetched per series. */
    val episodes: Map<String, List<Ep>> = emptyMap(),
    val epgUrl: String? = null,
    /** Xtream: the server's time zone, in which catch-up addresses are written. */
    val serverZone: String? = null,
    /** Xtream: the account as the TV shows it, "Active · expire le 11/11/2026 · connexions 0/1". */
    val account: String? = null,
)

data class Prog(val start: Long, val stop: Long, val title: String, val description: String?)

const val NO_GROUP = "Sans catégorie"

/** Downloads playlists and guides. */
class Library(private val http: OkHttpClient) {
    private fun xtream(p: SyncPlaylist) = XtreamApi(http.asHttp(), XtreamCredentials(p.source, p.username, p.password), p.ua.ifBlank { null })

    /** [step]: "Chargement des films · 12 000" and how far along (0..1, null: not known), for the bar at the top. */
    /**
     * Diagnosis (LUMEN_PROBE=1): asks a live channel of [p] in several ways and writes only the answer codes to the
     * playback log, so a provider refusing every stream (HTTP 513) can be told apart. No address nor login is written.
     */
    fun probe(p: SyncPlaylist, c: Catalog) {
        if (p.type != "xtream") return
        val ch = c.channels.firstOrNull { it.name.contains("TF1", true) && it.streamId > 0 } ?: c.channels.firstOrNull { it.streamId > 0 } ?: return
        val base = com.lumentv.data.xtream.XtreamApi.normalizeServer(p.source)
        val u = com.lumentv.core.urlEncode(p.username); val pw = com.lumentv.core.urlEncode(p.password)
        val plain = OkHttpClient.Builder().followRedirects(true).build()
        fun code(url: String, ua: String?): String = runCatching {
            val b = Request.Builder().url(url).header("Range", "bytes=0-1023")
            if (ua != null) b.header("User-Agent", ua)
            plain.newCall(b.build()).execute().use { r -> "${r.code}" + (r.request.url.host.let { h -> if (h == java.net.URI(base).host) "" else " (redirigé ailleurs)" }) }
        }.getOrElse { "erreur ${it::class.simpleName}" }
        val info = runCatching {
            plain.newCall(Request.Builder().url("$base/player_api.php?username=$u&password=$pw").build()).execute().use { r ->
                val o = kotlinx.serialization.json.Json.parseToJsonElement(r.body!!.string()) as kotlinx.serialization.json.JsonObject
                val s = o["server_info"] as? kotlinx.serialization.json.JsonObject
                val ui = o["user_info"] as? kotlinx.serialization.json.JsonObject
                val host = s?.get("url")?.toString()?.trim('"')
                Triple(host, s?.get("port")?.toString()?.trim('"'), "${ui?.get("allowed_output_formats")}, connexions en cours ${ui?.get("active_cons")} / ${ui?.get("max_connections")}")
            }
        }.getOrNull()
        PlaybackLog.line("diagnostic « ${ch.name} » : serveur annoncé ${if (info?.first == null) "?" else if (info.first == java.net.URI(base).host) "= adresse de la liste" else "≠ adresse de la liste"}, port ${info?.second}, formats ${info?.third}")
        val path = "live/$u/$pw/${ch.streamId}"
        listOf(
            "ts, lecteur par défaut" to ("$base/$path.ts" to null),
            "m3u8" to ("$base/$path.m3u8" to null),
            "sans /live" to ("$base/$u/$pw/${ch.streamId}" to null),
            "ts, UA VLC" to ("$base/$path.ts" to "VLC/3.0.20 LibVLC/3.0.20"),
            "ts, UA IPTV Smarters" to ("$base/$path.ts" to "IPTVSmartersPlayer"),
            "ts, UA navigateur" to ("$base/$path.ts" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"),
            "ts, UA Lumen" to ("$base/$path.ts" to p.ua.ifBlank { null }),
        ).forEach { (label, req) -> PlaybackLog.line("diagnostic $label : ${code(req.first, req.second)}"); Thread.sleep(1500) }
        // The same server over HTTPS (the port it announces), as some players use it.
        runCatching {
            plain.newCall(Request.Builder().url("$base/player_api.php?username=$u&password=$pw").build()).execute().use { r ->
                val o = kotlinx.serialization.json.Json.parseToJsonElement(r.body!!.string()) as kotlinx.serialization.json.JsonObject
                val s = o["server_info"] as? kotlinx.serialization.json.JsonObject
                val hp = s?.get("https_port")?.toString()?.trim('"')
                PlaybackLog.line("diagnostic protocole annoncé ${s?.get("server_protocol")}, port https $hp")
                val host = java.net.URI(base).host
                PlaybackLog.line("diagnostic https, ts : ${code("https://$host${hp?.takeIf { it.isNotBlank() && it != "443" }?.let { ":$it" }.orEmpty()}/$path.ts", null)}")
            }
        }.onFailure { PlaybackLog.line("diagnostic https : erreur ${it::class.simpleName}") }
        info?.first?.takeIf { it != java.net.URI(base).host }?.let { h ->
            val alt = "http://$h${info.second?.let { ":$it" }.orEmpty()}"
            PlaybackLog.line("diagnostic serveur annoncé, ts : ${code("$alt/$path.ts", null)}")
        }
    }

    /** The account's state alone (status, end date, connections), for a list read from the cache. */
    suspend fun account(p: SyncPlaylist): String? = withContext(Dispatchers.IO) { xtream(p).authenticate().summary().ifBlank { null } }

    suspend fun load(p: SyncPlaylist, step: (String, Float?) -> Unit = { _, _ -> }): Catalog =
        withContext(Dispatchers.IO) { if (p.type == "xtream") loadXtream(p, step) else loadM3u(p, step) }

    private fun loadXtream(p: SyncPlaylist, step: (String, Float?) -> Unit): Catalog {
        fun counted(label: String, from: Float, to: Float): (Int) -> Unit { step(label, from)
            return { n -> if (n % 2000 == 0) step("$label · ${"%,d".format(n).replace(',', ' ')}", from + (to - from) * (1f - 1f / (1f + n / 20_000f))) } }
        val api = xtream(p)
        step("Connexion au serveur", 0.02f)
        val account = api.authenticate()
        val liveCats = runCatching { api.liveCategories() }.getOrDefault(emptyMap())
        val vodCats = runCatching { api.vodCategories() }.getOrDefault(emptyMap())
        val serCats = runCatching { api.seriesCategories() }.getOrDefault(emptyMap())
        val live = ArrayList<XLive>(); counted("Chargement des chaînes", 0.05f, 0.3f).let { c -> api.liveStreams { live += it; c(live.size) } }
        val vod = ArrayList<XMovie>(); counted("Chargement des films", 0.3f, 0.65f).let { c -> runCatching { api.vodStreams { vod += it; c(vod.size) } } }
        val ser = ArrayList<XSeries>(); counted("Chargement des séries", 0.65f, 0.95f).let { c -> runCatching { api.series { ser += it; c(ser.size) } } }
        step("Enregistrement", 0.97f)
        return Catalog(
            loadedAt = System.currentTimeMillis(),
            version = CATALOG_VERSION,
            channels = live.mapIndexed { i, x -> Ch("x:${x.streamId}", x.name, liveCats[x.categoryId] ?: NO_GROUP, x.icon, streamId = x.streamId, epgId = x.epgId, num = x.num ?: (i + 1), catchupDays = x.archiveDays) },
            movies = vod.map { x -> Mv("x:${x.streamId}", x.name, vodCats[x.categoryId] ?: NO_GROUP, x.icon, streamId = x.streamId, ext = x.ext, year = x.year, rating = x.rating, added = x.added) },
            series = ser.map { x -> Se("x:${x.seriesId}", x.name, serCats[x.categoryId] ?: NO_GROUP, x.cover, x.seriesId, x.plot, x.year, x.rating, x.tmdb, x.updated, x.backdrop) },
            epgUrl = p.epgUrl.ifBlank { XtreamApi.xmltvUrl(XtreamCredentials(XtreamApi.normalizeServer(p.source), p.username, p.password)) },
            serverZone = account.timeZone,
            account = account.summary().ifBlank { null },
        )
    }

    private fun loadM3u(p: SyncPlaylist, step: (String, Float?) -> Unit): Catalog {
        step("Téléchargement de la liste", null)
        val channels = ArrayList<Ch>(); val movies = ArrayList<Mv>()
        val series = LinkedHashMap<String, Se>(); val episodes = HashMap<String, MutableList<Ep>>()
        val seen = HashMap<String, Int>()
        fun unique(base: String): String { val n = (seen[base] ?: 0) + 1; seen[base] = n; return if (n == 1) base else "$base#$n" }
        val seenUrls = HashSet<String>()
        var epgUrl: String? = null
        open(p.source, p.ua).use { input ->
            M3uParser.parse(BufferedReader(InputStreamReader(input, Charsets.UTF_8))::readLine, onHeader = { h -> if (epgUrl == null) epgUrl = h.epgUrl }) { e ->
                if (p.dedupe && !seenUrls.add(e.url)) return@parse
                val group = e.group.ifBlank { NO_GROUP }
                val c = M3uClassifier.classify(e)
                val norm = normalize(e.name)
                val ext = e.url.substringBefore('?').substringAfterLast('.', "").takeIf { it.length in 2..4 }
                when (c.kind) {
                    EntryKind.LIVE -> channels += Ch(unique("m:" + norm + "|" + (e.tvgId?.lowercase() ?: "")), e.name, group, e.logo, e.url, epgId = e.tvgId, num = channels.size + 1, catchupDays = e.catchupDays, catchupSource = e.catchupSource)
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
        return Catalog(System.currentTimeMillis(), CATALOG_VERSION, channels, movies, series.values.toList(), episodes, p.epgUrl.ifBlank { epgUrl.orEmpty() }.ifBlank { null })
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
        val d = info?.let { Details(it.s("plot"), it.s("genre"), it.s("rating"), it.s("releaseDate") ?: it.s("release_date"),
            (it["backdrop_path"] as? kotlinx.serialization.json.JsonArray)?.firstOrNull()?.let { b -> (b as? kotlinx.serialization.json.JsonPrimitive)?.content }) }
        (d to episodes).also { seriesCache[s.uid] = it }
    }

    private val movieCache = HashMap<String, Details?>()

    /** Film page and banner: plot, genre, duration, backdrop (Xtream only), kept for the session. */
    fun cachedMovieDetails(m: Mv): Details? = synchronized(movieCache) { movieCache[m.uid] }

    suspend fun movieDetails(p: SyncPlaylist, m: Mv): Details? = withContext(Dispatchers.IO) {
        if (p.type != "xtream") return@withContext null
        synchronized(movieCache) { if (m.uid in movieCache) return@withContext movieCache[m.uid] }
        loadMovieDetails(p, m).also { d -> synchronized(movieCache) { movieCache[m.uid] = d } }
    }

    private fun loadMovieDetails(p: SyncPlaylist, m: Mv): Details? {
        val root = getJson(p, "get_vod_info&vod_id=${m.streamId}") as? kotlinx.serialization.json.JsonObject
        val info = root?.get("info") as? kotlinx.serialization.json.JsonObject ?: return null
        return Details(info.s("plot") ?: info.s("description"), info.s("genre"), info.s("rating"),
            info.s("releasedate") ?: info.s("release_date"),
            (info["backdrop_path"] as? kotlinx.serialization.json.JsonArray)?.firstOrNull()?.let { b -> (b as? kotlinx.serialization.json.JsonPrimitive)?.content },
            XtreamApi.durationOf(info.s("duration_secs")?.toIntOrNull(), info.s("duration")))
    }

    private fun getJson(p: SyncPlaylist, action: String): kotlinx.serialization.json.JsonElement {
        fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
        val url = XtreamApi.normalizeServer(p.source) + "/player_api.php?username=${enc(p.username)}&password=${enc(p.password)}&action=$action"
        open(url, p.ua).use { return kotlinx.serialization.json.Json.parseToJsonElement(it.reader().readText()) }
    }

    private fun kotlinx.serialization.json.JsonObject.s(k: String): String? =
        (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }

    fun channelUrl(p: SyncPlaylist, c: Ch) = if (p.type == "xtream") xtream(p).liveUrl(c.streamId, p.liveFormat) else c.url
    /** A past or current programme on a channel with catch-up (Xtream timeshift, or the M3U template); null when it has none. */
    fun catchupUrl(p: SyncPlaylist, c: Ch, pr: Prog, serverZone: String?, offsetMs: Long = 0): String? {
        if (c.catchupDays <= 0) return null
        val from = pr.start + offsetMs
        if (p.type == "xtream") return xtream(p).catchupUrl(c.streamId, from, ((pr.stop - from) / 60_000L).toInt().coerceAtLeast(1), serverZone)
        val filled = com.lumentv.data.m3u.CatchupTemplate.fill(c.catchupSource ?: return null, from, pr.stop)
        return if (filled.contains("://")) filled else c.url + filled
    }

    fun movieUrl(p: SyncPlaylist, m: Mv) = if (p.type == "xtream") xtream(p).movieUrl(m.streamId, m.ext) else m.url
    fun episodeUrl(p: SyncPlaylist, e: Ep) = if (p.type == "xtream" && e.streamId > 0) xtream(p).episodeUrl(e.streamId, e.ext) else e.url

    private val introDb = com.lumentv.data.IntroDb(http.asHttp())

    /** Recap / intro / credits of an episode (TheIntroDB). */
    suspend fun segments(tmdb: String, season: Int, episode: Int): Segments? = introDb.segments(tmdb, season, episode)

    /** Programmes from 6 h ago (or the channel's catch-up, 7 days at most) to [days] days ahead (setting "Jours de programmes gardés"), by channel uid. */
    suspend fun guide(c: Catalog, offsetMinutes: Int, userAgent: String, days: Int = 3): Map<String, List<Prog>> = withContext(Dispatchers.IO) {
        val url = c.epgUrl ?: return@withContext emptyMap()
        fun since(catchupDays: Int) = System.currentTimeMillis() - maxOf(6 * 3_600_000L, catchupDays.coerceIn(0, 7) * 86_400_000L)
        val from = since(c.channels.maxOfOrNull { it.catchupDays } ?: 0)
        val to = System.currentTimeMillis() + days * 86_400_000L
        val shift = offsetMinutes * 60_000L
        val names = HashMap<String, String>() // channelKeyName(display name) -> lowercase XMLTV id
        val progs = HashMap<String, MutableList<Prog>>()
        open(url, userAgent).use { raw ->
            val input = java.io.BufferedInputStream(raw).let { b -> b.mark(2); val gz = b.read() == 0x1f && b.read() == 0x8b; b.reset(); if (gz) GZIPInputStream(b) else b }
            val parser = org.kxml2.io.KXmlParser().apply { setInput(input, null) }
            XmltvParser.parse(parser.asXmlReader(), onChannel = { ch -> ch.names.forEach { n -> names.putIfAbsent(channelKeyName(n), ch.id.lowercase()) } }) { pr ->
                if (pr.stop + shift < from || pr.start + shift > to) return@parse
                progs.getOrPut(pr.channel.lowercase()) { ArrayList() } += Prog(pr.start + shift, pr.stop + shift, pr.title, pr.description)
            }
        }
        val matcher = EpgMatcher(progs.keys, names)
        val out = HashMap<String, List<Prog>>()
        for (ch in c.channels) {
            val key = matcher.keyFor(null, ch.epgId, ch.name) ?: continue
            val oldest = since(ch.catchupDays)
            progs[key]?.let { out[ch.uid] = it.filter { p -> p.stop >= oldest }.sortedBy { p -> p.start }.distinctBy { p -> p.start } }
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

