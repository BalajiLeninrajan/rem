package dev.balaji.rem.data.contract

import dev.balaji.rem.core.RemSettings
import dev.balaji.rem.data.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Behaviour every [SettingsRepository] must have. Subclass it once per implementation. */
abstract class SettingsRepositoryContract {
    abstract fun createRepository(): SettingsRepository

    @Test
    fun `starts with the defaults`() = runTest {
        val repository = createRepository()

        assertEquals(RemSettings.DEFAULT, repository.current())
        assertEquals(RemSettings.DEFAULT, repository.settings.first())
    }

    @Test
    fun `update stores and returns the new settings`() = runTest {
        val repository = createRepository()

        val updated = repository.update { it.copy(cycleMinutes = 100, volumeRampSeconds = 0) }

        assertEquals(RemSettings.DEFAULT.copy(cycleMinutes = 100, volumeRampSeconds = 0), updated)
        assertEquals(updated, repository.settings.first())
    }

    @Test
    fun `out of range values are coerced on write`() = runTest {
        val repository = createRepository()

        val updated = repository.update { it.copy(cycleMinutes = 10, speakerFallbackSeconds = 100_000) }

        assertEquals(RemSettings.DEFAULT.copy(cycleMinutes = 60, speakerFallbackSeconds = 600), updated)
        assertEquals(updated, repository.current())
    }

    @Test
    fun `onboarding flag starts false and stays true once set`() = runTest {
        val repository = createRepository()
        assertFalse(repository.onboardingDone.first())

        repository.setOnboardingDone()
        repository.setOnboardingDone()

        assertTrue(repository.onboardingDone.first())
    }
}
