package com.lumentv.core

import com.lumentv.data.m3u.M3uParser
import com.lumentv.data.m3u.M3uEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The shared code, run on the JVM (TV, phone, PC) and on the iPhone simulator: same results everywhere. */
class CommonTest {
    @Test fun accentsAndPunctuation() {
        assertEquals("tele matin", normalize("Télé-Matin"))
        assertEquals("lecole", normalize("L'École").replace(" ", ""))
        assertEquals("tf1", channelKeyName("FR| TF1 HD"))
    }

    @Test fun numbersAsWrittenInFrench() {
        assertEquals("7,3", oneDecimal(7.26))
        assertEquals("10,0", oneDecimal(9.96))
        assertEquals("Reste 1 h 05", Resume.remaining(0, 65 * 60_000L))
        assertEquals("2026 · ★ 7,2 · MULTI", Featured.posterLine("2026", "7.2", "Hope (2026) MULTI"))
    }

    @Test fun urlDecoding() {
        assertEquals("Été à Paris", urlDecode("%C3%89t%C3%A9+%C3%A0+Paris"))
        assertEquals("100%", urlDecode("100%"))
        assertEquals("a b", urlDecode("a+b"))
    }

    @Test fun m3uLines() {
        val text = "#EXTM3U url-tvg=\"http://g.example/epg.xml\"\n#EXTINF:-1 tvg-id=\"tf1.fr\" group-title=\"FR\",TF1 HD\nhttp://s.example/1.ts\n"
        val lines = text.lines().iterator()
        val out = ArrayList<M3uEntry>()
        var epg: String? = null
        M3uParser.parse({ if (lines.hasNext()) lines.next() else null }, { epg = it.epgUrl }) { out += it }
        assertEquals("http://g.example/epg.xml", epg)
        assertEquals("TF1 HD", out.single().name)
        assertEquals("tf1.fr", out.single().tvgId)
    }

    @Test fun searchTitles() {
        val q = SmartSearch.Query("spidrman")
        val hits = SmartSearch.search(q, listOf(SmartSearch.Entry("a", "Spider-Man (2002)"), SmartSearch.Entry("b", "Batman")), 5)
        assertEquals(listOf("a"), hits)
        assertTrue(Featured.foreign("AR | The Greatest", "Séries SUB-AR"))
        assertEquals("Les Misérables", Featured.searchTitle("Les Misérables - 2012"))
    }
}
