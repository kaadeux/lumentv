package com.lumentv.core

import platform.Foundation.NSString
import platform.Foundation.decomposedStringWithCanonicalMapping

/** iPhone: there is no java.io, network and file errors are of this kind. */
actual open class IOException actual constructor(message: String?) : Exception(message)

@Suppress("CAST_NEVER_SUCCEEDS")
internal actual fun decomposeUnicode(text: String): String = (text as NSString).decomposedStringWithCanonicalMapping
