package com.lumentv.desktop

import com.sun.jna.NativeLibrary
import uk.co.caprica.vlcj.binding.support.runtime.RuntimeUtil
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent
import uk.co.caprica.vlcj.player.embedded.fullscreen.adaptive.AdaptiveFullScreenStrategy
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JSlider
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.Timer

/** The embedded VLC engine (resources\windows\vlc), loaded once. */
object Vlc {
    val dir: File by lazy {
        val res = System.getProperty("compose.application.resources.dir")?.let { File(it, "vlc") }
        listOfNotNull(res, File("resources/windows/vlc"), File("desktop/resources/windows/vlc")).first { File(it, "libvlc.dll").exists() }
    }

    val factory: MediaPlayerFactory by lazy {
        NativeLibrary.addSearchPath(RuntimeUtil.getLibVlcCoreLibraryName(), dir.absolutePath)
        NativeLibrary.addSearchPath(RuntimeUtil.getLibVlcLibraryName(), dir.absolutePath)
        MediaPlayerFactory("--no-video-title-show", "--quiet", "--network-caching=2000", "--live-caching=2000", "--no-snapshot-preview")
    }
}

/**
 * One player window at a time; opening something else while it is open replaces what plays.
 * Keys: Espace pause · ←/→ 10 s · ↑/↓ volume · F ou double-clic plein écran · Échap quitter le plein écran / fermer · N suivant.
 */
class PlayerWindow private constructor(private val state: AppState) {
    private val frame = JFrame("Lumen TV")
    private val component = EmbeddedMediaPlayerComponent(Vlc.factory, null, AdaptiveFullScreenStrategy(frame), null, null)
    private val player get() = component.mediaPlayer()
    private val dimColor = Color(0x9A, 0xA1, 0xAE)
    private val errorColor = Color(0xFF, 0x8A, 0x80)
    private val title = JLabel()
    private val time = JLabel("")
    private val message = JLabel("")
    private val slider = JSlider(0, 1000, 0)
    private val skipIntro = button("⏭ Passer l'intro") { player.controls().skipTime(85_000) }
    private val nextEp = button("Épisode suivant ▶") { playNext() }
    private var item: PlayItem? = null
    private var dragging = false
    private var lastSaved = 0L

