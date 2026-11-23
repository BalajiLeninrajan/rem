package dev.balaji.rem.data.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import java.io.File

/**
 * Opens the Preferences DataStore called [name] in the files directory of [storageContext].
 *
 * The file path is built here because the `preferencesDataStoreFile` helper resolves
 * against the application context, which would put the file in credential-protected
 * storage even when it is called on a device-protected context.
 *
 * A file that cannot be parsed is replaced by an empty one, so every value falls back to
 * its default and the alarm path keeps working.
 *
 * Open each name once per process. DataStore rejects a second instance on the same file.
 */
fun openPreferencesStore(storageContext: Context, name: String): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        produceFile = { preferencesFile(storageContext, name) },
    )

fun preferencesFile(storageContext: Context, name: String): File =
    File(storageContext.filesDir, "datastore/$name.preferences_pb")
