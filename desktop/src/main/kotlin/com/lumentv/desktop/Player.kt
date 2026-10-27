package com.lumentv.desktop

import com.lumentv.data.Segments
import com.lumentv.data.xtream.Presence
import com.lumentv.data.xtream.ScreenGuard
import com.lumentv.data.xtream.XtreamApi
import com.lumentv.data.xtream.XtreamCredentials
import com.sun.jna.NativeLibrary
import kotlinx.coroutines.runBlocking
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
import java.awt.Point
import java.awt.Toolkit
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.image.BufferedImage
import java.io.File
import javax.swing.AbstractAction
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.border.EmptyBorder

/** The embedded VLC engine (resources\windows\vlc), loaded once. */
object Vlc {
    val dir: File by lazy {
        val res = System.getProperty("compose.application.resources.dir")?.let { File(it, "vlc") }
        listOfNotNull(res, File("resources/windows/vlc"), File("desktop/resources/windows/vlc")).first { File(it, "libvlc.dll").exists() }
    }

    val factory: MediaPlayerFactory by lazy {
        NativeLibrary.addSearchPath(RuntimeUtil.getLibVlcCoreLibraryName(), dir.absolutePath)
        NativeLibrary.addSearchPath(RuntimeUtil.getLibVlcLibraryName(), dir.absolutePath)
        // Audio: DirectSound by default (WASAPI crackles with some sound cards / mixers such as the GoXLR) and the
        // high-quality resampler, which VLC also uses to follow the clock of live streams.
        MediaPlayerFactory("--no-video-title-show", "--quiet", "--network-caching=3000", "--live-caching=3000", "--no-snapshot-preview",
            "--avcodec-hw=any", "--aout=${System.getenv("LUMEN_AOUT") ?: Store.audioOut}", "--audio-resampler=samplerate")
    }
}

/**
 * One player window at a time; opening something else while it is open replaces what plays.
 * Keys: Espace pause · ←/→ 10 s · ↑/↓ volume · F ou double-clic plein écran · M muet · N épisode suivant · Échap.
 * Films and episodes read several minutes ahead (VLC prefetch), so a slow server does not stall the picture.
 */
class PlayerWindow private constructor(private val state: AppState) {
    private val frame = JFrame("Lumen TV")
    private val component = EmbeddedMediaPlayerComponent(Vlc.factory, null, AdaptiveFullScreenStrategy(frame), null, null)
    private val player get() = component.mediaPlayer()
    private var item: PlayItem? = null
    private var lastSaved = 0L
    private var lastBeat = 0L
    private var presenceKey: String? = null
    private var lastMove = System.currentTimeMillis()
    private var nextDismissed = false
    private var skipped = false
    private var learnIntroAt = 0L
    private var speedIndex = 2
    private var overlayOn = false
    /** A live channel that was paused plays behind live until "Revenir au direct". */
    private var behindLive = false
    /** Recap / intro / credits of the current episode (TheIntroDB), or null: then the learned markers are used. */
    @Volatile private var segments: Segments? = null
    private var skippedRecap = false
    private val speeds = listOf(0.75f, 0.9f, 1f, 1.25f, 1.5f, 2f)

