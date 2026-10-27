package com.lumentv.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import javax.swing.SwingUtilities

/**
 * Dev only (PLAYER_SMOKE=1): opens the real player window for PLAYER_SECONDS on PLAYER_SERIES (first unfinished
 * episode) or else on the first channel named PLAYER_CHANNEL (default TF1).
 */
class PlayerSmokeTest {
    @Test fun window() = runBlocking {
        assumeTrue(System.getenv("PLAYER_SMOKE") == "1")
        val state = AppState(CoroutineScope(Dispatchers.Default))
        val key = state.playlists.keys.first()
        state.open(key)
        // Wait for the up-to-date list (an older cached one is reloaded by the app).
        repeat(120) { if (state.catalog?.version == CATALOG_VERSION && !state.loading) return@repeat; Thread.sleep(500) }
        val c = state.catalog!!
        val seriesName = System.getenv("PLAYER_SERIES")
        val movieName = System.getenv("PLAYER_MOVIE")
        if (movieName != null) {
            val m = c.movies.first { it.name.contains(movieName, true) }
            SwingUtilities.invokeAndWait { state.playMovie(m, fromStart = true) }
        } else if (seriesName != null) {
            val s = c.series.first { it.name.equals(seriesName, true) }
            val eps = state.library.episodes(state.active!!, c, s)
            // PLAYER_EPISODE=2x2 picks season 2 episode 2; PLAYER_START (s) where to start.
            val want = System.getenv("PLAYER_EPISODE")?.split('x')?.map { it.toInt() }
            val i = if (want != null) eps.indexOfFirst { it.season == want[0] && it.episode == want[1] }
                else eps.indexOfFirst { !state.isWatched(Kind.EPISODE, it.uid) }.coerceAtLeast(0)
            val start = (System.getenv("PLAYER_START")?.toLong() ?: 0) * 1000
            SwingUtilities.invokeAndWait { PlayerWindow.open(state, state.episodeItem(s, eps, i, fromStart = true).copy(startMs = start)) }
        } else {
            val ch = c.channels.first { it.name.startsWith(System.getenv("PLAYER_CHANNEL") ?: "TF1") }
            SwingUtilities.invokeAndWait { state.playChannel(ch) }
        }
        Thread.sleep((System.getenv("PLAYER_SECONDS")?.toLong() ?: 20) * 1000)
        SwingUtilities.invokeAndWait { PlayerWindow.release() }
    }
}
