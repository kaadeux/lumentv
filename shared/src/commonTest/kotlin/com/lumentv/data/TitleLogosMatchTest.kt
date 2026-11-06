package com.lumentv.data

import com.lumentv.core.Http
import com.lumentv.core.HttpBody
import com.lumentv.core.HttpRequest
import com.lumentv.core.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TitleLogosMatchTest {
    /** TMDB: searches answered by [search] (does the URL ask a year?), details by id. */
    private class Fake(val search: (withYear: Boolean) -> String) : Http {
        val asked = ArrayList<String>()
        override fun execute(request: HttpRequest): HttpResponse {
            asked += request.url
            val body = if ("/search/" in request.url) search("year=" in request.url)
            else """{"overview":"Résumé de ${request.url.substringAfter("/movie/").substringBefore('?')}","vote_count":0}"""
            return HttpResponse(200, { null }, HttpBody.of(body.encodeToByteArray()))
        }
    }

    private fun results(vararg titles: Pair<Int, String>) =
        """{"results":[${titles.joinToString(",") { (id, t) -> """{"id":$id,"title":"$t","original_title":"$t"}""" }}]}"""

    private val title = "Un an à vivre, un homme à acheter"

    @Test fun anotherFilmInFirstPlaceIsSkipped() {
        val http = Fake { results(1 to "Un homme à part", 2 to title) }
        assertEquals("Résumé de 2", TitleLogos(http, "k").info(false, null, title, null)?.overview)
    }

    @Test fun noResultWithThatTitleKeepsTheProvidersSummary() {
        val http = Fake { results(1 to "Un homme à part", 3 to "Vivre") }
        assertNull(TitleLogos(http, "k").info(false, null, title, null))
    }

    @Test fun aWrongYearFallsBackToTheSearchWithoutYear() {
        val http = Fake { withYear -> if (withYear) results(1 to "Un homme à part") else results(2 to title) }
        assertEquals("Résumé de 2", TitleLogos(http, "k").info(false, null, title, 2019)?.overview)
        assertEquals(2, http.asked.count { "/search/" in it })
    }

    @Test fun theRightYearAsksOnce() {
        val http = Fake { results(2 to title) }
        assertNotNull(TitleLogos(http, "k").info(false, null, title, 2019))
        assertEquals(1, http.asked.count { "/search/" in it })
    }

    @Test fun titlesThatAreTheSameFilm() {
        assertEquals(3, TmdbMatch.score("L'École", listOf("L ecole")))
        assertEquals(2, TmdbMatch.score("Dune", listOf("Dune : Deuxième partie")))
        assertEquals(1, TmdbMatch.score("Harry Potter 1", listOf("Harry Potter à l'école des sorciers")))
        assertEquals(3, TmdbMatch.score("The Matrix", listOf("Matrix", "The Matrix")))
        assertEquals(0, TmdbMatch.score(title, listOf("Un homme à part")))
    }
}