    // ---- Widgets ----------------------------------------------------------------------------------------------
    private val title = JLabel().apply { font = Ui.font(16, true); foreground = Ui.text }
    private val subtitle = JLabel().apply { font = Ui.font(13); foreground = Ui.dim }
    private val badge = JLabel().apply { font = Ui.font(12, true); foreground = Ui.dim }
    private val message = JLabel("").apply { font = Ui.font(13); foreground = Ui.dim }
    private val timeNow = JLabel("").apply { font = Ui.font(12); foreground = Ui.dim }
    private val timeEnd = JLabel("").apply { font = Ui.font(12); foreground = Ui.dim }
    private val seek = SeekBar { f -> val len = player.status().length(); if (len > 0) player.controls().setTime((len * f).toLong()) }
    private val playPause = Bubble(Ui.PAUSE, tooltip = "Pause / lecture (Espace)", size = 56, primary = true) { togglePause() }
    private val fullBtn = Bubble(Ui.FULL, tooltip = "Plein écran (F)") { toggleFullScreen() }
    private val muteBtn = Bubble(Ui.VOLUME, tooltip = "Muet (M)", size = 36) { toggleMute() }
    private val volume = VolumeBar { v -> player.audio().setVolume(v); muteBtn.set(icon = if (v == 0) Ui.MUTE else Ui.VOLUME) }
    private val speedBtn = Bubble(Ui.SPEED, "1.0x", "Vitesse de lecture") { cycleSpeed() }
    private val back10 = Bubble(Ui.BACK10, tooltip = "Reculer de 10 s (←)") { skip(-10_000) }
    private val fwd30 = Bubble(Ui.FWD30, tooltip = "Avancer de 30 s") { skip(30_000) }
    private val restart = Bubble(Ui.RESTART, tooltip = "Reprendre au début") { player.controls().setTime(0) }
    private val nextBtn = Bubble(Ui.NEXT, tooltip = "Épisode suivant (N)") { playNext() }
    private val skipIntro = Bubble(label = "Passer le récap / l'intro", subtle = true, size = 38) { skipIntro() }
    private val nextCard = Bubble(Ui.NEXT, "Épisode suivant", subtle = true, size = 38) { playNext() }
    private val backToLive = Bubble(label = "Revenir au direct", subtle = true, size = 38) { goLive() }
    private val nextClose = Bubble(Ui.CLOSE, tooltip = "Finir celui-ci", subtle = true, size = 38) { nextDismissed = true; refreshActions() }
    private val top = ShadePanel(fromTop = true).apply { layout = BorderLayout() }
    private val bottom = ShadePanel(fromTop = false)
    private val actions = JPanel(FlowLayout(FlowLayout.RIGHT, 10, 0))
    private val seekRow = JPanel(BorderLayout(10, 0))
    /** Transparent window kept by VLC exactly over the picture: the controls float, the video never resizes. */
    private val overlay = javax.swing.JWindow(frame)

