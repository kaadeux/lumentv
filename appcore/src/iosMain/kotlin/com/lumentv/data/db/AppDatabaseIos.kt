package com.lumentv.data.db

import androidx.room.Room
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.lumentv.core.IoDispatcher

/** iPhone: SQLite built into the app, the file at [path] (in the app's documents). */
fun createAppDatabase(path: String): AppDatabase =
    Room.databaseBuilder<AppDatabase>(name = path)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(IoDispatcher)
        .build()

actual suspend fun <R> AppDatabase.transaction(block: suspend () -> R): R =
    useWriterConnection { it.immediateTransaction { block() } }
