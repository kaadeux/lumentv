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
    }

    /** Live check of the public calendar: SPORTS_LIVE=1. */
    @Test fun calendarAnswers() = runBlocking {
        assumeTrue(System.getenv("SPORTS_LIVE") == "1")
        val list = FixtureClient(OkHttpClient()).fixtures(listOf(ligue1, FixtureClient.league("top14")!!), 5)
        java.io.File("build/fixtures.txt").writeText(list.joinToString("\n") { "${java.util.Date(it.start)} ${it.state} ${it.league.label} ${it.label}" })
        assertTrue(list.isNotEmpty())
    }
}
