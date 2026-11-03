package com.lumentv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartSearchTest {
    private val titles = listOf(
        "Spider-Man (2002) FHD MULTI", "Spider-Man : No Way Home (2021) 4K VFF", "The Amazing Spider-Man (2012) HD VF",
        "Batman Begins (2005) FHD", "The Batman (2022) 4K MULTI", "Batman", "LEGO Batman, le film (2017)",
        "L'École du bien et du mal (2022) FHD", "Avengers : Endgame (2019) 4K", "Le Seigneur des anneaux : La Communauté de l'anneau",
        "Spiderhead (2022)", "Man on Fire (2004)", "Télé-Matin", "Pokémon : Le Film",
    )
    private val entries = titles.map { SmartSearch.Entry(it, it) }
    private fun find(q: String, n: Int = 5) = SmartSearch.search(SmartSearch.Query(q), entries, n)

    @Test fun dashesAndSpacesDoNotCount() {
        val r = find("spiderman")
        assertTrue(r.take(3).all { it.contains("Spider-Man") })
        assertEquals("Spider-Man (2002) FHD MULTI", r.first())
        assertTrue(find("spider man").first().startsWith("Spider-Man"))
    }

    @Test fun exactTitleFirst() {
        assertEquals("Batman", find("batman").first())
        assertTrue(find("batman", 10).count { it.contains("Batman") } == 4)
    }

    @Test fun accentsAndApostrophes() {
        assertTrue(find("ecole du bien").first().startsWith("L'École"))
        assertTrue(find("lecole").first().startsWith("L'École"))
        assertEquals("Télé-Matin", find("tele matin").first())
        assertEquals("Pokémon : Le Film", find("pokemon").first())
    }

    @Test fun partOfTheTitleAndWordsInAnyOrder() {
        assertTrue(find("communaute anneau").first().startsWith("Le Seigneur"))
        assertTrue(find("endgame").first().startsWith("Avengers"))
    }

    @Test fun typos() {
        assertTrue(find("spidrman").first().contains("Spider-Man"))
        assertTrue(find("avengrs").first().startsWith("Avengers"))
        assertTrue(find("batmn").first().contains("Batman"))
        // A typo match never comes before a real one.
        assertEquals("Spiderhead (2022)", find("spiderhead").first())
    }

    @Test fun distance() {
        assertEquals(1, SmartSearch.distance("spidrman", "spiderman", 2))
        assertEquals(1, SmartSearch.distance("btaman", "batman", 2))
        assertEquals(3, SmartSearch.distance("abc", "xyzabc", 2))
    }
}
