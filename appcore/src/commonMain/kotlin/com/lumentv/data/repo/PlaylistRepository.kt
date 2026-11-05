package com.lumentv.data.repo

import com.lumentv.core.Http
import com.lumentv.core.HttpBody
import com.lumentv.core.HttpRequest
import com.lumentv.core.IoDispatcher
import com.lumentv.core.lineReader
import com.lumentv.core.logWarn
import com.lumentv.core.nowMillis
import com.lumentv.core.openFile
import com.lumentv.core.ungzipped
import com.lumentv.data.db.transaction
import com.lumentv.core.FormatException
import com.lumentv.core.HttpStatusException
import com.lumentv.core.Redact
import com.lumentv.core.SecretBox
import com.lumentv.core.userMessage
import com.lumentv.data.db.AppDatabase
import com.lumentv.data.db.ChannelEntity
import com.lumentv.data.db.EpisodeEntity
import com.lumentv.data.db.MovieEntity
import com.lumentv.data.db.PlaylistEntity
import com.lumentv.data.db.PlaylistType
import com.lumentv.data.xtream.XLive
import com.lumentv.data.xtream.XMovie
import com.lumentv.data.xtream.XSeries
import com.lumentv.data.xtream.XtreamApi
import com.lumentv.data.xtream.XtreamCredentials
import com.lumentv.core.normalize
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class RefreshOutcome(val ok: Boolean, val message: String)

