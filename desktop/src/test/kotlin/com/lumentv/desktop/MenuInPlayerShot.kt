package com.lumentv.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.Component
import java.awt.Container
import java.awt.Window
import javax.swing.SwingUtilities

/**
 * Dev only (MENU_SHOT=<png>, MENU_IMAGE=<any picture> or MENU_URL=screen:// for moving video): the real player plays it on the second screen,
 * the subtitle menu is opened and the screen captured. No subscription involved (a file: address).
 */
class MenuInPlayerShot {
    @Test fun shot() {
        val out = System.getenv("MENU_SHOT"); val image = System.getenv("MENU_IMAGE")
        assumeTrue(out != null && (image != null || System.getenv("MENU_URL") != null))
        val state = AppState(CoroutineScope(Dispatchers.Default))
        val item = PlayItem(Kind.MOVIE, "menu-test", "Test du menu", null, null, null, (System.getenv("MENU_URL") ?: java.io.File(image!!).toURI().toString()), live = false)
        SwingUtilities.invokeAndWait { PlayerWindow.open(state, item) }
        Thread.sleep(1500)
        val frame = Window.getWindows().first { it is javax.swing.JFrame && it.isShowing }
        SwingUtilities.invokeAndWait { (frame as javax.swing.JFrame).extendedState = 0; frame.isAlwaysOnTop = true; frame.setBounds(2600, 80, 1200, 760) }
        Thread.sleep(2500)
        // MENU_FULL=1: full screen, as when watching.
        if (System.getenv("MENU_FULL") == "1") {
            SwingUtilities.invokeAndWait {
                val full = Window.getWindows().filter { it.isShowing }.firstNotNullOf { find(it, "Plein écran (F)") }
                full.dispatchEvent(java.awt.event.MouseEvent(full, java.awt.event.MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 5, 5, 1, false))
            }
            Thread.sleep(2500)
        }
        SwingUtilities.invokeAndWait {
            val subs = Window.getWindows().filter { it.isShowing }.firstNotNullOf { find(it) }
            subs.dispatchEvent(java.awt.event.MouseEvent(subs, java.awt.event.MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 5, 5, 1, false))
        }
        Thread.sleep(1000)
        java.io.File(out + ".txt").writeText(Window.getWindows().joinToString("\n") { "${it.javaClass.simpleName} showing=${it.isShowing} ${it.bounds}" })
        val img = java.awt.Robot().createScreenCapture(java.awt.Rectangle(frame.x, frame.y, frame.width, frame.height))
        javax.imageio.ImageIO.write(img, "png", java.io.File(out!!))
        SwingUtilities.invokeAndWait { PlayerWindow.release() }
    }

    private fun find(c: Component, tip: String = "Sous-titres"): Bubble? =
        (c as? Bubble)?.takeIf { it.toolTipText == tip } ?: (c as? Container)?.components?.firstNotNullOfOrNull { find(it, tip) }
}
