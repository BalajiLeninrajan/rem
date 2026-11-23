package dev.balaji.rem.data.db

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.balaji.rem.core.Alarm
import dev.balaji.rem.core.Barcode
import dev.balaji.rem.core.BarcodeFormat
import dev.balaji.rem.data.contract.AlarmStorage
import dev.balaji.rem.data.contract.BarcodeRepositoryContract
import java.time.LocalTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class RoomBarcodeRepositoryTest : BarcodeRepositoryContract() {
    @get:Rule
    val rule = InMemoryDatabaseRule()

    override fun createStorage() = AlarmStorage(
        RoomAlarmRepository(rule.database.alarmDao()),
        RoomBarcodeRepository(rule.database.barcodeDao()),
    )

    private suspend fun storedAlarm(): Long = RoomAlarmRepository(rule.database.alarmDao()).upsert(Alarm(time = LocalTime.of(7, 0)))

    @Test
    fun `a barcode that was not normalised by the caller is normalised on the way in`() = runTest {
        val repository = createStorage().barcodes
        val alarm = storedAlarm()

        val id = repository.add(alarm, Barcode(label = "toothpaste", format = BarcodeFormat.UPC_A, value = "036000291452"))
        val again = repository.add(alarm, Barcode(label = "bathroom", format = BarcodeFormat.EAN_13, value = "0036000291452"))

        assertEquals(id, again)
        assertEquals(listOf(Barcode(id, "bathroom", BarcodeFormat.EAN_13, "0036000291452")), repository.all(alarm))
    }

    @Test
    fun `the same value in another format is a different barcode`() = runTest {
        val repository = createStorage().barcodes
        val alarm = storedAlarm()

        val qr = repository.add(alarm, Barcode(label = "qr", format = BarcodeFormat.QR_CODE, value = "12345670"))
        val ean = repository.add(alarm, Barcode(label = "ean", format = BarcodeFormat.EAN_8, value = "12345670"))

        assertEquals(listOf(qr, ean), repository.all(alarm).map { it.id })
    }

    @Test
    fun `the table itself refuses a second row with the same key on one alarm`() = runTest {
        val dao = rule.database.barcodeDao()
        val alarm = storedAlarm()
        val row = BarcodeEntity(id = 0, alarmId = alarm, label = "mug", format = BarcodeFormat.QR_CODE, value = "mug")
        dao.insert(row)

        val failure = runCatching { dao.insert(row.copy(label = "other")) }.exceptionOrNull()

        assertTrue("was $failure", failure is SQLiteConstraintException)
    }

    @Test
    fun `the table refuses a barcode of an alarm that does not exist`() = runTest {
        val repository = createStorage().barcodes

        val failure = runCatching {
            repository.add(404, Barcode(label = "mug", format = BarcodeFormat.QR_CODE, value = "mug"))
        }.exceptionOrNull()

        assertTrue("was $failure", failure is SQLiteConstraintException)
        assertTrue(repository.all(404).isEmpty())
    }
}
