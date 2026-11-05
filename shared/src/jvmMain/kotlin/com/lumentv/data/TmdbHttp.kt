package com.lumentv.data

import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File

/**
 * TV, phone and PC: the OkHttp client TMDB's requests go through (the API itself, [Tmdb], is shared with the
 * iPhone). Answers kept on disk ([cacheDir]): lists 6 hours, the rest 7 days, so a restart asks TMDB again only for
 * what changed. The logos ([TitleLogos]) share it, and so its cache.
 */
object TmdbHttp {
    fun client(base: OkHttpClient, cacheDir: File?): OkHttpClient = base.newBuilder().apply {
        if (cacheDir != null) cache(Cache(File(cacheDir, "tmdb"), Tmdb.CACHE_BYTES))
        // TMDB limits the requests per second: a "429" or a busy server is retried a little later, not lost.
        addInterceptor(com.lumentv.core.RetryInterceptor())
        // TMDB says "do not cache" or a few minutes: kept longer here, the lists change slowly and a title never.
        addNetworkInterceptor { chain ->
            val req = chain.request()
            val path = req.url.encodedPath
            val seconds = if (path.contains("/trending/") || path.contains("/discover/") || path.endsWith("/popular") || path.endsWith("/top_rated")) 6 * 3600 else 7 * 86400
            chain.proceed(req).newBuilder().removeHeader("Pragma").header("Cache-Control", "public, max-age=$seconds").build()
        }
    }.build()

    /** A cache left bigger than its limit (the limit was lowered) is emptied once; it then stays under it. Blocking. */
    fun trimCache(client: OkHttpClient) { client.cache?.let { c -> if (c.size() > c.maxSize()) c.evictAll() } }
}
