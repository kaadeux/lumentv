package com.lumentv.phone

/** Android: TMDB's cache is OkHttp's (TmdbHttp in :shared), emptied by the app itself. */
actual object PlatformCache {
    actual fun sizeBytes(): Long = 0
    actual fun clear() {}
}
