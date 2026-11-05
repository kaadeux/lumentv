package com.lumentv.core

/**
 * The "Reprendre la lecture" row of the Films and Séries screens, the same rules on the TV, the phone and the PC:
 * films started and not finished; series whose last episode watched is not finished, or is but has a next one.
 */
object Resume {
    /** Less than this and it was a glance, not a start. */
    const val STARTED_MS = 60_000L
    const val LIMIT = 20

    /** Seen to the end (the credits may be skipped): the episodes' "Vu" mark and the end of the resume row use it. */
    fun watched(positionMs: Long, durationMs: Long) = durationMs > 0 && positionMs >= minOf(durationMs * 92 / 100, durationMs - 60_000)

    /** A film to resume: started and not finished. */
    fun movieToResume(positionMs: Long, durationMs: Long) = positionMs >= STARTED_MS && !watched(positionMs, durationMs)

    /** "Reste 1 h 12" / "Reste 24 min", or null without a duration. */
    fun remaining(positionMs: Long, durationMs: Long): String? {
        if (durationMs <= 0) return null
        val min = ((durationMs - positionMs).coerceAtLeast(0) / 60_000).toInt().coerceAtLeast(1)
        return if (min >= 60) "Reste ${min / 60} h ${(min % 60).toString().padStart(2, '0')}" else "Reste $min min"
    }

    /** "S4 E6 · …" → (4, 6). */
    fun seasonEpisode(subtitle: String?): Pair<Int, Int>? =
        subtitle?.let { Regex("^S(\\d+) E(\\d+)").find(it) }?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }

    /**
     * Of a series' history, the episode furthest in the series (season, then number), the last one watched when
     * unknown: the series goes on from there, and leaves "Reprendre la lecture" once its last episode is seen, even
     * when an earlier one was watched again since.
     */
    fun <T> furthest(eps: List<T>, subtitle: (T) -> String?, watchedAt: (T) -> Long): T =
        eps.filter { seasonEpisode(subtitle(it)) != null }.maxWithOrNull(compareBy({ seasonEpisode(subtitle(it))!!.first }, { seasonEpisode(subtitle(it))!!.second }))
            ?: eps.maxBy(watchedAt)
}
