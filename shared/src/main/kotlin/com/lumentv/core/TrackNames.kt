package com.lumentv.core

/**
 * How a subtitle track says what it is, the same for the TV's player (Media3) and the PC's (VLC): "Français (forced)",
 * "FR Forcés", "French [Forced]", "Piste 3 - [Français]"…
 */
object TrackNames {
    private val forced = Regex("\\bforc(ed|és?|ees?|ée?s?)\\b", RegexOption.IGNORE_CASE)
    private val french = Regex("\\b(fr|fre|fra|fr-fr|français|francais|french|vf|vff)\\b", RegexOption.IGNORE_CASE)

    /** Forced subtitles: only the lines in another language. [flag]: the file marks the track as forced. */
    fun isForced(flag: Boolean, label: String?): Boolean = flag || (label != null && forced.containsMatchIn(label))

    /** [language]: the track's ISO code when the file gives one ("fr", "fre", "fra"). */
    fun isFrench(language: String?, label: String?): Boolean =
        language?.lowercase()?.substringBefore('-') in setOf("fr", "fre", "fra") || (label != null && french.containsMatchIn(label))
}
