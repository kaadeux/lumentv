package com.lumentv.core

/**
 * The same channel in several qualities ("TF1 FHD", "TF1 HD", "TF1 SD", "FR | TF1 [H265]"): its name without the
 * quality marks, and a rank to step down to when the picture keeps stopping, and back up once it is steady.
 */
object ChannelQuality {
    private val marks = Regex("\\b(uhd|4k|fhd|hd|sd|hevc|h265|h 265|1080p?|720p?|576p?|50fps|60fps|backup|raw)\\b")
    private val prefix = Regex("^([a-z]{2,3})\\s*[|:]\\s*")

    /** "FR | TF1 FHD" → "tf1". */
    fun base(name: String): String =
        normalize(name).replace(prefix, "").replace(marks, " ").replace(Regex("[\\[\\](){}|]"), " ").replace(Regex("\\s+"), " ").trim()

    /** 4 = 4K, 3 = FHD, 2 = HD (or unmarked), 1 = SD. */
    fun rank(name: String): Int {
        val n = " " + normalize(name) + " "
        return when {
            Regex("\\b(uhd|4k)\\b").containsMatchIn(n) -> 4
            Regex("\\b(fhd|1080p?)\\b").containsMatchIn(n) -> 3
            Regex("\\b(sd|576p?)\\b").containsMatchIn(n) -> 1
            else -> 2
        }
    }

    /** An H265 copy: not every device decodes it, so a change of quality takes it only when there is no other. */
    fun isHevc(name: String) = Regex("\\b(hevc|h265|h 265)\\b").containsMatchIn(normalize(name))

    /** "FHD", "HD", "SD", "4K". */
    fun label(name: String) = when (rank(name)) { 4 -> "4K"; 3 -> "FHD"; 1 -> "SD"; else -> "HD" }

    /** Wait this long without a stall before trying the better quality again. */
    const val STEADY_MS = 6 * 60_000L
    /** Two stalls within this window, or one longer than [LONG_STALL_MS], step down. */
    const val STALL_WINDOW_MS = 2 * 60_000L
    const val LONG_STALL_MS = 6_000L
}
