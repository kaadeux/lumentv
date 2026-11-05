package com.lumentv.data.sports

import com.lumentv.core.normalize
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class FixturesTest {
    private val ligue1 = FixtureClient.league("fra1")!!
    private val psg = Team("Paris Saint-Germain", "PSG", "PSG", null)
    private val havre = Team("Le Havre AC", "Le Havre AC", "HAC", null)
    private val game = Fixture("1", ligue1, start = 1_000_000_000L, state = "pre", detail = "", home = havre, away = psg, name = "PSG at Le Havre")

    @Test fun guideTitlesMatchTheGame() {
        assertTrue(FixtureMatcher.matches(game, normalize("Football : Ligue 1 | Le Havre / PSG"), game.start - 15 * 60_000))
        assertTrue(FixtureMatcher.matches(game, normalize("Havre vs. PSG"), game.start))
        assertTrue(FixtureMatcher.matches(game, normalize("Ligue 1 McDonald's : Le Havre - Paris Saint-Germain"), game.start + 5 * 60_000))
        assertTrue(FixtureMatcher.matches(game, normalize("Football - Ligue 1 : PSG en direct"), game.start)) // one team + the competition
    }

    @Test fun replaysAndOtherGamesDoNot() {
        assertFalse(FixtureMatcher.matches(game, normalize("Ligue 1 : Le Havre / PSG"), game.start + 6 * 3_600_000L)) // replay later
        assertFalse(FixtureMatcher.matches(game, normalize("Ligue 1 : Lens / Lille"), game.start))
        assertFalse(FixtureMatcher.matches(game, normalize("Journal des sports"), game.start))
    }

    @Test fun eventChannelNamesWithTheirPreShowTime() {
        assertTrue(FixtureMatcher.matches(game, normalize("Havre vs. PSG"), game.start - 92 * 60_000, channelName = true))
        assertFalse(FixtureMatcher.matches(game, normalize("PSG vs. Lens"), game.start - 60 * 60_000, channelName = true))
    }

    @Test fun frenchGuideNamesAndChannels() {
        val l = Listing("DAZN 1", game.start, normalize("Le Havre / Paris-SG"))
        assertTrue(FrenchTvGuide.broadcast(game, listOf(l)) != null)
        assertTrue(FrenchTvGuide.channelKey("CANAL+ SPORT360 FHD") == FrenchTvGuide.channelKey("Canal+ Sport 360"))
        assertFalse(FrenchTvGuide.channelKey("CANAL+ SPORT+") == FrenchTvGuide.channelKey("Canal+ Sport"))
        assertTrue(FrenchTvGuide.channelKey("FR | CANAL+ SPORT [H265]") == FrenchTvGuide.channelKey("Canal+ Sport"))
        val pro = Fixture("2", FixtureClient.league("prod2")!!, game.start, "pre", "", Team("Colomiers", null, null, null), Team("SU Agen Lot-et-Garonne", null, null, null), "")
        assertTrue(FrenchTvGuide.broadcast(pro, listOf(Listing("Canal+ Sport 360", game.start, normalize("Colomiers / Agen")))) != null)
    }

    /** Live check of the public calendar: SPORTS_LIVE=1. */
    @Test fun calendarAnswers() = runBlocking {
        assumeTrue(System.getenv("SPORTS_LIVE") == "1")
        val list = FixtureClient(OkHttpClient()).fixtures(listOf(ligue1, FixtureClient.league("top14")!!, FixtureClient.league("ufc")!!, FixtureClient.league("prod2")!!), SportsFunnel.DAYS)
        java.io.File("build/fixtures.txt").writeText(list.joinToString("\n") { "${java.util.Date(it.start)} ${it.state} ${it.clock} ${it.homeScore}-${it.awayScore} | ${it.details} | ${it.label} | ${it.venue} | ${it.home?.logo} | ${it.away?.logo}" })
        assertTrue(list.isNotEmpty())
        // Whole Pro D2 rounds (the day request gives 3 games at most), and no country flag for a club.
        assertTrue(list.count { it.league.id == "prod2" } > 3)
        assertFalse(list.any { it.league.id == "top14" && listOfNotNull(it.home?.logo, it.away?.logo).any { u -> "/countries/" in u } })
    }

    @Test fun liveStatusInFrench() {
        val c = FixtureClient(OkHttpClient())
        org.junit.Assert.assertEquals("Mi-temps", c.liveFr("Halftime"))
        org.junit.Assert.assertEquals("2e mi-temps", c.liveFr("2nd Half"))
        org.junit.Assert.assertEquals("1re période", c.liveFr("1st Period"))
        org.junit.Assert.assertEquals("QT3 5:21", c.liveFr("Q3 5:21"))
        org.junit.Assert.assertEquals("Fin 1er temps", c.liveFr("End of 1st Quarter"))
        org.junit.Assert.assertEquals("67'", c.liveFr("67'"))
    }
}
