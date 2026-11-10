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
        val f = Fixture("1", FixtureClient.league("nat_foot")!!, 1_000_000_000L, "pre", "", home = Team("Italie", "Italie", "ITA", null),
            away = Team("France", "France", "FRA", null), name = "Italie - France")
        assertTrue(FixtureMatcher.matches(f, "football ligue des nations italie france", 1_000_000_000L))
    }

    @Test fun nationalCompetitionsAreFollowed() {
        listOf("nat_foot", "nat_rugby").forEach { assertTrue(FixtureClient.league(it)!!.parts.isNotEmpty(), it) }
        // One filter: national teams only when ticked, like any other competition.
        val ticked = SportsFunnel.effectiveLeagues(setOf("football"), setOf("fra1")).map { it.id }
        assertTrue("nat_foot" !in ticked)
        assertTrue("nat_foot" in SportsFunnel.effectiveLeagues(setOf("football"), setOf("fra1", "nat_foot")).map { it.id })
    }
}
