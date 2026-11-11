package com.lumentv.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

object PlaylistType {
    const val M3U = "m3u"
    const val XTREAM = "xtream"
}

/** One IPTV source. Secrets (`*Enc`) are encrypted with [com.lumentv.core.SecretBox]. */
@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String,
    /** M3U: playlist URL or `file:` path. Xtream: server base URL. Encrypted. */
    val sourceEnc: String,
    val username: String = "",
    val passwordEnc: String = "",
    /** XMLTV URL chosen by the user (encrypted). Empty: the provider's own guide, if any. */
    val epgUrlEnc: String = "",
    /** XMLTV URL announced in the M3U header (url-tvg), used when [epgUrlEnc] is empty. Encrypted. */
    val autoEpgUrlEnc: String = "",
    val epgOffsetMinutes: Int = 0,
    val userAgent: String = "",
    /** Xtream live format: "ts" or "m3u8". */
    val liveFormat: String = "ts",
    val refreshHours: Int = 24,
    val dedupe: Boolean = true,
    val lastRefresh: Long = 0,
    val lastEpgRefresh: Long = 0,
    val lastError: String? = null,
    val channelCount: Int = 0,
    val movieCount: Int = 0,
    val seriesCount: Int = 0,
    /** Xtream account info shown in settings: "expires 2027-01-01 · 1/2 connections". */
    val accountInfo: String? = null,
    /** Xtream server time zone (IANA id), for catch-up URLs. */
    val serverTimeZone: String? = null,
    val createdAt: Long = com.lumentv.core.nowMillis(),
)

@Entity(
    tableName = "channels",
    indices = [Index("playlistId", "groupName"), Index("playlistId", "uid"), Index("nameNorm")],
)
data class ChannelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    /** Stable across refreshes: "x:<streamId>" for Xtream, "m:<name>|<tvg-id>" for M3U. */
    val uid: String,
    val name: String,
    val nameNorm: String,
    val groupName: String,
    val logo: String? = null,
    /** Full stream URL for M3U; empty for Xtream (built at play time from the encrypted credentials). */
    val url: String = "",
    val streamId: Long = 0,
    val epgId: String? = null,
    /** Position in the provider's order, 1-based. Also the channel number typed on the remote. */
    val number: Int,
    val userAgent: String? = null,
    val referer: String? = null,
    val catchupDays: Int = 0,
    /** M3U catchup-source template, e.g. "?utc={utc}&lutc={lutc}". */
    val catchupSource: String? = null,
)

@Entity(tableName = "movies", indices = [Index("playlistId", "category"), Index("playlistId", "uid"), Index("nameNorm")])
data class MovieEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val uid: String,
    val name: String,
    val nameNorm: String,
    val category: String,
    val poster: String? = null,
    val url: String = "",
    val streamId: Long = 0,
    val ext: String? = null,
    val rating: String? = null,
    val year: String? = null,
    val addedAt: Long = 0,
    val number: Int,
)

@Entity(tableName = "series", indices = [Index("playlistId", "category"), Index("playlistId", "uid"), Index("nameNorm")])
data class SeriesEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val uid: String,
    val seriesId: Long = 0,
    val name: String,
    val nameNorm: String,
    val category: String,
    val cover: String? = null,
    val plot: String? = null,
    val rating: String? = null,
    val year: String? = null,
    val number: Int,
    /** TMDB id given by the provider, to find recap / intro / credits (TheIntroDB). */
    val tmdb: String? = null,
    /** Last change on the provider's side (new series or new episodes), epoch ms; 0 = unknown. */
    @androidx.room.ColumnInfo(defaultValue = "0") val updatedAt: Long = 0,
    /** A wide picture of the series for the banner and its page; null until the next refresh, or when the provider has none. */
    val backdrop: String? = null,
)

/** Episodes: stored from the M3U, or fetched on demand from Xtream (get_series_info) and cached. */
@Entity(tableName = "episodes", indices = [Index("playlistId", "seriesUid"), Index("nameNorm")])
data class EpisodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val seriesUid: String,
    val uid: String,
    val title: String,
    val nameNorm: String,
    val season: Int,
    val episode: Int,
    val url: String = "",
    val streamId: Long = 0,
    val ext: String? = null,
    val image: String? = null,
    val plot: String? = null,
    val durationSecs: Int = 0,
)

object PrefKind {
    const val CHANNEL = "ch"
    const val MOVIE = "mv"
    const val SERIES = "se"
    const val GROUP = "grp"
    const val MOVIE_CATEGORY = "mvcat"
    const val SERIES_CATEGORY = "secat"
}

/** User choices that must survive a playlist refresh, keyed by the stable uid (or the group name). */
@Entity(tableName = "prefs", primaryKeys = ["playlistId", "kind", "uid"])
data class PrefEntity(
    val playlistId: Long,
    val kind: String,
    val uid: String,
    val favorite: Boolean = false,
    val favOrder: Long = 0,
    val hidden: Boolean = false,
    val locked: Boolean = false,
    val epgOverride: String? = null,
)

