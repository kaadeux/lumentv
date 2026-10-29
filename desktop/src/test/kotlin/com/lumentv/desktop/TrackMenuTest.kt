package com.lumentv.desktop

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackMenuTest {
    @Test fun readableNames() {
        assertEquals("Désactivés" to null, TrackMenu.label(-1, "Disable", audio = false))
        assertEquals("Français" to "VFF forcés", TrackMenu.label(2, "VFF Forced - [French]", audio = false))
        assertEquals("Français" to "VFF", TrackMenu.label(3, "VFF - [French]", audio = false))
        assertEquals("Anglais" to null, TrackMenu.label(4, "Track 3 - [English]", audio = false))
        assertEquals("Piste 5" to null, TrackMenu.label(5, "Track 5", audio = true))
    }
}
