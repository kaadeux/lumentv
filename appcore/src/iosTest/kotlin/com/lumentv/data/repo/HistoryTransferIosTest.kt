package com.lumentv.data.repo

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.lumentv.core.Http
import com.lumentv.core.HttpRequest
import com.lumentv.data.db.AppDatabase
import com.lumentv.data.db.EpisodeEntity
import com.lumentv.data.db.HistoryEntity
import com.lumentv.data.db.HistoryKind
import com.lumentv.data.db.MovieEntity
import com.lumentv.data.db.PlaylistEntity
import com.lumentv.data.db.SeriesEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A list removed: its history moves to another list by title, or is kept aside until a new list has the titles. */
class HistoryTransferIosTest {
    private fun db() = Room.inMemoryDatabaseBuilder<AppDatabase>().setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.Default).build()

    private val noNetwork = object : Http { override fun execute(request: HttpRequest) = error("no network") }

    private suspend fun list(db: AppDatabase, name: String, movies: List<String>, series: String?): Long {
        val id = db.playlists().insert(PlaylistEntity(name = name, type = "m3u", sourceEnc = "x"))
        db.vod().insertMovies(movies.mapIndexed { i, m -> MovieEntity(playlistId = id, uid = "$name:m$i", name = m, nameNorm = m.lowercase(), category = "Films", number = i) })
        series?.let { s ->
            db.vod().insertSeries(listOf(SeriesEntity(playlistId = id, uid = "$name:s", name = s, nameNorm = s.lowercase(), category = "Séries", number = 0)))
            db.vod().insertEpisodes((1..3).map { e -> EpisodeEntity(playlistId = id, seriesUid = "$name:s", uid = "$name:e$e", title = "Épisode $e", nameNorm = "e$e", season = 1, episode = e) })
        }
        return id
    }

    @Test fun movesKeepsAsideAndComesBack() = runBlocking {
        val db = db()
        val playlists = PlaylistRepository(db, noNetwork) { "" }
        val library = LibraryRepository(db)
        val transfer = HistoryTransfer(db, playlists, LibraryIndex(db))
        val a = list(db, "A", listOf("FR - Dune (2021)", "FR - Heart of the Beast (2026)"), "FR - FROM (2022)")
        val b = list(db, "B", listOf("4K-TOP - Dune (2021)"), "EN - FROM (2022) (US)")
        db.history().upsert(HistoryEntity(LibraryRepository.key(HistoryKind.MOVIE, a, "A:m0"), HistoryKind.MOVIE, a, "A:m0", "FR - Dune (2021)", watchedAt = 3, positionMs = 600_000, durationMs = 9_000_000))
        db.history().upsert(HistoryEntity(LibraryRepository.key(HistoryKind.MOVIE, a, "A:m1"), HistoryKind.MOVIE, a, "A:m1", "FR - Heart of the Beast (2026)", watchedAt = 2))
        db.history().upsert(HistoryEntity(LibraryRepository.key(HistoryKind.EPISODE, a, "A:e2"), HistoryKind.EPISODE, a, "A:e2", "FR - FROM (2022)", "S1 E2 · Épisode 2", parentUid = "A:s", watchedAt = 1))

        // Into B: Dune and FROM S1 E2 found by name, Heart of the Beast is not in B.
        val r = transfer.move(a, b)
        assertEquals(2, r.moved)
        assertEquals(listOf("Heart of the Beast"), r.missing)
        assertEquals(600_000, db.history().get(LibraryRepository.key(HistoryKind.MOVIE, b, "B:m0"))?.positionMs)
        assertTrue(db.history().get(LibraryRepository.key(HistoryKind.EPISODE, b, "B:e2")) != null)
        playlists.delete(a)

        // Kept aside, shown nowhere; B removed too: all of it kept aside.
        assertEquals(1, transfer.count(HistoryTransfer.RESERVE))
        playlists.delete(b)
        assertEquals(3, transfer.count(HistoryTransfer.RESERVE))
        assertTrue(library.observeHistory().first().isEmpty())

        // A new list with Heart of the Beast and FROM: they come back, Dune is still kept aside.
        val c = list(db, "C", listOf("Heart of the Beast (2026)"), "FROM (2022)")
        val text = transfer.adopt(c)
        assertTrue(text != null && text.contains("2 titres"), text)
        assertEquals(1, transfer.count(HistoryTransfer.RESERVE))
        assertEquals(2, library.observeHistory().first().size)
        db.close()
    }
}
