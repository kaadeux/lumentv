package com.lumentv.phone

import coil3.PlatformContext
import coil3.SingletonImageLoader

/** The platform's own network cache (TMDB's answers: URLCache on the iPhone). */
expect object PlatformCache {
    fun sizeBytes(): Long
    fun clear()
}

/** What the caches take: posters and pictures (Coil, 60 MB at most) and TMDB's answers (20 MB at most). */
internal fun cacheBytes(context: PlatformContext): Long =
    (SingletonImageLoader.get(context).diskCache?.size ?: 0L) + PlatformCache.sizeBytes()

/** Empties them; downloads, the list and the guide are not touched. Pictures are fetched again as needed. */
internal fun clearCaches(context: PlatformContext) {
    val loader = SingletonImageLoader.get(context)
    loader.memoryCache?.clear()
    loader.diskCache?.clear()
    PlatformCache.clear()
}
