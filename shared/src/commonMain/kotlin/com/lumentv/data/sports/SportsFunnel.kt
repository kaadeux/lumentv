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

    /** The order the sports are offered in; the others follow. */
    private val ORDER = listOf("football", "rugby", "basket", "combat", "moteur", "nfl")

    /** Sports that have at least one competition in the official calendar. */
    val sports: List<Sport> = SportsCatalog.all.filter { s -> FixtureClient.leagues.any { it.sport == s.id } }
        .sortedBy { s -> ORDER.indexOf(s.id).let { if (it < 0) ORDER.size else it } }

    fun leaguesOf(sports: Set<String>): List<League> =
        FixtureClient.leagues.filter { it.sport in sports }.sortedBy { l -> this.sports.indexOfFirst { it.id == l.sport } }

    /** What a filter button says: "Toutes", "Ligue 1", "Ligue 1, Top 14", "4 compétitions". */
    fun summary(chosen: List<String>, none: String, plural: String): String = when (chosen.size) {
        0 -> none
        1, 2 -> chosen.joinToString(", ")
        else -> "${chosen.size} $plural"
    }

    /**
     * The competitions to load, sport by sport: the ones ticked for that sport, or all of its competitions when none
     * of them is ticked (ticking Ligue 1 must not hide the rugby). The France teams' games come with their sport always:
     * a selection made before they were offered must not hide the Bleus.
     */
    fun effectiveLeagues(sports: Set<String>, leagues: Set<String>): List<League> =
        sports.flatMap { s -> leaguesOf(setOf(s)).let { all -> all.filter { it.id in leagues }.ifEmpty { all } + all.filter { it.national } } }.distinct()

    /** Clubs (or fighters, drivers…) of the listed games, for the third step. */
    fun clubs(games: List<Fixture>): List<String> = games.flatMap { listOfNotNull(it.home?.name, it.away?.name) }.distinct().sorted()

    /**
     * Games of the chosen clubs, sport by sport: a sport where no listed club is ticked keeps all its games. Chosen
     * clubs absent from the list are ignored.
     */
    fun byClubs(games: List<Fixture>, clubs: Collection<String>): List<Fixture> {
        val chosen = clubs.toSet()
        if (chosen.isEmpty()) return games
        val bySport = games.groupBy { it.league.sport }.mapValues { (_, g) -> clubs(g).toSet().intersect(chosen) }
        return games.filter { g ->
            val picked = bySport[g.league.sport].orEmpty()
            picked.isEmpty() || g.home?.name in picked || g.away?.name in picked
        }
    }
}
