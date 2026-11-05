package com.lumentv.data.db

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The shared database on the iPhone: tables created, reads and writes, transactions kept or undone. */
class DatabaseIosTest {
    private fun db() = Room.inMemoryDatabaseBuilder<AppDatabase>()
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.Default)
        .build()

    @Test
    fun readAndWrite() = runBlocking {
        val db = db()
        val id = db.playlists().insert(PlaylistEntity(name = "Test", type = "xtream", sourceEnc = "x"))
        assertEquals("Test", db.playlists().get(id)?.name)
        db.history().upsert(HistoryEntity("mv:1", "mv", id, "1", "Titanic", watchedAt = 10, positionMs = 60_000, durationMs = 600_000))
        assertEquals(60_000, db.history().get("mv:1")?.positionMs)
        assertEquals(listOf("Titanic"), db.history().recentOf("mv", 10).map { it.title })
        db.close()
    }

    @Test
    fun transactions() = runBlocking {
        val db = db()
        val id = db.playlists().insert(PlaylistEntity(name = "Test", type = "m3u", sourceEnc = "x"))
        db.transaction {
            db.history().upsert(HistoryEntity("a", "mv", id, "a", "A", watchedAt = 1))
            db.history().upsert(HistoryEntity("b", "mv", id, "b", "B", watchedAt = 2))
        }
        assertEquals(2, db.history().everything().size)
        // A failure inside undoes the whole transaction.
        val failed = runCatching {
            db.transaction {
                db.history().upsert(HistoryEntity("c", "mv", id, "c", "C", watchedAt = 3))
                error("stop")
            }
        }
        assertTrue(failed.isFailure)
        assertNull(db.history().get("c"))
        db.close()
    }
}
