package dev.balaji.rem.data

import android.content.Context
import dev.balaji.rem.data.db.RemDatabase
import dev.balaji.rem.data.db.RoomAlarmRepository
import dev.balaji.rem.data.db.RoomBarcodeRepository
import dev.balaji.rem.data.store.DataStoreRingStateStore
import dev.balaji.rem.data.store.DataStoreSettingsRepository
import dev.balaji.rem.data.store.DataStoreSleepSessionRepository
import dev.balaji.rem.data.store.openPreferencesStore

/**
 * Builds the data layer for the app container. This file is the only place that names
 * concrete repository classes.
 *
 * Every store is opened on device-protected storage, which is readable before the first
 * unlock after a reboot. Nothing is read from disk until a repository is first used.
 *
 * Create one instance per process. A second one would open the same DataStore files again,
 * which DataStore rejects.
 *
 * @param context the application context.
 */
class DataModule(context: Context) {
    private val storage = context.createDeviceProtectedStorageContext()
    private val database = RemDatabase.open(storage)

    val alarmRepository: AlarmRepository = RoomAlarmRepository(database.alarmDao())
    val barcodeRepository: BarcodeRepository = RoomBarcodeRepository(database.barcodeDao())
    val settingsRepository: SettingsRepository = DataStoreSettingsRepository(openPreferencesStore(storage, SETTINGS_FILE))
    val sleepSessionRepository: SleepSessionRepository =
        DataStoreSleepSessionRepository(openPreferencesStore(storage, SESSION_FILE))
    val ringStateStore: RingStateStore = DataStoreRingStateStore(openPreferencesStore(storage, RING_STATE_FILE))

    companion object {
        const val SETTINGS_FILE = "settings"
        const val SESSION_FILE = "sleep_session"
        const val RING_STATE_FILE = "ring_state"
    }
}
