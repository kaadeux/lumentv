package com.lumentv.core

import java.io.File
import java.util.zip.GZIPInputStream

actual class TextFile actual constructor(path: String) {
    private val file = File(path)
    actual fun read(): String? = runCatching { file.readText() }.getOrNull()
    actual fun write(text: String): Boolean = runCatching { file.parentFile?.mkdirs(); file.writeText(text) }.isSuccess
    actual fun delete() { file.delete() }
}

actual fun gunzip(body: HttpBody): HttpBody {
    val stream = GZIPInputStream(body.inputStream())
    return StreamBody(stream) { stream.close(); body.close() }
}

/**
 * The XML reader factory for the shared guides: the platform's XmlPullParser (Android's, or kxml2 on the PC).
 * [encoding] null: the one the file declares (<?xml encoding="ISO-8859-1"?>), UTF-8 otherwise.
 */
fun xmlPullReaders(encoding: String? = "UTF-8", newParser: () -> org.xmlpull.v1.XmlPullParser): (HttpBody) -> XmlReader =
    { body -> newParser().apply { setInput(body.inputStream(), encoding) }.asXmlReader() }

actual fun openFile(path: String): HttpBody = java.io.File(path).inputStream().let { StreamBody(it) }

actual fun HttpBody.lineReader(): () -> String? = inputStream().bufferedReader(Charsets.UTF_8)::readLine