/** Adds, edits, refreshes and deletes playlists. Refresh parses everything first, then swaps it in one transaction. */
class PlaylistRepository(
    private val db: AppDatabase,
    private val http: Http,
    private val defaultUserAgent: suspend () -> String,
) {
    private val locks = HashMap<Long, Mutex>()
    private val locksLock = SynchronizedObject()
    private val _busy = MutableStateFlow<Set<Long>>(emptySet())
    /** Playlists being refreshed right now, for the progress indicators. */
    val busy: StateFlow<Set<Long>> = _busy

    fun observe(): Flow<List<PlaylistEntity>> = db.playlists().observeAll()
    suspend fun all() = db.playlists().all()
    suspend fun get(id: Long) = db.playlists().get(id)

    suspend fun save(p: PlaylistEntity): Long =
        if (p.id == 0L) db.playlists().insert(p) else { db.playlists().update(p); p.id }

    suspend fun delete(id: Long) = db.transaction {
        db.channels().deleteFor(id)
        db.vod().deleteMovies(id)
        db.vod().deleteSeries(id)
        db.vod().deleteEpisodes(id)
        db.prefs().deleteFor(id)
        db.epg().deletePrograms(id)
        db.epg().deleteChannels(id)
        db.history().deleteFor(id)
        db.playlists().delete(id)
    }

    fun credentials(p: PlaylistEntity) = XtreamCredentials(SecretBox.decrypt(p.sourceEnc), p.username, SecretBox.decrypt(p.passwordEnc))

    suspend fun xtream(p: PlaylistEntity) = XtreamApi(http, credentials(p), userAgentFor(p))

    suspend fun userAgentFor(p: PlaylistEntity): String? = p.userAgent.ifBlank { defaultUserAgent() }.ifBlank { null }

    suspend fun refresh(id: Long): RefreshOutcome = withContext(IoDispatcher) {
        val lock = synchronized(locksLock) { locks.getOrPut(id) { Mutex() } }
        lock.withLock {
            val p = db.playlists().get(id) ?: return@withContext RefreshOutcome(false, "Playlist introuvable.")
            _busy.value = _busy.value + id
            try {
                val summary = when (p.type) {
                    PlaylistType.XTREAM -> refreshXtream(p)
                    else -> refreshM3u(p)
                }
                RefreshOutcome(true, summary)
            } catch (t: Throwable) {
                logWarn(TAG, "refresh failed: ${Redact.text(t.toString())}")
                val msg = userMessage(t)
                db.playlists().get(id)?.let { db.playlists().update(it.copy(lastError = msg)) }
                RefreshOutcome(false, msg)
            } finally {
                _busy.value = _busy.value - id
            }
        }
    }

    private suspend fun refreshM3u(p: PlaylistEntity): String {
        val source = SecretBox.decrypt(p.sourceEnc)
        val result = openSource(source, userAgentFor(p)).use { stream ->
            M3uImport.build(p.id, stream.lineReader(), p.dedupe)
        }
        db.transaction {
            db.channels().deleteFor(p.id)
            db.vod().deleteMovies(p.id)
            db.vod().deleteSeries(p.id)
            db.vod().deleteEpisodes(p.id)
            result.channels.chunked(CHUNK).forEach { db.channels().insertAll(it) }
            result.movies.chunked(CHUNK).forEach { db.vod().insertMovies(it) }
            result.series.chunked(CHUNK).forEach { db.vod().insertSeries(it) }
            result.episodes.chunked(CHUNK).forEach { db.vod().insertEpisodes(it) }
            db.playlists().update(
                p.copy(
                    lastRefresh = nowMillis(), lastError = null,
                    channelCount = result.channels.size, movieCount = result.movies.size, seriesCount = result.series.size,
                    autoEpgUrlEnc = result.epgUrl?.let { SecretBox.encrypt(resolveRelative(source, it)) } ?: p.autoEpgUrlEnc,
                )
            )
        }
        return summary(result.channels.size, result.movies.size, result.series.size) +
            if (result.duplicatesSkipped > 0) " · ${result.duplicatesSkipped} doublons ignorés" else ""
    }

    private suspend fun refreshXtream(p: PlaylistEntity) : String {
        val api = xtream(p)
        val account = api.authenticate()
        val liveCats = api.liveCategories()
        val live = ArrayList<XLive>(); api.liveStreams { live += it }
        val vodCats = runCatching { api.vodCategories() }.getOrDefault(emptyMap())
        val movies = ArrayList<XMovie>(); runCatching { api.vodStreams { movies += it } }
        val seriesCats = runCatching { api.seriesCategories() }.getOrDefault(emptyMap())
        val series = ArrayList<XSeries>(); runCatching { api.series { series += it } }
        if (live.isEmpty() && movies.isEmpty() && series.isEmpty()) {
            throw FormatException("Le serveur Xtream n'a renvoyé aucune chaîne, aucun film et aucune série.")
        }
        val chRows = XtreamImport.channels(p.id, live, liveCats)
        val mvRows = XtreamImport.movies(p.id, movies, vodCats)
        val seRows = XtreamImport.series(p.id, series, seriesCats)
        db.transaction {
            db.channels().deleteFor(p.id)
            db.vod().deleteMovies(p.id)
            db.vod().deleteSeries(p.id)
            db.vod().deleteEpisodes(p.id)
            chRows.chunked(CHUNK).forEach { db.channels().insertAll(it) }
            mvRows.chunked(CHUNK).forEach { db.vod().insertMovies(it) }
            seRows.chunked(CHUNK).forEach { db.vod().insertSeries(it) }
            db.playlists().update(
                p.copy(
                    lastRefresh = nowMillis(), lastError = null,
                    channelCount = chRows.size, movieCount = mvRows.size, seriesCount = seRows.size,
                    accountInfo = account.summary().ifBlank { null }, serverTimeZone = account.timeZone,
                )
            )
        }
        return summary(chRows.size, mvRows.size, seRows.size)
    }

    /** Xtream episodes are fetched when a series is opened, then cached in the episodes table. */
    suspend fun loadXtreamEpisodes(p: PlaylistEntity, seriesUid: String, seriesId: Long): List<EpisodeEntity> = withContext(IoDispatcher) {
        val eps = xtream(p).seriesInfo(seriesId).map {
            EpisodeEntity(
                playlistId = p.id, seriesUid = seriesUid, uid = "x:${it.id}", title = it.title.ifBlank { "Épisode ${it.episode}" },
                nameNorm = normalize(it.title), season = it.season, episode = it.episode, streamId = it.id, ext = it.ext,
                image = it.image, plot = it.plot, durationSecs = it.durationSecs,
            )
        }
        db.transaction {
            db.vod().deleteEpisodesOf(p.id, seriesUid)
            eps.chunked(CHUNK).forEach { db.vod().insertEpisodes(it) }
        }
        // Read them back: the rows built above all have id 0, and the episode lists use the id as their key.
        db.vod().episodes(p.id, seriesUid)
    }

    /** Opens an http(s) URL or a local file, transparently un-gzipping either. Close it once read. */
    fun openSource(source: String, userAgent: String?): HttpBody {
        val raw: HttpBody = if (source.startsWith("http://", true) || source.startsWith("https://", true)) {
            val resp = http.execute(HttpRequest(source, headers = userAgent?.let { mapOf("User-Agent" to it) }.orEmpty()))
            if (!resp.isSuccessful) { resp.close(); throw HttpStatusException(resp.code) }
            resp.body
        } else {
            openFile(source.removePrefix("file://").removePrefix("file:"))
        }
        return raw.ungzipped()
    }

    private fun summary(ch: Int, mv: Int, se: Int) =
        listOfNotNull("$ch chaîne" + (if (ch > 1) "s" else ""), mv.takeIf { it > 0 }?.let { "$it film" + if (it > 1) "s" else "" },
            se.takeIf { it > 0 }?.let { "$it série" + if (it > 1) "s" else "" }).joinToString(" · ")

    companion object {
        private const val TAG = "Playlists"
        private const val CHUNK = 500

        /** "guide.xml" next to "http://h/lists/tv.m3u" -> "http://h/lists/guide.xml"; same for local files. */
        fun resolveRelative(base: String, ref: String): String {
            if (ref.contains("://") || ref.startsWith("/")) return ref
            if (base.contains("://")) {
                // As URI.resolve: the base's folder (query and fragment dropped), then "." and ".." folded.
                val clean = base.substringBefore('#').substringBefore('?')
                val root = clean.indexOf('/', clean.indexOf("://") + 3).let { if (it < 0) clean.length else it }
                val host = clean.substring(0, root)
                val dir = clean.substring(root).substringBeforeLast('/', "")
                val parts = ArrayList<String>()
                "$dir/$ref".split('/').forEachIndexed { i, seg ->
                    when (seg) {
                        "." -> {}
                        ".." -> if (parts.size > 1) parts.removeAt(parts.size - 1)
                        "" -> if (i == 0) parts += ""
                        else -> parts += seg
                    }
                }
                if (ref.endsWith("/") || ref.endsWith("/.") || ref.endsWith("/..")) parts += ""
                return host + parts.joinToString("/").let { if (it.startsWith("/")) it else "/$it" }
            }
            val path = base.removePrefix("file://").removePrefix("file:")
            val parent = path.replace('\\', '/').substringBeforeLast('/', "")
            return if (parent.isEmpty()) ref else "$parent/$ref"
        }
    }
}
