package dev.balaji.rem.data.db

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.balaji.rem.core.Alarm
import dev.balaji.rem.core.Barcode
import dev.balaji.rem.core.BarcodeFormat
import dev.balaji.rem.core.RepeatDays
import java.time.LocalTime
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Builds a version 1 file with the statements from `schemas/.../1.json` and opens it with
 * [RemDatabase.open]. Room checks the migrated schema against the entities on open, so a
 * migration that leaves anything different fails here.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class MigrationTest {
    private val storage: Context =
        ApplicationProvider.getApplicationContext<Context>().createDeviceProtectedStorageContext()
    private var database: RemDatabase? = null

    @Before
    fun deleteOldFile() {
        storage.deleteDatabase(RemDatabase.FILE_NAME)
    }

    @After
    fun close() {
        database?.close()
        storage.deleteDatabase(RemDatabase.FILE_NAME)
    }

    private fun createVersion1(fill: SQLiteDatabase.() -> Unit) {
        val file = storage.getDatabasePath(RemDatabase.FILE_NAME)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            V1_SCHEMA.forEach { db.execSQL(it) }
            db.fill()
            db.version = 1
        }
    }

    private fun openMigrated(): RemDatabase = RemDatabase.open(storage).also { database = it }

    @Test
    fun `alarms keep their fields and get the default volume`() = runTest {
        createVersion1 {
            execSQL(
                "INSERT INTO alarms (id, time_of_day, label, repeat_days, enabled, headphones_only, vibrate, ringtone_uri) " +
                    "VALUES (3, ${LocalTime.of(6, 30).toSecondOfDay()}, 'work', ${RepeatDays.toMask(RepeatDays.WEEKDAYS)}, 1, 1, 0, 'content://tone')",
            )
            execSQL(
                "INSERT INTO alarms (id, time_of_day, label, repeat_days, enabled, headphones_only, vibrate, ringtone_uri) " +
                    "VALUES (5, ${LocalTime.of(9, 0).toSecondOfDay()}, '', 0, 0, 0, 1, NULL)",
            )
        }

        val alarms = RoomAlarmRepository(openMigrated().alarmDao()).all()

        assertEquals(
            listOf(
                Alarm(
                    id = 3,
                    time = LocalTime.of(6, 30),
                    label = "work",
                    repeatDays = RepeatDays.WEEKDAYS,
                    enabled = true,
                    vibrate = false,
                    ringtoneUri = "content://tone",
                    minRingVolumePercent = 70,
                    volumeRampSeconds = 30,
                ),
                Alarm(id = 5, time = LocalTime.of(9, 0), enabled = false, vibrate = true),
            ),
            alarms,
        )
    }

    @Test
    fun `every old barcode is copied to every alarm`() = runTest {
        createVersion1 {
            execSQL("INSERT INTO alarms VALUES (1, 25200, 'a', 0, 1, 0, 1, NULL)")
            execSQL("INSERT INTO alarms VALUES (2, 28800, 'b', 0, 1, 0, 1, NULL)")
            execSQL("INSERT INTO barcodes (id, label, format, value) VALUES (7, 'toothpaste', 'EAN_13', '0036000291452')")
            execSQL("INSERT INTO barcodes (id, label, format, value) VALUES (9, 'mug', 'QR_CODE', 'mug')")
        }

        val barcodes = RoomBarcodeRepository(openMigrated().barcodeDao())

        val expected = listOf(
            Barcode(label = "toothpaste", format = BarcodeFormat.EAN_13, value = "0036000291452"),
            Barcode(label = "mug", format = BarcodeFormat.QR_CODE, value = "mug"),
        )
        assertEquals(expected, barcodes.all(1).map { it.copy(id = Barcode.NEW_ID) })
        assertEquals(expected, barcodes.all(2).map { it.copy(id = Barcode.NEW_ID) })
        assertEquals(4, (barcodes.all(1) + barcodes.all(2)).map { it.id }.toSet().size)
    }

    @Test
    fun `old barcodes go away when there is no alarm`() = runTest {
        createVersion1 {
            execSQL("INSERT INTO barcodes (id, label, format, value) VALUES (1, 'mug', 'QR_CODE', 'mug')")
        }

        val barcodes = RoomBarcodeRepository(openMigrated().barcodeDao())

        assertTrue(barcodes.all(1).isEmpty())
        assertTrue(rowCount("barcodes") == 0L)
    }

    @Test
    fun `the migrated tables enforce the new rules`() = runTest {
        createVersion1 {
            execSQL("INSERT INTO alarms VALUES (1, 25200, 'a', 0, 1, 0, 1, NULL)")
            execSQL("INSERT INTO barcodes (id, label, format, value) VALUES (1, 'mug', 'QR_CODE', 'mug')")
        }
        val database = openMigrated()
        val alarms = RoomAlarmRepository(database.alarmDao())
        val barcodes = RoomBarcodeRepository(database.barcodeDao())

        val again = barcodes.add(1, Barcode(label = "coffee", format = BarcodeFormat.QR_CODE, value = "mug"))
        assertEquals(listOf(Barcode(again, "coffee", BarcodeFormat.QR_CODE, "mug")), barcodes.all(1))

        alarms.delete(1)
        assertTrue(barcodes.all(1).isEmpty())
        assertEquals(0L, rowCount("barcodes"))
    }

    @Test
    fun `a new alarm never takes the id of one deleted before the upgrade`() = runTest {
        createVersion1 {
            execSQL("INSERT INTO alarms VALUES (1, 25200, 'a', 0, 1, 0, 1, NULL)")
            execSQL("INSERT INTO alarms VALUES (2, 28800, 'b', 0, 1, 0, 1, NULL)")
            execSQL("DELETE FROM alarms WHERE id = 2")
        }

        val id = RoomAlarmRepository(openMigrated().alarmDao()).upsert(Alarm(time = LocalTime.of(7, 0)))

        assertEquals(3L, id)
    }

    private fun rowCount(table: String): Long =
        database!!.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0)
        }

    private companion object {
        /** `createSql` and `setupQueries` of schemas/dev.balaji.rem.data.db.RemDatabase/1.json. */
        val V1_SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS `alarms` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `time_of_day` INTEGER NOT NULL, `label` TEXT NOT NULL, `repeat_days` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, `headphones_only` INTEGER NOT NULL, `vibrate` INTEGER NOT NULL, `ringtone_uri` TEXT)",
            "CREATE TABLE IF NOT EXISTS `barcodes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `label` TEXT NOT NULL, `format` TEXT NOT NULL, `value` TEXT NOT NULL)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_barcodes_format_value` ON `barcodes` (`format`, `value`)",
            "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
            "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'ecb14884e9d125dfe32485d1887b3931')",
        )
    }
}
