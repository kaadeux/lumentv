package com.lumentv.data.repo

import com.lumentv.core.Featured
import com.lumentv.core.IoDispatcher
import com.lumentv.data.db.AppDatabase
import com.lumentv.data.db.EpisodeEntity
import com.lumentv.data.db.HistoryEntity
import com.lumentv.data.db.HistoryKind
import com.lumentv.data.db.PlaylistEntity
import com.lumentv.data.db.PlaylistType
import com.lumentv.data.xtream.XtreamApi
import kotlinx.coroutines.withContext

/**
 * Keeps the history of a list that is about to be removed: each film and episode watched there is found in another
 * list and its line (with where it stopped) moves there. On the same Xtream server the titles have the same ids; on
 * another one they are found by name and year (a series, then its season and episode). What cannot be found is told,
 * by name, so nothing is lost without the viewer knowing.
 */
class HistoryTransfer(private val db: AppDatabase, private val playlists: PlaylistRepository, private val index: LibraryIndex) {
    companion object {
        /** The "list" of the history kept aside: no list has id 0. */
        const val RESERVE = 0L
    }

    /** What moved, and the titles the other list does not have (kept aside, see [RESERVE]). */
    class Report(val moved: Int, val missing: List<String>) {
        /** "18 titres retrouvés dans « Liste 2 ». 2 introuvables, gardés de côté : FROM S1 E2, Dune." */
        fun text(target: String): String = buildString {
            append(if (moved == 1) "1 titre de votre historique retrouvé dans « $target »." else "$moved titres de votre historique retrouvés dans « $target ».")
            if (missing.isNotEmpty()) {
                append("\n\n")
                append(if (missing.size == 1) "1 n'y est pas. Il reste gardé de côté pour une prochaine liste :"
                       else "${missing.size} n'y sont pas. Ils restent gardés de côté pour une prochaine liste :")
                missing.take(20).forEach { append("\n• ").append(it) }
                if (missing.size > 20) append("\n• et ${missing.size - 20} autres")
            }
        }
    }

    /** The films and episodes watched in [pid] ([RESERVE]: kept aside): what a transfer would keep. */
    suspend fun count(pid: Long): Int = withContext(IoDispatcher) { lines(pid).size }

    private suspend fun lines(pid: Long) =
        db.history().everything().filter { it.playlistId == pid && (it.kind == HistoryKind.MOVIE || it.kind == HistoryKind.EPISODE) }

    /**
     * Moves the history of [from] (a list, or [RESERVE]) into [to]. What is found leaves [from]; what is not goes to (or
     * stays in) the reserve, for a list added later.
     */
    suspend fun move(from: Long, to: Long): Report = withContext(IoDispatcher) {
        val dst = db.playlists().get(to) ?: return@withContext Report(0, emptyList())
        val src = if (from == RESERVE) null else db.playlists().get(from)
        val same = src != null && sameServer(src, dst)
        // The other list's titles by name, built once and only when a title is not found by its id.
        var built: com.lumentv.data.discover.TitleIndex? = null
        suspend fun idx() = built ?: index.of(to).also { built = it }
        val targetEpisodes = HashMap<String, List<EpisodeEntity>>()
        var moved = 0
        val missing = ArrayList<String>()
        for (h in lines(from).sortedByDescending { it.watchedAt }) {
            val found: Pair<String, String?>? = when (h.kind) {
                HistoryKind.MOVIE -> {
                    val uid = if (same && db.vod().movieByUid(to, h.uid) != null) h.uid
                    else idx().find(false, Featured.searchTitle(h.title), "", Featured.yearOf(null, h.title))?.uid
                    uid?.let { it to null }
                }
                else -> {
                    val seriesUid = h.parentUid
                    // The series' name: the list's, else the one the history line keeps (the list may be gone).
                    val name = seriesUid?.let { src?.let { _ -> db.vod().seriesByUid(from, it)?.s?.name } } ?: h.title
                    val dstSeriesUid = if (same && seriesUid != null && db.vod().seriesByUid(to, seriesUid) != null) seriesUid
                    else idx().find(true, Featured.searchTitle(name), "", Featured.yearOf(null, name))?.uid
                    val ep = if (src != null && seriesUid != null) db.vod().episodes(from, seriesUid).firstOrNull { it.uid == h.uid } else null
                    val (season, number) = ep?.let { it.season to it.episode } ?: numbersOf(h.subtitle) ?: (null to null)
                    if (dstSeriesUid == null || season == null || number == null) null
                    else {
                        val eps = targetEpisodes.getOrPut(dstSeriesUid) { episodesOf(dst, dstSeriesUid) }
                        eps.firstOrNull { it.season == season && it.episode == number }?.let { it.uid to dstSeriesUid }
                    }
                }
            }
            if (found == null) {
                missing += label(h)
                // Kept aside (and no longer the removed list's): the next list added looks for it.
                if (from != RESERVE) { keepAside(h); db.history().delete(h.key) }
                continue
            }
            val (uid, parent) = found
            val key = LibraryRepository.key(h.kind, to, uid)
            // The other list may have its own line for it: the latest one stays.
            val there = db.history().get(key)
            if (there == null || there.watchedAt < h.watchedAt)
                db.history().upsert(h.copy(key = key, playlistId = to, uid = uid, parentUid = parent ?: h.parentUid))
            db.history().delete(h.key)
            moved++
        }
        Report(moved, missing.distinct())
    }

