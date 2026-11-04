package com.lumentv.data.sports

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrenchChannelTest {
    @Test fun frenchOnly() {
        assertTrue(FrenchTvGuide.french("CANAL+ SPORT FHD", "SPORT FR"))
        assertTrue(FrenchTvGuide.french("FR | beIN SPORTS 1 HD", "FRANCE HD"))
        assertTrue(FrenchTvGuide.french("Ligue 1+ 2", "SPORT FR"))
        assertFalse(FrenchTvGuide.french("AR | beIN SPORTS 1", "SPORT AR"))
        assertFalse(FrenchTvGuide.french("beIN SPORTS 1 HD", "SPORT AR"))
        assertFalse(FrenchTvGuide.french("DAZN 1", "DAZN ARABIC"))
        assertFalse(FrenchTvGuide.french("UK: Sky Sports Main Event", "UK SPORTS"))
        assertFalse(FrenchTvGuide.french("ES | DAZN LaLiga", "ESPAÑA"))
        assertFalse(FrenchTvGuide.french("STAN 012| Borussia Dortmund v Real Madrid", "EVENTS"))
        assertTrue(FrenchTvGuide.french("MAURIENNE TV", "FRANCE HD"))
    }

    @Test fun liveBroadcastAtKickOffWithFrenchNames() {
        val lg = FixtureClient.league("ucl")!!
        val k = 1_800_000_000_000L
        val lens = Fixture("1", lg, k, "pre", "", Team("Lens", null, null, null), Team("Como", null, null, null), "")
        val porto = Fixture("2", lg, k, "pre", "", Team("FC Porto", "Porto", null, null), Team("Napoli", null, null, null), "")
        val l = listOf(
            Listing("Canal+ Foot", k - 7 * 3_600_000L, com.lumentv.core.normalize("Lens / Côme")),
            Listing("Canal+", k, com.lumentv.core.normalize("Lens / Côme")),
            Listing("Canal+ Live 1", k, com.lumentv.core.normalize("Lens / Côme")),
            Listing("Canal+ Foot", k + 3 * 3_600_000L, com.lumentv.core.normalize("Lens / Côme")),
            Listing("Canal+ Live 5", k - 5 * 60_000L, com.lumentv.core.normalize("FC Porto / Naples")),
        )
        org.junit.Assert.assertEquals(setOf("Canal+", "Canal+ Live 1"), FrenchTvGuide.broadcasts(lens, l).map { it.channel }.toSet())
        org.junit.Assert.assertEquals(listOf("Canal+ Live 5"), FrenchTvGuide.broadcasts(porto, l).map { it.channel })
    }
}
