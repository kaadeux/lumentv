package com.lumentv.data.sports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NationalTeamsTest {
    @Test fun countriesInFrench() {
        assertEquals("Italie", Team.country("Italy"))
        assertEquals("Turquie", Team.country("Türkiye"))
        assertEquals("Irlande féminines", Team.country("Ireland Women"))
        assertNull(Team.country("Paris Saint-Germain"))
    }

    @Test fun guideFindsTheBleusGame() {
        val f = Fixture("1", FixtureClient.league("unl")!!, 1_000_000_000L, "pre", "", home = Team("Italie", "Italie", "ITA", null),
            away = Team("France", "France", "FRA", null), name = "Italie - France")
        assertTrue(FixtureMatcher.matches(f, "football ligue des nations italie france", 1_000_000_000L))
    }

    @Test fun nationalCompetitionsAreFollowed() {
        listOf("unl", "friendly", "nations").forEach { assertTrue(FixtureClient.league(it) != null, it) }
    }
}
