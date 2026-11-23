package dev.balaji.rem.data.store

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import org.junit.rules.TemporaryFolder

/** Preferences DataStores on temporary files. Every store is shut down after the test. */
class PreferencesStoreRule : TemporaryFolder() {
    private val scopes = mutableMapOf<File, CoroutineScope>()
    private var created = 0

    fun newStoreFile(): File = File(root, "store${created++}.preferences_pb")

    fun open(file: File = newStoreFile()): DataStore<Preferences> {
        val scope = CoroutineScope(Dispatchers.IO + Job())
        scopes[file] = scope
        return PreferenceDataStoreFactory.create(scope = scope) { file }
    }

    /** Stops the store on [file] and waits until the file may be opened again, like a process restart. */
    suspend fun close(file: File) {
        scopes.remove(file)?.coroutineContext?.job?.cancelAndJoin()
    }

    override fun after() {
        scopes.values.forEach { it.cancel() }
        super.after()
    }
}
