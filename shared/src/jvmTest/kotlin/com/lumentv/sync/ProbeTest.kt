package com.lumentv.sync

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Manual check of a real profile, acting as a second device. Run with SYNC_TEST_URL, SYNC_PROBE_NAME and
 * SYNC_PROBE_PASS; with SYNC_PROBE_FAV=1 it also favourites the most recent channel of the history.
 * Writes a summary (no secrets) to build/probe.txt.
 */
class ProbeTest {
    @Test fun probe() = runBlocking {
        val url = System.getenv("SYNC_TEST_URL"); val name = System.getenv("SYNC_PROBE_NAME"); val pass = System.getenv("SYNC_PROBE_PASS")
        assumeTrue(url != null && name != null && pass != null)
        val sync = ProfileSync(OkHttpClient(), url!!)
        val keys = ProfileKeys.derive(name!!, pass!!)
        var doc = sync.pull(keys)!!.doc
        val out = StringBuilder()
        fun summary(title: String, d: ProfileDoc) {
            out.appendLine("== $title")
            out.appendLine("playlists: " + d.playlists.values.joinToString { "${it.name} (${it.type}, password ${if (it.password.isNotEmpty()) "present" else "empty"})" })
            out.appendLine("favorites on: " + d.favorites.values.count { it.on })
            out.appendLine("history: " + d.history.values.count { !it.deleted } + " — latest: " +
                d.history.values.filter { !it.deleted }.sortedByDescending { it.watchedAt }.take(3).joinToString { "${it.kind}:${it.title}" })
            out.appendLine("settings: " + d.settings.keys.sorted())
        }
        summary("pulled", doc)
        if (System.getenv("SYNC_PROBE_FAV") == "1") {
            val ch = doc.history.values.filter { it.kind == "ch" && !it.deleted }.maxBy { it.watchedAt }
            val k = ProfileMerge.itemKey(ch.playlist, ch.kind, ch.uid)
            val local = doc.copy(favorites = doc.favorites + (k to SyncFavorite(ch.playlist, ch.kind, ch.uid, order = 1, on = true, at = System.currentTimeMillis())))
            doc = sync.sync(keys, local).doc
            out.appendLine("favourited channel: ${ch.title}")
            summary("after push", doc)
        }
        File("build/probe.txt").writeText(out.toString())
    }
}
