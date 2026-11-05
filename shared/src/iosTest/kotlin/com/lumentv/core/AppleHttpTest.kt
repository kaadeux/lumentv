package com.lumentv.core

import com.lumentv.data.IntroDb
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The iPhone's network (NSURLSession) under the shared clients: real requests, on the simulator. */
class AppleHttpTest {
    private val http = AppleHttp()

    @Test fun getAndHeaders() {
        http.execute(HttpRequest("https://lumentv-sync.lumentv-sync.workers.dev/v1/health")).use { r ->
            assertEquals(200, r.code)
            assertTrue(r.text().contains("lumentv-sync"))
            assertTrue(r.header("content-type")?.contains("json") == true)
        }
        // An HTTP error status is an answer, not an exception.
        http.execute(HttpRequest("https://lumentv-sync.lumentv-sync.workers.dev/nope")).use { assertEquals(404, it.code) }
    }

    @Test fun introDbThroughAppleNetwork() = runBlocking {
        // The same episode as the JVM IntroDbTest (TMDB 124364, S3 E4): an intro and credits in the databases.
        val seg = IntroDb(http).segments("124364", 3, 4)
        assertNotNull(seg?.intro)
        assertNotNull(seg?.credits)
    }
}
