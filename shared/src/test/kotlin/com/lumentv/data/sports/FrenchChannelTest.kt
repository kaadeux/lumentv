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
}
