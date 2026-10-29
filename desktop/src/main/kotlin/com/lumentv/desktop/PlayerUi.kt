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
    /** Discreet: dark glass with soft text, full colour only under the mouse (skip intro, next episode). */
    private val subtle: Boolean = false,
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
            subtle -> if (hover) Ui.accent else Color(10, 12, 18, 110)
            primary -> if (hover) Color(255, 255, 255, 235) else Color.WHITE
            accent -> if (hover) Ui.accent.brighter() else Ui.accent
            active -> Color(255, 255, 255, 70)
            hover -> Ui.bubbleHover
            else -> Ui.bubble
        }
        if (label == null) g.fill(Ellipse2D.Double(0.0, 0.0, w - 1, h - 1)) else g.fill(RoundRectangle2D.Double(0.0, 0.0, w - 1, h - 1, h, h))
        val fg = if (primary) Ui.bg else if (subtle && !hover) Color(255, 255, 255, 170) else Ui.text
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

/** Transparent panel that shades the video from the top or from the bottom, so white controls stay readable. */
class ShadePanel(private val fromTop: Boolean) : javax.swing.JPanel() {
    init { isOpaque = false }
    override fun paintComponent(g0: java.awt.Graphics) {
        val g = g0 as Graphics2D
        val dark = Color(0, 0, 0, 170); val clear = Color(0, 0, 0, 0)
        g.paint = if (fromTop) java.awt.GradientPaint(0f, 0f, dark, 0f, height.toFloat(), clear) else java.awt.GradientPaint(0f, 0f, clear, 0f, height * 0.45f, dark)
        g.fillRect(0, 0, width, height)
    }
}

/**
 * The audio / subtitle picker: a dark panel with a title and one row per track, the current one ticked.
 * VLC's raw names are made readable: "Track 3 - [English]" → "Anglais", "VFF Forced - [French]" → "Français · VFF forcés".
 */
object TrackMenu {
    private val panelBg = Color(0x16, 0x19, 0x22)
    private const val CHECK = ""

    private var close: (() -> Unit)? = null

    /**
     * Opens above [anchor], drawn in the controls' own window (its popup layer) like the buttons: no window of its own,
     * as a Swing popup over the video could get a white frame. Closes on a pick, a click elsewhere, Escape, or when the
     * controls hide.
     */
    fun show(anchor: JComponent, title: String, tracks: List<Pair<Int, String?>>, current: Int, audio: Boolean, onPick: (Int) -> Unit) {
        close?.invoke()
        val owner = javax.swing.SwingUtilities.getWindowAncestor(anchor)
        val layer = javax.swing.SwingUtilities.getRootPane(anchor)?.layeredPane ?: return
        val list = javax.swing.JPanel().apply {
            isOpaque = true; background = panelBg; layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
            border = javax.swing.BorderFactory.createCompoundBorder(javax.swing.BorderFactory.createLineBorder(Color(0x2C, 0x31, 0x3D)),
                javax.swing.BorderFactory.createEmptyBorder(8, 6, 8, 6))
        }
        list.add(javax.swing.JLabel(title.uppercase()).apply {
            font = Ui.font(11, bold = true); foreground = Ui.dim; border = javax.swing.BorderFactory.createEmptyBorder(2, 12, 8, 12)
            alignmentX = 0f
        })
        if (tracks.isEmpty()) list.add(javax.swing.JLabel("Aucune piste").apply { font = Ui.font(14); foreground = Ui.dim; border = javax.swing.BorderFactory.createEmptyBorder(6, 12, 6, 12) })
        tracks.forEach { (id, desc) ->
            val (main, detail) = label(id, desc, audio)
            list.add(Row(main, detail, id == current) { close?.invoke(); onPick(id) }.apply { alignmentX = 0f })
        }
        val size = list.preferredSize
        val at = javax.swing.SwingUtilities.convertPoint(anchor, 0, 0, layer)
        list.setBounds((at.x + anchor.width - size.width).coerceAtLeast(8), (at.y - size.height - 8).coerceAtLeast(8), size.width, size.height)
        layer.add(list, javax.swing.JLayeredPane.POPUP_LAYER)
        layer.revalidate(); layer.repaint()

        val outside = java.awt.event.AWTEventListener { e ->
            when {
                e is MouseEvent && e.id == MouseEvent.MOUSE_PRESSED && !javax.swing.SwingUtilities.isDescendingFrom(e.component, list)
                    && e.component !== anchor -> close?.invoke()
                e is java.awt.event.KeyEvent && e.id == java.awt.event.KeyEvent.KEY_PRESSED && e.keyCode == java.awt.event.KeyEvent.VK_ESCAPE -> close?.invoke()
            }
        }
        val hidden = object : java.awt.event.ComponentAdapter() { override fun componentHidden(e: java.awt.event.ComponentEvent) { close?.invoke() } }
        owner?.addComponentListener(hidden)
        java.awt.Toolkit.getDefaultToolkit().addAWTEventListener(outside, java.awt.AWTEvent.MOUSE_EVENT_MASK or java.awt.AWTEvent.KEY_EVENT_MASK)
        close = {
            close = null
            java.awt.Toolkit.getDefaultToolkit().removeAWTEventListener(outside)
            owner?.removeComponentListener(hidden)
            layer.remove(list); layer.repaint()
        }
    }

