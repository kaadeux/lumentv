package com.lumentv.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import okio.Path.Companion.toPath

/** iPhone: [path] is a file in the app's documents, e.g. ".../Documents/settings.preferences_pb". */
fun settingsStore(path: String): SettingsStore =
    SettingsStore(PreferenceDataStoreFactory.createWithPath(produceFile = { path.toPath() }))
