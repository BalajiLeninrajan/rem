package dev.balaji.rem.data.contract

import dev.balaji.rem.core.Alarm
import dev.balaji.rem.data.AlarmRepository
import java.time.LocalTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Behaviour every [AlarmRepository] must have. Subclass it once per implementation. */
abstract class AlarmRepositoryContract {
    abstract fun createRepository(): AlarmRepository

    private val seven = Alarm(time = LocalTime.of(7, 0), label = "work")
    private val six = Alarm(time = LocalTime.of(6, 0))

    @Test
    fun `new alarms get distinct ids above zero`() = runTest {
        val repository = createRepository()

        val first = repository.upsert(seven)
        val second = repository.upsert(six)

        assertTrue(first > 0 && second > 0)
        assertNotEquals(first, second)
        assertEquals(seven.copy(id = first), repository.get(first))
    }

    @Test
    fun `upsert with a known id replaces that alarm`() = runTest {
        val repository = createRepository()
        val id = repository.upsert(seven)

        val returned = repository.upsert(seven.copy(id = id, label = "gym", vibrate = false))

        assertEquals(id, returned)
        assertEquals(listOf(seven.copy(id = id, label = "gym", vibrate = false)), repository.all())
    }

    @Test
    fun `alarms are ordered by time of day`() = runTest {
        val repository = createRepository()
        val sevenId = repository.upsert(seven)
        val sixId = repository.upsert(six)

        assertEquals(listOf(sixId, sevenId), repository.alarms.first().map { it.id })
        assertEquals(repository.alarms.first(), repository.all())
    }

    @Test
    fun `every field survives storage`() = runTest {
        val repository = createRepository()
        val full = Alarm(
            time = LocalTime.of(23, 59),
            label = "night shift",
            repeatDays = setOf(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.SUNDAY),
            enabled = false,
            headphonesOnly = true,
            vibrate = false,
            ringtoneUri = "content://media/internal/audio/media/12",
        )

        val id = repository.upsert(full)

        assertEquals(full.copy(id = id), repository.get(id))
    }

    @Test
    fun `setEnabled changes only the flag`() = runTest {
        val repository = createRepository()
        val id = repository.upsert(seven)

        repository.setEnabled(id, false)

        assertEquals(seven.copy(id = id, enabled = false), repository.get(id))
    }

    @Test
    fun `delete removes the alarm`() = runTest {
        val repository = createRepository()
        val id = repository.upsert(seven)

        repository.delete(id)

        assertNull(repository.get(id))
        assertTrue(repository.alarms.first().isEmpty())
    }

    @Test
    fun `unknown ids are ignored`() = runTest {
        val repository = createRepository()
        val id = repository.upsert(seven)

        repository.setEnabled(id + 100, false)
        repository.delete(id + 100)

        assertNull(repository.get(id + 100))
        assertEquals(listOf(seven.copy(id = id)), repository.all())
    }
}
