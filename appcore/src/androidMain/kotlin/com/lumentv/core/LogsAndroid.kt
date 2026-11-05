package com.lumentv.core

actual fun logWarn(tag: String, message: String) { android.util.Log.w(tag, message) }
