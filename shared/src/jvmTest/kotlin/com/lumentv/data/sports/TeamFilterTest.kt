package com.lumentv.data.sports

import kotlin.test.Test
import kotlin.test.assertEquals

class TeamFilterTest {
    private val l1 = FixtureClient.league("fra1")!!
    private val nat = FixtureClient.league("nat_foot")!!
    private fun game(l: League, h: String, a: String) = Fixture("$h$a", l, 0, "pre", "", Team(h, null, null, null), Team(a, null, null, null), "$h - $a")

    @Test fun aTeamWithNoGameInThePeriodStillFilters() {
        // International break: Rennes does not play, yet ticking it must not show every other club.
        val games = listOf(game(l1, "Lens", "Lille"), game(nat, "Italie", "France"))
        val known = listOf(TeamEntry("Rennes", "football", false))
        assertEquals(emptyList(), SportsFunnel.byClubs(games, listOf("Rennes"), known))
    }

    @Test fun clubsAndNationsApart() {
        val games = listOf(game(l1, "Lens", "Lille"), game(nat, "Italie", "France"))
        val teams = SportsFunnel.teamEntries(games, listOf(TeamEntry("Rennes", "football", false)))
        assertEquals(listOf("Lens", "Lille", "Rennes"), teams.filter { !it.nation }.map { it.name })
        assertEquals(listOf("France", "Italie"), teams.filter { it.nation }.map { it.name })
        assertEquals("Nations · France", SportsFunnel.teamCategory(teams, listOf("France", "Rennes"), true))
    }
}
