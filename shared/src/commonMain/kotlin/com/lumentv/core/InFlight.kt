package com.lumentv.core

/**
 * Runs a load once for a key even when several callers ask at the same time: the others wait for and share its
 * answer (two rows asking TMDB for the same title at once make one request). JVM: in Net.kt; iPhone: each call loads.
 */
expect class InFlight<K : Any, V>() {
    fun get(key: K, load: () -> V): V
}
