package com.lumentv.data.db

import androidx.room.AutoMigration
import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor

@Database(
    entities = [
        PlaylistEntity::class, ChannelEntity::class, MovieEntity::class, SeriesEntity::class, EpisodeEntity::class,
        PrefEntity::class, ProgramEntity::class, EpgChannelEntity::class, HistoryEntity::class,
        TransferEntity::class, ReminderEntity::class, RatingEntity::class, TmdbLinkEntity::class, TitleEntity::class,
    ],
    version = 8,
    exportSchema = true,
    // v2 only adds tables: Room migrates by itself and keeps playlists, favourites and history.
    // v3 adds an index on programme start times (sports panel). Both migrations keep every row.
    // v4 adds the series' TMDB id (a nullable column), v5 the date a series last changed (0 until the next refresh),
    // v6 its backdrop picture, v7 the personal ratings and the TMDB links (two new tables), v8 the prepared titles (a
    // new table, filled at the next start).
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6), AutoMigration(from = 6, to = 7), AutoMigration(from = 7, to = 8)],
)
// Shared by the TV / phone (Android's SQLite, file "lumen.db" as before) and the iPhone (bundled SQLite): see
// createAppDatabase in androidMain and iosMain.
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun playlists(): PlaylistDao
    abstract fun channels(): ChannelDao
    abstract fun vod(): VodDao
    abstract fun ratings(): RatingDao
    abstract fun prefs(): PrefDao
    abstract fun epg(): EpgDao
    abstract fun history(): HistoryDao
    abstract fun transfers(): TransferDao
    abstract fun reminders(): ReminderDao
    abstract fun titles(): TitleDao
}

/** Written by Room's compiler for each platform. */
@Suppress("NO_ACTUAL_FOR_EXPECT", "EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}

/** Runs [block] in one write transaction: everything or nothing. */
expect suspend fun <R> AppDatabase.transaction(block: suspend () -> R): R
