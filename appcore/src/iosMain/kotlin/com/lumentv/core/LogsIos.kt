package com.lumentv.core

actual fun logWarn(tag: String, message: String) = println("W/$tag: $message")

/** The iPhone keeps them off: no setting to turn them on yet. */
actual fun logDebug(tag: String, message: () -> String) {}
