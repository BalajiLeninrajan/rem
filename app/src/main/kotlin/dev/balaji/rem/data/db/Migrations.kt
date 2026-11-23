package dev.balaji.rem.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 1 to 2:
 * - Barcodes belong to one alarm. Every old barcode is copied to every alarm, so each
 *   alarm still accepts what it accepted before. With no alarm the old barcodes go away.
 * - `headphones_only` leaves the alarms. It is a global setting now and starts off.
 * - Alarms get `min_ring_volume_percent` and `volume_ramp_seconds`, at the old defaults.
 *
 * A migration cannot read DataStore, so the user's version 1 volume floor and ramp are not
 * carried over, and neither is a headphones-only choice. The app has not run on a device
 * yet, so no one is known to lose a setting. The old volume keys stay in the settings file
 * until the next settings write removes them.
 *
 * SQLite on API 31 has no DROP COLUMN, so both tables are rebuilt. The statements create
 * exactly what Room expects for version 2, which Room checks when it opens the database.
 */
internal val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE `alarms_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`time_of_day` INTEGER NOT NULL, `label` TEXT NOT NULL, `repeat_days` INTEGER NOT NULL, " +
                "`enabled` INTEGER NOT NULL, `vibrate` INTEGER NOT NULL, `ringtone_uri` TEXT, " +
                "`min_ring_volume_percent` INTEGER NOT NULL, `volume_ramp_seconds` INTEGER NOT NULL)",
        )
        db.execSQL(
            "INSERT INTO `alarms_new` (`id`, `time_of_day`, `label`, `repeat_days`, `enabled`, `vibrate`, " +
                "`ringtone_uri`, `min_ring_volume_percent`, `volume_ramp_seconds`) " +
                "SELECT `id`, `time_of_day`, `label`, `repeat_days`, `enabled`, `vibrate`, `ringtone_uri`, " +
                "$OLD_MIN_RING_VOLUME_PERCENT, $OLD_VOLUME_RAMP_SECONDS FROM `alarms`",
        )
        // Keep the id counter, so a new alarm never gets the id of one deleted before the upgrade.
        // Alarm ids name AlarmManager entries and fired records.
        db.execSQL("DELETE FROM sqlite_sequence WHERE name = 'alarms_new'")
        db.execSQL("INSERT INTO sqlite_sequence (name, seq) SELECT 'alarms_new', seq FROM sqlite_sequence WHERE name = 'alarms'")
        db.execSQL("DROP TABLE `alarms`")
        db.execSQL("ALTER TABLE `alarms_new` RENAME TO `alarms`")

        db.execSQL(
            "CREATE TABLE `barcodes_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`alarm_id` INTEGER NOT NULL, `label` TEXT NOT NULL, `format` TEXT NOT NULL, `value` TEXT NOT NULL, " +
                "FOREIGN KEY(`alarm_id`) REFERENCES `alarms`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
        )
        db.execSQL(
            "INSERT INTO `barcodes_new` (`alarm_id`, `label`, `format`, `value`) " +
                "SELECT `alarms`.`id`, `barcodes`.`label`, `barcodes`.`format`, `barcodes`.`value` " +
                "FROM `alarms` CROSS JOIN `barcodes` ORDER BY `alarms`.`id`, `barcodes`.`id`",
        )
        db.execSQL("DROP TABLE `barcodes`")
        db.execSQL("ALTER TABLE `barcodes_new` RENAME TO `barcodes`")
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_barcodes_alarm_id_format_value` " +
                "ON `barcodes` (`alarm_id`, `format`, `value`)",
        )
    }
}

/** Every migration, oldest first. */
internal val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)

/** The global volume defaults of version 1. Version 1 kept no per-alarm volume. */
private const val OLD_MIN_RING_VOLUME_PERCENT = 70
private const val OLD_VOLUME_RAMP_SECONDS = 30
