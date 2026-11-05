package com.lumentv.core

/**
 * A small streaming XML reader in plain Kotlin, for the iPhone (no XmlPullParser there): enough for XMLTV guides
 * and the like. Elements, attributes ('' or ""), text, CDATA, comments, processing instructions and DOCTYPE
 * (skipped), the five XML entities and numeric ones. Reads UTF-8 piece by piece from [body]: a 100 MB guide never
 * sits in memory. Not a validating parser: tolerant of what guides get wrong.
 */
class KotlinXmlReader(body: HttpBody) : XmlReader {
    private val chars = Utf8Chars(body)

    override var eventType: Int = XmlReader.START_DOCUMENT
        private set
    override var name: String? = null
        private set
    override var depth: Int = 0
        private set
    override var text: String? = null
        private set

    private val attributes = HashMap<String, String>()
    /** An empty element (<a/>) gives START_TAG then END_TAG. */
    private var pendingEnd = false
    /** After an END_TAG, the depth goes down at the next event (as XmlPullParser). */
    private var leaving = false

    override fun getAttributeValue(namespace: String?, name: String): String? = attributes[name]

    override fun next(): Int {
        if (leaving) { depth--; leaving = false }
        if (pendingEnd) {
            pendingEnd = false
            attributes.clear()
            leaving = true
            return set(XmlReader.END_TAG)
        }
        while (true) {
            val c = chars.peek()
            if (c < 0) return set(XmlReader.END_DOCUMENT).also { name = null; text = null }
            if (c != '<'.code) return text(readText())
            chars.read() // '<'
            when (chars.peek()) {
                '?'.code -> skipUntil("?>")
                '!'.code -> {
                    chars.read()
                    when {
                        chars.startsWith("--") -> { chars.skip(2); skipUntil("-->") }
                        chars.startsWith("[CDATA[") -> { chars.skip(7); return cdata(readUntil("]]>")) }
                        else -> skipDeclaration()
                    }
                }
                '/'.code -> {
                    chars.read()
                    name = readName()
                    skipUntil(">")
                    attributes.clear()
                    text = null
                    leaving = true
                    return set(XmlReader.END_TAG)
                }
                else -> return startTag()
            }
        }
    }

    private fun set(event: Int): Int { eventType = event; return event }

    private fun text(t: String): Int { name = null; text = t; return set(XmlReader.TEXT) }

    private fun cdata(t: String): Int { name = null; text = t; return set(XmlReader.CDSECT) }

    private fun startTag(): Int {
        name = readName()
        text = null
        attributes.clear()
        while (true) {
            skipSpaces()
            val c = chars.read()
            when {
                c < 0 -> break
                c == '>'.code -> break
                c == '/'.code -> { if (chars.peek() == '>'.code) chars.read(); pendingEnd = true; break }
                else -> {
                    val key = (c.toChar().toString() + readName())
                    skipSpaces()
                    if (chars.peek() == '='.code) {
                        chars.read()
                        skipSpaces()
                        val quote = chars.read()
                        val raw = if (quote == '"'.code || quote == '\''.code) readUntilChar(quote) else (quote.toChar() + readName())
                        attributes[key] = decodeEntities(raw)
                    } else attributes[key] = ""
                }
            }
        }
        depth++
        return set(XmlReader.START_TAG)
    }

    /** A name: up to a space, '=', '/', '>' or the end. */
    private fun readName(): String {
        val sb = StringBuilder()
        while (true) {
            val c = chars.peek()
            if (c < 0 || c == '>'.code || c == '/'.code || c == '='.code || c.toChar().isWhitespace()) break
            sb.append(chars.read().toChar())
        }
        return sb.toString()
    }

    private fun readText(): String {
        val sb = StringBuilder()
        while (true) {
            val c = chars.peek()
            if (c < 0 || c == '<'.code) break
            sb.append(chars.read().toChar())
        }
        return decodeEntities(sb.toString())
    }

    private fun readUntilChar(end: Int): String {
        val sb = StringBuilder()
        while (true) {
            val c = chars.read()
            if (c < 0 || c == end) break
            sb.append(c.toChar())
        }
        return sb.toString()
    }

    private fun readUntil(end: String): String {
        val sb = StringBuilder()
        while (true) {
            if (chars.peek() < 0) break
            if (chars.startsWith(end)) { chars.skip(end.length); break }
            sb.append(chars.read().toChar())
        }
        return sb.toString()
    }

    private fun skipUntil(end: String) {
        while (chars.peek() >= 0) {
            if (chars.startsWith(end)) { chars.skip(end.length); return }
            chars.read()
        }
    }

