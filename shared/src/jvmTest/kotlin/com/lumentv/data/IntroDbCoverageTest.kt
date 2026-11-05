package com.lumentv.data

import com.lumentv.core.asHttp
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Dev only (COVERAGE_TMDB=<series tmdb id>): what the merged sources know for every episode of a series. */
class IntroDbCoverageTest {
    @Test fun coverage() = runBlocking {
        val tmdb = System.getenv("COVERAGE_TMDB")
        assumeTrue(tmdb != null)
        val seasons = System.getenv("COVERAGE_SEASONS")?.toInt() ?: 4
        val db = IntroDb(OkHttpClient().asHttp())
        fun fmt(r: LongRange?) = r?.let { "%d:%02d".format(it.first / 60_000, it.first / 1000 % 60) } ?: "—"
        val out = StringBuilder()
        for (s in 1..seasons) for (e in 1..12) {
            val seg = db.segments(tmdb!!, s, e)
            out.append("S${s}E$e\t${fmt(seg?.recap)}\t${fmt(seg?.intro)}\t${fmt(seg?.credits)}\n")
            Thread.sleep(300)
        }
        java.io.File("build/coverage.txt").writeText(out.toString())
    }
}
