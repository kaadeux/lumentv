package com.lumentv.core

import com.lumentv.data.TitleLogos
import com.lumentv.data.ratingText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedTextTest {
    /** The hash must not change: every installed app compares against it. */
    @Test fun accessHash() {
        assertEquals("2e8b09703b9ae0b09f9ea615137d9fa695e1a2be100c61b4d30a257ee70103ea", AccessCode.hash(" 123456 "))
    }

    /** Never "Protégée" when the video's server could not be checked. */
    @Test fun privacy() {
        assertEquals("Protégée", Privacy.of(listOf("a.site"), emptyList()).title)
        assertEquals(Privacy.PARTIAL, Privacy.of(listOf("a.site"), listOf("1.2.3.4")).title)
        val unchecked = Privacy.of(listOf("a.site"), emptyList(), videoUnchecked = true)
        assertEquals(Privacy.PARTIAL, unchecked.title)
        assertTrue(unchecked.lines.any { it.startsWith("Vidéo non contrôlée") })
    }

    @Test fun rating() {
        assertEquals("★ 7,3", ratingText(TitleLogos.Info(null, 7.26, 10), null))
        assertNull(ratingText(null, null))
    }

    @Test fun days() {
        val noon = 1_800_000_000_000L
        val day = 86_400_000L
        assertEquals("Aujourd'hui", dayLabel(noon, noon))
        assertEquals("Demain", dayLabel(noon + day, noon))
        assertEquals("Hier", dayLabel(noon - day, noon))
        val far = dayLabel(noon + 3 * day, noon)
        assertTrue(far.first().isUpperCase() && far.count { it == ' ' } == 2, far)
    }
}
