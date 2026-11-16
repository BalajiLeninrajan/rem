package dev.balaji.rem.data

import android.content.Context
import dev.balaji.rem.data.fake.InMemoryAlarmRepository
import dev.balaji.rem.data.fake.InMemoryBarcodeRepository
import dev.balaji.rem.data.fake.InMemoryRingStateStore
import dev.balaji.rem.data.fake.InMemorySettingsRepository
import dev.balaji.rem.data.fake.InMemorySleepSessionRepository

/**
 * Builds the data layer for the app container. This file is the only place that names
 * concrete repository classes, so swapping the fakes for Room and DataStore touches
 * nothing outside the `data` package.
 *
 * @param context the application context. Open every store with
 *   `context.createDeviceProtectedStorageContext()`.
 */
class DataModule(@Suppress("UNUSED_PARAMETER") context: Context) {
    val alarmRepository: AlarmRepository = InMemoryAlarmRepository()
    val barcodeRepository: BarcodeRepository = InMemoryBarcodeRepository()
    val settingsRepository: SettingsRepository = InMemorySettingsRepository()
    val sleepSessionRepository: SleepSessionRepository = InMemorySleepSessionRepository()
    val ringStateStore: RingStateStore = InMemoryRingStateStore()
}
