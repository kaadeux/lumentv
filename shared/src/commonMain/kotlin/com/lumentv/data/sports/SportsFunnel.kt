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
     * of them is ticked (ticking Ligue 1 must not hide the rugby). The national teams are one of them ("Équipes
     * nationales"): ticked, their games come with every nation, which the team filter then narrows (France…).
     */
    fun effectiveLeagues(sports: Set<String>, leagues: Set<String>): List<League> =
        sports.flatMap { s -> leaguesOf(setOf(s)).let { all -> all.filter { it.id in leagues }.ifEmpty { all } } }

    /**
     * The teams to offer in the third step: those of the listed games and every team of the chosen competitions
     * ([known]), clubs and nations apart, in alphabetical order (accents ignored).
     */
    fun teamEntries(games: List<Fixture>, known: List<TeamEntry>): List<TeamEntry> =
        (games.flatMap { g -> listOfNotNull(g.home, g.away).map { TeamEntry(it.name, g.league.sport, g.league.parts.isNotEmpty()) } } + known)
            .distinctBy { it.name to it.sport }.sortedBy { com.lumentv.core.normalize(it.name) }

    /** "Clubs · Rennes, PSG" or "Nations · Toutes": a category of the team filter and what is ticked in it. */
    fun teamCategory(teams: List<TeamEntry>, chosen: Collection<String>, nation: Boolean): String {
        val names = teams.filter { it.nation == nation }.mapTo(HashSet()) { it.name }
        return (if (nation) "Nations" else "Clubs") + " · " + summary(chosen.filter { it in names }, if (nation) "Toutes" else "Tous", if (nation) "nations" else "clubs")
    }

    /** Clubs (or fighters, drivers…) of the listed games, for the third step. */
    fun clubs(games: List<Fixture>): List<String> = games.flatMap { listOfNotNull(it.home?.name, it.away?.name) }.distinct().sorted()

    /**
     * Games of the chosen clubs, sport by sport: a sport where no listed club is ticked keeps all its games. Chosen
     * clubs absent from the list are ignored.
     */
    fun byClubs(games: List<Fixture>, clubs: Collection<String>, known: List<TeamEntry> = emptyList()): List<Fixture> {
        val chosen = clubs.toSet()
        if (chosen.isEmpty()) return games
        // A team of the sport counts even with no game in the period: Rennes ticked shows no other club meanwhile.
        val knownBySport = known.groupBy({ it.sport }, { it.name })
        val bySport = games.groupBy { it.league.sport }.mapValues { (s, g) -> (clubs(g) + knownBySport[s].orEmpty()).toSet().intersect(chosen) }
        return games.filter { g ->
            val picked = bySport[g.league.sport].orEmpty()
            picked.isEmpty() || g.home?.name in picked || g.away?.name in picked
        }
    }
}
