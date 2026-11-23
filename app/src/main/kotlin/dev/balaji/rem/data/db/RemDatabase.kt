package dev.balaji.rem.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(entities = [AlarmEntity::class, BarcodeEntity::class], version = 3)
@TypeConverters(Converters::class)
abstract class RemDatabase : RoomDatabase() {
    abstract fun alarmDao(): AlarmDao

    abstract fun barcodeDao(): BarcodeDao

    companion object {
        const val FILE_NAME = "rem.db"

        /**
         * @param storageContext decides where the file lives. Pass the device-protected
         *   storage context, so the database opens before the first unlock.
         */
        fun open(storageContext: Context): RemDatabase =
            Room.databaseBuilder(storageContext, RemDatabase::class.java, FILE_NAME)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
