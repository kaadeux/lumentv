package com.lumentv

import com.lumentv.transfer.Hls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsTest {
    @Test fun picksHighestBandwidthVariant() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            low/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080
            hd/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2000000
            http://other/mid.m3u8
        """.trimIndent()
        assertTrue(Hls.isMaster(master))
        assertEquals("http://h/live/hd/index.m3u8", Hls.bestVariant(master, "http://h/live/master.m3u8"))
    }

    @Test fun parsesLiveMediaPlaylistWithKey() {
        val media = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:1200
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin",IV=0x000000000000000000000000000004B0
            #EXTINF:6.0,
            seg1200.ts
            #EXTINF:6.0,
            seg1201.ts
            #EXT-X-KEY:METHOD=NONE
            #EXTINF:6.0,
            /abs/seg1202.ts
        """.trimIndent()
        val m = Hls.parseMedia(media, "http://h/live/chan/index.m3u8")
        assertEquals(6, m.targetDurationSec)
        assertFalse(m.ended)
        assertEquals(listOf(1200L, 1201L, 1202L), m.segments.map { it.sequence })
        assertEquals("http://h/live/chan/seg1200.ts", m.segments[0].uri)
        assertEquals("http://h/abs/seg1202.ts", m.segments[2].uri)
        assertEquals("http://h/live/chan/key.bin", m.segments[0].key?.uri)
        assertEquals(null, m.segments[2].key)
        assertEquals(0x04.toByte(), m.segments[0].key!!.iv!![14])
    }

    @Test fun derivesIvFromSequenceWhenMissing() {
        val m = Hls.parseMedia("#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:258\n#EXT-X-KEY:METHOD=AES-128,URI=\"k\"\n#EXTINF:4,\na.ts\n#EXT-X-ENDLIST", "http://h/x.m3u8")
        assertTrue(m.ended)
        val iv = Hls.ivFor(m.segments[0])
        assertEquals(16, iv.size)
        assertEquals(0x01.toByte(), iv[14])
        assertEquals(0x02.toByte(), iv[15])
    }

    @Test fun keepsFmp4InitSegment() {
        val m = Hls.parseMedia("#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:4,\nsegment1.m4s\n", "http://h/v/media.m3u8")
        assertEquals("http://h/v/init.mp4", m.segments[0].map)
    }
}
