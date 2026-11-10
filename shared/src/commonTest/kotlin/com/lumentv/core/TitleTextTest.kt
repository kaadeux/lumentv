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
