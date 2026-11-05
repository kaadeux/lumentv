package com.lumentv.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FilesTest {
    private val text = "#EXTM3U\r\n#EXTINF:-1,Télé 1\nhttp://s/1\r\rfin"

    /** A body that gives one byte per read: the worst case for the gzip check and the line splitting. */
    private fun slow(bytes: ByteArray) = object : HttpBody {
        private var pos = 0
        override fun read(buffer: ByteArray): Int { if (pos >= bytes.size) return -1; buffer[0] = bytes[pos++]; return 1 }
        override fun close() {}
    }

    private fun lines(body: HttpBody): List<String> { val next = body.lineReader(); return generateSequence { next() }.toList() }

    @Test
    fun plainAndGzipBodiesGiveTheSameLines() {
        val expected = listOf("#EXTM3U", "#EXTINF:-1,Télé 1", "http://s/1", "", "fin")
        assertEquals(expected, lines(HttpBody.of(text.encodeToByteArray()).ungzipped()))
        assertEquals(expected, lines(slow(text.encodeToByteArray()).ungzipped()))
        assertEquals(expected, lines(HttpBody.of(Crypto.gzip(text.encodeToByteArray())).ungzipped()))
        assertEquals(expected, lines(slow(Crypto.gzip(text.encodeToByteArray())).ungzipped()))
    }

    @Test
    fun emptyAndOneByteBodies() {
        assertNull(HttpBody.of(ByteArray(0)).ungzipped().lineReader()())
        assertEquals(listOf("x"), lines(HttpBody.of("x".encodeToByteArray()).ungzipped()))
    }
}
