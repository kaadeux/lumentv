package com.lumentv.core

import platform.Foundation.NSUserDefaults

/** NSUserDefaults, the keys prefixed by [name] ("profile.keys"…). */
fun userDefaults(name: String): KeyValues = object : KeyValues {
    private val d = NSUserDefaults.standardUserDefaults
    private fun k(key: String) = "$name.$key"
    override fun string(key: String) = d.stringForKey(k(key))
    override fun long(key: String, default: Long) = if (d.objectForKey(k(key)) == null) default else d.integerForKey(k(key))
    override fun put(key: String, value: String) = d.setObject(value, forKey = k(key))
    override fun put(key: String, value: Long) = d.setInteger(value, forKey = k(key))
    override fun clear() {
        d.dictionaryRepresentation().keys.filterIsInstance<String>().filter { it.startsWith("$name.") }.forEach { d.removeObjectForKey(it) }
    }
}
