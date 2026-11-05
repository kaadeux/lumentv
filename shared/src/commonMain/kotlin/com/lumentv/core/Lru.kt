package com.lumentv.core

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * A small map that forgets the least recently used entries beyond [max], safe to use from several threads. A null
 * value is a remembered "nothing found" ([contains] tells it from an unknown key).
 */
class Lru<K, V>(private val max: Int) {
    private val lock = SynchronizedObject()
    private val map = LinkedHashMap<K, V>()

    operator fun get(key: K): V? = synchronized(lock) {
        if (!map.containsKey(key)) return null
        @Suppress("UNCHECKED_CAST")
        val v = map.remove(key) as V
        map[key] = v // the most recently used last
        v
    }

    operator fun set(key: K, value: V) = synchronized(lock) {
        map.remove(key)
        map[key] = value
        while (map.size > max) map.remove(map.keys.first())
    }

    operator fun contains(key: K): Boolean = synchronized(lock) { map.containsKey(key) }

    /** The value without counting as a use (for the "already known" checks). */
    fun peek(key: K): V? = synchronized(lock) { map[key] }
}
