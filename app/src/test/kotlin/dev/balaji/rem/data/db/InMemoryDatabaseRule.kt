package dev.balaji.rem.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.rules.ExternalResource

/** A fresh in-memory [RemDatabase] per test, closed afterwards. */
class InMemoryDatabaseRule : ExternalResource() {
    lateinit var database: RemDatabase
        private set

    override fun before() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), RemDatabase::class.java).build()
    }

    override fun after() {
        database.close()
    }
}
