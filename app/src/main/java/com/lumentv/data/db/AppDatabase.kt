package com.lumentv.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        PlaylistEntity::class, ChannelEntity::class, MovieEntity::class, SeriesEntity::class, EpisodeEntity::class,
        PrefEntity::class, ProgramEntity::class, EpgChannelEntity::class, HistoryEntity::class,
        TransferEntity::class, ReminderEntity::class,
    ],
    version = 6,
    exportSchema = true,
    // v2 only adds tables: Room migrates by itself and keeps playlists, favourites and history.
    // v3 adds an index on programme start times (sports panel). Both migrations keep every row.
    // v4 adds the series' TMDB id (a nullable column), v5 the date a series last changed (0 until the next refresh),
    // v6 its backdrop picture.
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun playlists(): PlaylistDao
    abstract fun channels(): ChannelDao
    abstract fun vod(): VodDao
    abstract fun prefs(): PrefDao
    abstract fun epg(): EpgDao
    abstract fun history(): HistoryDao
    abstract fun transfers(): TransferDao
    abstract fun reminders(): ReminderDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "lumen.db")
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}