    /** A line kept aside: no list shows it, the next list added looks for its title ([adopt]). */
    suspend fun keepAside(h: HistoryEntity) {
        val key = LibraryRepository.key(h.kind, RESERVE, h.uid)
        val there = db.history().get(key)
        if (there == null || there.watchedAt < h.watchedAt) db.history().upsert(h.copy(key = key, playlistId = RESERVE))
    }

    /** A list just loaded for the first time: the history kept aside, found in it. Null when there was none. */
    suspend fun adopt(pid: Long): String? {
        if (count(RESERVE) == 0) return null
        val name = db.playlists().get(pid)?.name ?: return null
        val r = move(RESERVE, pid)
        return if (r.moved == 0 && r.missing.isEmpty()) null else r.text(name)
    }

    /** The target list's episodes of a series: read from the database, asked to the Xtream server when not cached. */
    private suspend fun episodesOf(p: PlaylistEntity, seriesUid: String): List<EpisodeEntity> {
        val cached = db.vod().episodes(p.id, seriesUid)
        if (cached.isNotEmpty() || p.type != PlaylistType.XTREAM) return cached
        val row = db.vod().seriesByUid(p.id, seriesUid) ?: return cached
        return runCatching { playlists.loadXtreamEpisodes(p, seriesUid, row.s.seriesId) }.getOrDefault(cached)
    }

    /** Two Xtream accounts of the same server and user: their titles have the same ids. */
    private fun sameServer(a: PlaylistEntity, b: PlaylistEntity): Boolean {
        if (a.type != PlaylistType.XTREAM || b.type != PlaylistType.XTREAM) return false
        val x = runCatching { playlists.credentials(a) }.getOrNull() ?: return false
        val y = runCatching { playlists.credentials(b) }.getOrNull() ?: return false
        return x.username == y.username &&
            runCatching { XtreamApi.normalizeServer(x.server) == XtreamApi.normalizeServer(y.server) }.getOrDefault(false)
    }

    private val sxe = Regex("S(\\d{1,2})\\s*E(\\d{1,3})", RegexOption.IGNORE_CASE)
    private fun numbersOf(line: String?): Pair<Int?, Int?>? =
        line?.let { sxe.find(it) }?.let { it.groupValues[1].toIntOrNull() to it.groupValues[2].toIntOrNull() }

    /** "Dune", "FROM S1 E2": the title the viewer will recognise. */
    private fun label(h: HistoryEntity): String =
        if (h.kind == HistoryKind.MOVIE) Featured.shortTitle(h.title)
        else Featured.shortTitle(h.title) + (numbersOf(h.subtitle)?.let { (s, e) -> " S$s E$e" } ?: "")
}
