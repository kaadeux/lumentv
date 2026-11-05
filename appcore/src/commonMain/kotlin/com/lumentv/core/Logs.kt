package com.lumentv.core

/** A warning in the system log (logcat on Android, the console on the iPhone). Never secrets: use [Redact]. */
expect fun logWarn(tag: String, message: String)

/** A debug line, built only when the tag's debug logs are on (adb shell setprop log.tag.<tag> DEBUG on Android). */
expect fun logDebug(tag: String, message: () -> String)
