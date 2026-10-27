package com.lumentv.player

import android.content.Context

/**
 * Where a series' intro ends and how long its end credits last, learned from the viewer: IPTV providers
 * give no chapter markers. Kept per series, so one correction serves every following episode.
 */
class SeriesMarkers(context: Context) {
    private val prefs = context.getSharedPreferences("series_markers", Context.MODE_PRIVATE)

    /** Position (ms) where the intro ends, or 0 when not learned yet. */
    fun introEnd(seriesKey: String): Long = prefs.getLong("intro:$seriesKey", 0)

    fun setIntroEnd(seriesKey: String, ms: Long) {
        if (ms in MIN_INTRO..MAX_INTRO) prefs.edit().putLong("intro:$seriesKey", ms).apply()
    }

    /** Length (ms) of the end credits, or 0 when not learned yet. */
    fun creditsLength(seriesKey: String): Long = prefs.getLong("credits:$seriesKey", 0)

    fun setCreditsLength(seriesKey: String, ms: Long) {
        if (ms in MIN_CREDITS..MAX_CREDITS) prefs.edit().putLong("credits:$seriesKey", ms).apply()
    }

    companion object {
        /** First press when nothing is learned: a typical recap + opening titles. */
        const val DEFAULT_SKIP = 85_000L
        /** The skip button shows during this opening part of an episode when nothing is learned. */
        const val DEFAULT_INTRO_WINDOW = 6 * 60_000L
        private const val MIN_INTRO = 10_000L
        private const val MAX_INTRO = 10 * 60_000L
        private const val MIN_CREDITS = 10_000L
        private const val MAX_CREDITS = 6 * 60_000L

        /** Before anything is learned: about 2.5 % of the episode, between 30 s and 90 s. */
        fun defaultCredits(durationMs: Long) = (durationMs * 0.025).toLong().coerceIn(30_000L, 90_000L)
    }
}
