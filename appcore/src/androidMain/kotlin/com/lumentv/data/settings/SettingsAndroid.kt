package com.lumentv.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/** The same file as before the shared code ("settings"): the TV and the phone keep their settings. */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("settings")

fun settingsStore(context: Context): SettingsStore = SettingsStore(context.applicationContext.dataStore)
