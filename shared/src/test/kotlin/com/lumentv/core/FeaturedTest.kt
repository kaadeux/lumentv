package com.lumentv.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeaturedTest {
    @Test fun arabicCopiesAndCategoriesAreForeign() {
        // Seen in the family's playlist: the same series twice in "Ajoutées récemment", the second one subtitled in Arabic.
        assertTrue(Featured.foreign("AR | The Greatest", "Séries SUB-AR"))
        assertTrue(Featured.foreign("The Greatest", "Séries SUB-AR"))
        assertTrue(Featured.foreign("Kurulus Osman", "Séries TURQUES SUB-AR"))
        assertTrue(Featured.foreign("Bab El Hara", "Séries ARABES "))
        assertTrue(Featured.foreign("Tom et Jerry", "Séries CARTOON ARABY"))
        assertTrue(Featured.foreign("Shrek", "Séries DUB-AR"))
        assertTrue(Featured.foreign("Inception", "AR · SUBT- AR أفلام مترجمة"))
        assertTrue(Featured.foreign("Breach (2026) SUBT AR", "Films"))
    }

    @Test fun frenchTitlesAreNot() {
        assertFalse(Featured.foreign("The Greatest", "Séries PRIME VIDEO"))
        assertFalse(Featured.foreign("Lawrence d'Arabie (1962)", "FR · Films CULTES"))
        assertFalse(Featured.foreign("Arabesque", "Séries POLICIÈRES"))
        assertFalse(Featured.foreign("Paraboles", "FR · Documentaire"))
        assertFalse(Featured.foreign("ARTE Reportage", "FR · Documentaire"))
    }
}
