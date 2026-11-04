package com.lumentv.data.repo

import kotlinx.coroutines.async
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
/** Titles shown for one person of the search. */
const val PERSON_LIMIT = 40

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
    /** Actors, directors and creators found by their name, with their titles in the playlist. */
    val people: List<PersonHit> = emptyList(),
) {
    val isEmpty get() = channels.isEmpty() && movies.isEmpty() && series.isEmpty() && episodes.isEmpty() && programs.isEmpty() && people.isEmpty()
}

/** A person found by the search: "Avec Tom Holland", "Réalisé par Christopher Nolan", and their titles of the playlist. */
data class PersonHit(val key: String, val title: String, val movies: List<MovieRow>, val series: List<SeriesRow>)

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

    // ---- Personal ratings ------------------------------------------------------------------------------------

    fun observeRatings() = db.ratings().observeAll()

    /** 1 to 5 stars, or 0 to remove the rating. [kind]: mv (film) or se (series). */
    suspend fun rate(kind: String, pid: Long, uid: String, stars: Int, title: String, image: String?) = withContext(Dispatchers.IO) {
        val key = key(kind, pid, uid)
        if (stars <= 0) db.ratings().delete(key)
        else db.ratings().upsert(com.lumentv.data.db.RatingEntity(key, kind, pid, uid, stars.coerceIn(1, 5), System.currentTimeMillis(), title, image))
    }

    // ---- Search ----------------------------------------------------------------------------------------------

    /**
     * Channels, films and series by a tolerant search in the playlist's titles (see [com.lumentv.core.SmartSearch]),
     * plus the films and series TMDB finds under another title (French or original) that the playlist has; then
     * episodes and programmes as before (by words). [pid] is the active playlist.
     */
    suspend fun search(query: String, epg: EpgRepository, pid: Long?, index: LibraryIndex, tmdb: com.lumentv.data.Tmdb): SearchResults = withContext(Dispatchers.IO) {
        val q = normalize(query)
        if (q.length < 2) return@withContext SearchResults()
        val sq = com.lumentv.core.SmartSearch.Query(query)
        val idx = pid?.let { index.of(it) }
        // People: the two best known whose name is the query, and what they played in / made that the playlist has.
        val people = async { if (idx != null && tmdb.enabled && sq.compact.length >= 3) kotlinx.coroutines.coroutineScope {
            tmdb.people(query).take(2).map { person ->
                async {
                    val works = tmdb.works(person.id)
                    listOf(true, false).mapNotNull { made ->
                        val found = works.filter { it.made == made }.mapNotNull { w -> idx.find(w.series, w.item)?.let { w.series to it } }
                            .filter { (_, v) -> !com.lumentv.core.Featured.foreign(v.name, v.category) }
                            .distinctBy { (series, v) -> "$series${com.lumentv.core.Featured.baseTitle(v.name)}" }.take(PERSON_LIMIT)
                        if (found.isEmpty()) return@mapNotNull null
                        val movies = found.filter { !it.first }.map { it.second.id }
                        val series = found.filter { it.first }.map { it.second.id }
                        val title = when {
                            !made -> "Avec ${person.name}"
                            movies.isEmpty() -> "Créé par ${person.name}"
                            else -> "Réalisé par ${person.name}"
                        }
                        // Kept in TMDB's order (most popular first).
                        val mv = db.vod().moviesByIds(movies).associateBy { it.m.id }
                        val se = db.vod().seriesByIds(series).associateBy { it.s.id }
                        PersonHit("${person.id}:$made", title, movies.mapNotNull { mv[it] }, series.mapNotNull { se[it] })
                    }
                }
            }.flatMap { it.await() }
        } else emptyList() }
        // Other titles: TMDB's French and original titles of what the query means ("seigneur des anneaux" → "The Lord of the Rings").
        val other: Pair<List<com.lumentv.data.db.VodLite>, List<com.lumentv.data.db.VodLite>> =
            if (idx != null && tmdb.enabled && sq.compact.length >= 3) kotlinx.coroutines.coroutineScope {
                val mv = async { tmdb.search(false, query).take(8).mapNotNull { idx.find(false, it) } }
                val se = async { tmdb.search(true, query).take(8).mapNotNull { idx.find(true, it) } }
                mv.await() to se.await()
            } else emptyList<com.lumentv.data.db.VodLite>() to emptyList()
        fun merge(local: List<com.lumentv.data.db.VodLite>, alt: List<com.lumentv.data.db.VodLite>) =
            (local + alt).distinctBy { it.id }.distinctBy { com.lumentv.core.Featured.baseTitle(it.name) }.take(SEARCH_LIMIT)
        val movieIds = idx?.let { merge(com.lumentv.core.SmartSearch.search(sq, it.movies, SEARCH_LIMIT * 2), other.first) }?.map { it.id }
        val seriesIds = idx?.let { merge(com.lumentv.core.SmartSearch.search(sq, it.series, SEARCH_LIMIT * 2), other.second) }?.map { it.id }
        val channelIds = idx?.let { com.lumentv.core.SmartSearch.search(sq, it.channels, SEARCH_LIMIT) }?.map { it.id }
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
        // The search's order kept (the rows come back from the database in any order).
        fun <T, K> ordered(ids: List<K>, rows: List<T>, key: (T) -> K): List<T> { val m = rows.associateBy(key); return ids.mapNotNull { m[it] } }
        SearchResults(
            channels = channelIds?.let { ids -> ordered(ids, ids.chunked(500).flatMap { db.channels().byIds(it) }) { it.ch.id } } ?: db.channels().search(q, SEARCH_LIMIT),
            movies = movieIds?.let { ids -> ordered(ids, db.vod().moviesByIds(ids)) { it.m.id } } ?: db.vod().searchMovies(q, SEARCH_LIMIT),
            series = seriesIds?.let { ids -> ordered(ids, db.vod().seriesByIds(ids)) { it.s.id } } ?: db.vod().searchSeries(q, SEARCH_LIMIT),
            episodes = db.vod().searchEpisodes(q, SEARCH_LIMIT),
            programs = progPairs,
            people = people.await(),
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
