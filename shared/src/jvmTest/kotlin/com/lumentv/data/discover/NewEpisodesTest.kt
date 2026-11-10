package com.lumentv.data.discover

import com.lumentv.core.Http
import com.lumentv.data.Tmdb
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NewEpisodesTest {
    @Test fun badges() {
        assertEquals("Nouvelle saison", NewEpisodes.badge(2, listOf(3)))
        assertEquals("Nouvel épisode", NewEpisodes.badge(2, listOf(2)))
        assertEquals("3 nouveaux épisodes", NewEpisodes.badge(2, listOf(2, 2, 2)))
    }

    @Test fun rowFirstOnlyWhenSomethingIsNew() = runBlocking {
        val from = VodLite(1, "s1", "EN - FROM (2022) (US)", "Séries", "2022", "8")
        val idx = TitleIndex(emptyList(), listOf(from))
        val engine = DiscoverEngine(Tmdb(object : Http { override fun execute(request: com.lumentv.core.HttpRequest) = error("no network") }, ""))
        val withNew = engine.rows(DiscoverInput(true, idx, listOf(from), emptyList(), LinkedHashMap(), link = { null },
            newEpisodes = { listOf(from to "Nouvelle saison") })).last()
        assertEquals(DiscoverEngine.NEW_EPISODES, withNew.first().title)
        assertEquals("Nouvelle saison", withNew.first().badges[1L])
        val without = engine.rows(DiscoverInput(true, idx, listOf(from), emptyList(), LinkedHashMap(), link = { null },
            newEpisodes = { emptyList() })).last()
        assertTrue(without.none { it.title == DiscoverEngine.NEW_EPISODES })
        assertTrue(without.none { it.title == "Vos notes" })
    }
}