    init {
        frame.iconImage = javax.imageio.ImageIO.read(PlayerWindow::class.java.getResource("/icon.png"))
        frame.defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
        frame.size = Dimension(1280, 800)
        frame.minimumSize = Dimension(720, 480)
        frame.setLocationRelativeTo(null)
        frame.contentPane.background = Ui.bg
        frame.layout = BorderLayout()
        frame.add(component, BorderLayout.CENTER)
        overlay.background = Color(0, 0, 0, 0)
        val root = JPanel(BorderLayout()).apply { isOpaque = false }
        root.add(buildTop(), BorderLayout.NORTH)
        val south = JPanel(BorderLayout()).apply { isOpaque = false }
        south.add(actions, BorderLayout.NORTH); south.add(buildBottom(), BorderLayout.CENTER)
        root.add(south, BorderLayout.SOUTH)
        overlay.contentPane = root
        val awake = object : MouseAdapter() { override fun mouseMoved(e: MouseEvent) = wake() }
        listOf(root, top, bottom).forEach { it.addMouseMotionListener(awake) }
        keys(overlay.rootPane)
        frame.addWindowListener(object : WindowAdapter() { override fun windowClosing(e: WindowEvent) = close() })
        val surface = component.videoSurfaceComponent()
        surface.background = Color.BLACK
        surface.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { if (e.clickCount == 2) toggleFullScreen() else if (e.clickCount == 1) togglePause() }
        })
        surface.addMouseMotionListener(object : MouseAdapter() { override fun mouseMoved(e: MouseEvent) = wake() })
        keys(frame.rootPane)
        player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun finished(mediaPlayer: MediaPlayer) = SwingUtilities.invokeLater { onFinished() }
            override fun error(mediaPlayer: MediaPlayer) = SwingUtilities.invokeLater {
                message.foreground = Ui.danger
                message.text = "Lecture impossible : le flux ne répond pas, ou l'abonnement est déjà utilisé sur un autre écran."
            }
            override fun videoOutput(mediaPlayer: MediaPlayer, newCount: Int) { if (newCount > 0) trace("image") }
            override fun playing(mediaPlayer: MediaPlayer) = SwingUtilities.invokeLater { message.text = ""; playPause.set(icon = Ui.PAUSE); applyLanguages(); describeVideo() }
            override fun paused(mediaPlayer: MediaPlayer) = SwingUtilities.invokeLater { playPause.set(icon = Ui.PLAY) }
            override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) = SwingUtilities.invokeLater {
                if (message.foreground == Ui.danger) return@invokeLater
                message.text = if (newCache < 100f) "Mise en mémoire… ${newCache.toInt()} %" else ""
            }
        })
        Timer(500) { tick() }.start()
    }

    private fun dark(p: JPanel) = p.apply { isOpaque = false }

    private fun buildTop(): JComponent {
        top.border = EmptyBorder(12, 14, 40, 14)
        val left = dark(JPanel(FlowLayout(FlowLayout.LEFT, 12, 0)))
        left.add(Bubble(Ui.CHEVRON_LEFT, "Retour", size = 36) { close() })
        val texts = dark(JPanel()).apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); add(title); add(subtitle) }
        left.add(texts)
        val right = dark(JPanel(FlowLayout(FlowLayout.RIGHT, 10, 0)))
        right.add(badge); right.add(fullBtn)
        top.add(left, BorderLayout.WEST); top.add(right, BorderLayout.EAST)
        return top
    }

    private fun buildBottom(): JComponent {
        bottom.layout = BoxLayout(bottom, BoxLayout.Y_AXIS)
        bottom.border = EmptyBorder(50, 16, 14, 16)
        // Skip intro / next episode stay on screen (discreet) even when the bars are hidden.
        dark(actions).border = EmptyBorder(0, 0, 8, 18)
        actions.add(message); actions.add(backToLive); actions.add(skipIntro); actions.add(nextClose); actions.add(nextCard)
        dark(seekRow).border = EmptyBorder(6, 0, 4, 0)
        seekRow.add(timeNow, BorderLayout.WEST); seekRow.add(seek, BorderLayout.CENTER); seekRow.add(timeEnd, BorderLayout.EAST)
        seek.hoverText = { f -> val len = player.status().length(); if (len > 0) fmt((len * f).toLong()) else "" }
        val controls = dark(JPanel(BorderLayout()))
        val center = dark(JPanel(FlowLayout(FlowLayout.CENTER, 12, 0)))
        listOf(restart, back10, playPause, fwd30, nextBtn).forEach { center.add(it) }
        val left = dark(JPanel(FlowLayout(FlowLayout.LEFT, 10, 0))); left.add(speedBtn)
        val right = dark(JPanel(FlowLayout(FlowLayout.RIGHT, 10, 0)))
        right.add(Bubble(Ui.AUDIO, tooltip = "Piste audio") { tracksMenu(it, audio = true) })
        right.add(Bubble(Ui.SUBS, tooltip = "Sous-titres") { tracksMenu(it, audio = false) })
        right.add(muteBtn); right.add(volume)
        // Same width on both sides, so play / pause sits exactly in the middle.
        left.preferredSize = Dimension(right.preferredSize.width, right.preferredSize.height)
        controls.add(left, BorderLayout.WEST); controls.add(center, BorderLayout.CENTER); controls.add(right, BorderLayout.EAST)
        bottom.add(seekRow); bottom.add(controls)
        return bottom
    }

    fun play(it: PlayItem) {
        saveNow()
        if (item == null) { ScreenGuard.started(); presenceKey = state.presenceKey(); lastBeat = 0 }
        item = it
        lastSaved = System.currentTimeMillis()
        nextDismissed = false; skipped = false; learnIntroAt = 0; behindLive = false; skippedRecap = false
        segments = null
        if (it.kind == Kind.EPISODE) trace("episode S${it.season}E${it.episode} tmdb=${it.tmdb}")
        if (it.kind == Kind.EPISODE && it.tmdb != null) {
            val wanted = it
            Thread { val seg = runBlocking { state.library.segments(wanted.tmdb!!, wanted.season, wanted.episode) }
                trace("segments S${wanted.season}E${wanted.episode} tmdb=${wanted.tmdb} -> $seg")
                SwingUtilities.invokeLater { if (item === wanted) { segments = seg; refreshActions() } } }.start()
        }
        frame.title = "Lumen TV — " + it.title + (it.subtitle?.let { s -> " · $s" } ?: "")
        title.text = it.title
        subtitle.text = it.subtitle ?: if (it.live) "En direct" else ""
        badge.text = ""
        message.foreground = Ui.dim; message.text = "Chargement…"
        seekRow.isVisible = !it.live
        listOf(restart, back10, fwd30, speedBtn).forEach { b -> b.isVisible = !it.live }
        nextBtn.isVisible = it.next != null
        refreshActions()
        val opts = buildList {
            if (it.userAgent.isNotBlank()) add(":http-user-agent=${it.userAgent}")
            if (it.startMs > 5_000) add(":start-time=${it.startMs / 1000}")
            if (!it.live && it.url.startsWith("http")) {
                // Read several minutes ahead: a slow or bursty server no longer stalls films and episodes.
                // Small start buffer for a quick start; the prefetch keeps reading minutes ahead in the background.
                add(":network-caching=1500"); add(":stream-filter=prefetch"); add(":prefetch-buffer-size=196608")
            }
        }
        // VLC draws into the window: it must be on screen before playback starts.
        frame.isVisible = true
        frame.toFront()
        if (!overlayOn) { player.overlay().set(overlay); player.overlay().enable(true); overlayOn = true }
        player.media().play(it.url, *opts.toTypedArray())
        player.controls().setRate(1f); speedIndex = 2; speedBtn.set(label = "1.0x")
        wake()
    }

    // ---- Actions ----------------------------------------------------------------------------------------------

    private fun keys(root: JComponent) {
        fun bind(key: Int, name: String, block: () -> Unit) {
            root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key, 0), name)
            root.actionMap.put(name, object : AbstractAction() { override fun actionPerformed(e: ActionEvent) { wake(); block() } })
        }
        bind(KeyEvent.VK_SPACE, "pause") { togglePause() }
        bind(KeyEvent.VK_LEFT, "back") { skip(-10_000) }
        bind(KeyEvent.VK_RIGHT, "fwd") { skip(10_000) }
        bind(KeyEvent.VK_UP, "volup") { setVolume(volume.value + 5) }
        bind(KeyEvent.VK_DOWN, "voldown") { setVolume(volume.value - 5) }
        bind(KeyEvent.VK_F, "full") { toggleFullScreen() }
        bind(KeyEvent.VK_F11, "full2") { toggleFullScreen() }
        bind(KeyEvent.VK_M, "mute") { toggleMute() }
        bind(KeyEvent.VK_N, "next") { playNext() }
        bind(KeyEvent.VK_ESCAPE, "esc") { if (player.fullScreen().isFullScreen) toggleFullScreen() else close() }
    }

    private fun skip(ms: Long) { if (item?.live == false) player.controls().skipTime(ms) }
    private fun togglePause() {
        if (item?.live == true && player.status().isPlaying) behindLive = true
        player.controls().pause()
        refreshActions()
    }

    /** Reopens the channel: back to what is on now. */
    private fun goLive() {
        val it = item ?: return
        behindLive = false
        player.media().play(it.url, *(if (it.userAgent.isNotBlank()) arrayOf(":http-user-agent=${it.userAgent}") else emptyArray()))
        refreshActions()
    }
    private fun setVolume(v: Int) { volume.value = v.coerceIn(0, 100); volume.repaint(); player.audio().setVolume(volume.value); muteBtn.set(icon = if (volume.value == 0) Ui.MUTE else Ui.VOLUME) }
    private fun toggleMute() { player.audio().mute(); muteBtn.set(icon = if (player.audio().isMute) Ui.MUTE else Ui.VOLUME) }
    private fun cycleSpeed() { speedIndex = (speedIndex + 1) % speeds.size; player.controls().setRate(speeds[speedIndex]); speedBtn.set(label = "${speeds[speedIndex]}x") }

    private fun toggleFullScreen() {
        player.fullScreen().toggle()
        fullBtn.set(icon = if (player.fullScreen().isFullScreen) Ui.UNFULL else Ui.FULL)
        wake()
    }

    /** Shows the bars again after a mouse move or a key; in full screen they hide after 3 s of calm. */
    private fun wake() {
        lastMove = System.currentTimeMillis()
        if (!top.isVisible) { top.isVisible = true; bottom.isVisible = true; component.videoSurfaceComponent().cursor = Cursor.getDefaultCursor(); overlay.repaint() }
    }

    private fun seriesKey() = item?.takeIf { it.kind == Kind.EPISODE }?.parentUid

    private fun skipIntro() {
        val seg = segments
        val pos = player.status().time()
        // Known segments: jump to the end of the recap or of the intro, whichever is playing.
        seg?.recap?.takeIf { pos in it }?.let { r -> skippedRecap = true; player.controls().setTime(r.last + 1); refreshActions(); return }
        seg?.intro?.takeIf { pos in (it.first - 3_000)..it.last }?.let { i -> skipped = true; player.controls().setTime(i.last + 1); refreshActions(); return }
        val key = seriesKey() ?: return
        skipped = true
        val end = Markers.introEnd(key)
        player.controls().setTime(if (end > 0) end else player.status().time() + Markers.DEFAULT_SKIP)
        // Whatever the viewer corrects with ←/→ in the next 15 s becomes this series' intro end.
        learnIntroAt = System.currentTimeMillis() + 15_000
        refreshActions()
    }

    private fun playNext() {
        val it = item ?: return
        val len = player.status().length(); val pos = player.status().time()
        seriesKey()?.let { k -> if (len > 0 && len - pos > 0) Markers.setCreditsLength(k, len - pos) }
        if (!it.live && len > 0) state.saveProgress(it, len, len)
        it.next?.invoke()?.let { n -> play(n) }
    }

    private fun refreshActions() {
        val it = item
        val key = seriesKey()
        val pos = player.status().time(); val len = player.status().length()
        val introEnd = key?.let { Markers.introEnd(it) } ?: 0
        val seg = segments
        if (seg != null && (seg.recap != null || seg.intro != null)) {
            // Two distinct buttons, each only while its part plays (a recap, a scene, then the intro is common).
            val inRecap = seg.recap?.let { pos in it && it.last - pos > 3_000 } == true && !skippedRecap
            val inIntro = seg.intro?.let { pos in (it.first - 3_000)..it.last && it.last - pos > 3_000 } == true && !skipped
            skipIntro.isVisible = inRecap || inIntro
            if (inRecap) skipIntro.set(label = "Passer le récap") else if (inIntro) skipIntro.set(label = "Passer l'intro")
        } else {
            skipIntro.isVisible = key != null && !skipped && pos in 3_000 until (if (introEnd > 0) introEnd - 2_000 else Markers.DEFAULT_INTRO_WINDOW)
            skipIntro.set(label = "Passer le récap / l'intro")
        }
        val credits = key?.let { Markers.creditsLength(it) }?.takeIf { it > 0 } ?: Markers.defaultCredits(len)
        val creditsStart = seg?.credits?.first?.takeIf { it in 60_000 until len }
        val showNext = it?.next != null && !nextDismissed && len > 120_000 &&
            (if (creditsStart != null) pos >= creditsStart && pos < len else len - pos in 1..credits)
        nextCard.isVisible = showNext; nextClose.isVisible = showNext
        backToLive.isVisible = it?.live == true && behindLive
        backToLive.set(label = if (player.status().isPlaying) "En différé · Revenir au direct" else "En pause · Revenir au direct")
        if (showNext) nextCard.set(label = "Épisode suivant dans ${(len - pos) / 1000} s")
    }

    private fun tick() {
        val it = item ?: return
        if (!frame.isVisible) return
        val pos = player.status().time(); val len = player.status().length()
        if (!it.live && len > 0) {
            if (!seek.isDragging) { seek.played = pos.toFloat() / len; seek.repaint() }
            timeNow.text = fmt(pos); timeEnd.text = fmt(len)
        }
        refreshActions()
        if (learnIntroAt in 1..System.currentTimeMillis()) { seriesKey()?.let { Markers.setIntroEnd(it, player.status().time() - 15_000) }; learnIntroAt = 0 }
        if (top.isVisible && System.currentTimeMillis() - lastMove > 3_000 && player.status().isPlaying) {
            top.isVisible = false; bottom.isVisible = false; overlay.repaint()
            component.videoSurfaceComponent().cursor = Toolkit.getDefaultToolkit().createCustomCursor(BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), Point(0, 0), "none")
        }
        if (System.currentTimeMillis() - lastSaved > 15_000) saveNow()
        if (System.currentTimeMillis() - lastBeat > Presence.BEAT_MS) {
            lastBeat = System.currentTimeMillis()
            presenceKey?.let { k -> Thread { runBlocking { state.presence.beat(k) } }.start() }
        }
    }

    /** "FHD · 24 FPS" in the corner, from what VLC decodes. */
    private fun describeVideo() {
        val d = player.video().videoDimension() ?: return
        val q = when { d.height >= 2000 -> "4K"; d.height >= 1000 -> "FHD"; d.height >= 700 -> "HD"; else -> "SD" }
        val fps = runCatching { player.media().info().videoTracks().firstOrNull()?.let { t -> if (t.frameRateBase() > 0) t.frameRate() / t.frameRateBase() else 0 } }.getOrNull() ?: 0
        badge.text = if (fps > 0) "$q · $fps FPS" else q
    }

    private fun saveNow() {
        val it = item ?: return
        val pos = player.status().time(); val len = player.status().length()
        if (it.live || pos > 0) state.saveProgress(it, pos.coerceAtLeast(0), len.coerceAtLeast(0))
        lastSaved = System.currentTimeMillis()
    }

    private fun onFinished() {
        val it = item ?: return
        if (!it.live) state.saveProgress(it, player.status().length(), player.status().length())
        if (it.next != null && state.setting("auto_next", "true") != "false") playNext() else if (!it.live) close()
    }

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

    private fun tracksMenu(anchor: JComponent, audio: Boolean) {
        val menu = JPopupMenu()
        val tracks = if (audio) player.audio().trackDescriptions() else player.subpictures().trackDescriptions()
        val current = if (audio) player.audio().track() else player.subpictures().track()
        if (tracks.isEmpty()) menu.add("Aucune piste").isEnabled = false
        tracks.forEach { t ->
            menu.add((if (t.id() == current) "● " else "   ") + (t.description() ?: "Piste ${t.id()}")).addActionListener {
                if (audio) player.audio().setTrack(t.id()) else player.subpictures().setTrack(t.id())
            }
        }
        menu.show(anchor, 0, -menu.preferredSize.height - 6)
    }

    private fun close() {
        saveNow()
        if (player.fullScreen().isFullScreen) player.fullScreen().set(false)
        player.controls().stop()
        if (item != null) { ScreenGuard.stopped(); presenceKey?.let { k -> Thread { runBlocking { state.presence.leave(k) } }.start() } }
        item = null
        if (overlayOn) { player.overlay().enable(false); overlayOn = false }
        frame.isVisible = false
    }

    companion object {
        private var instance: PlayerWindow? = null

        /** LUMEN_TRACE=<file>: logs how long each start takes (click → first image), for measurements. */
        private val traceFile = System.getenv("LUMEN_TRACE")?.let { java.io.File(it) }
        @Volatile private var openedAt = 0L
        @Volatile private var openedWhat = ""
        private fun trace(step: String) = traceFile?.appendText("$openedWhat $step ${System.currentTimeMillis() - openedAt} ms\n")

        fun open(state: AppState, item: PlayItem) {
            openedAt = System.currentTimeMillis(); openedWhat = item.kind
            if (instance?.item != null || item.url.startsWith("file:")) return show(state, item)
            val p = state.active ?: return show(state, item)
            val key = state.presenceKey()
            // Before taking a screen (off the UI thread, a few seconds at most): another Lumen TV device playing on
            // this subscription, or the provider saying every screen is in use.
            Thread {
                val others = key?.let { runBlocking { kotlinx.coroutines.withTimeoutOrNull(1_500) { state.presence.others(it) } } }.orEmpty()
                val warning = if (others.isNotEmpty()) Presence.warning(others) else if (p.type != "xtream" || ScreenGuard.ownStreamCounted()) null else runCatching {
                    val http = state.http.newBuilder().callTimeout(1500, java.util.concurrent.TimeUnit.MILLISECONDS).build()
                    ScreenGuard.warning(XtreamApi(http, XtreamCredentials(p.source, p.username, p.password), p.userAgent.ifBlank { null }).authenticate())
                }.getOrNull()
                SwingUtilities.invokeLater {
                    if (warning == null) return@invokeLater show(state, item)
                    val choice = JOptionPane.showOptionDialog(null, "<html><body style='width:360px'>$warning</body></html>", "Un autre écran regarde déjà",
                        JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, arrayOf("Lancer quand même", "Annuler"), "Annuler")
                    if (choice == 0) show(state, item)
                }
            }.start()
        }

        private fun show(state: AppState, item: PlayItem) {
            trace("vérification écran terminée")
            val w = instance ?: PlayerWindow(state).also { instance = it }
            w.play(item)
        }

        fun release() { instance?.let { it.close(); it.component.release() }; instance = null }
        fun fmt(ms: Long): String { val s = ms / 1000; return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }
    }
}
