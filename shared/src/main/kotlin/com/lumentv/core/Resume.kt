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
        return if (min >= 60) "Reste ${min / 60} h ${"%02d".format(min % 60)}" else "Reste $min min"
    }
}
