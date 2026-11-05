package com.lumentv.core

/**
 * A live channel paused, or held up by stalls, plays behind live: the player then offers "Revenir au direct" for a
 * while, the same on the TV, the phone and the PC. It cannot do better than the provider's own delay (about a minute
 * behind the TNT, measured on CNEWS): only the delay added here is won back.
 */
object LiveLag {
    /** Less than this is not worth a button. */
    const val MIN_MS = 5_000L
    /** How long the button stays after the delay grew. */
    const val SHOWN_MS = 20_000L
}
