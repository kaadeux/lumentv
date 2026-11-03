package com.lumentv.data.repo

import com.lumentv.core.normalize
import com.lumentv.data.db.AppDatabase
import com.lumentv.data.db.ChannelRow
import com.lumentv.data.db.EpisodeEntity
import com.lumentv.data.db.HistoryEntity
import com.lumentv.data.db.HistoryKind
import com.lumentv.data.db.MovieRow
import com.lumentv.data.db.PrefEntity
import com.lumentv.data.db.PrefKind
import com.lumentv.data.db.ProgramEntity
import com.lumentv.data.db.SeriesRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

data class GroupInfo(val name: String, val count: Int, val hidden: Boolean, val locked: Boolean)

/** Results per kind in the global search. */
const val SEARCH_LIMIT = 300

object Groups {
    const val ALL = "\u0001all"
    const val FAVORITES = "\u0001fav"
}

data class SearchResults(
    val channels: List<ChannelRow> = emptyList(),
    val movies: List<MovieRow> = emptyList(),
    val series: List<SeriesRow> = emptyList(),
    val episodes: List<EpisodeEntity> = emptyList(),
    val programs: List<Pair<ProgramEntity, ChannelRow?>> = emptyList(),
) {
    val isEmpty get() = channels.isEmpty() && movies.isEmpty() && series.isEmpty() && episodes.isEmpty() && programs.isEmpty()
}

class LibraryRepository(private val db: AppDatabase) {
    private val _changes = MutableStateFlow(0)
    /** Bumped whenever a favourite, hidden or locked flag changes, so lists reload. */
    val changes: StateFlow<Int> = _changes

    /** Lists reload after data changed underneath them (a profile sync, for instance). */
    fun bump() { _changes.value++ }

    // ---- Live -----------------------------------------------------------------------------------------------

    suspend fun groups(pid: Long, includeHidden: Boolean): List<GroupInfo> = withContext(Dispatchers.IO) {
        val prefs = db.prefs().allOf(pid, PrefKind.GROUP).associateBy { it.uid }
        db.channels().groups(pid).map {
            val p = prefs[it.groupName]
            GroupInfo(it.groupName, it.count, p?.hidden == true, p?.locked == true)
        }.filter { includeHidden || !it.hidden }
    }

    suspend fun channels(pid: Long, group: String, includeHidden: Boolean, sort: String): List<ChannelRow> = withContext(Dispatchers.IO) {
        val rows = when (group) {
            Groups.FAVORITES -> db.channels().favorites(pid)
            Groups.ALL -> {
                val hiddenGroups = if (includeHidden) emptySet() else
                    db.prefs().allOf(pid, PrefKind.GROUP).filter { it.hidden }.mapTo(HashSet()) { it.uid }
                db.channels().inGroup(pid, null).filter { it.ch.groupName !in hiddenGroups }
            }
            else -> db.channels().inGroup(pid, group)
        }
        val visible = if (includeHidden) rows else rows.filter { !it.isHidden }
        if (group == Groups.FAVORITES) visible else sortChannels(visible, sort)
    }

    suspend fun channel(id: Long) = withContext(Dispatchers.IO) { db.channels().get(id) }
    suspend fun channelByUid(pid: Long, uid: String) = withContext(Dispatchers.IO) { db.channels().byUid(pid, uid) }

    suspend fun lockedGroups(pid: Long): Set<String> =
        db.prefs().allOf(pid, PrefKind.GROUP).filter { it.locked }.mapTo(HashSet()) { it.uid }

    // ---- Movies & series -----------------------------------------------------------------------------------

    suspend fun movieCategories(pid: Long, includeHidden: Boolean) = withContext(Dispatchers.IO) {
        val hidden = hiddenSet(pid, PrefKind.MOVIE_CATEGORY)
        db.vod().movieCategories(pid).filter { includeHidden || it.category !in hidden }
    }
    suspend fun movies(pid: Long, category: String?) = withContext(Dispatchers.IO) { db.vod().movies(pid, category) }
    suspend fun recentMovies(pid: Long) = withContext(Dispatchers.IO) { db.vod().recentMovies(pid, 60) }
    suspend fun movie(id: Long) = withContext(Dispatchers.IO) { db.vod().movie(id) }
    suspend fun movieByUid(pid: Long, uid: String) = withContext(Dispatchers.IO) { db.vod().movieByUid(pid, uid) }

    suspend fun seriesCategories(pid: Long, includeHidden: Boolean) = withContext(Dispatchers.IO) {
        val hidden = hiddenSet(pid, PrefKind.SERIES_CATEGORY)
        db.vod().seriesCategories(pid).filter { includeHidden || it.category !in hidden }
    }
    suspend fun series(pid: Long, category: String?) = withContext(Dispatchers.IO) { db.vod().series(pid, category) }
    /** Channels of a playlist whose name contains [q] (normalised), hidden ones included: the copies of a channel. */
    suspend fun channelsNamed(pid: Long, q: String) = withContext(Dispatchers.IO) { db.channels().search(q, 60).filter { it.ch.playlistId == pid } }
    suspend fun recentSeries(pid: Long) = withContext(Dispatchers.IO) { db.vod().recentSeries(pid, 120) }

