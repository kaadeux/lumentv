package com.lumentv.core

actual fun logWarn(tag: String, message: String) { android.util.Log.w(tag, message) }

actual fun logDebug(tag: String, message: () -> String) {
    if (android.util.Log.isLoggable(tag, android.util.Log.DEBUG)) android.util.Log.d(tag, message())
}
