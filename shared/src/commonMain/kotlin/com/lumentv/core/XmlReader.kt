package com.lumentv.core

/**
 * An XML pull reader, the part of XmlPullParser the guides use (XMLTV): events one after another, never the whole
 * document in memory. TV / phone: Android's own parser; PC: kxml2 (both through [asXmlReader] in jvmMain); iPhone:
 * [KotlinXmlReader]. Event numbers are XmlPullParser's.
 */
interface XmlReader {
    /** The current event: [START_TAG], [END_TAG], [TEXT], [CDSECT]… */
    val eventType: Int
    fun next(): Int
    /** The current element's name (START_TAG / END_TAG). */
    val name: String?
    /** Nesting depth: 1 for the root element, the same on an element's START_TAG and END_TAG. */
    val depth: Int
    /** The text of a TEXT / CDSECT event, entities decoded. */
    val text: String?
    fun getAttributeValue(namespace: String?, name: String): String?

    companion object {
        const val START_DOCUMENT = 0
        const val END_DOCUMENT = 1
        const val START_TAG = 2
        const val END_TAG = 3
        const val TEXT = 4
        const val CDSECT = 5
        const val ENTITY_REF = 6
    }
}
