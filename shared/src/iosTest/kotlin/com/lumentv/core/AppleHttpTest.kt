package com.lumentv.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The iPhone's network (NSURLSession) under the shared clients, with a real request on the simulator. Plain HTTP
 * only: GitHub's simulators have no usable certificate store, every HTTPS site fails there ("certificate invalid",
 * apple.com too, checked on 2026-11-05); HTTPS (TMDB, the profile) is checked on a real iPhone.
 */
class AppleHttpTest {
    @Test fun realRequest() {
        AppleHttp(15_000).execute(HttpRequest("http://example.com/", headers = mapOf("User-Agent" to "Lumen TV"))).use { r ->
            assertEquals(200, r.code)
            assertTrue(r.text().contains("Example Domain"))
            assertTrue(r.header("Content-Type")?.contains("text/html") == true)
        }
    }

    @Test fun noServer() {
        val e = runCatching { AppleHttp(5_000).execute(HttpRequest("http://serveur-qui-n-existe-pas.invalid/")) }.exceptionOrNull()
        assertTrue(e is IOException, "attendu : IOException, reçu : $e")
        assertTrue(userMessage(e!!).isNotBlank())
    }
}
