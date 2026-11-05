package com.lumentv.core

import android.content.Context

/** The SharedPreferences file [name] (as before the shared code: "profile" keeps the TV signed in). */
fun sharedPrefs(context: Context, name: String): KeyValues = object : KeyValues {
    private val p = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    override fun string(key: String) = p.getString(key, null)
    override fun long(key: String, default: Long) = p.getLong(key, default)
    override fun put(key: String, value: String) = p.edit().putString(key, value).apply()
    override fun put(key: String, value: Long) = p.edit().putLong(key, value).apply()
    override fun clear() = p.edit().clear().apply()
}
