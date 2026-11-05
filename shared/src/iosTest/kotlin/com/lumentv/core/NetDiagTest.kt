package com.lumentv.core

import kotlin.test.Test

/** Diagnostic (temporary): which connections the CI simulator allows. Never fails, prints the outcome. */
class NetDiagTest {
    @Test fun reachability() {
        val http = AppleHttp(15_000)
        for (u in listOf("http://example.com/", "https://example.com/", "https://www.apple.com/", "https://api.themoviedb.org/3/configuration",
            "https://lumentv-sync.lumentv-sync.workers.dev/v1/health", "https://api.theintrodb.org/v3/media?tmdb_id=124364&season=3&episode=4")) {
            val r = runCatching { http.execute(HttpRequest(u)).use { "HTTP ${it.code} (${it.bytes().size} octets)" } }
            println("DIAG $u -> ${r.getOrElse { "ERREUR ${it.message}" }}")
        }
    }
}