    /** The menu button clicked again while open: closes it instead of reopening. */
    val isOpen get() = close != null
    fun dismiss() { close?.invoke() }

    private val languages = mapOf("french" to "Français", "english" to "Anglais", "spanish" to "Espagnol", "german" to "Allemand", "italian" to "Italien",
        "portuguese" to "Portugais", "arabic" to "Arabe", "dutch" to "Néerlandais", "japanese" to "Japonais", "korean" to "Coréen", "russian" to "Russe",
        "turkish" to "Turc", "polish" to "Polonais", "chinese" to "Chinois", "fre" to "Français", "fra" to "Français", "eng" to "Anglais")

    internal fun label(id: Int, desc: String?, audio: Boolean): Pair<String, String?> {
        if (id < 0 || desc.isNullOrBlank() || desc.equals("Disable", true)) return (if (audio) "Aucun son" else "Désactivés") to null
        val lang = Regex("""\[([^\]]+)]""").find(desc)?.groupValues?.get(1)?.trim()
        val name = desc.replace(Regex("""\s*-?\s*\[[^\]]*]"""), "").trim()
            .takeUnless { Regex("""(?i)^(track|piste)\s*\d+$""").matches(it) || it.isBlank() }
            ?.replace(Regex("""(?i)\bforced\b"""), "forcés")?.replace(Regex("""(?i)\bSDH\b"""), "malentendants")
        val language = lang?.let { languages[it.lowercase()] ?: it.replaceFirstChar { c -> c.uppercase() } }
        return when {
            language != null -> language to name
            name != null -> name to null
            else -> "Piste $id" to null
        }
    }

    private class Row(val main: String, val detail: String?, val selected: Boolean, val onClick: () -> Unit) : JComponent() {
        private var hover = false
        init {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) { hover = true; repaint() }
                override fun mouseExited(e: MouseEvent) { hover = false; repaint() }
                override fun mouseReleased(e: MouseEvent) { onClick() }
            })
        }
        override fun getPreferredSize(): Dimension {
            val w = getFontMetrics(Ui.font(14, true)).stringWidth(main) + (detail?.let { getFontMetrics(Ui.font(13)).stringWidth("  ·  $it") } ?: 0)
            return Dimension(maxOf(240, w + 64), 38)
        }
        override fun getMaximumSize() = Dimension(Int.MAX_VALUE, 38)
        override fun paintComponent(g0: Graphics) {
            val g = Ui.smooth(g0)
            if (hover || selected) { g.color = if (hover) Color(255, 255, 255, 30) else Color(79, 140, 255, 40); g.fill(RoundRectangle2D.Double(0.0, 1.0, width - 1.0, height - 2.0, 10.0, 10.0)) }
            if (selected) {
                g.font = if (Ui.iconFamily != null) Ui.icons(13) else Ui.font(13, true); g.color = Ui.accent
                val fm = g.fontMetrics; g.drawString(if (Ui.iconFamily != null) CHECK else "✓", 14, (height - fm.height) / 2 + fm.ascent)
            }
            g.font = Ui.font(14, selected); g.color = Ui.text
            var fm = g.fontMetrics
            val y = (height - fm.height) / 2 + fm.ascent
            g.drawString(main, 40, y)
            detail?.let { d -> val x = 40 + fm.stringWidth(main); g.font = Ui.font(13); g.color = Ui.dim; g.drawString("  ·  $d", x, y) }
        }
    }
}
