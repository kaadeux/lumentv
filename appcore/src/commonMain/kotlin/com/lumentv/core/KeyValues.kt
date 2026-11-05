package com.lumentv.core

/** A few small values kept by the app (the profile's keys, the last sync): SharedPreferences on Android, NSUserDefaults on the iPhone. */
interface KeyValues {
    fun string(key: String): String?
    fun long(key: String, default: Long = 0): Long
    fun put(key: String, value: String)
    fun put(key: String, value: Long)
    fun clear()
}
