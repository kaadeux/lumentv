package com.lumentv.desktop

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities

/** TRACK_SHOT=<png>: opens the subtitle menu over a heavyweight surface (like VLC's video) and captures the screen. */
class TrackMenuShot {
    @Test fun shot() {
        val out = System.getenv("TRACK_SHOT"); assumeTrue(out != null)
        SwingUtilities.invokeAndWait {
            val f = JFrame().apply { setSize(600, 400); setLocation(2700, 100); isAlwaysOnTop = true; contentPane = JPanel(null).apply { background = Ui.bg }; isVisible = true }
            val anchor = Bubble(Ui.SUBS) {}.apply { setBounds(520, 330, 40, 40) }
            f.contentPane.add(anchor)
            // Heavyweight, like the video: the menu then opens in a window of its own, as during playback.
            f.contentPane.add(java.awt.Canvas().apply { background = java.awt.Color(40, 60, 40); setBounds(0, 0, 600, 320) })
            TrackMenu.show(anchor, "Sous-titres", listOf(-1 to "Disable", 2 to "VFF Forced - [French]", 3 to "VFF - [French]", 4 to "Track 3 - [English]"), 3, false) {}
        }
        Thread.sleep(800)
        val w = java.awt.Window.getWindows().first { it.isShowing && it is JFrame }
        val img = java.awt.Robot().createScreenCapture(java.awt.Rectangle(w.x, w.y, w.width, w.height))
        javax.imageio.ImageIO.write(img, "png", java.io.File(out!!))
    }
    private fun findMenu(c: java.awt.Component): javax.swing.JPopupMenu? =
        c as? javax.swing.JPopupMenu ?: (c as? java.awt.Container)?.components?.firstNotNullOfOrNull { findMenu(it) }
}
