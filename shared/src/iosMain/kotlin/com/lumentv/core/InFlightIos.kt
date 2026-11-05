package com.lumentv.core

/** iPhone: no sharing yet (each caller loads); NSURLSession's own cache answers repeated requests. */
actual class InFlight<K : Any, V> actual constructor() {
    actual fun get(key: K, load: () -> V): V = load()
}
