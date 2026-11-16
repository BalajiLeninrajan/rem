package dev.balaji.rem.data.db

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.balaji.rem.core.Barcode
import dev.balaji.rem.core.BarcodeFormat
import dev.balaji.rem.data.BarcodeRepository
import dev.balaji.rem.data.contract.BarcodeRepositoryContract
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

    override fun createRepository(): BarcodeRepository = RoomBarcodeRepository(rule.database.barcodeDao())

    @Test
    fun `a barcode that was not normalised by the caller is normalised on the way in`() = runTest {
        val repository = createRepository()

        val id = repository.add(Barcode(label = "toothpaste", format = BarcodeFormat.UPC_A, value = "036000291452"))
        val again = repository.add(Barcode(label = "bathroom", format = BarcodeFormat.EAN_13, value = "0036000291452"))

        assertEquals(id, again)
        assertEquals(listOf(Barcode(id, "bathroom", BarcodeFormat.EAN_13, "0036000291452")), repository.all())
    }

    @Test
    fun `the same value in another format is a different barcode`() = runTest {
        val repository = createRepository()

        val qr = repository.add(Barcode(label = "qr", format = BarcodeFormat.QR_CODE, value = "12345670"))
        val ean = repository.add(Barcode(label = "ean", format = BarcodeFormat.EAN_8, value = "12345670"))

        assertEquals(listOf(qr, ean), repository.all().map { it.id })
    }

    @Test
    fun `the table itself refuses a second row with the same key`() = runTest {
        val dao = rule.database.barcodeDao()
        val row = BarcodeEntity(id = 0, label = "mug", format = BarcodeFormat.QR_CODE, value = "mug")
        dao.insert(row)

        val failure = runCatching { dao.insert(row.copy(label = "other")) }.exceptionOrNull()

        assertTrue("was $failure", failure is SQLiteConstraintException)
    }
}