    init {
        frame.iconImage = javax.imageio.ImageIO.read(PlayerWindow::class.java.getResource("/icon.png"))
        frame.defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
        frame.size = Dimension(1280, 760)
        frame.setLocationRelativeTo(null)
        frame.contentPane.background = Color.BLACK
        frame.layout = BorderLayout()
        frame.add(component, BorderLayout.CENTER)
        frame.add(controls(), BorderLayout.SOUTH)
        frame.addWindowListener(object : WindowAdapter() { override fun windowClosing(e: WindowEvent) = close() })
        component.videoSurfaceComponent().addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) { if (e.clickCount == 2) toggleFullScreen() else togglePause() }
        })
        keys(frame.rootPane)
        player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun finished(mediaPlayer: MediaPlayer) = SwingUtilities.invokeLater { onFinished() }
            override fun error(mediaPlayer: MediaPlayer) = SwingUtilities.invokeLater {
                message.foreground = errorColor
                message.text = "Lecture impossible : le flux ne répond pas ou n'est pas pris en charge."
            }
            override fun playing(mediaPlayer: MediaPlayer) = SwingUtilities.invokeLater { message.text = ""; applyLanguages() }
        })
        Timer(500) { tick() }.start()
    }

    fun play(it: PlayItem) {
        saveNow()
        item = it
        lastSaved = System.currentTimeMillis()
        frame.title = "Lumen TV — " + it.title + (it.subtitle?.let { s -> " · $s" } ?: "")
        title.text = it.title + (it.subtitle?.let { s -> "  ·  $s" } ?: "")
        message.foreground = dimColor; message.text = "Chargement…"
        slider.isVisible = !it.live
        skipIntro.isVisible = it.kind == Kind.EPISODE
        nextEp.isVisible = it.next != null
        val opts = buildList {
            if (it.userAgent.isNotBlank()) add(":http-user-agent=${it.userAgent}")
            if (it.startMs > 5_000) add(":start-time=${it.startMs / 1000}")
        }
        // VLC draws into the window: it must be on screen before playback starts.
        frame.isVisible = true
        frame.toFront()
        player.media().play(it.url, *opts.toTypedArray())
    }

    private fun controls(): JComponent {
        val dark = Color(0x14, 0x17, 0x1E)
        val p = JPanel(BorderLayout()).apply { background = dark; border = BorderFactory.createEmptyBorder(6, 12, 8, 12) }
        slider.background = dark
        slider.addChangeListener { if (slider.valueIsAdjusting) dragging = true else if (dragging) { dragging = false; seekToSlider() } }
        listOf(title, time, message).forEach { it.foreground = Color(0xE6, 0xE8, 0xEE); it.font = Font("Segoe UI", Font.PLAIN, 14) }
        message.foreground = dimColor
        title.font = Font("Segoe UI", Font.BOLD, 15)
        val row = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply { background = dark }
        row.add(button("Pause / Lecture") { togglePause() }); row.add(button("−10 s") { player.controls().skipTime(-10_000) }); row.add(button("+30 s") { player.controls().skipTime(30_000) })
        row.add(time); row.add(skipIntro); row.add(nextEp)
        row.add(button("Audio") { tracksMenu(it, audio = true) }); row.add(button("Sous-titres") { tracksMenu(it, audio = false) })
        row.add(button("Plein écran") { toggleFullScreen() })
        row.add(message)
        p.add(title, BorderLayout.NORTH); p.add(slider, BorderLayout.CENTER); p.add(row, BorderLayout.SOUTH)
        return p
    }

    private fun button(text: String, action: (JButton) -> Unit): JButton = JButton(text).apply {
        isFocusable = false
        background = Color(0x24, 0x29, 0x33); foreground = Color.WHITE; font = Font("Segoe UI", Font.PLAIN, 13)
        border = BorderFactory.createEmptyBorder(6, 12, 6, 12); cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addActionListener { _ -> action(this) }
    }

    private fun keys(root: JComponent) {
        fun bind(key: Int, name: String, block: () -> Unit) {
            root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key, 0), name)
            root.actionMap.put(name, object : AbstractAction() { override fun actionPerformed(e: ActionEvent) = block() })
        }
        bind(KeyEvent.VK_SPACE, "pause") { togglePause() }
        bind(KeyEvent.VK_LEFT, "back") { if (item?.live == false) player.controls().skipTime(-10_000) }
        bind(KeyEvent.VK_RIGHT, "fwd") { if (item?.live == false) player.controls().skipTime(10_000) }
        bind(KeyEvent.VK_UP, "volup") { player.audio().setVolume((player.audio().volume() + 10).coerceAtMost(200)) }
        bind(KeyEvent.VK_DOWN, "voldown") { player.audio().setVolume((player.audio().volume() - 10).coerceAtLeast(0)) }
        bind(KeyEvent.VK_F, "full") { toggleFullScreen() }
        bind(KeyEvent.VK_F11, "full2") { toggleFullScreen() }
        bind(KeyEvent.VK_N, "next") { playNext() }
        bind(KeyEvent.VK_ESCAPE, "esc") { if (player.fullScreen().isFullScreen) player.fullScreen().set(false) else close() }
    }

    private fun togglePause() = player.controls().pause()
    private fun toggleFullScreen() = player.fullScreen().toggle()

    private fun seekToSlider() {
        val len = player.status().length()
        if (len > 0) player.controls().setTime(len * slider.value / 1000)
    }

    private fun tick() {
        val it = item ?: return
        if (!frame.isVisible) return
        val pos = player.status().time(); val len = player.status().length()
        if (!it.live && len > 0) {
            if (!dragging) slider.value = (pos * 1000 / len).toInt()
            time.text = "${fmt(pos)} / ${fmt(len)}"
            nextEp.text = if (it.next != null && len - pos in 1..90_000) "Épisode suivant dans ${(len - pos) / 1000} s ▶" else "Épisode suivant ▶"
            skipIntro.isVisible = it.kind == Kind.EPISODE && pos in 3_000..360_000
        } else time.text = if (it.live) "En direct" else ""
        if (System.currentTimeMillis() - lastSaved > 15_000) saveNow()
    }

    private fun saveNow() {
        val it = item ?: return
        val pos = player.status().time(); val len = player.status().length()
        if (it.live || pos > 0) state.saveProgress(it, pos.coerceAtLeast(0), len.coerceAtLeast(0))
        lastSaved = System.currentTimeMillis()
    }

    private fun onFinished() {
        val it = item ?: return
        if (!it.live) state.saveProgress(it, 0, player.status().length())
        if (it.next != null && state.setting("auto_next", "true") != "false") playNext() else if (!it.live) close()
    }

    private fun playNext() { item?.next?.invoke()?.let { play(it) } }

    private fun applyLanguages() {
        val audio = state.setting("audio_lang"); val subs = state.setting("sub_lang"); val subsOn = state.setting("subs_on") == "true"
        if (audio.isNotEmpty()) player.audio().trackDescriptions().firstOrNull { matches(it.description(), audio) }?.let { player.audio().setTrack(it.id()) }
        if (subsOn && subs.isNotEmpty()) player.subpictures().trackDescriptions().firstOrNull { matches(it.description(), subs) }?.let { player.subpictures().setTrack(it.id()) }
        else if (!subsOn) player.subpictures().setTrack(-1)
    }

    private fun matches(description: String?, lang: String): Boolean {
        val d = description?.lowercase() ?: return false
        val names = mapOf("fr" to listOf("fr", "fre", "fra", "français", "french"), "en" to listOf("en", "eng", "english", "anglais"),
            "es" to listOf("es", "spa", "español", "spanish"), "de" to listOf("de", "ger", "deu", "deutsch", "german"), "it" to listOf("it", "ita", "italian"))
        return names[lang].orEmpty().any { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(d) }
    }

    private fun tracksMenu(anchor: JButton, audio: Boolean) {
        val menu = JPopupMenu()
        val tracks = if (audio) player.audio().trackDescriptions() else player.subpictures().trackDescriptions()
        val current = if (audio) player.audio().track() else player.subpictures().track()
        if (tracks.isEmpty()) menu.add("Aucune piste").isEnabled = false
        tracks.forEach { t ->
            menu.add((if (t.id() == current) "● " else "   ") + (t.description() ?: "Piste ${t.id()}")).addActionListener {
                if (audio) player.audio().setTrack(t.id()) else player.subpictures().setTrack(t.id())
            }
        }
        menu.show(anchor, 0, -menu.preferredSize.height)
    }

    private fun close() {
        saveNow()
        if (player.fullScreen().isFullScreen) player.fullScreen().set(false)
        player.controls().stop()
        item = null
        frame.isVisible = false
    }

    companion object {
        private var instance: PlayerWindow? = null
        fun open(state: AppState, item: PlayItem) {
            val w = instance ?: PlayerWindow(state).also { instance = it }
            w.play(item)
        }
        fun release() { instance?.let { it.saveNow(); it.component.release() }; instance = null }
        private fun fmt(ms: Long): String { val s = ms / 1000; return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }
    }
}
