@file:OptIn(UnstableApi::class)

package com.lumentv.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import com.lumentv.core.Redact
import com.lumentv.data.repo.StreamSpec
import com.lumentv.data.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import java.util.Locale

data class PlaybackState(
    val buffering: Boolean = true,
    val playing: Boolean = false,
    val ended: Boolean = false,
    val error: String? = null,
    /** Seconds before the next automatic retry, 0 when none is scheduled. */
    val retryIn: Int = 0,
    val attempt: Int = 0,
    val videoInfo: String? = null,
)

data class TrackOption(val label: String, val selected: Boolean, internal val group: Tracks.Group?, internal val index: Int)

/**
 * Owns one ExoPlayer for the player screen. Live streams are retried forever with a growing delay (1 s to 15 s)
 * and immediately when the network comes back; on-demand content three times, from where it stopped.
 */
class PlayerController(private val context: Context, private val http: OkHttpClient, private val settings: Settings) {

    private val trackSelector = DefaultTrackSelector(context).apply {
        parameters = buildUponParameters()
            .setPreferredAudioLanguage(settings.audioLang.ifBlank { null })
            .setPreferredTextLanguage(settings.subtitleLang.ifBlank { Locale.getDefault().language })
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !settings.subtitlesOn)
            .build()
    }

    // Up to 2 minutes ahead (ExoPlayer also caps the buffer in bytes, about 130 MB, so memory stays bounded), and
    // the last 10 s kept for an instant "−10 s".
    private val loadControl = if (settings.bufferMode == "stable") {
        DefaultLoadControl.Builder().setBufferDurationsMs(30_000, 120_000, 4_000, 8_000).setBackBuffer(10_000, true).build()
    } else {
        DefaultLoadControl.Builder().setBufferDurationsMs(20_000, 120_000, 1_500, 4_000).setBackBuffer(10_000, true).build()
    }

    val player: ExoPlayer = ExoPlayer.Builder(context, DefaultRenderersFactory(context).setEnableDecoderFallback(true))
        .setTrackSelector(trackSelector)
        .setLoadControl(loadControl)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        .setHandleAudioBecomingNoisy(true)
        .build()

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state

    private val _tracksVersion = MutableStateFlow(0)
    /** Changes whenever the stream's tracks change, so the track menus refresh. */
    val tracksVersion: StateFlow<Int> = _tracksVersion

    private val main = Handler(Looper.getMainLooper())
    private var current: StreamSpec? = null
    private var attempt = 0
    private var retryRunnable: Runnable? = null
    private var released = false
    private var stallSince = 0L

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            val s = _state.value
            _state.value = s.copy(
                buffering = state == Player.STATE_BUFFERING || state == Player.STATE_IDLE && s.error == null,
                ended = state == Player.STATE_ENDED,
                error = if (state == Player.STATE_READY) null else s.error,
                retryIn = if (state == Player.STATE_READY) 0 else s.retryIn,
            )
            if (state == Player.STATE_READY) attempt = 0
            // A wait for data after playback started is a stall: logged with its length (adb logcat -s Player).
            if (state == Player.STATE_BUFFERING && player.currentPosition > 0 && stallSince == 0L) stallSince = android.os.SystemClock.elapsedRealtime()
            if (state == Player.STATE_READY && stallSince > 0) {
                Log.i(TAG, "stall at ${player.currentPosition / 1000} s: ${android.os.SystemClock.elapsedRealtime() - stallSince} ms"); stallSince = 0
            }
            // The provider closed a live stream: reopen it instead of leaving the last picture frozen.
            val spec = current
            if (state == Player.STATE_ENDED && spec?.live == true) scheduleRetry(spec, "Flux interrompu")
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.value = _state.value.copy(playing = isPlaying)
        }

        override fun onTracksChanged(tracks: Tracks) {
            _tracksVersion.value++
            val f = player.videoFormat
            _state.value = _state.value.copy(videoInfo = f?.let { v ->
                listOfNotNull(
                    if (v.height > 0) "${v.height}p" else null,
                    v.sampleMimeType?.substringAfter('/')?.uppercase(),
                    if (v.frameRate > 0) "${v.frameRate.toInt()} i/s" else null,
                ).joinToString(" · ")
            })
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "playback error ${error.errorCodeName}: ${Redact.text(error.cause?.toString() ?: "")}")
            val spec = current ?: return
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                player.seekToDefaultPosition()
                player.prepare()
                return
            }
            scheduleRetry(spec, describe(error))
        }
    }

    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            main.post { if (!released && _state.value.error != null) retryNow() }
        }
    }

    // Live watchdog: no error, but nothing moves (stalled stream, stuck picture) → reconnect like after an error.
    private var lastPosition = -1L
    private var lastFrames = -1
    private var movedAt = 0L
    private val watchdog = object : Runnable {
        override fun run() {
            if (released) return
            main.postDelayed(this, 2_000)
            val spec = current?.takeIf { it.live } ?: return
            val now = android.os.SystemClock.elapsedRealtime()
            val frames = player.videoDecoderCounters?.let { it.ensureUpdated(); it.renderedOutputBufferCount } ?: -1
            val pos = player.currentPosition
            // Paused, or a retry already on its way: nothing to watch.
            val waiting = !player.playWhenReady || retryRunnable != null
            val clockMoves = pos != lastPosition
            val pictureMoves = player.videoFormat == null || frames != lastFrames
            if (waiting || clockMoves && pictureMoves) movedAt = now
            lastPosition = pos; lastFrames = frames
            if (now - movedAt > STALL_MS) {
                Log.w(TAG, "live stalled (clock ${if (clockMoves) "moves" else "stopped"}), reconnecting")
                scheduleRetry(spec, if (clockMoves) "Image figée" else "Signal perdu")
            }
        }
    }

    init {
        player.addListener(listener)
        main.postDelayed(watchdog, 2_000)
        runCatching {
            connectivity.registerNetworkCallback(
                NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), netCallback,
            )
        }
    }

    fun play(spec: StreamSpec, startPositionMs: Long = 0) {
        cancelRetry()
        current = spec
        attempt = 0
        _state.value = PlaybackState(buffering = true)
        prepare(spec, startPositionMs)
    }

    private fun prepare(spec: StreamSpec, positionMs: Long) {
        movedAt = android.os.SystemClock.elapsedRealtime()
        stallSince = 0
        val okHttp = OkHttpDataSource.Factory(http).apply {
            spec.userAgent?.let { setUserAgent(it) }
            spec.referer?.let { setDefaultRequestProperties(mapOf("Referer" to it)) }
        }
        // DefaultDataSource adds file:// on top of HTTP, for recordings and downloads.
        val ds = DefaultDataSource.Factory(context, okHttp)
        // Live: report quickly and let scheduleRetry() take over; on demand: let ExoPlayer retry a few chunks itself.
        val factory = DefaultMediaSourceFactory(ds).setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(if (spec.live) 1 else 3))
        val item = MediaItem.Builder().setUri(spec.url).apply {
            val lower = spec.url.substringBefore('?').lowercase()
            if (lower.endsWith(".m3u8") || lower.contains("/hls/") || lower.endsWith(".m3u")) setMimeType(MimeTypes.APPLICATION_M3U8)
            else if (lower.endsWith(".mpd")) setMimeType(MimeTypes.APPLICATION_MPD)
        }.build()
        player.setMediaSource(factory.createMediaSource(item), positionMs.coerceAtLeast(0))
        player.prepare()
        player.playWhenReady = true
    }

    private fun scheduleRetry(spec: StreamSpec, message: String) {
        attempt++
        val maxAttempts = if (spec.live) Int.MAX_VALUE else 3
        if (attempt > maxAttempts) {
            _state.value = _state.value.copy(buffering = false, error = message, retryIn = 0, attempt = attempt)
            return
        }
        val delaySec = listOf(1, 2, 4, 8, 15)[(attempt - 1).coerceAtMost(4)]
        _state.value = _state.value.copy(buffering = false, error = message, retryIn = delaySec, attempt = attempt)
        cancelRetry()
        val position = if (spec.live) 0 else player.currentPosition
        val r = Runnable { retryRunnable = null; if (!released && current === spec) prepare(spec, position) }
        retryRunnable = r
        main.postDelayed(r, delaySec * 1000L)
    }

    /** Retry right now (the "Réessayer" button, or the network came back). */
    fun retryNow() {
        val spec = current ?: return
        cancelRetry()
        attempt = 0
        _state.value = _state.value.copy(error = null, retryIn = 0, buffering = true)
        prepare(spec, if (spec.live) 0 else player.currentPosition)
    }

    private fun cancelRetry() {
        retryRunnable?.let { main.removeCallbacks(it) }
        retryRunnable = null
    }

    fun audioTracks(): List<TrackOption> = options(C.TRACK_TYPE_AUDIO)

    fun textTracks(): List<TrackOption> {
        val textOn = !player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        val list = options(C.TRACK_TYPE_TEXT)
        return listOf(TrackOption("Désactivés", !textOn || list.none { it.selected }, null, -1)) + list.map { it.copy(selected = it.selected && textOn) }
    }

    private fun options(type: Int): List<TrackOption> {
        val out = ArrayList<TrackOption>()
        for (g in player.currentTracks.groups) {
            if (g.type != type) continue
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                val f = g.getTrackFormat(i)
                val locale = f.language?.takeIf { it != C.LANGUAGE_UNDETERMINED }?.let { Locale(it) }
                val lang = locale?.getDisplayLanguage(Locale.FRENCH)?.replaceFirstChar { c -> c.titlecase() }
                // Providers often label a track with its language name in English ("English"): show it once, in French.
                val extraLabel = f.label?.takeIf { l ->
                    locale == null || (!l.equals(locale.getDisplayLanguage(Locale.ENGLISH), true) && !l.equals(lang, true))
                }
                val label = listOfNotNull(
                    lang ?: extraLabel ?: "Piste ${out.size + 1}",
                    extraLabel?.takeIf { lang != null },
                    if (type == C.TRACK_TYPE_AUDIO && f.channelCount > 0) channels(f.channelCount) else null,
                    f.sampleMimeType?.substringAfter('/')?.uppercase()?.takeIf { type == C.TRACK_TYPE_AUDIO },
                ).joinToString(" · ")
                out += TrackOption(label, g.isTrackSelected(i), g, i)
            }
        }
        return out
    }

    fun select(type: Int, option: TrackOption) {
        val b = player.trackSelectionParameters.buildUpon()
        if (option.group == null) {
            b.setTrackTypeDisabled(type, true)
        } else {
            b.setTrackTypeDisabled(type, false)
            b.setOverrideForType(TrackSelectionOverride(option.group.mediaTrackGroup, option.index))
        }
        player.trackSelectionParameters = b.build()
    }

    /** 0 mutes this player (the tiles of the multi-view that are not focused). */
    fun setVolume(v: Float) { player.volume = v }

    fun release() {
        released = true
        cancelRetry()
        main.removeCallbacks(watchdog)
        runCatching { connectivity.unregisterNetworkCallback(netCallback) }
        player.removeListener(listener)
        player.release()
    }

    private fun channels(n: Int) = when (n) { 1 -> "Mono"; 2 -> "Stéréo"; 6 -> "5.1"; 8 -> "7.1"; else -> "$n canaux" }

    private fun describe(e: PlaybackException): String {
        val http = generateSequence(e.cause) { it.cause }.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
        return when {
            http != null -> when (http.responseCode) {
                401, 403 -> "Accès refusé par le fournisseur (HTTP ${http.responseCode}). Abonnement expiré, ou déjà utilisé sur un autre écran ?"
                429, 456, 458, 509 -> com.lumentv.data.xtream.ScreenGuard.REFUSED
                404 -> "Flux introuvable (HTTP 404)."
                else -> "Le serveur a répondu HTTP ${http.responseCode}."
            }
            e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "Connexion au flux impossible."
            e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Le flux ne répond pas (délai dépassé)."
            e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
                e.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> "Flux illisible ou corrompu."
            e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "Format de flux non pris en charge."
            e.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                e.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
                e.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> "Codec vidéo ou audio non pris en charge par cet appareil."
            e.errorCode == PlaybackException.ERROR_CODE_DRM_UNSPECIFIED || e.errorCode in 6000..6999 -> "Contenu protégé (DRM) : lecture impossible."
            else -> "Erreur de lecture (${e.errorCodeName})."
        }
    }

    companion object {
        private const val TAG = "Player"
        /** A live channel whose clock or picture stays still this long (while meant to play) is reconnected. */
        private const val STALL_MS = 20_000L
    }
}
