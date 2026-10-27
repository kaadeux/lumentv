package com.lumentv.desktop

import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Ellipse2D
import java.awt.geom.RoundRectangle2D
import javax.swing.JComponent
import java.util.prefs.Preferences

/** Look of the player window: dark glass bubbles, white icons (Windows' own icon font). */
object Ui {
    val bg = Color(0x0B, 0x0D, 0x12)
    val bubble = Color(255, 255, 255, 28)
    val bubbleHover = Color(255, 255, 255, 52)
    val accent = Color(0x4F, 0x8C, 0xFF)
    val text = Color(0xF2, 0xF4, 0xF8)
    val dim = Color(0xA3, 0xAA, 0xB8)
    val danger = Color(0xFF, 0x8A, 0x80)

    private val families = GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
    /** Segoe Fluent Icons (Windows 11) or Segoe MDL2 Assets (Windows 10); null → text labels. */
    val iconFamily: String? = listOf("Segoe Fluent Icons", "Segoe MDL2 Assets").firstOrNull { it in families }
    fun font(size: Int, bold: Boolean = false) = Font("Segoe UI", if (bold) Font.BOLD else Font.PLAIN, size)
    fun icons(size: Int) = Font(iconFamily ?: "Segoe UI", Font.PLAIN, size)

    // Segoe MDL2 / Fluent code points.
    const val PLAY = ""; const val PAUSE = ""; const val STOP = ""; const val BACK10 = ""; const val FWD30 = ""
    const val NEXT = ""; const val RESTART = ""; const val FULL = ""; const val UNFULL = ""; const val VOLUME = ""
    const val MUTE = ""; const val SUBS = ""; const val AUDIO = ""; const val SPEED = ""; const val CHEVRON_LEFT = ""
    const val CLOSE = ""

    fun smooth(g: Graphics): Graphics2D = (g as Graphics2D).apply {
        setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)
        setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
    }
}

/**
 * A round (icon) or pill (text) button. [icon] uses the icon font, [label] the text font; both can be set.
 * [primary] draws the big white play/pause disc, [accent] a blue pill (skip intro, next episode).
 */
class Bubble(
    var icon: String? = null,
    var label: String? = null,
    tooltip: String? = null,
    private val size: Int = 40,
    private val primary: Boolean = false,
    private val accent: Boolean = false,
    private val onClick: (Bubble) -> Unit,
) : JComponent() {
    private var hover = false
    var active = false

    init {
        toolTipText = tooltip
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        isOpaque = false
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) { hover = true; repaint() }
            override fun mouseExited(e: MouseEvent) { hover = false; repaint() }
            override fun mouseClicked(e: MouseEvent) { onClick(this@Bubble) }
        })
    }

    fun set(icon: String? = this.icon, label: String? = this.label) { this.icon = icon; this.label = label; revalidate(); repaint() }

    override fun getPreferredSize(): Dimension {
        val l = label ?: return Dimension(size, size)
        val fm = getFontMetrics(Ui.font(14, accent))
        val iconW = if (icon != null && Ui.iconFamily != null) getFontMetrics(Ui.icons(15)).stringWidth(icon) + 8 else 0
        return Dimension(fm.stringWidth(l) + iconW + 30, size)
    }
    override fun getMaximumSize() = preferredSize
    override fun getMinimumSize() = preferredSize

    override fun paintComponent(g0: Graphics) {
        val g = Ui.smooth(g0)
        val w = width.toDouble(); val h = height.toDouble()
        g.color = when {
            primary -> if (hover) Color(255, 255, 255, 235) else Color.WHITE
            accent -> if (hover) Ui.accent.brighter() else Ui.accent
            active -> Color(255, 255, 255, 70)
            hover -> Ui.bubbleHover
            else -> Ui.bubble
        }
        if (label == null) g.fill(Ellipse2D.Double(0.0, 0.0, w - 1, h - 1)) else g.fill(RoundRectangle2D.Double(0.0, 0.0, w - 1, h - 1, h, h))
        val fg = if (primary) Ui.bg else Ui.text
        g.color = fg
        val ic = icon?.takeIf { Ui.iconFamily != null }
        val l = label ?: if (ic == null) icon else null
        var x = if (l != null) 15 else 0
        if (ic != null) {
            g.font = Ui.icons(if (primary) (size * 0.42).toInt() else if (l != null) 15 else (size * 0.40).toInt())
            val fm = g.fontMetrics
            val iw = fm.stringWidth(ic)
            val ix = if (l == null) (width - iw) / 2 else x
            g.drawString(ic, ix, (height - fm.height) / 2 + fm.ascent)
            x += iw + 8
        }
        if (l != null) {
            g.font = Ui.font(14, accent)
            val fm = g.fontMetrics
            val lx = if (ic == null && label == null) (width - fm.stringWidth(l)) / 2 else x
            g.drawString(l, lx, (height - fm.height) / 2 + fm.ascent)
        }
    }
}

