package com.lumentv

import com.lumentv.core.FormatException
import com.lumentv.data.m3u.EntryKind
import com.lumentv.data.m3u.M3uClassifier
import com.lumentv.data.m3u.M3uEntry
import com.lumentv.data.m3u.M3uParser
import com.lumentv.data.repo.M3uImport
import com.lumentv.data.repo.NO_GROUP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader

class M3uTest {
    private fun parse(text: String): Pair<List<M3uEntry>, String?> {
        val out = ArrayList<M3uEntry>()
        var epg: String? = null
        M3uParser.parse(BufferedReader(StringReader(text))::readLine, { epg = it.epgUrl }) { out += it }
        return out to epg
    }

    @Test fun parsesAttributesNameAndHeader() {
        val (list, epg) = parse(
            """
            #EXTM3U url-tvg="http://epg.example/guide.xml.gz"
            #EXTINF:-1 tvg-id="tf1.fr" tvg-name="TF1" tvg-logo="http://logo/tf1.png" group-title="France, TNT",TF1 HD, le direct
            http://srv/live/1.ts
            """.trimIndent()
        )
        assertEquals("http://epg.example/guide.xml.gz", epg)
        assertEquals(1, list.size)
        val e = list[0]
        assertEquals("TF1 HD, le direct", e.name)
        assertEquals("France, TNT", e.group)
        assertEquals("tf1.fr", e.tvgId)
        assertEquals("http://logo/tf1.png", e.logo)
        assertEquals("http://srv/live/1.ts", e.url)
    }

    @Test fun readsHeadersFromVlcOptKodiPropAndPipe() {
        val (list, _) = parse(
            """
            #EXTM3U
            #EXTINF:-1,One
            #EXTVLCOPT:http-user-agent=MyAgent
            #EXTVLCOPT:http-referrer=http://ref/
            http://a/1.m3u8
            #EXTINF:-1,Two
            http://a/2.m3u8|User-Agent=Other%20Agent&Referer=http://r2/
            #EXTINF:-1,Three
            #KODIPROP:inputstream.adaptive.stream_headers=User-Agent=K&Referer=http://k/
            http://a/3.m3u8
            """.trimIndent()
        )
        assertEquals("MyAgent", list[0].userAgent)
        assertEquals("http://ref/", list[0].referer)
        assertEquals("http://a/2.m3u8", list[1].url)
        assertEquals("Other Agent", list[1].userAgent)
        assertEquals("http://r2/", list[1].referer)
        assertEquals("K", list[2].userAgent)
        // Options do not leak to the next entry.
        assertNull(parse("#EXTM3U\n#EXTINF:-1,A\n#EXTVLCOPT:http-user-agent=X\nhttp://a\n#EXTINF:-1,B\nhttp://b").first[1].userAgent)
    }

    @Test fun handlesBomCrlfExtgrpAndMissingExtinf() {
        val (list, _) = parse("﻿#EXTM3U\r\n#EXTINF:-1,Chan\r\n#EXTGRP:Sports\r\nhttp://x/1\r\nhttp://x/2\r\n")
        assertEquals(2, list.size)
        assertEquals("Sports", list[0].group)
        assertEquals("2", list[1].name)
    }

    @Test fun emptyPlaylistWithHeaderIsValidAndEmpty() {
        assertEquals(0, parse("#EXTM3U\n").first.size)
    }

    @Test(expected = FormatException::class) fun htmlIsRejected() {
        parse("<!DOCTYPE html><html><body>Login</body></html>")
    }

    @Test(expected = FormatException::class) fun garbageIsRejected() {
        parse("hello world\nnot a playlist")
    }

    @Test(expected = FormatException::class) fun xmltvGivenAsPlaylistIsRejected() {
        parse("<?xml version=\"1.0\"?><tv></tv>")
    }

    @Test fun classifiesLiveMoviesAndEpisodes() {
        fun e(name: String, url: String, group: String = "") = M3uEntry(name, url, emptyMap(), group, null, null)
        assertEquals(EntryKind.LIVE, M3uClassifier.classify(e("TF1", "http://s/live/u/p/1.ts")).kind)
        assertEquals(EntryKind.LIVE, M3uClassifier.classify(e("France 2", "http://s/fr2.m3u8")).kind)
        assertEquals(EntryKind.MOVIE, M3uClassifier.classify(e("Dune (2021)", "http://s/movie/u/p/9.mkv")).kind)
        assertEquals(EntryKind.MOVIE, M3uClassifier.classify(e("Film", "http://s/a/b/film.mp4")).kind)
        val ep = M3uClassifier.classify(e("The Office S02 E05", "http://s/series/u/p/77.mp4"))
        assertEquals(EntryKind.EPISODE, ep.kind)
        assertEquals("The Office", ep.seriesName)
        assertEquals(2, ep.season)
        assertEquals(5, ep.episode)
        val ep2 = M3uClassifier.classify(e("Lupin S1E3", "http://s/x/lupin.mkv"))
        assertEquals(EntryKind.EPISODE, ep2.kind)
        assertEquals("Lupin", ep2.seriesName)
    }

    @Test fun importDedupesAndKeepsUidsUnique() {
        val r = M3uImport.build(
            1, BufferedReader(StringReader(
                """
                #EXTM3U
                #EXTINF:-1 group-title="A",TF1
                http://s/1
                #EXTINF:-1 group-title="B",TF1
                http://s/1
                #EXTINF:-1 group-title="B",TF1
                http://s/2
                #EXTINF:-1,No group
                http://s/3
                #EXTINF:-1 group-title="Séries",Lupin S01E01
                http://s/series/u/p/1.mkv
                #EXTINF:-1 group-title="Séries",Lupin S01E02
                http://s/series/u/p/2.mkv
                #EXTINF:-1 group-title="Films",Dune
                http://s/movie/u/p/5.mp4
                """.trimIndent()
            )), dedupe = true,
        )
        assertEquals(1, r.duplicatesSkipped)
        assertEquals(3, r.channels.size)
        assertEquals(setOf(3), setOf(r.channels.map { it.uid }.toSet().size))
        assertEquals(NO_GROUP, r.channels.last().groupName)
        assertEquals(listOf(1, 2, 3), r.channels.map { it.number })
        assertEquals(1, r.series.size)
        assertEquals(2, r.episodes.size)
        assertEquals("Lupin", r.series[0].name)
        assertEquals(1, r.movies.size)
    }

    @Test fun uidIsStableAcrossRefreshes() {
        val text = "#EXTM3U\n#EXTINF:-1 tvg-id=\"x\",Chaîne Été\nhttp://s/token=AAA\n"
        val a = M3uImport.build(1, BufferedReader(StringReader(text)), true).channels[0].uid
        val b = M3uImport.build(1, BufferedReader(StringReader(text.replace("AAA", "BBB"))), true).channels[0].uid
        assertEquals(a, b)
    }

    @Test fun largePlaylistParsesQuickly() {
        val sb = StringBuilder("#EXTM3U\n")
        repeat(60_000) { i ->
            sb.append("#EXTINF:-1 tvg-id=\"c$i\" tvg-logo=\"http://l/$i.png\" group-title=\"G${i % 40}\",Channel $i HD\nhttp://srv/live/u/p/$i.ts\n")
        }
        val t0 = System.nanoTime()
        val r = M3uImport.build(1, BufferedReader(StringReader(sb.toString())), dedupe = true)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(60_000, r.channels.size)
        assertTrue("60 000 entries took $ms ms", ms < 8_000)
    }
}
