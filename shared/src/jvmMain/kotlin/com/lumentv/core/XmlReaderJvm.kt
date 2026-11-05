package com.lumentv.core

import org.xmlpull.v1.XmlPullParser

/** TV, phone and PC: the platform's XmlPullParser (Android's, or kxml2 on the PC) seen as the shared [XmlReader]. */
fun XmlPullParser.asXmlReader(): XmlReader = object : XmlReader {
    private val p = this@asXmlReader
    override val eventType: Int get() = p.eventType
    override fun next(): Int = p.next()
    override val name: String? get() = p.name
    override val depth: Int get() = p.depth
    override val text: String? get() = p.text
    override fun getAttributeValue(namespace: String?, name: String): String? = p.getAttributeValue(namespace, name)
}
