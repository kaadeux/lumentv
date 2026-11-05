package com.lumentv.data

import com.lumentv.core.asHttp
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Live: the databases together find the parts of episodes TheIntroDB alone lacked. */
class IntroDbTest {
    @Test fun mergedSources() = runBlocking {
        val db = IntroDb(OkHttpClient().asHttp())
        val out = StringBuilder()
        fun fmt(r: LongRange?) = r?.let { "${it.first / 1000}-${if (it.last > 1_000_000_000) "fin" else (it.last / 1000).toString()} s" } ?: "—"
        val cases = listOf(Triple("124364", 3, 4), Triple("124364", 1, 1), Triple("66732", 1, 1), Triple("67744", 1, 3))
        for ((tmdb, s, e) in cases) {
            val t = System.currentTimeMillis()
            val seg = db.segments(tmdb, s, e)
            out.append("tmdb $tmdb S${s}E$e (${System.currentTimeMillis() - t} ms): récap ${fmt(seg?.recap)} · intro ${fmt(seg?.intro)} · générique ${fmt(seg?.credits)}\n")
        }
        val t = System.currentTimeMillis(); db.segments("124364", 3, 4)
        out.append("again from cache: ${System.currentTimeMillis() - t} ms\n")
        java.io.File("build/introdb.txt").writeText(out.toString())
        val from = db.segments("124364", 3, 4)
        assertNotNull(from?.intro); assertNotNull(from?.credits)
        assertTrue(db.segments("66732", 1, 1)?.intro != null)
    }

    @Test fun introOverTheRecapIsDropped() {
        val wrong = Segments(0L until 115_000L, 1_800L until 118_500L, null)
        assertTrue(wrong.sane().intro == null)
        val right = Segments(0L until 115_000L, 363_000L until 484_000L, null)
        assertTrue(right.sane().intro == right.intro)
        assertTrue(wrong.sane().fill(Segments(null, 363_000L until 484_000L, null)).intro == right.intro)
    }
}
