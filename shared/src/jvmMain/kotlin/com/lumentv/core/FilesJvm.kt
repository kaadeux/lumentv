package com.lumentv.core

import java.io.File
import java.util.zip.GZIPInputStream

actual class TextFile actual constructor(path: String) {
    private val file = File(path)
    actual fun read(): String? = runCatching { file.readText() }.getOrNull()
    actual fun write(text: String): Boolean = runCatching { file.parentFile?.mkdirs(); file.writeText(text) }.isSuccess
}

actual fun gunzip(body: HttpBody): HttpBody {
    val stream = GZIPInputStream(body.inputStream())
    return StreamBody(stream) { stream.close(); body.close() }
}

/** The XML reader factory for the shared guides: the platform's XmlPullParser (Android's, or kxml2 on the PC). */
fun xmlPullReaders(newParser: () -> org.xmlpull.v1.XmlPullParser): (HttpBody) -> XmlReader =
    { body -> newParser().apply { setInput(body.inputStream(), "UTF-8") }.asXmlReader() }
