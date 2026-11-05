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

    /** The player was closed at [positionMs] of [durationMs] (0 when unknown). */
    fun finish(positionMs: Long, durationMs: Long) = onFinish(positionMs, durationMs)

    /** After [finish]: opens the episode before / after in a new player. */
    fun previous() { onPrevious?.invoke() }
    fun next() { onNext?.invoke() }
}
