package com.lumentv.data.repo

import kotlin.test.Test
import kotlin.test.assertEquals

/** The guide announced next to a playlist ("url-tvg" relative), as java.net.URI.resolve did. */
class PlaylistPathsTest {
    private fun r(base: String, ref: String) = PlaylistRepository.resolveRelative(base, ref)

    @Test
    fun relativeGuides() {
        assertEquals("http://h/lists/guide.xml", r("http://h/lists/tv.m3u", "guide.xml"))
        assertEquals("http://h/a/g.xml", r("http://h/a/b/tv.m3u?x=1", "../g.xml"))
        assertEquals("http://h/g.xml", r("http://h/tv.m3u", "./g.xml"))
        assertEquals("https://h:8080/x/y/g.xml.gz", r("https://h:8080/x/tv.m3u#top", "y/g.xml.gz"))
        assertEquals("http://other/g.xml", r("http://h/tv.m3u", "http://other/g.xml"))
        assertEquals("/sdcard/g.xml", r("/sdcard/tv.m3u", "g.xml"))
        assertEquals("/sdcard/x/g.xml", r("file:/sdcard/x/tv.m3u", "g.xml"))
        assertEquals("g.xml", r("tv.m3u", "g.xml"))
    }
}
