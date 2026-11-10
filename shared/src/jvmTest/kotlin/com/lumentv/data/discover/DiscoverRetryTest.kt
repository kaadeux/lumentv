package com.lumentv.data.discover

import com.lumentv.core.Http
import com.lumentv.core.HttpBody
import com.lumentv.core.HttpRequest
import com.lumentv.core.HttpResponse
import com.lumentv.core.IOException
import com.lumentv.data.Tmdb
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiscoverRetryTest {
    private val film = VodLite(1, "x:1", "Le Film Test", "Action", "2026", null)
    private val other = VodLite(2, "x:2", "Autre Film", "Action", "2026", null)

    private fun input() = DiscoverInput(false, TitleIndex(listOf(film, other), emptyList()), listOf(film), emptyList(), LinkedHashMap(), emptySet(), link = { null })

    /** TMDB's answer: the two titles of the playlist, on any list. */
    private val answer = """{"results":[{"id":1,"title":"Le Film Test","original_title":"Le Film Test","release_date":"2026-01-01"},
        {"id":2,"title":"Autre Film","original_title":"Autre Film","release_date":"2026-01-01"}]}"""

    private fun tmdb(failFirst: Int): Pair<Tmdb, () -> Int> {
        var calls = 0
        val http = object : Http {
            override fun execute(request: HttpRequest): HttpResponse {
                calls++
                if (calls <= failFirst) throw IOException("pas de réseau")
                return HttpResponse(200, { null }, HttpBody.of(answer.encodeToByteArray()))
            }
        }
        return Tmdb(http, "cle") to { calls }
    }

    private fun titles(rows: List<Row>) = rows.filter { it.items.isNotEmpty() }.map { it.title }

    @Test fun rowsComeBackAfterAFailure() = runBlocking {
        // Network down all along.
        val (t, _) = tmdb(failFirst = Int.MAX_VALUE)
        val down = DiscoverEngine(t, retryMs = 1).rows(input()).toList().last()
        assertFalse("Tendances du moment" in titles(down), "sans réseau, pas de rangée TMDB")

        // Down for the first calls only: the rows arrive on a later pass.
        val (t2, _) = tmdb(failFirst = 30)
        val back = DiscoverEngine(t2, retryMs = 1).rows(input()).toList().last()
        assertTrue("Tendances du moment" in titles(back), "la rangée revient : ${titles(back)}")
    }

    @Test fun noRetryWhenAllIsWell() = runBlocking {
        val (t, calls) = tmdb(failFirst = 0)
        val steps = DiscoverEngine(t, retryMs = 1).rows(input()).toList()
        assertEquals(3, steps.size, "trois étapes, sans nouvel essai")
        assertTrue(calls() > 0)
    }
}
