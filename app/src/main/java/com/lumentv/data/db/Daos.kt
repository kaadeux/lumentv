package com.lumentv.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class ChannelRow(
    @Embedded val ch: ChannelEntity,
    val favorite: Boolean?,
    val favOrder: Long?,
    val hidden: Boolean?,
    val epgOverride: String?,
) {
    val isFavorite get() = favorite == true
    val isHidden get() = hidden == true
}

data class MovieRow(@Embedded val m: MovieEntity, val favorite: Boolean?) {
    val isFavorite get() = favorite == true
}

data class SeriesRow(@Embedded val s: SeriesEntity, val favorite: Boolean?) {
    val isFavorite get() = favorite == true
}

data class GroupCount(val groupName: String, val count: Int, val firstNumber: Int)

private const val CH_SELECT = """
    SELECT c.*, p.favorite AS favorite, p.favOrder AS favOrder, p.hidden AS hidden, p.epgOverride AS epgOverride
    FROM channels c LEFT JOIN prefs p ON p.playlistId = c.playlistId AND p.kind = 'ch' AND p.uid = c.uid
"""

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY createdAt")
    fun observeAll(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists ORDER BY createdAt")
    suspend fun all(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun get(id: Long): PlaylistEntity?

    @Insert
    suspend fun insert(p: PlaylistEntity): Long

    @Update
    suspend fun update(p: PlaylistEntity)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ChannelDao {
    @Query("SELECT id, name, number FROM channels WHERE playlistId = :pid")
    suspend fun channelIndex(pid: Long): List<ChannelLite>

    @Query("$CH_SELECT WHERE c.id IN (:ids) AND IFNULL(p.hidden, 0) = 0")
    suspend fun byIds(ids: List<Long>): List<ChannelRow>

    @Query("$CH_SELECT WHERE c.playlistId = :pid AND (:group IS NULL OR c.groupName = :group) ORDER BY c.number")
    suspend fun inGroup(pid: Long, group: String?): List<ChannelRow>

    @Query("$CH_SELECT WHERE c.playlistId = :pid AND p.favorite = 1 ORDER BY p.favOrder, c.number")
    suspend fun favorites(pid: Long): List<ChannelRow>

    @Query("$CH_SELECT WHERE p.favorite = 1 ORDER BY p.favOrder, c.number")
    suspend fun favoritesAll(): List<ChannelRow>

    @Query("$CH_SELECT WHERE c.id = :id")
    suspend fun get(id: Long): ChannelRow?

    @Query("$CH_SELECT WHERE c.playlistId = :pid AND c.uid = :uid LIMIT 1")
    suspend fun byUid(pid: Long, uid: String): ChannelRow?

    @Query("$CH_SELECT WHERE c.nameNorm LIKE '%' || :q || '%' AND IFNULL(p.hidden, 0) = 0 ORDER BY LENGTH(c.nameNorm), c.number LIMIT :limit")
    suspend fun search(q: String, limit: Int): List<ChannelRow>

    /** Channels whose name holds a date such as "(2026-10-30 17:28:00)": pay-per-view event channels. */
    @Query("$CH_SELECT WHERE c.playlistId = :pid AND c.name GLOB '*20[0-9][0-9]-[0-1][0-9]-[0-3][0-9]*' ORDER BY c.number")
    suspend fun withDates(pid: Long): List<ChannelRow>

    @Query("SELECT groupName, COUNT(*) AS count, MIN(number) AS firstNumber FROM channels WHERE playlistId = :pid GROUP BY groupName ORDER BY firstNumber")
    suspend fun groups(pid: Long): List<GroupCount>

    @Query("SELECT COUNT(*) FROM channels WHERE playlistId = :pid")
    suspend fun count(pid: Long): Int

    @Query("SELECT COUNT(*) FROM channels")
    fun observeTotal(): Flow<Int>

    @Query("SELECT DISTINCT LOWER(epgId) FROM channels WHERE playlistId = :pid AND epgId IS NOT NULL AND epgId != ''")
    suspend fun epgIds(pid: Long): List<String>

    @Query("SELECT nameNorm FROM channels WHERE playlistId = :pid")
    suspend fun names(pid: Long): List<String>

    @Insert
    suspend fun insertAll(items: List<ChannelEntity>)

    @Query("DELETE FROM channels WHERE playlistId = :pid")
    suspend fun deleteFor(pid: Long)
}

@Dao
interface VodDao {
    @Query("SELECT id, uid, name, category, year, rating FROM movies WHERE playlistId = :pid")
    suspend fun movieIndex(pid: Long): List<VodLite>

    @Query("SELECT id, uid, name, category, year, rating FROM series WHERE playlistId = :pid")
    suspend fun seriesIndex(pid: Long): List<VodLite>

    @Query("""SELECT m.*, p.favorite AS favorite FROM movies m LEFT JOIN prefs p ON p.playlistId = m.playlistId AND p.kind = 'mv' AND p.uid = m.uid
        WHERE m.id IN (:ids)""")
    suspend fun moviesByIds(ids: List<Long>): List<MovieRow>

    @Query("""SELECT s.*, p.favorite AS favorite FROM series s LEFT JOIN prefs p ON p.playlistId = s.playlistId AND p.kind = 'se' AND p.uid = s.uid
        WHERE s.id IN (:ids)""")
    suspend fun seriesByIds(ids: List<Long>): List<SeriesRow>

    @Query("SELECT COUNT(*) FROM series WHERE playlistId = :pid AND tmdb IS NOT NULL")
    suspend fun seriesWithTmdb(pid: Long): Int

    @Query("SELECT category, COUNT(*) AS count, MIN(number) AS firstNumber FROM movies WHERE playlistId = :pid GROUP BY category ORDER BY firstNumber")
    suspend fun movieCategories(pid: Long): List<CategoryCount>

    @Query("""SELECT m.*, p.favorite AS favorite FROM movies m LEFT JOIN prefs p ON p.playlistId = m.playlistId AND p.kind = 'mv' AND p.uid = m.uid
        WHERE m.playlistId = :pid AND (:category IS NULL OR m.category = :category) ORDER BY m.number""")
    suspend fun movies(pid: Long, category: String?): List<MovieRow>

    @Query("""SELECT m.*, p.favorite AS favorite FROM movies m LEFT JOIN prefs p ON p.playlistId = m.playlistId AND p.kind = 'mv' AND p.uid = m.uid
        WHERE m.playlistId = :pid ORDER BY m.addedAt DESC, m.number DESC LIMIT :limit""")
    suspend fun recentMovies(pid: Long, limit: Int): List<MovieRow>

    @Query("""SELECT m.*, p.favorite AS favorite FROM movies m JOIN prefs p ON p.playlistId = m.playlistId AND p.kind = 'mv' AND p.uid = m.uid
        WHERE p.favorite = 1 ORDER BY p.favOrder""")
    suspend fun favoriteMovies(): List<MovieRow>

    @Query("""SELECT m.*, p.favorite AS favorite FROM movies m LEFT JOIN prefs p ON p.playlistId = m.playlistId AND p.kind = 'mv' AND p.uid = m.uid
        WHERE m.id = :id""")
    suspend fun movie(id: Long): MovieRow?

    @Query("""SELECT m.*, p.favorite AS favorite FROM movies m LEFT JOIN prefs p ON p.playlistId = m.playlistId AND p.kind = 'mv' AND p.uid = m.uid
        WHERE m.playlistId = :pid AND m.uid = :uid LIMIT 1""")
    suspend fun movieByUid(pid: Long, uid: String): MovieRow?

    @Query("""SELECT m.*, NULL AS favorite FROM movies m WHERE m.nameNorm LIKE '%' || :q || '%' ORDER BY LENGTH(m.nameNorm) LIMIT :limit""")
    suspend fun searchMovies(q: String, limit: Int): List<MovieRow>

    @Query("""SELECT s.*, p.favorite AS favorite FROM series s LEFT JOIN prefs p ON p.playlistId = s.playlistId AND p.kind = 'se' AND p.uid = s.uid
        WHERE s.playlistId = :pid ORDER BY s.updatedAt DESC, s.number LIMIT :limit""")
    suspend fun recentSeries(pid: Long, limit: Int): List<SeriesRow>

    @Query("""SELECT m.*, p.favorite AS favorite FROM movies m LEFT JOIN prefs p ON p.playlistId = m.playlistId AND p.kind = 'mv' AND p.uid = m.uid
        WHERE m.playlistId = :pid AND CAST(m.rating AS REAL) BETWEEN :min AND 9.0
        ORDER BY CAST(m.rating AS REAL) DESC LIMIT :limit""")
    suspend fun topMovies(pid: Long, min: Double, limit: Int): List<MovieRow>

    @Query("""SELECT s.*, p.favorite AS favorite FROM series s LEFT JOIN prefs p ON p.playlistId = s.playlistId AND p.kind = 'se' AND p.uid = s.uid
        WHERE s.playlistId = :pid AND CAST(s.rating AS REAL) BETWEEN :min AND 9.0
        ORDER BY CAST(s.rating AS REAL) DESC LIMIT :limit""")
    suspend fun topSeries(pid: Long, min: Double, limit: Int): List<SeriesRow>

    @Query("SELECT category, COUNT(*) AS count, MIN(number) AS firstNumber FROM series WHERE playlistId = :pid GROUP BY category ORDER BY firstNumber")
    suspend fun seriesCategories(pid: Long): List<CategoryCount>

    @Query("""SELECT s.*, p.favorite AS favorite FROM series s LEFT JOIN prefs p ON p.playlistId = s.playlistId AND p.kind = 'se' AND p.uid = s.uid
        WHERE s.playlistId = :pid AND (:category IS NULL OR s.category = :category) ORDER BY s.number""")
    suspend fun series(pid: Long, category: String?): List<SeriesRow>

    @Query("""SELECT s.*, p.favorite AS favorite FROM series s JOIN prefs p ON p.playlistId = s.playlistId AND p.kind = 'se' AND p.uid = s.uid
        WHERE p.favorite = 1 ORDER BY p.favOrder""")
    suspend fun favoriteSeries(): List<SeriesRow>

    @Query("""SELECT s.*, p.favorite AS favorite FROM series s LEFT JOIN prefs p ON p.playlistId = s.playlistId AND p.kind = 'se' AND p.uid = s.uid
        WHERE s.playlistId = :pid AND s.uid = :uid LIMIT 1""")
    suspend fun seriesByUid(pid: Long, uid: String): SeriesRow?

    @Query("""SELECT s.*, NULL AS favorite FROM series s WHERE s.nameNorm LIKE '%' || :q || '%' ORDER BY LENGTH(s.nameNorm) LIMIT :limit""")
    suspend fun searchSeries(q: String, limit: Int): List<SeriesRow>

    @Query("SELECT * FROM episodes WHERE playlistId = :pid AND seriesUid = :seriesUid ORDER BY season, episode, id")
    suspend fun episodes(pid: Long, seriesUid: String): List<EpisodeEntity>

    @Query("SELECT * FROM episodes WHERE nameNorm LIKE '%' || :q || '%' ORDER BY LENGTH(nameNorm) LIMIT :limit")
    suspend fun searchEpisodes(q: String, limit: Int): List<EpisodeEntity>

    @Query("SELECT COUNT(*) FROM movies")
    fun observeMovieTotal(): Flow<Int>

    @Query("SELECT COUNT(*) FROM series")
    fun observeSeriesTotal(): Flow<Int>

    @Insert suspend fun insertMovies(items: List<MovieEntity>)
    @Insert suspend fun insertSeries(items: List<SeriesEntity>)
    @Insert suspend fun insertEpisodes(items: List<EpisodeEntity>)

    @Query("DELETE FROM movies WHERE playlistId = :pid") suspend fun deleteMovies(pid: Long)
    @Query("DELETE FROM series WHERE playlistId = :pid") suspend fun deleteSeries(pid: Long)
    @Query("DELETE FROM episodes WHERE playlistId = :pid") suspend fun deleteEpisodes(pid: Long)
    @Query("DELETE FROM episodes WHERE playlistId = :pid AND seriesUid = :seriesUid")
    suspend fun deleteEpisodesOf(pid: Long, seriesUid: String)
}

data class CategoryCount(val category: String, val count: Int, val firstNumber: Int)

@Dao
interface PrefDao {
    @Query("SELECT * FROM prefs WHERE playlistId = :pid AND kind = :kind AND uid = :uid")
    suspend fun get(pid: Long, kind: String, uid: String): PrefEntity?

    @Query("SELECT * FROM prefs WHERE playlistId = :pid AND kind = :kind")
    suspend fun allOf(pid: Long, kind: String): List<PrefEntity>

    @Query("SELECT * FROM prefs")
    suspend fun everything(): List<PrefEntity>

    @Query("SELECT IFNULL(MAX(favOrder), 0) FROM prefs WHERE favorite = 1")
    suspend fun maxFavOrder(): Long

    @Upsert
    suspend fun upsert(p: PrefEntity)

    @Upsert
    suspend fun upsertAll(p: List<PrefEntity>)

    @Query("DELETE FROM prefs WHERE playlistId = :pid")
    suspend fun deleteFor(pid: Long)
}

data class ProgramWithKey(val epgKey: String, val start: Long, val stop: Long, val title: String, val description: String?)

@Dao
interface EpgDao {
    @Query("SELECT * FROM programs WHERE playlistId = :pid AND epgKey = :key AND stop > :from AND start < :to ORDER BY start")
    suspend fun programs(pid: Long, key: String, from: Long, to: Long): List<ProgramEntity>

    @Query("SELECT * FROM programs WHERE playlistId = :pid AND epgKey IN (:keys) AND stop > :from AND start < :to ORDER BY epgKey, start")
    suspend fun programsFor(pid: Long, keys: List<String>, from: Long, to: Long): List<ProgramEntity>

    @Query("SELECT * FROM programs WHERE titleNorm LIKE '%' || :q || '%' AND stop > :now ORDER BY start LIMIT :limit")
    suspend fun search(q: String, now: Long, limit: Int): List<ProgramEntity>

    @androidx.room.RawQuery
    suspend fun raw(query: androidx.sqlite.db.SupportSQLiteQuery): List<ProgramEntity>

    @Query("SELECT COUNT(*) FROM programs WHERE playlistId = :pid")
    suspend fun count(pid: Long): Int

    @Query("SELECT COUNT(*) FROM programs")
    fun observeTotal(): Flow<Int>

    @Insert suspend fun insertPrograms(items: List<ProgramEntity>)
    @Insert suspend fun insertChannels(items: List<EpgChannelEntity>)

    @Query("SELECT * FROM epg_channels WHERE playlistId = :pid")
    suspend fun channels(pid: Long): List<EpgChannelEntity>

    @Query("DELETE FROM programs WHERE playlistId = :pid") suspend fun deletePrograms(pid: Long)
    @Query("DELETE FROM epg_channels WHERE playlistId = :pid") suspend fun deleteChannels(pid: Long)
    @Query("DELETE FROM programs WHERE stop < :before") suspend fun deleteOlderThan(before: Long)
    @Query("DELETE FROM programs") suspend fun clearPrograms()
    @Query("DELETE FROM epg_channels") suspend fun clearChannels()
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY watchedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history WHERE kind = :kind ORDER BY watchedAt DESC LIMIT :limit")
    suspend fun recentOf(kind: String, limit: Int): List<HistoryEntity>

    @Query("SELECT * FROM history WHERE key = :key")
    suspend fun get(key: String): HistoryEntity?

    @Query("SELECT * FROM history WHERE kind = 'ep' AND playlistId = :pid AND parentUid = :seriesUid")
    suspend fun episodesOf(pid: Long, seriesUid: String): List<HistoryEntity>

    @Upsert
    suspend fun upsert(h: HistoryEntity)

    @Query("DELETE FROM history WHERE key = :key")
    suspend fun delete(key: String)

    @Query("DELETE FROM history")
    suspend fun clear()

    @Query("SELECT * FROM history")
    suspend fun everything(): List<HistoryEntity>

    @Query("SELECT COUNT(*) || ':' || IFNULL(MAX(watchedAt), 0) || ':' || IFNULL(SUM(positionMs), 0) FROM history")
    fun observeFingerprint(): Flow<String>

    @Query("DELETE FROM history WHERE playlistId = :pid")
    suspend fun deleteFor(pid: Long)
}

@Dao
interface TransferDao {
    @Query("SELECT * FROM transfers ORDER BY CASE status WHEN 'running' THEN 0 WHEN 'scheduled' THEN 1 ELSE 2 END, startAt DESC")
    fun observeAll(): Flow<List<TransferEntity>>

    @Query("SELECT * FROM transfers WHERE id = :id")
    suspend fun get(id: Long): TransferEntity?

    @Query("SELECT * FROM transfers WHERE status IN ('scheduled', 'running')")
    suspend fun pending(): List<TransferEntity>

    @Query("SELECT * FROM transfers WHERE kind = 'dl' AND status = 'done' AND playlistId = :pid AND refUid = :uid LIMIT 1")
    suspend fun downloaded(pid: Long, uid: String): TransferEntity?

    @Insert suspend fun insert(t: TransferEntity): Long
    @Update suspend fun update(t: TransferEntity)

    @Query("UPDATE transfers SET bytes = :bytes, totalBytes = :total WHERE id = :id")
    suspend fun progress(id: Long, bytes: Long, total: Long)

    @Query("DELETE FROM transfers WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminders WHERE stop > :now ORDER BY start")
    fun observeUpcoming(now: Long): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders WHERE stop > :now ORDER BY start")
    suspend fun upcoming(now: Long): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun get(id: Long): ReminderEntity?

    @Query("SELECT * FROM reminders WHERE playlistId = :pid AND channelUid = :uid AND start = :start LIMIT 1")
    suspend fun find(pid: Long, uid: String, start: Long): ReminderEntity?

    @Insert suspend fun insert(r: ReminderEntity): Long

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM reminders WHERE stop < :before")
    suspend fun prune(before: Long)
}

@Dao
interface RatingDao {
    @Query("SELECT * FROM ratings ORDER BY ratedAt DESC")
    fun observeAll(): Flow<List<RatingEntity>>

    @Query("SELECT * FROM ratings")
    suspend fun all(): List<RatingEntity>

    @Upsert
    suspend fun upsert(r: RatingEntity)

    @Query("DELETE FROM ratings WHERE key = :key")
    suspend fun delete(key: String)

    @Query("SELECT * FROM tmdb_links WHERE key = :key")
    suspend fun link(key: String): TmdbLinkEntity?

    @Upsert
    suspend fun saveLink(l: TmdbLinkEntity)
}
