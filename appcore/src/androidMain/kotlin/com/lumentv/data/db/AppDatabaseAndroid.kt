package com.lumentv.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction

/** TV and phone: Android's SQLite, the same file as before ("lumen.db"), so nothing is lost on update. */
fun createAppDatabase(context: Context): AppDatabase =
    Room.databaseBuilder(context, AppDatabase::class.java, "lumen.db")
        .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        .build()

actual suspend fun <R> AppDatabase.transaction(block: suspend () -> R): R = withTransaction { block() }