    /**
     * The other copies of a film in the playlist ("4K MULTI", "FHD VF", "VOSTFR"…): same title once the quality and
     * language marks are taken off. The film itself is included.
     */
    suspend fun versions(m: com.lumentv.data.db.MovieEntity): List<MovieRow> = withContext(Dispatchers.IO) {
        val base = com.lumentv.core.Featured.baseTitle(m.name)
        if (base.length < 2) return@withContext emptyList()
        db.vod().searchMovies(base, 80).filter { it.m.playlistId == m.playlistId && com.lumentv.core.Featured.baseTitle(it.m.name) == base }
            .sortedBy { it.m.number }
    }

    /** A category's films or series for a row of the Films / Séries home: one per title, newest first. */
    suspend fun categoryMovies(pid: Long, category: String) = withContext(Dispatchers.IO) {
        com.lumentv.core.Featured.clean(db.vod().movies(pid, category).sortedByDescending { it.m.addedAt }, { it.m.name }, { it.m.category })
    }
    suspend fun categorySeries(pid: Long, category: String) = withContext(Dispatchers.IO) {
        com.lumentv.core.Featured.clean(db.vod().series(pid, category).sortedByDescending { it.s.updatedAt }, { it.s.name }, { it.s.category })
    }

    /** A row of the featured section ("Nouveautés", "Les mieux notés", "Parce que vous avez regardé…"). */
    class Shelf<T>(val title: String, val items: List<T>)

