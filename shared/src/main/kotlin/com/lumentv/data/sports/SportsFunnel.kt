package com.lumentv.data.sports

/**
 * The sports filter, as a funnel (same on TV and PC): one or more sports, then optionally some of their competitions
 * (none = all of them), then optionally some clubs among the games listed (none = all games).
 */
object SportsFunnel {
    /** How far ahead games are listed, by default; the user picks among [DAY_CHOICES] (setting "sport_days"). */
    const val DAYS = 7
    const val MAX_DAYS = 14
    val DAY_CHOICES = listOf(3, 7, 14)

    /** The saved period, or the default one. */
    fun days(saved: Int?): Int = saved?.takeIf { it in DAY_CHOICES } ?: DAYS

    /** Sports that have at least one competition in the official calendar. */
    val sports: List<Sport> = SportsCatalog.all.filter { s -> FixtureClient.leagues.any { it.sport == s.id } }

    fun leaguesOf(sports: Set<String>): List<League> = FixtureClient.leagues.filter { it.sport in sports }

    /** The competitions to load: the chosen ones among the chosen sports, or all competitions of those sports. */
    fun effectiveLeagues(sports: Set<String>, leagues: Set<String>): List<League> {
        val available = leaguesOf(sports)
        return available.filter { it.id in leagues }.ifEmpty { available }
    }

    /** Clubs (or fighters, drivers…) of the listed games, for the third step. */
    fun clubs(games: List<Fixture>): List<String> = games.flatMap { listOfNotNull(it.home?.name, it.away?.name) }.distinct().sorted()

    /** Games of the chosen clubs; chosen clubs absent from the list are ignored (no filter when none is present). */
    fun byClubs(games: List<Fixture>, clubs: Collection<String>): List<Fixture> {
        val present = clubs.toSet().intersect(clubs(games).toSet())
        if (present.isEmpty()) return games
        return games.filter { it.home?.name in present || it.away?.name in present }
    }
}
