package com.lumentv.core

import com.lumentv.data.epg.XmltvChannel
import com.lumentv.data.epg.XmltvParser
import com.lumentv.data.epg.XmltvProgram
import org.junit.Assert.assertEquals
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.StringReader

/** The iPhone's XML reader gives the same guide as kxml2 (the PC's), on awkward XMLTV: entities, CDATA, comments… */
class KotlinXmlReaderTest {
    private val guide = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE tv SYSTEM "xmltv.dtd">
<!-- généré par un fournisseur -->
<tv generator-info-name='test' date="20261105">
  <channel id="tf1.fr"><display-name lang="fr">TF1 HD</display-name><display-name>TF1</display-name><icon src="http://x/tf1.png"/></channel>
  <channel id='fr2'><display-name>France 2 &amp; Co</display-name></channel>
  <channel id=""><display-name>Sans id</display-name></channel>
  <programme start="20261105203000 +0100" stop="20261105222000 +0100" channel="tf1.fr">
    <title lang="fr">Koh-Lanta : l'île &#233;t&#xE9; &lt;inédit&gt;</title>
    <sub-title>Épisode 3</sub-title>
    <desc lang="fr"><![CDATA[Les aventuriers <b>s'affrontent</b> & se méfient.]]></desc>
    <category>Jeu</category>
    <rating system="CSA"><value>-10</value></rating>
  </programme>
  <programme start="20261105222000" channel="fr2"><title>Sans fin &quot;indiquée&quot; 😀</title></programme>
  <programme start="pas une date" channel="fr2"><title>Ignoré</title></programme>
</tv>"""

    private fun parse(reader: XmlReader): Pair<List<XmltvChannel>, List<XmltvProgram>> {
        val ch = ArrayList<XmltvChannel>(); val pr = ArrayList<XmltvProgram>()
        XmltvParser.parse(reader, { ch += it }, { pr += it })
        return ch to pr
    }

    @Test fun sameAsKxml() {
        val kxml = parse(KXmlParser().apply { setInput(StringReader(guide)) }.asXmlReader())
        // Read in very small pieces too: characters cut between two pieces (é, the emoji) are put back together.
        for (piece in listOf(1, 2, 3, 7, 64 * 1024)) {
            val mine = parse(KotlinXmlReader(chunked(guide.encodeToByteArray(), piece)))
            assertEquals("pieces of $piece", kxml, mine)
        }
        assertEquals(2, kxml.first.size)
        assertEquals("Koh-Lanta : l'île été <inédit>", kxml.second[0].title)
        assertEquals("Les aventuriers <b>s'affrontent</b> & se méfient.", kxml.second[0].description)
        assertEquals("Sans fin \"indiquée\" 😀", kxml.second[1].title)
    }

    @Test fun entities() {
        assertEquals("a & b < c > d \" e ' f é g 😀 &unknown; &", KotlinXmlReader.decodeEntities("a &amp; b &lt; c &gt; d &quot; e &apos; f &#233; g &#x1F600; &unknown; &"))
    }

    private fun chunked(bytes: ByteArray, size: Int) = object : HttpBody {
        var pos = 0
        override fun read(buffer: ByteArray): Int {
            if (pos >= bytes.size) return -1
            val n = minOf(size, buffer.size, bytes.size - pos)
            bytes.copyInto(buffer, 0, pos, pos + n); pos += n
            return n
        }
        override fun close() {}
    }
}
