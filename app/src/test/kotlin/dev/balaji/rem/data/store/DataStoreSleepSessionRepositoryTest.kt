package dev.balaji.rem.data.store

import dev.balaji.rem.core.SleepSession
import dev.balaji.rem.data.SleepSessionRepository
import dev.balaji.rem.data.contract.SleepSessionRepositoryContract
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DataStoreSleepSessionRepositoryTest : SleepSessionRepositoryContract() {
    @get:Rule
    val stores = PreferencesStoreRule()

    override fun createRepository(): SleepSessionRepository = DataStoreSleepSessionRepository(stores.open())

    @Test
    fun `the session survives a restart, cut to the millisecond`() = runTest {
        val file = stores.newStoreFile()
        val startedAt = Instant.parse("2026-01-15T04:00:00.123456789Z")
        val started = DataStoreSleepSessionRepository(stores.open(file)).start(startedAt)
        stores.close(file)

        val after = DataStoreSleepSessionRepository(stores.open(file))

        assertEquals(SleepSession(Instant.parse("2026-01-15T04:00:00.123Z")), started)
        assertEquals(started, after.current())
    }

    @Test
    fun `accepted late options survive a restart`() = runTest {
        val file = stores.newStoreFile()
        val before = DataStoreSleepSessionRepository(stores.open(file))
        before.start(Instant.parse("2026-01-15T04:00:00Z"))
        val six = Instant.parse("2026-01-15T11:00:00Z")
        before.acceptLate(3, six)
        before.acceptLate(Long.MAX_VALUE, six.plusSeconds(1800))
        stores.close(file)

        val after = DataStoreSleepSessionRepository(stores.open(file))

        assertEquals(mapOf(3L to six, Long.MAX_VALUE to six.plusSeconds(1800)), after.current()?.acceptedLate)
    }
}

