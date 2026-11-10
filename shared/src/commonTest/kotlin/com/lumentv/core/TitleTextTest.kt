package com.lumentv.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The title helpers the Films screens call for every poster, run on every platform (the iPhone's regex engine differs). */
class TitleTextTest {
    private val names = listOf(
        "FR - Heart of the Beast (2026)", "4K-SC-HDR - Heart of the Beast (2026)", "The Dog Stars (2026) 4K MULTI",
        "Breach (2026) VOSTFR", "Heart of the Beast (2026) قلب هیولا", "AR | The Greatest", "NF - From Scratch",
        "EN - FROM (2022) (US)", "Spider-man (IT)", "Les Misérables - 2012", "ᴿᴬᵂ Dune ⁶⁰ᶠᵖˢ (2021)", "🎬 Film",
    )

    @Test fun everyHelperAnswers() {
        for (n in names) {
            Featured.versionLabel(n)
            Featured.posterLine("2026", "7.2", n)
            Featured.shortTitle(n); Featured.searchTitle(n); Featured.baseTitle(n); Featured.untagged(n)
            Featured.otherLanguage(n); Featured.foreign(n, "Films"); Featured.frenchRank(n)
            SmartSearch.languageRank(n); SmartSearch.qualityRank(n)
            SmartSearch.score(SmartSearch.Query("from"), SmartSearch.Entry(n, n))
        }
    }

    @Test fun versionLabels() {
        assertEquals("VOSTFR", Featured.versionLabel("Breach (2026) VOSTFR"))
        assertEquals("4K MULTI", Featured.versionLabel("The Dog Stars (2026) 4K MULTI"))
        assertEquals("Français", Featured.versionLabel("FR - Heart of the Beast (2026)"))
        assertEquals("4K · Scandinave · HDR", Featured.versionLabel("4K-SC-HDR - Heart of the Beast (2026)"))
        assertEquals("Version", Featured.versionLabel("Heart of the Beast (2026) قلب هیولا"))
    }

    @Test fun englishMarksStayNearTheTop() {
        assertTrue(SmartSearch.Entry(1, "EN - FROM (2022) (US)").english)
        assertTrue(!SmartSearch.Entry(1, "Spider-man (IT)").english)
    }
}

class CopiesTest {
    @Test fun oneFilmWhateverTheCopy() {
        val names = listOf("4K-TOP - Spider-Man: Brand New Day (2026)", "TOP - Spider-Man: Brand New Day (2026)",
            "EN - Spider-Man: Brand New Day  (2026)", "FR - Spider-Man: Brand New Day (2026)", "SE-CAM - Spider-Man: Brand New Day (2026)")
        assertEquals(1, names.map { Featured.baseTitle(it) }.distinct().size)
        assertEquals("From Scratch", Featured.untagged("NF - From Scratch"))
        assertEquals("LOL - Qui rit sort", Featured.untagged("LOL - Qui rit sort"))
    }
}

class HistoryNamesTest {
    @Test fun readableNames() {
        assertEquals("Heart of the Beast", Featured.shortTitle("4K-TOP - Heart of the Beast (2026)"))
        assertEquals("FROM", Featured.shortTitle("FR - FROM (2022) (US)"))
        assertEquals("S1 E8 · Le Changement de ligne", Featured.episodeLine("S1 E8 · FR - Off Campus (2026) (US) - S01E08 - Le Changement de ligne"))
        assertEquals("S1 E1 · Long Day's Journey Into Night", Featured.episodeLine("S1 E1 · FR - From - S01E01 - Long Day's Journey Into Night"))
        assertEquals("S2 E3 · Le retour", Featured.episodeLine("S2 E3 · Le retour"))
    }
}
