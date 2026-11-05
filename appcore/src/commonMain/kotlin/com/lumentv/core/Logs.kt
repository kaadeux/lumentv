package com.lumentv.core

/** A warning in the system log (logcat on Android, the console on the iPhone). Never secrets: use [Redact]. */
expect fun logWarn(tag: String, message: String)
