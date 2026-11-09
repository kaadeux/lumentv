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

/** Titles from a provider that writes the language first ("FR - …"): the same titles as without it. */
class LanguageTagTest {
    @Test fun tagIsLeftOut() {
        org.junit.Assert.assertEquals("heart of the beast", Featured.baseTitle("FR - Heart of the Beast (2026)"))
        org.junit.Assert.assertEquals(Featured.baseTitle("Heart of the Beast (2026) MULTI"), Featured.baseTitle("EN - Heart of the Beast (2026)"))
        org.junit.Assert.assertEquals("The Highest Stakes", Featured.shortTitle("IN-EN - The Highest Stakes (2026)"))
        org.junit.Assert.assertEquals("Traffik", Featured.shortTitle("AR-SUBS - Traffik (2018)"))
        org.junit.Assert.assertEquals("the greatest", Featured.baseTitle("AR | The Greatest"))
        org.junit.Assert.assertEquals("Heart of the Beast", Featured.searchTitle("FR - Heart of the Beast (2026)"))
        // A title of its own that only looks like a tag.
        org.junit.Assert.assertEquals("LOL - Qui rit sort", Featured.shortTitle("LOL - Qui rit sort (2023)"))
        org.junit.Assert.assertEquals("Spider-Man", Featured.shortTitle("Spider-Man (2002)"))
        org.junit.Assert.assertEquals("Happy Raj", Featured.shortTitle("4K-AR-IN - Happy Raj"))
        org.junit.Assert.assertTrue(Featured.foreign("4K-AR-IN - Happy Raj", "IN - HINDI"))
        org.junit.Assert.assertFalse(Featured.foreign("FR - Heart of the Beast (2026)", "FR - LE DRENIER AJOUTEE"))
    }

    @Test fun frenchCopyFirst() {
        org.junit.Assert.assertEquals(0, SmartSearch.languageRank("FR - Heart of the Beast (2026)"))
        org.junit.Assert.assertEquals(1, SmartSearch.languageRank("EN - Heart of the Beast (2026)"))
        org.junit.Assert.assertEquals(2, SmartSearch.languageRank("DE - Heart of the Beast (2026)"))
        org.junit.Assert.assertEquals(0, SmartSearch.languageRank("Heart of the Beast (2026) MULTI"))
        val copies = listOf("DE - Heart of the Beast (2026)", "EN - Heart of the Beast (2026)", "FR - Heart of the Beast (2026)")
            .mapIndexed { i, n -> com.lumentv.data.discover.VodLite(i.toLong(), "x:$i", n, "Films", null, null) }
        val idx = com.lumentv.data.discover.TitleIndex(copies, emptyList())
        org.junit.Assert.assertEquals("FR - Heart of the Beast (2026)", idx.find(false, "Heart of the Beast", "Heart of the Beast", 2026)?.name)
    }
}

/** Live channel groups: French first, the provider's order kept within each part. */
class FrenchGroupsTest {
    /** Other playlists write it in other ways: the French groups are found all the same. */
    @Test fun anyWriting() {
        listOf("[FR] TF1", "|FR| CINEMA", "FR: SPORT", "FR · Films CULTES", "FR-NEWS", "🇫🇷 Chaînes", "Français HD", "France Sport", "Sport VF", "HBO FR")
            .forEach { org.junit.Assert.assertEquals(it, 0, Featured.frenchRank(it)) }
        listOf("BE| RTBF", "QC: TVA", "CA| GENERAL FR", "Suisse romande", "BELGIUM", "Québec")
            .forEach { org.junit.Assert.assertEquals(it, 1, Featured.frenchRank(it)) }
        listOf("US| NEWS", "UK| SPORTS", "TV5MONDE", "AFR| SOMALIA", "DSTV AFRICA", "SPORT AR", "Freeform", "UK| BBC")
            .forEach { org.junit.Assert.assertEquals(it, 2, Featured.frenchRank(it)) }
    }

    @Test fun frenchFirst() {
        val strong = listOf("US| AT&T ᴿᴬᵂ", "US| NEWS ᴴᴰ", "CA| GENERAL FR", "UK| SPORTS", "FR| FRANCE ⱽᴵᴾ ᴿᴬᵂ", "FR| CANAL+ LIVE ᴴᴰ", "AFR| SOMALIA", "DSTV AFRICA")
        org.junit.Assert.assertEquals(
            listOf("FR| FRANCE ⱽᴵᴾ ᴿᴬᵂ", "FR| CANAL+ LIVE ᴴᴰ", "CA| GENERAL FR", "US| AT&T ᴿᴬᵂ", "US| NEWS ᴴᴰ", "UK| SPORTS", "AFR| SOMALIA", "DSTV AFRICA"),
            Featured.frenchFirst(strong) { it })
        // A list already French first keeps its order.
        val atlas = listOf("FRANCE HD", "FRANCE FHD", "SPORT FR", "FRANCE SD", "FORMULA 1 & MOTO GP", "DISNEY+ SPORT FR", "DAZN FRANCE", "SPORT AR", "AFRICA")
        org.junit.Assert.assertEquals(
            listOf("FRANCE HD", "FRANCE FHD", "SPORT FR", "FRANCE SD", "DISNEY+ SPORT FR", "DAZN FRANCE", "FORMULA 1 & MOTO GP", "SPORT AR", "AFRICA"),
            Featured.frenchFirst(atlas) { it })
    }
}
