package com.lumentv

import com.lumentv.core.Redact
import com.lumentv.core.channelKeyName
import com.lumentv.core.normalize
import com.lumentv.data.db.ChannelEntity
import com.lumentv.data.db.ChannelRow
import com.lumentv.data.repo.LibraryRepository
import com.lumentv.data.repo.StreamResolver
import com.lumentv.data.repo.XtreamImport
import com.lumentv.data.xtream.XLive
import com.lumentv.data.xtream.XtreamApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XtreamAndCoreTest {
    @Test fun normalizesServerAddresses() {
        assertEquals("http://host:8080", XtreamApi.normalizeServer("host:8080"))
        assertEquals("https://host", XtreamApi.normalizeServer("https://host/"))
        assertEquals("http://h:80", XtreamApi.normalizeServer("http://h:80/player_api.php?username=a"))
        assertEquals("http://h:80", XtreamApi.normalizeServer("http://h:80/get.php?username=a&password=b&type=m3u_plus"))
    }

    @Test fun detectsXtreamInM3uLink() {
        val c = XtreamApi.fromM3uLink("http://h:8080/get.php?username=bob&password=s%40cret&type=m3u_plus&output=ts")!!
        assertEquals("http://h:8080", c.server)
        assertEquals("bob", c.username)
        assertEquals("s@cret", c.password)
        assertNull(XtreamApi.fromM3uLink("http://h/list.m3u"))
    }

    @Test fun buildsCatchupUrlInServerTimeZone() {
        // 2026-10-27 19:30 UTC = 20:30 in Paris (winter time from 25 Oct 2026).
        val url = XtreamApi.catchupUrl("http://h:80", "u", "p", 42, 1_793_129_400_000L, 60, "Europe/Paris")
        assertEquals("http://h:80/timeshift/u/p/60/2026-10-27:20-30/42.ts", url)
    }

    @Test fun streamsListsAndSkipsJunk() {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        val items = ArrayList<JsonObject>()
        XtreamApi.forEachObject(json, """[{"stream_id":"1","name":"A"},{"stream_id":2,"name":"B"},5,null]""".byteInputStream()) { items += it }
        assertEquals(listOf("A", "B"), items.map { it["name"]!!.jsonPrimitive.content })
        items.clear()
        XtreamApi.forEachObject(json, "[]".byteInputStream()) { items += it }
        assertTrue(items.isEmpty())
    }

    @Test fun parsesSeriesInfoInBothShapes() {
        val json = Json { ignoreUnknownKeys = true }
        val asObject = json.parseToJsonElement(
            """{"episodes":{"2":[{"id":"21","episode_num":"1","title":"B1","season":2,"container_extension":"mkv","info":{"duration":"00:45:00"}}],
               "1":[{"id":"12","episode_num":2,"title":"A2","season":1},{"id":"11","episode_num":1,"title":"A1","season":1}]}}"""
        )
        val eps = XtreamApi.parseSeriesEpisodes(asObject)
        assertEquals(listOf("A1", "A2", "B1"), eps.map { it.title })
        assertEquals(2700, eps[2].durationSecs)
        val asArray = json.parseToJsonElement("""{"episodes":[[{"id":"5","episode_num":"1","title":"X","season":"1"}]]}""")
        assertEquals(1, XtreamApi.parseSeriesEpisodes(asArray).size)
        assertTrue(XtreamApi.parseSeriesEpisodes(json.parseToJsonElement("""{"episodes":null}""")).isEmpty())
    }

    @Test fun xtreamImportMapsCategoriesAndArchive() {
        val rows = XtreamImport.channels(7, listOf(XLive(10, "TF1", "3", "", "tf1.fr", 1, 7), XLive(11, "X", "99", null, null, null, 0)), mapOf("3" to "France"))
        assertEquals("France", rows[0].groupName)
        assertEquals("Sans catégorie", rows[1].groupName)
        assertEquals("x:10", rows[0].uid)
        assertNull(rows[0].logo)
        assertEquals(7, rows[0].catchupDays)
    }

    @Test fun redactsCredentials() {
        val r = Redact.text("GET http://bob:pw@h/get.php?username=bob&password=secret&type=m3u and http://h/live/bob/secret/12.ts")
        assertFalse(r, r.contains("secret"))
        assertFalse(r, r.contains("bob:pw"))
        assertTrue(r.contains("/live/***/***/12.ts"))
    }

    @Test fun normalizesForSearch() {
        assertEquals("tele matin", normalize("Télé-Matin"))
        assertEquals("canal plus sport 360", normalize("  CANAL+ plus  Sport 360 "))
        assertEquals("tf1", channelKeyName("FR| TF1 HD"))
        assertEquals("bfm tv", channelKeyName("BFM TV"))
        assertEquals("m6", channelKeyName("M6 FHD 1080p"))
    }

    @Test fun fillsCatchupTemplates() {
        val start = 1_793_129_400_000L
        val stop = start + 3_600_000L
        assertEquals("?utc=1793129400&lutc=1793136600", StreamResolver.fillTemplate("?utc={utc}&lutc={lutc}", start, stop, 1_793_136_600_000L))
        assertEquals("http://h/2026/10/27/19-30/3600.ts", StreamResolver.fillTemplate("http://h/{Y}/{m}/{d}/{H}-{M}/{duration}.ts", start, stop))
    }

    @Test fun sortsChannels() {
        fun row(n: String, i: Int) = ChannelRow(ChannelEntity(id = i.toLong(), playlistId = 1, uid = n, name = n, nameNorm = normalize(n), groupName = "", number = i), null, null, null, null)
        val list = listOf(row("Zeta", 1), row("alpha", 2), row("Été", 3))
        assertEquals(listOf("alpha", "Été", "Zeta"), LibraryRepository.sortChannels(list, "az").map { it.ch.name })
        assertEquals(listOf("Zeta", "Été", "alpha"), LibraryRepository.sortChannels(list, "za").map { it.ch.name })
        assertEquals(list, LibraryRepository.sortChannels(list, "provider"))
    }
}
