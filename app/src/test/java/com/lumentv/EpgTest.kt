package com.lumentv

import com.lumentv.core.asXmlReader
import com.lumentv.core.FormatException
import com.lumentv.data.epg.EpgMatcher
import com.lumentv.data.epg.XmltvChannel
import com.lumentv.data.epg.XmltvParser
import com.lumentv.data.epg.XmltvProgram
import com.lumentv.core.channelKeyName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.StringReader

class EpgTest {
    private fun parse(xml: String): Pair<List<XmltvChannel>, List<XmltvProgram>> {
        val p = KXmlParser()
        p.setInput(StringReader(xml))
        val ch = ArrayList<XmltvChannel>()
        val pr = ArrayList<XmltvProgram>()
        XmltvParser.parse(p.asXmlReader(), { ch += it }, { pr += it })
        return ch to pr
    }

    @Test fun parsesTimesWithOffsets() {
        // 2026-10-27 20:30 at +01:00 = 19:30 UTC
        assertEquals(1_793_129_400_000L, XmltvParser.parseTime("20261027203000 +0100"))
        assertEquals(1_793_133_000_000L, XmltvParser.parseTime("20261027203000 +0000"))
        assertEquals(1_793_133_000_000L, XmltvParser.parseTime("20261027203000"))
        assertEquals(1_793_151_000_000L, XmltvParser.parseTime("20261027203000 -0500"))
        assertNull(XmltvParser.parseTime("garbage"))
    }

    @Test fun readsChannelsAndProgrammes() {
        val (ch, pr) = parse(
            """<?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE tv SYSTEM "xmltv.dtd">
            <tv generator-info-name="x">
              <channel id="TF1.fr"><display-name lang="fr">TF1</display-name><display-name>TF1 HD</display-name><icon src="http://i"/></channel>
              <programme start="20261027200000 +0100" stop="20261027210000 +0100" channel="TF1.fr">
                <title lang="fr">Le 20h &amp; la météo</title>
                <sub-title>Édition</sub-title>
                <desc lang="fr"><![CDATA[Les infos.]]></desc>
                <category>News</category>
              </programme>
              <programme start="20261027210000 +0100" channel="TF1.fr"><title>Film</title></programme>
              <programme start="bad" channel="TF1.fr"><title>Ignored</title></programme>
            </tv>"""
        )
        assertEquals(1, ch.size)
        assertEquals(listOf("TF1", "TF1 HD"), ch[0].names)
        assertEquals(2, pr.size)
        assertEquals("Le 20h & la météo", pr[0].title)
        assertEquals("Les infos.", pr[0].description)
        assertEquals(60 * 60_000L, pr[0].stop - pr[0].start)
        // Missing stop: 30 minutes by default.
        assertEquals(30 * 60_000L, pr[1].stop - pr[1].start)
    }

    @Test(expected = FormatException::class) fun notXmltvIsRejected() {
        parse("<?xml version=\"1.0\"?><rss><channel></channel></rss>")
    }

    @Test fun emptyGuideIsFine() {
        assertEquals(0, parse("<tv></tv>").second.size)
    }

    @Test fun matcherUsesOverrideThenIdThenName() {
        val m = EpgMatcher(listOf("tf1.fr", "france2.fr"), mapOf(channelKeyName("France 2") to "france2.fr"))
        assertEquals("tf1.fr", m.keyFor(null, "TF1.fr", "whatever"))
        assertEquals("france2.fr", m.keyFor(null, null, "FR| France 2 FHD"))
        assertEquals("custom", m.keyFor("Custom", "TF1.fr", "TF1"))
        assertNull(m.keyFor(null, "unknown.id", "Unknown channel"))
    }

    @Test fun largeGuideStreams() {
        val sb = StringBuilder("<tv>")
        repeat(300) { c -> sb.append("<channel id=\"c$c\"><display-name>C $c</display-name></channel>") }
        repeat(300) { c ->
            repeat(150) { i ->
                val h = 20260101000000L + i * 10000
                sb.append("<programme start=\"$h +0000\" stop=\"${h + 3000} +0000\" channel=\"c$c\"><title>P $i</title><desc>d</desc></programme>")
            }
        }
        sb.append("</tv>")
        var n = 0
        val p = KXmlParser(); p.setInput(StringReader(sb.toString()))
        XmltvParser.parse(p.asXmlReader(), {}, { n++ })
        assertEquals(45_000, n)
    }
}