    /** Films put forward above the list: new, best rated of the last years, more of the last one watched. */
    suspend fun featuredMovies(pid: Long, lastWatched: String?): List<Shelf<MovieRow>> = withContext(Dispatchers.IO) {
        val hidden = hiddenSet(pid, PrefKind.MOVIE_CATEGORY)
        fun clean(l: List<MovieRow>) = com.lumentv.core.Featured.clean(l, { it.m.name }, { it.m.category }, hidden)
        val last = lastWatched?.let { db.vod().movieByUid(pid, it) }
        listOfNotNull(
            Shelf("Nouveautés", clean(db.vod().recentMovies(pid, 200))),
            // The year is often only in the title ("Breach (2026)"): the recent ones are kept here, not in SQL.
            Shelf("Les mieux notés", clean(db.vod().topMovies(pid, com.lumentv.core.Featured.GOOD, 3000).filter { com.lumentv.core.Featured.recent(it.m.year, it.m.name) })),
            last?.let { l ->
                val base = com.lumentv.core.Featured.baseTitle(l.m.name)
                Shelf("Parce que vous avez regardé ${l.m.name.substringBefore(" (")}", clean(db.vod().movies(pid, l.m.category)
                    .filter { com.lumentv.core.Featured.baseTitle(it.m.name) != base }
                    .sortedWith(compareByDescending<MovieRow> { com.lumentv.core.Featured.rating(it.m.rating) ?: 0.0 }.thenByDescending { it.m.addedAt })))
            },
        ).filter { it.items.size >= 3 }
    }

    /** Series put forward above the list: new or with new episodes, best rated of the last years, more of the last one watched. */
    suspend fun featuredSeries(pid: Long, lastWatched: String?): List<Shelf<SeriesRow>> = withContext(Dispatchers.IO) {
        val hidden = hiddenSet(pid, PrefKind.SERIES_CATEGORY)
        fun clean(l: List<SeriesRow>) = com.lumentv.core.Featured.clean(l, { it.s.name }, { it.s.category }, hidden)
        val last = lastWatched?.let { db.vod().seriesByUid(pid, it) }
        listOfNotNull(
            Shelf("Nouveautés", clean(db.vod().recentSeries(pid, 200))),
            Shelf("Les mieux notées", clean(db.vod().topSeries(pid, com.lumentv.core.Featured.GOOD, 3000).filter { com.lumentv.core.Featured.recent(it.s.year, it.s.name) })),
            last?.let { l ->
                Shelf("Parce que vous avez regardé ${l.s.name}", clean(db.vod().series(pid, l.s.category).filter { it.s.uid != l.s.uid }
                    .sortedWith(compareByDescending<SeriesRow> { com.lumentv.core.Featured.rating(it.s.rating) ?: 0.0 }.thenByDescending { it.s.updatedAt })))
            },
        ).filter { it.items.size >= 3 }
    }
    suspend fun seriesByUid(pid: Long, uid: String) = withContext(Dispatchers.IO) { db.vod().seriesByUid(pid, uid) }
    suspend fun storedEpisodes(pid: Long, seriesUid: String) = withContext(Dispatchers.IO) { db.vod().episodes(pid, seriesUid) }

    suspend fun favoriteMovies() = withContext(Dispatchers.IO) { db.vod().favoriteMovies() }
    suspend fun favoriteSeries() = withContext(Dispatchers.IO) { db.vod().favoriteSeries() }
    suspend fun favoriteChannelsAll() = withContext(Dispatchers.IO) { db.channels().favoritesAll() }

    private suspend fun hiddenSet(pid: Long, kind: String) =
        db.prefs().allOf(pid, kind).filter { it.hidden }.mapTo(HashSet()) { it.uid }

    // ---- Prefs ---------------------------------------------------------------------------------------------

    suspend fun toggleFavorite(pid: Long, kind: String, uid: String): Boolean = withContext(Dispatchers.IO) {
        val cur = db.prefs().get(pid, kind, uid) ?: PrefEntity(pid, kind, uid)
        val fav = !cur.favorite
        db.prefs().upsert(cur.copy(favorite = fav, favOrder = if (fav) db.prefs().maxFavOrder() + 1 else 0))
        _changes.value++
        fav
    }

    suspend fun setHidden(pid: Long, kind: String, uid: String, hidden: Boolean) = withContext(Dispatchers.IO) {
        val cur = db.prefs().get(pid, kind, uid) ?: PrefEntity(pid, kind, uid)
        db.prefs().upsert(cur.copy(hidden = hidden))
        _changes.value++
    }

    suspend fun setLocked(pid: Long, group: String, locked: Boolean) = withContext(Dispatchers.IO) {
        val cur = db.prefs().get(pid, PrefKind.GROUP, group) ?: PrefEntity(pid, PrefKind.GROUP, group)
        db.prefs().upsert(cur.copy(locked = locked))
        _changes.value++
    }

    suspend fun setEpgOverride(pid: Long, uid: String, epgKey: String?) = withContext(Dispatchers.IO) {
        val cur = db.prefs().get(pid, PrefKind.CHANNEL, uid) ?: PrefEntity(pid, PrefKind.CHANNEL, uid)
        db.prefs().upsert(cur.copy(epgOverride = epgKey?.lowercase()))
        _changes.value++
    }

    /** Moves a favourite channel one place up (-1) or down (+1) in the favourites list. */
    suspend fun moveFavorite(pid: Long, uid: String, delta: Int) = withContext(Dispatchers.IO) {
        val favs = db.channels().favorites(pid)
        val i = favs.indexOfFirst { it.ch.uid == uid }
        val j = i + delta
        if (i < 0 || j !in favs.indices) return@withContext
        val list = favs.toMutableList()
        val moved = list.removeAt(i)
        list.add(j, moved)
        db.prefs().upsertAll(list.mapIndexed { idx, r ->
            (db.prefs().get(pid, PrefKind.CHANNEL, r.ch.uid) ?: PrefEntity(pid, PrefKind.CHANNEL, r.ch.uid)).copy(favorite = true, favOrder = idx + 1L)
        })
        _changes.value++
    }

    // ---- History -------------------------------------------------------------------------------------------

    fun observeHistory(limit: Int = 100) = db.history().observeRecent(limit)

    suspend fun history(key: String) = withContext(Dispatchers.IO) { db.history().get(key) }
    suspend fun episodeHistory(pid: Long, seriesUid: String) = withContext(Dispatchers.IO) { db.history().episodesOf(pid, seriesUid) }
    suspend fun recentChannels(limit: Int) = withContext(Dispatchers.IO) { db.history().recentOf(HistoryKind.CHANNEL, limit) }

    suspend fun record(h: HistoryEntity) = withContext(Dispatchers.IO) { db.history().upsert(h) }
    suspend fun deleteHistory(key: String) = withContext(Dispatchers.IO) { db.history().delete(key) }
    suspend fun clearHistory() = withContext(Dispatchers.IO) { db.history().clear() }

    // ---- Search (local only) ------------------------------------------------------------------------------

    suspend fun search(query: String, epg: EpgRepository): SearchResults = withContext(Dispatchers.IO) {
        val q = normalize(query)
        if (q.length < 2) return@withContext SearchResults()
        val programs = db.epg().search(q, System.currentTimeMillis(), SEARCH_LIMIT)
        val byPlaylist = HashMap<Long, Map<String, ChannelRow>>()
        val progPairs = programs.map { p ->
            val map = byPlaylist.getOrPut(p.playlistId) {
                val m = epg.matcher(p.playlistId)
                val out = HashMap<String, ChannelRow>()
                db.channels().inGroup(p.playlistId, null).forEach { c ->
                    m.keyFor(c.epgOverride, c.ch.epgId, c.ch.name)?.let { out.putIfAbsent(it, c) }
                }
                out
            }
            p to map[p.epgKey]
        }
        SearchResults(
            channels = db.channels().search(q, SEARCH_LIMIT),
            movies = db.vod().searchMovies(q, SEARCH_LIMIT),
            series = db.vod().searchSeries(q, SEARCH_LIMIT),
            episodes = db.vod().searchEpisodes(q, SEARCH_LIMIT),
            programs = progPairs,
        )
    }

    companion object {
        fun key(kind: String, pid: Long, uid: String) = "$kind:$pid:$uid"

        fun sortChannels(rows: List<ChannelRow>, sort: String): List<ChannelRow> = when (sort) {
            "az" -> rows.sortedBy { it.ch.nameNorm }
            "za" -> rows.sortedByDescending { it.ch.nameNorm }
            else -> rows
        }
    }
}
