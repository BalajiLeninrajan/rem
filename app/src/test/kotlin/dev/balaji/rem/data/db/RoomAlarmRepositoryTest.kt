package dev.balaji.rem.data.db

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import dev.balaji.rem.core.Alarm
import dev.balaji.rem.data.AlarmRepository
import dev.balaji.rem.data.contract.AlarmRepositoryContract
import java.time.LocalTime
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class RoomAlarmRepositoryTest : AlarmRepositoryContract() {
    @get:Rule
    val rule = InMemoryDatabaseRule()

    override fun createRepository(): AlarmRepository = RoomAlarmRepository(rule.database.alarmDao())

    private val seven = Alarm(time = LocalTime.of(7, 0))

    @Test
    fun `the flow emits after each change and not after a write that changes nothing`() = runTest {
        val repository = createRepository()

        // The first item waits for the database to open, which is slow on a cold CI machine.
        repository.alarms.test(timeout = 30.seconds) {
            assertTrue(awaitItem().isEmpty())

            val id = repository.upsert(seven)
            assertEquals(listOf(seven.copy(id = id)), awaitItem())

            repository.setEnabled(id, true)
            repository.setEnabled(id, false)
            assertEquals(listOf(seven.copy(id = id, enabled = false)), awaitItem())

            repository.delete(id)
            assertTrue(awaitItem().isEmpty())
        }
    }

    @Test
    fun `alarms at the same time are ordered by id`() = runTest {
        val repository = createRepository()
        val first = repository.upsert(seven)
        val second = repository.upsert(seven.copy(label = "again"))
        val earlier = repository.upsert(Alarm(time = LocalTime.of(6, 59, 30)))

        assertEquals(listOf(earlier, first, second), repository.all().map { it.id })
    }

    @Test
    fun `an alarm saved under an unused id keeps that id`() = runTest {
        val repository = createRepository()

        val id = repository.upsert(seven.copy(id = 40))
        val next = repository.upsert(seven)

        assertEquals(40L, id)
        assertEquals(41L, next)
    }
}