@Entity(tableName = "programs", indices = [Index("playlistId", "epgKey", "start"), Index("titleNorm"), Index("playlistId", "start")])
data class ProgramEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    /** XMLTV channel id, lower case. */
    val epgKey: String,
    val start: Long,
    val stop: Long,
    val title: String,
    val titleNorm: String,
    val description: String? = null,
)

/** XMLTV <channel> entries, used to match channels by display name when tvg-id is missing or wrong. */
@Entity(tableName = "epg_channels", primaryKeys = ["playlistId", "epgKey", "nameKey"], indices = [Index("playlistId", "nameKey")])
data class EpgChannelEntity(
    val playlistId: Long,
    val epgKey: String,
    val displayName: String,
    val nameKey: String,
)

object HistoryKind {
    const val CHANNEL = "ch"
    const val MOVIE = "mv"
    const val EPISODE = "ep"
    /** A recording or download played from its file; uid = "file:<transfer id>". */
    const val FILE = "file"
}

/** Watch history and resume position. key = "<kind>:<playlistId>:<uid>". */
@Entity(tableName = "history", indices = [Index("watchedAt")])
data class HistoryEntity(
    @PrimaryKey val key: String,
    val kind: String,
    val playlistId: Long,
    val uid: String,
    val title: String,
    val subtitle: String? = null,
    val image: String? = null,
    /** For episodes: the series uid, to open the series page. */
    val parentUid: String? = null,
    val watchedAt: Long,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
)

object TransferKind {
    const val DOWNLOAD = "dl"
}

object TransferStatus {
    const val SCHEDULED = "scheduled"
    const val RUNNING = "running"
    const val DONE = "done"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"
}

/**
 * A recording of a live channel (now or scheduled) or a download of a movie / episode. The stream URL is
 * never stored: it is resolved from the channel or item when the job starts.
 */
@Entity(tableName = "transfers", indices = [Index("status"), Index("startAt")])
data class TransferEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val title: String,
    val subtitle: String? = null,
    val playlistId: Long,
    /** "ch" (recording), "mv" or "ep" (download). */
    val refKind: String,
    val refUid: String,
    val seriesUid: String? = null,
    val startAt: Long,
    /** Recordings: when to stop. Downloads: 0. */
    val endAt: Long = 0,
    val filePath: String? = null,
    val status: String = TransferStatus.SCHEDULED,
    val bytes: Long = 0,
    val totalBytes: Long = 0,
    val error: String? = null,
    val createdAt: Long = com.lumentv.core.nowMillis(),
)

/** "Remind me" on a programme: a dialog or notification shortly before it starts. */
@Entity(tableName = "reminders", indices = [Index("start")])
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val channelUid: String,
    val channelName: String,
    val title: String,
    val start: Long,
    val stop: Long,
    val createdAt: Long = com.lumentv.core.nowMillis(),
)

/** A personal rating, 1 to 5 stars, of a film or a series (kind = mv / se). key = "<kind>:<playlistId>:<uid>". Kept on this device. */
@Entity(tableName = "ratings", indices = [Index("ratedAt")])
data class RatingEntity(
    @PrimaryKey val key: String,
    val kind: String,
    val playlistId: Long,
    val uid: String,
    val stars: Int,
    val ratedAt: Long,
    val title: String,
    val image: String? = null,
)

/** The TMDB title a film or series of the playlist is (null = looked for, not found). key as [RatingEntity]. */
@Entity(tableName = "tmdb_links")
data class TmdbLinkEntity(
    @PrimaryKey val key: String,
    val tmdbId: String?,
    val checkedAt: Long,
)

/** Light rows of the channels, for the search ([com.lumentv.data.discover.VodLite] for films and series). */
data class ChannelLite(val id: Long, val name: String, val number: Int)

/** What finds a channel's guide ([com.lumentv.data.epg.EpgMatcher.keyFor]), without the rest of its row. */
data class ChannelGuideKey(val id: Long, val name: String, val epgId: String?, val epgOverride: String?)

/** A channel's name and group only, to pick the French ones before reading their full rows. */
data class ChannelNameGroup(val id: Long, val name: String, val groupName: String)

/**
 * A film or series of a playlist as the index keeps it (TitleIndex): only the titles it keeps, already cleaned. Written
 * after each refresh of the list; [stamp] is the playlist's lastRefresh then (other rows are stale). Read at each start
 * instead of preparing every title again: 20 s on a TV before the Films rows could show.
 */
@Entity(tableName = "titles", indices = [Index("playlistId", "stamp")])
data class TitleEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val playlistId: Long,
    val stamp: Long,
    val series: Boolean,
    /** The row of the movies or series table. */
    val vodId: Long,
    val uid: String,
    val name: String,
    val category: String,
    val year: String?,
    val rating: String?,
    val norm: String,
    val otherLanguage: Boolean,
    val english: Boolean,
)
