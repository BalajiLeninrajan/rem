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
    fun `accepting a late option adds the alarm to the active session`() = runTest {
        val repository = createRepository()
        repository.start(evening)

        repository.acceptLate(3)
        repository.acceptLate(5)
        repository.acceptLate(3)

        assertEquals(SleepSession(evening, setOf(3L, 5L)), repository.current())
        assertEquals(repository.current(), repository.session.first())
        assertTrue(repository.current()!!.acceptsLate(3))
    }

    @Test
    fun `revoking a late option takes only that alarm back`() = runTest {
        val repository = createRepository()
        repository.start(evening)
        repository.acceptLate(3)
        repository.acceptLate(5)

        repository.revokeLate(3)
        repository.revokeLate(9)

        assertEquals(SleepSession(evening, setOf(5L)), repository.current())

        repository.revokeLate(5)

        assertEquals(SleepSession(evening), repository.current())
    }

    @Test
    fun `a late option cannot be accepted without a session`() = runTest {
        val repository = createRepository()

        repository.acceptLate(3)
        repository.revokeLate(3)

        assertNull(repository.current())
        repository.start(evening)
        assertEquals(SleepSession(evening), repository.current())
    }

    @Test
    fun `ending or starting a session forgets the accepted late options`() = runTest {
        val repository = createRepository()
        repository.start(evening)
        repository.acceptLate(3)

        repository.end()
        repository.start(evening)

        assertEquals(emptySet<Long>(), repository.current()?.acceptedLate)

        repository.acceptLate(3)
        repository.start(evening.plusSeconds(600))

        assertEquals(SleepSession(evening.plusSeconds(600)), repository.current())
    }
}