    /** <!DOCTYPE …> with its optional [internal subset]. */
    private fun skipDeclaration() {
        var brackets = 0
        while (true) {
            val c = chars.read()
            if (c < 0) return
            if (c == '['.code) brackets++
            if (c == ']'.code) brackets--
            if (c == '>'.code && brackets <= 0) return
        }
    }

    private fun skipSpaces() { while (chars.peek() >= 0 && chars.peek().toChar().isWhitespace()) chars.read() }

    companion object {
        /** &amp; &lt; &gt; &quot; &apos; &#233; &#xE9;; an unknown entity is left as written. */
        fun decodeEntities(s: String): String {
            if ('&' !in s) return s
            val sb = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                val end = if (c == '&') s.indexOf(';', i + 1).takeIf { it in (i + 2)..(i + 10) } else null
                if (end == null) { sb.append(c); i++; continue }
                val e = s.substring(i + 1, end)
                val decoded = when {
                    e == "amp" -> "&"; e == "lt" -> "<"; e == "gt" -> ">"; e == "quot" -> "\""; e == "apos" -> "'"
                    e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)?.let(::codePoint)
                    e.startsWith("#") -> e.substring(1).toIntOrNull()?.let(::codePoint)
                    else -> null
                }
                if (decoded == null) { sb.append(c); i++ } else { sb.append(decoded); i = end + 1 }
            }
            return sb.toString()
        }

        private fun codePoint(cp: Int): String? = when {
            cp < 0 || cp > 0x10FFFF -> null
            cp < 0x10000 -> cp.toChar().toString()
            else -> { val v = cp - 0x10000; charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString() }
        }
    }
}

/**
 * UTF-8 characters of a body, decoded piece by piece (a character cut between two pieces is put back together),
 * with a look-ahead for the reader. A wrong byte becomes U+FFFD, as Java's decoders do.
 */
internal class Utf8Chars(private val body: HttpBody) {
    private val bytes = ByteArray(64 * 1024)
    private var len = 0
    private var pos = 0
    private var ended = false
    /** Decoded characters waiting: the look-ahead. */
    private val ahead = CharArray(16)
    private var aheadStart = 0
    private var aheadLen = 0

    fun peek(): Int = if (fill(1)) ahead[aheadStart].code else -1

    fun read(): Int {
        if (!fill(1)) return -1
        val c = ahead[aheadStart]
        aheadStart = (aheadStart + 1) % ahead.size
        aheadLen--
        return c.code
    }

    fun skip(n: Int) = repeat(n) { read() }

    fun startsWith(s: String): Boolean {
        if (!fill(s.length)) return false
        for (i in s.indices) if (ahead[(aheadStart + i) % ahead.size] != s[i]) return false
        return true
    }

    /** At least [n] characters in the look-ahead, unless the body ends first. */
    private fun fill(n: Int): Boolean {
        while (aheadLen < n) {
            val cp = decode() ?: return false
            if (cp < 0x10000) push(cp.toChar())
            else { val v = cp - 0x10000; push((0xD800 + (v shr 10)).toChar()); push((0xDC00 + (v and 0x3FF)).toChar()) }
        }
        return true
    }

    private fun push(c: Char) { ahead[(aheadStart + aheadLen) % ahead.size] = c; aheadLen++ }

    private fun byte(): Int {
        if (pos >= len) {
            if (ended) return -1
            len = body.read(bytes)
            pos = 0
            if (len <= 0) { ended = true; len = 0; return -1 }
        }
        return bytes[pos++].toInt() and 0xff
    }

    /** The next code point, or null at the end. */
    private fun decode(): Int? {
        val b0 = byte()
        if (b0 < 0) return null
        if (b0 < 0x80) return b0
        val extra = when { b0 >= 0xF0 -> 3; b0 >= 0xE0 -> 2; b0 >= 0xC0 -> 1; else -> return 0xFFFD }
        var cp = b0 and (0x3F shr extra)
        repeat(extra) {
            val b = byte()
            if (b < 0 || b and 0xC0 != 0x80) return 0xFFFD
            cp = (cp shl 6) or (b and 0x3F)
        }
        return cp
    }
}

/** UTF-8 lines of a body ("\n", "\r\n" or "\r"), as BufferedReader.readLine gives them. */
internal class Utf8Lines(body: HttpBody) {
    private val chars = Utf8Chars(body)

    fun next(): String? {
        if (chars.peek() < 0) return null
        val sb = StringBuilder()
        while (true) {
            val c = chars.read()
            if (c < 0) break
            if (c == '\n'.code) break
            if (c == '\r'.code) { if (chars.peek() == '\n'.code) chars.read(); break }
            sb.append(c.toChar())
        }
        return sb.toString()
    }
}
