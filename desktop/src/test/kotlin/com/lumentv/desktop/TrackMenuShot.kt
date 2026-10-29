package com.lumentv.desktop

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities

/** TRACK_SHOT=<png>: draws the subtitle menu into an image, to look at it. */
class TrackMenuShot {
    @Test fun shot() {
        val out = System.getenv("TRACK_SHOT"); assumeTrue(out != null)
        SwingUtilities.invokeAndWait {
            val f = JFrame().apply { setSize(600, 400); setLocation(0, 0); contentPane = JPanel(null).apply { background = Ui.bg }; isVisible = true }
            val anchor = Bubble(Ui.SUBS) {}.apply { setBounds(520, 330, 40, 40) }
            f.contentPane.add(anchor)
            TrackMenu.show(anchor, "Sous-titres", listOf(-1 to "Disable", 2 to "VFF Forced - [French]", 3 to "VFF - [French]", 4 to "Track 3 - [English]"), 3, false) {}
        }
        Thread.sleep(800)
        SwingUtilities.invokeAndWait {
            val menu = java.awt.Window.getWindows().flatMap { w -> (w as? javax.swing.RootPaneContainer)?.let { listOf(it.rootPane) }.orEmpty() }
                .mapNotNull { findMenu(it) }.first()
            val img = BufferedImage(menu.width, menu.height, BufferedImage.TYPE_INT_RGB)
            menu.paint(img.graphics)
            javax.imageio.ImageIO.write(img, "png", java.io.File(out!!))
        }
    }
    private fun findMenu(c: java.awt.Component): javax.swing.JPopupMenu? =
        c as? javax.swing.JPopupMenu ?: (c as? java.awt.Container)?.components?.firstNotNullOfOrNull { findMenu(it) }
}