/** Thin progress bar: played part, buffered part ahead, a knob on hover; click or drag to seek. */
class SeekBar(private val onSeek: (Float) -> Unit) : JComponent() {
    var played = 0f; var buffered = 0f
    var hoverText: ((Float) -> String)? = null
    private var hoverX = -1
    private var dragging = false

    init {
        preferredSize = Dimension(100, 22)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        val m = object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) { hoverX = e.x; toolTipText = hoverText?.invoke(frac(e.x)); repaint() }
            override fun mouseExited(e: MouseEvent) { hoverX = -1; repaint() }
            override fun mousePressed(e: MouseEvent) { dragging = true; played = frac(e.x); repaint() }
            override fun mouseDragged(e: MouseEvent) { hoverX = e.x; played = frac(e.x); repaint() }
            override fun mouseReleased(e: MouseEvent) { if (dragging) { dragging = false; onSeek(frac(e.x)) } }
        }
        addMouseListener(m); addMouseMotionListener(m)
    }

    val isDragging get() = dragging
    private fun frac(x: Int) = ((x - 8f) / (width - 16f)).coerceIn(0f, 1f)

    override fun paintComponent(g0: Graphics) {
        val g = Ui.smooth(g0)
        val big = hoverX >= 0 || dragging
        val th = if (big) 6.0 else 4.0
        val y = (height - th) / 2
        val w = width - 16.0
        g.color = Color(255, 255, 255, 45); g.fill(RoundRectangle2D.Double(8.0, y, w, th, th, th))
        g.color = Color(255, 255, 255, 90); g.fill(RoundRectangle2D.Double(8.0, y, w * buffered.coerceIn(played, 1f), th, th, th))
        g.color = Color.WHITE; g.fill(RoundRectangle2D.Double(8.0, y, w * played, th, th, th))
        if (big) { val kx = 8 + w * played; g.fill(Ellipse2D.Double(kx - 7, height / 2.0 - 7, 14.0, 14.0)) }
        if (hoverX >= 0 && !dragging) { g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f); g.fillRect(hoverX, y.toInt(), 2, th.toInt()) }
    }
}

/** Small horizontal volume slider (0..100). */
class VolumeBar(private val onChange: (Int) -> Unit) : JComponent() {
    var value = 100
    init {
        preferredSize = Dimension(90, 22); maximumSize = preferredSize
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        val m = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = set(e.x)
            override fun mouseDragged(e: MouseEvent) = set(e.x)
        }
        addMouseListener(m); addMouseMotionListener(m)
    }
    private fun set(x: Int) { value = ((x - 4) * 100 / (width - 8)).coerceIn(0, 100); onChange(value); repaint() }
    override fun paintComponent(g0: Graphics) {
        val g = Ui.smooth(g0)
        val w = width - 8.0; val y = height / 2.0 - 2
        g.color = Color(255, 255, 255, 45); g.fill(RoundRectangle2D.Double(4.0, y, w, 4.0, 4.0, 4.0))
        g.color = Color.WHITE; g.fill(RoundRectangle2D.Double(4.0, y, w * value / 100, 4.0, 4.0, 4.0))
        g.fill(Ellipse2D.Double(4 + w * value / 100 - 6, height / 2.0 - 6, 12.0, 12.0))
        g.stroke = BasicStroke(1f)
    }
}

/**
 * Where a series' intro ends and how long its credits last, learned from the viewer (same rules as the TV app):
 * one correction serves every following episode.
 */
object Markers {
    private val prefs = Preferences.userRoot().node("lumentv/markers")
    const val DEFAULT_SKIP = 85_000L
    const val DEFAULT_INTRO_WINDOW = 6 * 60_000L
    fun introEnd(series: String) = prefs.getLong("intro:${series.hashCode()}", 0)
    fun setIntroEnd(series: String, ms: Long) { if (ms in 10_000..600_000) prefs.putLong("intro:${series.hashCode()}", ms) }
    fun creditsLength(series: String) = prefs.getLong("credits:${series.hashCode()}", 0)
    fun setCreditsLength(series: String, ms: Long) { if (ms in 10_000..360_000) prefs.putLong("credits:${series.hashCode()}", ms) }
    fun defaultCredits(durationMs: Long) = (durationMs * 0.025).toLong().coerceIn(30_000L, 90_000L)
}
