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
        val c = Store.loadCatalog(key)!!
        state.open(key)
        Thread.sleep(4000)
        val seriesName = System.getenv("PLAYER_SERIES")
        if (seriesName != null) {
            val s = c.series.first { it.name.equals(seriesName, true) }
            val eps = state.library.episodes(state.active!!, c, s)
            val i = eps.indexOfFirst { !state.isWatched(Kind.EPISODE, it.uid) }.coerceAtLeast(0)
            SwingUtilities.invokeAndWait { PlayerWindow.open(state, state.episodeItem(s, eps, i)) }
        } else {
            val ch = c.channels.first { it.name.startsWith(System.getenv("PLAYER_CHANNEL") ?: "TF1") }
            SwingUtilities.invokeAndWait { state.playChannel(ch) }
        }
        Thread.sleep((System.getenv("PLAYER_SECONDS")?.toLong() ?: 20) * 1000)
        SwingUtilities.invokeAndWait { PlayerWindow.release() }
    }
}
