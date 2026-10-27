package com.lumentv.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import javax.swing.SwingUtilities

/** Dev only (PLAYER_SMOKE=1): opens the real player window on the first channel of the cached playlist for 20 s. */
class PlayerSmokeTest {
    @Test fun window() = runBlocking {
        assumeTrue(System.getenv("PLAYER_SMOKE") == "1")
        val state = AppState(CoroutineScope(Dispatchers.Default))
        val key = state.playlists.keys.first()
        val c = Store.loadCatalog(key)!!
        state.open(key)
        Thread.sleep(3000)
        val ch = c.channels.first { it.name.startsWith(System.getenv("PLAYER_CHANNEL") ?: "TF1") }
        SwingUtilities.invokeAndWait { state.playChannel(ch) }
        Thread.sleep(20_000)
        SwingUtilities.invokeAndWait { PlayerWindow.release() }
    }
}
