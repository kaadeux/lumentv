package com.lumentv.sync

import kotlinx.serialization.Serializable

/**
 * Everything a profile shares between devices. Each entry carries the time it last changed ([at]); merging two
 * copies keeps, entry by entry, the most recent one. Removals are kept as tombstones so they spread too.
 *
 * Playlists are keyed by [playlistKey] (same account = same key on every device); favourites, history and ratings by
 * "playlistKey|kind|uid", where uid is the provider's stable id for the channel / film / episode / series.
 */
@Serializable
data class ProfileDoc(
    val schema: Int = 1,
    val playlists: Map<String, SyncPlaylist> = emptyMap(),
    val favorites: Map<String, SyncFavorite> = emptyMap(),
    val history: Map<String, SyncHistory> = emptyMap(),
    val settings: Map<String, SyncValue> = emptyMap(),
    /** Personal star ratings of films ("mv") and series ("se"). Older devices ignore them. */
    val ratings: Map<String, SyncRating> = emptyMap(),
)

@Serializable
data class SyncPlaylist(
    val name: String,
    val type: String,
    val source: String,
    val username: String = "",
    val password: String = "",
    val epgUrl: String = "",
    val epgOffsetMinutes: Int = 0,
    val userAgent: String = "",
    val liveFormat: String = "ts",
    val refreshHours: Int = 24,
    val dedupe: Boolean = false,
    val deleted: Boolean = false,
    val at: Long = 0,
)

@Serializable
data class SyncFavorite(
    val playlist: String,
    val kind: String,
    val uid: String,
    val order: Long = 0,
    val on: Boolean = true,
    val at: Long = 0,
)

@Serializable
data class SyncHistory(
    val playlist: String,
    val kind: String,
    val uid: String,
    val title: String,
    val subtitle: String? = null,
    val image: String? = null,
    val parentUid: String? = null,
    val watchedAt: Long,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val deleted: Boolean = false,
    val at: Long = 0,
)

/** A personal rating: 1 to 5 stars. [deleted]: the rating was taken away. */
@Serializable
data class SyncRating(
    val playlist: String,
    val kind: String,
    val uid: String,
    val stars: Int,
    val ratedAt: Long,
    val title: String = "",
    val image: String? = null,
    val deleted: Boolean = false,
    val at: Long = 0,
)

@Serializable
data class SyncValue(val value: String, val at: Long = 0)

/** Item keys and the merge rules. */
object ProfileMerge {
    const val HISTORY_LIMIT = 1500
    private const val TOMBSTONE_DAYS = 180L

    fun playlistKey(type: String, source: String, username: String): String =
        ProfileKeys.hex(ProfileKeys.sha256("$type|${source.trim().trimEnd('/').lowercase()}|${username.trim()}".toByteArray())).take(16)

    fun itemKey(playlist: String, kind: String, uid: String) = "$playlist|$kind|$uid"

    fun merge(a: ProfileDoc, b: ProfileDoc?): ProfileDoc {
        if (b == null) return a
        return ProfileDoc(
            schema = maxOf(a.schema, b.schema),
            playlists = lww(a.playlists, b.playlists) { it.at },
            favorites = lww(a.favorites, b.favorites) { it.at },
            history = lww(a.history, b.history) { it.at },
            settings = lww(a.settings, b.settings) { it.at },
            ratings = lww(a.ratings, b.ratings) { it.at },
        )
    }

    /** Drops old tombstones and the oldest history beyond [HISTORY_LIMIT], so the profile stays small. */
    fun prune(d: ProfileDoc, now: Long = com.lumentv.core.nowMillis()): ProfileDoc {
        val cutoff = now - TOMBSTONE_DAYS * 86_400_000L
        val history = d.history.filterValues { !it.deleted || it.at > cutoff }
            .entries.sortedByDescending { it.value.watchedAt }.take(HISTORY_LIMIT).associate { it.key to it.value }
        return d.copy(
            playlists = d.playlists.filterValues { !it.deleted || it.at > cutoff },
            favorites = d.favorites.filterValues { it.on || it.at > cutoff },
            history = history,
            ratings = d.ratings.filterValues { !it.deleted || it.at > cutoff },
        )
    }

    /**
     * Stamps what changed on this device since the last sync. [current] is the device's state with every `at` = 0;
     * [synced] is the copy merged at the last sync. Unchanged entries keep their old time, changed ones get [now],
     * and entries gone since then become tombstones dated [now].
     */
    fun stampLocal(synced: ProfileDoc, current: ProfileDoc, now: Long = com.lumentv.core.nowMillis()): ProfileDoc = ProfileDoc(
        playlists = stamp(synced.playlists, current.playlists, now, { it.copy(at = 0) }, { p, t -> p.copy(at = t) }, { it.copy(deleted = true) }) { it.deleted },
        favorites = stamp(synced.favorites, current.favorites, now, { it.copy(at = 0) }, { p, t -> p.copy(at = t) }, { it.copy(on = false) }) { !it.on },
        history = stamp(synced.history, current.history, now, { it.copy(at = 0) }, { p, t -> p.copy(at = t) }, { it.copy(deleted = true) }) { it.deleted },
        ratings = stamp(synced.ratings, current.ratings, now, { it.copy(at = 0) }, { p, t -> p.copy(at = t) }, { it.copy(deleted = true) }) { it.deleted },
        // A setting missing on this device is simply not set here: it is not a removal.
        settings = synced.settings + current.settings.mapValues { (k, v) ->
            synced.settings[k]?.takeIf { it.value == v.value } ?: v.copy(at = now)
        },
    )

    private fun <T> lww(a: Map<String, T>, b: Map<String, T>, at: (T) -> Long): Map<String, T> {
        val out = HashMap<String, T>(a)
        for ((k, v) in b) {
            val mine = out[k]
            if (mine == null || at(v) >= at(mine)) out[k] = v
        }
        return out
    }

    private inline fun <T> stamp(
        synced: Map<String, T>, current: Map<String, T>, now: Long,
        crossinline plain: (T) -> T, crossinline withAt: (T, Long) -> T, crossinline tombstone: (T) -> T,
        crossinline isTombstone: (T) -> Boolean,
    ): Map<String, T> {
        val out = HashMap<String, T>()
        for ((k, v) in current) {
            val old = synced[k]
            out[k] = if (old != null && plain(old) == plain(v)) old else withAt(v, now)
        }
        for ((k, old) in synced) {
            if (k in current) continue
            out[k] = if (isTombstone(old)) old else withAt(tombstone(old), now)
        }
        return out
    }
}
