package dev.balaji.rem.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.balaji.rem.core.Alarm
import dev.balaji.rem.core.Barcode
import dev.balaji.rem.core.BarcodeFormat
import dev.balaji.rem.data.db.RemDatabase
import dev.balaji.rem.data.store.preferencesFile
import java.io.File
import java.time.Instant
import java.time.LocalTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class DataModuleTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `every store is written to device-protected storage and none to credential-protected storage`() = runTest {
        val module = DataModule(context)
        val alarm = Alarm(time = LocalTime.of(7, 0), label = "work")

        val alarmId = module.alarmRepository.upsert(alarm)
        module.barcodeRepository.add(alarmId, Barcode(label = "mug", format = BarcodeFormat.QR_CODE, value = "mug"))
        module.settingsRepository.update { it.copy(cycleMinutes = 100) }
        module.sleepSessionRepository.start(Instant.parse("2026-01-15T04:00:00Z"))
        module.ringStateStore.markFired(alarmId, Instant.parse("2026-01-15T12:00:00Z"))

        assertEquals(alarm.copy(id = alarmId), module.alarmRepository.get(alarmId))

        val protectedStorage = context.createDeviceProtectedStorageContext()
        assertTrue(protectedStorage.getDatabasePath(RemDatabase.FILE_NAME).isFile)
        listOf(DataModule.SETTINGS_FILE, DataModule.SESSION_FILE, DataModule.RING_STATE_FILE).forEach { name ->
            assertTrue(name, preferencesFile(protectedStorage, name).isFile)
        }

        assertFalse(context.getDatabasePath(RemDatabase.FILE_NAME).exists())
        assertFalse(File(context.filesDir, "datastore").exists())
    }
}
