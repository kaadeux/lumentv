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

class ResumeTest {
    private class H(val subtitle: String?, val watchedAt: Long)

    @Test fun furthestEpisodeNotTheLastWatched() {
        // From: the whole series seen up to S4 E10, then S1 E1 watched again today.
        val eps = listOf(H("S4 E10 · Fin", 100), H("S2 E3 · …", 50), H("S1 E1 · Voyage au bout du jour", 900))
        org.junit.Assert.assertEquals("S4 E10 · Fin", Resume.furthest(eps, { it.subtitle }, { it.watchedAt }).subtitle)
        // Without "S… E…" in the subtitles: the last one watched.
        org.junit.Assert.assertEquals(9L, Resume.furthest(listOf(H(null, 3), H("Bonus", 9)), { it.subtitle }, { it.watchedAt }).watchedAt)
    }
}

class SearchTitleTest {
    @Test fun providerTitlesAsTmdbKnowsThem() {
        org.junit.Assert.assertEquals("Les Misérables", Featured.searchTitle("Les Misérables SD"))
        org.junit.Assert.assertEquals("Les Misérables", Featured.searchTitle("Les Misérables - 2012"))
        org.junit.Assert.assertEquals(2012, Featured.yearOf(null, "Les Misérables - 2012"))
        org.junit.Assert.assertEquals("Dune", Featured.searchTitle("Dune (2021) 4K MULTI"))
        org.junit.Assert.assertEquals("Spider-Man : No Way Home", Featured.searchTitle("Spider-Man : No Way Home"))
        org.junit.Assert.assertEquals("Vice-Versa", Featured.searchTitle("Vice-Versa "))
        org.junit.Assert.assertEquals(2026, Featured.yearOf(null, "Breach (2026) VOSTFR"))
    }
}
