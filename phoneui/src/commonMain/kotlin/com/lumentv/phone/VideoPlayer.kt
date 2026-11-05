package com.lumentv.phone

/**
 * The platform's player, opened full screen over the app: KSPlayer on the iPhone (Swift side, iosApp/), the app's
 * own player on Android. It calls [PlayRequest.finish] once closed, with where the viewer stopped.
 */
interface VideoPlayer {
    fun play(request: PlayRequest)
}

class PlayRequest(
    val url: String,
    val userAgent: String?,
    val referer: String?,
    val title: String,
    val subtitle: String?,
    /** Where to start, in milliseconds (0: the beginning). */
    val startMs: Long,
    /** A live channel: no position to keep, no resume. */
    val live: Boolean,
    private val onFinish: (positionMs: Long, durationMs: Long) -> Unit,
    /** A series: the episode before and the one after (null at the ends, and for a film or a channel). */
    private val onPrevious: (() -> Unit)? = null,
    private val onNext: (() -> Unit)? = null,
    /** Goes on with [next] at the end of the episode (setting "Épisode suivant automatique"). */
    val autoNext: Boolean = false,
) {
    val hasPrevious get() = onPrevious != null
    val hasNext get() = onNext != null

    /** Languages wanted ("fr", "en"): the audio track; subtitles full ([subtitlesOn]) or only forced ones. */
    var audioLang: String = "fr"
    var subtitleLang: String = "fr"
    var subtitlesOn: Boolean = false

    /** Recap, intro and credits of an episode (TheIntroDB), filled in while it plays; the player offers to skip them. */
    var skips: List<PlaySkip> = emptyList()

    /** Live: the same channel in lower qualities (FHD → HD → SD), tried in turn when the picture keeps stopping. */
    var variants: List<PlayVariant> = emptyList()

    internal var onClosed: (() -> Unit)? = null

    /** The player was closed at [positionMs] of [durationMs] (0 when unknown). */
    fun finish(positionMs: Long, durationMs: Long) {
        onClosed?.invoke()
        onClosed = null
        onFinish(positionMs, durationMs)
    }

    /** After [finish]: opens the episode before / after in a new player. */
    fun previous() { onPrevious?.invoke() }
    fun next() { onNext?.invoke() }
}

/** A part of an episode to skip, in milliseconds; [toNext]: the credits, skipping them opens the next episode. */
class PlaySkip(val startMs: Long, val endMs: Long, val label: String, val toNext: Boolean)

/** A lower quality of a live channel: where it plays and what it is ("HD", "SD"). */
class PlayVariant(val url: String, val label: String)
