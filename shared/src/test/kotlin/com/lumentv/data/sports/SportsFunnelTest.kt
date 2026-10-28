package com.lumentv.data.sports

import org.junit.Assert.assertEquals
import org.junit.Test

/** The funnel's rules apply sport by sport: a choice in one sport never hides another sport. */
class SportsFunnelTest {
    private fun game(league: String, home: String, away: String) = FixtureClient.league(league)!!.let { l ->
        Fixture("$league:$home", l, 0, "pre", "", Team(home, null, null, null), Team(away, null, null, null), "$home - $away")
    }

    @Test fun competitionsTickedInOneSportKeepTheOtherSportWhole() {
        val leagues = SportsFunnel.effectiveLeagues(setOf("football", "rugby"), setOf("fra1", "ucl")).map { it.id }.toSet()
        val rugby = SportsFunnel.leaguesOf(setOf("rugby")).map { it.id }.toSet()
        assertEquals(setOf("fra1", "ucl") + rugby, leagues)
    }

    @Test fun noCompetitionTickedMeansAllOfThatSport() {
        assertEquals(SportsFunnel.leaguesOf(setOf("rugby")), SportsFunnel.effectiveLeagues(setOf("rugby"), emptySet()))
    }

    @Test fun clubsTickedInOneSportKeepTheOtherSportWhole() {
        val games = listOf(game("fra1", "Lille", "Lens"), game("fra1", "Nice", "Rennes"), game("top14", "Toulon", "Castres"))
        val kept = SportsFunnel.byClubs(games, listOf("Lille")).map { it.home!!.name }
        assertEquals(listOf("Lille", "Toulon"), kept)
    }
}
