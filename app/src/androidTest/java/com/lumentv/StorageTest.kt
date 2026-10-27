package com.lumentv

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lumentv.core.SecretBox
import com.lumentv.data.db.AppDatabase
import com.lumentv.data.db.HistoryEntity
import com.lumentv.data.db.PlaylistEntity
import com.lumentv.data.db.PrefKind
import com.lumentv.data.repo.Groups
import com.lumentv.data.repo.LibraryRepository
import com.lumentv.data.repo.M3uImport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.StringReader

/** Runs on the TV (`gradlew connectedDebugAndroidTest`): real SQLite and real Keystore. */
@RunWith(AndroidJUnit4::class)
class StorageTest {
    private lateinit var db: AppDatabase
    private lateinit var lib: LibraryRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        lib = LibraryRepository(db)
    }

    @After fun tearDown() = db.close()

    private suspend fun importText(pid: Long, text: String) {
        val r = M3uImport.build(pid, BufferedReader(StringReader(text)), true)
        db.withTransaction {
            db.channels().deleteFor(pid)
            db.channels().insertAll(r.channels)
        }
    }

    @Test fun favoritesAndHiddenSurviveRefresh() = runTest {
        val pid = db.playlists().insert(PlaylistEntity(name = "t", type = "m3u", sourceEnc = ""))
        val v1 = "#EXTM3U\n#EXTINF:-1 group-title=\"A\",One\nhttp://s/1?t=1\n#EXTINF:-1 group-title=\"A\",Two\nhttp://s/2?t=1\n#EXTINF:-1 group-title=\"B\",Three\nhttp://s/3?t=1\n"
        importText(pid, v1)
        val one = db.channels().inGroup(pid, "A").first { it.ch.name == "One" }
        val two = db.channels().inGroup(pid, "A").first { it.ch.name == "Two" }
        assertTrue(lib.toggleFavorite(pid, PrefKind.CHANNEL, one.ch.uid))
        lib.setHidden(pid, PrefKind.CHANNEL, two.ch.uid, true)
        lib.setHidden(pid, PrefKind.GROUP, "B", true)

        // Refresh: new tokens in the URLs, a new channel, ids change.
        importText(pid, v1.replace("t=1", "t=2") + "#EXTINF:-1 group-title=\"A\",Four\nhttp://s/4\n")
        val favs = lib.channels(pid, Groups.FAVORITES, false, "provider")
        assertEquals(listOf("One"), favs.map { it.ch.name })
        val all = lib.channels(pid, Groups.ALL, false, "provider").map { it.ch.name }
        assertEquals(listOf("One", "Four"), all)
        assertFalse(lib.groups(pid, false).any { it.name == "B" })
        assertEquals(3, lib.channels(pid, "A", true, "provider").size)
    }

    @Test fun favoritesCanBeReordered() = runTest {
        val pid = db.playlists().insert(PlaylistEntity(name = "t", type = "m3u", sourceEnc = ""))
        importText(pid, "#EXTM3U\n#EXTINF:-1,A\nhttp://a\n#EXTINF:-1,B\nhttp://b\n#EXTINF:-1,C\nhttp://c\n")
        db.channels().inGroup(pid, null).forEach { lib.toggleFavorite(pid, PrefKind.CHANNEL, it.ch.uid) }
        val c = db.channels().inGroup(pid, null).first { it.ch.name == "C" }
        lib.moveFavorite(pid, c.ch.uid, -1)
        assertEquals(listOf("A", "C", "B"), lib.channels(pid, Groups.FAVORITES, false, "provider").map { it.ch.name })
    }

    @Test fun searchIsLocalAndAccentInsensitive() = runTest {
        val pid = db.playlists().insert(PlaylistEntity(name = "t", type = "m3u", sourceEnc = ""))
        importText(pid, "#EXTM3U\n#EXTINF:-1,France Télévisions\nhttp://a\n#EXTINF:-1,Arte\nhttp://b\n")
        assertEquals(listOf("France Télévisions"), db.channels().search("televi", 10).map { it.ch.name })
    }

    @Test fun historyKeepsResumePosition() = runTest {
        lib.record(HistoryEntity("mv:1:x", "mv", 1, "x", "Film", watchedAt = 1, positionMs = 120_000, durationMs = 600_000))
        lib.record(HistoryEntity("mv:1:x", "mv", 1, "x", "Film", watchedAt = 2, positionMs = 180_000, durationMs = 600_000))
        assertEquals(180_000, lib.history("mv:1:x")!!.positionMs)
        assertEquals(1, db.history().observeRecent(10).first().size)
    }

    /** 50 000 channels and 30 000 movies: import, group list, a group, search; timings are printed to logcat. */
    @Test fun largePlaylistOnDevice() = runTest(timeout = kotlin.time.Duration.parse("5m")) {
        val pid = db.playlists().insert(PlaylistEntity(name = "big", type = "m3u", sourceEnc = ""))
        val sb = StringBuilder("#EXTM3U\n")
        repeat(50_000) { i -> sb.append("#EXTINF:-1 tvg-id=\"c$i\" tvg-logo=\"http://l/$i.png\" group-title=\"Groupe ${i % 120}\",Chaîne $i HD\nhttp://s/live/u/p/$i.ts\n") }
        repeat(30_000) { i -> sb.append("#EXTINF:-1 group-title=\"Films ${i % 60}\",Film numéro $i (2020)\nhttp://s/movie/u/p/$i.mkv\n") }
        var t = System.currentTimeMillis()
        val r = M3uImport.build(pid, BufferedReader(StringReader(sb.toString())), true)
        val parseMs = System.currentTimeMillis() - t
        t = System.currentTimeMillis()
        db.withTransaction {
            r.channels.chunked(500).forEach { db.channels().insertAll(it) }
            r.movies.chunked(500).forEach { db.vod().insertMovies(it) }
        }
        val insertMs = System.currentTimeMillis() - t
        t = System.currentTimeMillis(); val groups = lib.groups(pid, false); val groupsMs = System.currentTimeMillis() - t
        t = System.currentTimeMillis(); val one = lib.channels(pid, "Groupe 7", false, "provider"); val groupMs = System.currentTimeMillis() - t
        t = System.currentTimeMillis(); val found = db.channels().search("chaine 4999", 60); val searchMs = System.currentTimeMillis() - t
        android.util.Log.i("LumenPerf", "parse=${parseMs}ms insert=${insertMs}ms groups=${groupsMs}ms group=${groupMs}ms search=${searchMs}ms")
        assertEquals(50_000, r.channels.size)
        assertEquals(30_000, r.movies.size)
        assertEquals(120, groups.size)
        assertTrue(one.size in 416..417)
        assertTrue(found.any { it.ch.name == "Chaîne 4999 HD" })
        assertTrue("group query took $groupMs ms", groupMs < 1500)
        assertTrue("search took $searchMs ms", searchMs < 1500)
    }

    @Test fun secretsRoundTripThroughKeystore() {
        val enc = SecretBox.encrypt("http://h/get.php?username=u&password=p")
        assertTrue(enc.startsWith("k1:"))
        assertFalse(enc.contains("password"))
        assertEquals("http://h/get.php?username=u&password=p", SecretBox.decrypt(enc))
    }
}
