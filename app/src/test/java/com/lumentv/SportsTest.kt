package com.lumentv

import com.lumentv.data.sports.EventParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SportsTest {
    @Test fun readsSportCompetitionAndTeams() {
        val e = EventParser.parse("Football : Ligue 2 BKT | Rodez / Annecy")!!
        assertEquals("football", e.sport.id)
        assertEquals("Ligue 2 BKT", e.competition)
        assertEquals("Rodez", e.home)
        assertEquals("Annecy", e.away)
        assertEquals("Rodez – Annecy", e.label)
    }

    @Test fun moreSpecificSportWins() {
        assertEquals("nfl", EventParser.parse("Football américain : NFL | Philadelphia Eagles / Dallas Cowboys")!!.sport.id)
        assertEquals("baseball", EventParser.parse("Baseball : MLB | Tampa Bay Rays / Milwaukee Brewers")!!.sport.id)
    }

    @Test fun otherCommonShapes() {
        val a = EventParser.parse("Ligue 1 - PSG - OM")!!
        assertEquals("football", a.sport.id)
        assertEquals("PSG", a.home)
        assertEquals("OM", a.away)
        val b = EventParser.parse("NBA : Lakers vs Celtics")!!
        assertEquals("basket", b.sport.id)
        assertEquals("Lakers", b.home)
        assertEquals("Celtics", b.away)
        val c = EventParser.parse("Rugby : Top 14 | Toulouse - La Rochelle (J8)")!!
        assertEquals("La Rochelle", c.away)
    }

    @Test fun teamsWithXAndCompetitionAfter() {
        val a = EventParser.parse("Stuttgart x Atlético de Madrid – UEFA Champions League")!!
        assertEquals("football", a.sport.id)
        assertEquals("Stuttgart", a.home)
        assertEquals("Atlético de Madrid", a.away)
        assertEquals("UEFA Champions League", a.competition)
        val b = EventParser.parse("Raiders x Rams – NFL Domestic")!!
        assertEquals("nfl", b.sport.id)
        assertEquals("Rams", b.away)
        assertEquals("NFL Domestic", b.competition)
    }

    @Test fun readsEventChannelNames() {
        val (label, start) = com.lumentv.data.sports.EventChannelParser.parse("PRIME FR 022| Havre vs. PSG (2026-10-30 17:28:00)")!!
        assertEquals("Havre vs. PSG", label)
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = start }
        assertEquals(17, cal.get(java.util.Calendar.HOUR_OF_DAY))
        assertEquals(30, cal.get(java.util.Calendar.DAY_OF_MONTH))
        val e = EventParser.parse(label)
        assertNull(e) // no sport keyword: shown under "Chaînes événement"
        assertNull(com.lumentv.data.sports.EventChannelParser.parse("TF1 HD"))
    }

    @Test fun removesKodiColourTags() {
        val e = EventParser.parse("Golf : [COLOR tomato]PGA Tour[/COLOR] | [B]Zozo Championship[/B]")!!
        assertEquals("golf", e.sport.id)
        assertEquals("PGA Tour", e.competition)
        assertEquals("Zozo Championship", e.label)
    }

    @Test fun individualSportsWithoutTeams() {
        val f1 = EventParser.parse("Formule 1 : Grand Prix du Mexique - Course")
        assertEquals("moteur", f1!!.sport.id)
        assertNull(f1.home)
        assertEquals("tennis", EventParser.parse("Tennis : Rolex Paris Masters | Sinner / Alcaraz")!!.sport.id)
    }

    @Test fun skipsShowsThatAreNotMatches() {
        assertNull(EventParser.parse("Football : Canal Football Club"))
        assertNull(EventParser.parse("Ligue 1 : le résumé du multiplex"))
        assertNull(EventParser.parse("Football : magazine de la Ligue 1"))
        assertNull(EventParser.parse("Journal de 20h"))
        assertNull(EventParser.parse("Transporter T2 E2 Vuelta al pasado"))
        // A team sport needs two teams to be a match.
        assertNull(EventParser.parse("Football : Premier League"))
    }
}
