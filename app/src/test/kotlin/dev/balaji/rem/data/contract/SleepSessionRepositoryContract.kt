package dev.balaji.rem.data.contract

import dev.balaji.rem.core.SleepSession
import dev.balaji.rem.data.SleepSessionRepository
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Behaviour every [SleepSessionRepository] must have. Subclass it once per implementation. */
abstract class SleepSessionRepositoryContract {
    abstract fun createRepository(): SleepSessionRepository

    private val evening = Instant.parse("2026-01-15T04:00:00Z")
    private val six = Instant.parse("2026-01-15T11:00:00Z")
    private val seven = Instant.parse("2026-01-15T12:00:00Z")

    @Test
    fun `no session at first`() = runTest {
        val repository = createRepository()

        assertNull(repository.current())
        assertNull(repository.session.first())
    }

    @Test
    fun `start makes the session current`() = runTest {
        val repository = createRepository()

        val session = repository.start(evening)

        assertEquals(SleepSession(evening), session)
        assertEquals(session, repository.current())
        assertEquals(session, repository.session.first())
    }

    @Test
    fun `starting again replaces the session`() = runTest {
        val repository = createRepository()
        repository.start(evening)

        repository.start(evening.plusSeconds(600))

        assertEquals(SleepSession(evening.plusSeconds(600)), repository.current())
    }

    @Test
    fun `end clears the session and is harmless without one`() = runTest {
        val repository = createRepository()
        repository.start(evening)

        repository.end()
        repository.end()

        assertNull(repository.current())
        assertNull(repository.session.first())
    }

    @Test
    fun `accepting a late option adds the occurrence to the active session`() = runTest {
        val repository = createRepository()
        repository.start(evening)

        repository.acceptLate(3, six)
        repository.acceptLate(5, seven)
        repository.acceptLate(3, six)

        assertEquals(SleepSession(evening, mapOf(3L to six, 5L to seven)), repository.current())
        assertEquals(repository.current(), repository.session.first())
        assertTrue(repository.current()!!.acceptsLate(3, six))
    }

    @Test
    fun `accepting again for another deadline replaces the earlier one`() = runTest {
        val repository = createRepository()
        repository.start(evening)
        repository.acceptLate(3, six)

        repository.acceptLate(3, seven)

        assertEquals(SleepSession(evening, mapOf(3L to seven)), repository.current())
    }

    @Test
    fun `revoking a late option takes only that alarm back`() = runTest {
        val repository = createRepository()
        repository.start(evening)
        repository.acceptLate(3, six)
        repository.acceptLate(5, six)

        repository.revokeLate(3)
        repository.revokeLate(9)

        assertEquals(SleepSession(evening, mapOf(5L to six)), repository.current())

        repository.revokeLate(5)

        assertEquals(SleepSession(evening), repository.current())
    }

    @Test
    fun `a late option cannot be accepted without a session`() = runTest {
        val repository = createRepository()

        repository.acceptLate(3, six)
        repository.revokeLate(3)

        assertNull(repository.current())
        repository.start(evening)
        assertEquals(SleepSession(evening), repository.current())
    }

    @Test
    fun `ending or starting a session forgets the accepted late options`() = runTest {
        val repository = createRepository()
        repository.start(evening)
        repository.acceptLate(3, six)

        repository.end()
        repository.start(evening)

        assertEquals(emptyMap<Long, Instant>(), repository.current()?.acceptedLate)

        repository.acceptLate(3, six)
        repository.start(evening.plusSeconds(600))

        assertEquals(SleepSession(evening.plusSeconds(600)), repository.current())
    }
}
