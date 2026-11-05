package com.lumentv.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackNamesTest {
    @Test fun frenchForcedTracks() {
        // Media3: language code and the file's forced flag; VLC: only the description.
        assertTrue(TrackNames.isForced(true, null) && TrackNames.isFrench("fr", null))
        assertTrue(TrackNames.isFrench("fre", "Forced") && TrackNames.isForced(false, "Forced"))
        listOf("Français (forced) - [Français]", "FR Forcés", "French [Forced]", "Track 3 - [French] forcé", "VFF forcés").forEach {
            assertTrue(it, TrackNames.isForced(false, it) && TrackNames.isFrench(null, it))
        }
    }

    @Test fun fullOrOtherLanguageTracksAreNot() {
        assertFalse(TrackNames.isForced(false, "Français - [Français]"))
        assertFalse(TrackNames.isForced(false, "Français SDH"))
        assertFalse(TrackNames.isFrench("en", "English (Forced)"))
        assertFalse(TrackNames.isFrench(null, "Track 2 - [English] Forced"))
        assertFalse(TrackNames.isForced(false, "Force majeure"))
    }
}
