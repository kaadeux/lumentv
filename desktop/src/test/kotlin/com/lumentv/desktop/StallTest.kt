package com.lumentv.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assume.assumeTrue
import org.junit.Test
import javax.swing.SwingUtilities

/**
 * Dev only (STALL_URL=http://…): plays a film from a server that stops sending mid-way; the player must reopen it
 * where it stopped (see LUMEN_TRACE). No subscription is used.
 */
class StallTest {
    @Test fun filmReopensWhereItStalled() {
        val url = System.getenv("STALL_URL")
        assumeTrue(url != null)
        val state = AppState(CoroutineScope(Dispatchers.Default))
        val item = PlayItem(Kind.MOVIE, "stall-test", "Test de coupure", null, null, null, url!!, live = false)
        SwingUtilities.invokeAndWait { PlayerWindow.open(state, item) }
        Thread.sleep((System.getenv("STALL_SECONDS")?.toLong() ?: 60) * 1000)
        SwingUtilities.invokeAndWait { PlayerWindow.release() }
    }
}
