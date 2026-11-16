package dev.balaji.rem.data.contract

import dev.balaji.rem.core.SleepSession
import dev.balaji.rem.data.SleepSessionRepository
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
