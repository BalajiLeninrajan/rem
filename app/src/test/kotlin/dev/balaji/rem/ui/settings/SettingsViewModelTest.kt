package dev.balaji.rem.ui.settings

import dev.balaji.rem.core.RemSettings
import dev.balaji.rem.data.SettingsRepository
import dev.balaji.rem.data.fake.InMemorySettingsRepository
import dev.balaji.rem.ui.testing.MainDispatcherRule
import dev.balaji.rem.ui.testing.keepCollecting
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private fun TestScope.viewModel(repository: SettingsRepository): SettingsViewModel =
        SettingsViewModel(repository).also {
            keepCollecting(it.state)
            advanceUntilIdle()
        }

    @Test
    fun `each setter changes its own field`() = runTest {
        val viewModel = viewModel(InMemorySettingsRepository())

        viewModel.setCycleMinutes(85)
        viewModel.setFallAsleepMinutes(20)
        viewModel.setEarlyWindowMinutes(45)
        viewModel.setSpeakerFallbackSeconds(300)
        viewModel.setHeadphonesOnly(true)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertFalse(state.loading)
        assertFalse(state.isDefault)
        assertEquals(
            RemSettings(
                cycleMinutes = 85,
                fallAsleepMinutes = 20,
                earlyWindowMinutes = 45,
                speakerFallbackSeconds = 300,
                headphonesOnly = true,
            ),
            state.settings,
        )
    }

    @Test
    fun `out of range values are clamped before they reach the repository`() = runTest {
        val repository = UncheckedSettingsRepository()
        val viewModel = viewModel(repository)

        viewModel.setCycleMinutes(500)
        viewModel.setFallAsleepMinutes(-5)
        viewModel.setEarlyWindowMinutes(91)
        viewModel.setSpeakerFallbackSeconds(1)
        advanceUntilIdle()

        val expected = RemSettings(
            cycleMinutes = RemSettings.CYCLE_MINUTES.last,
            fallAsleepMinutes = RemSettings.FALL_ASLEEP_MINUTES.first,
            earlyWindowMinutes = RemSettings.EARLY_WINDOW_MINUTES.last,
            speakerFallbackSeconds = RemSettings.SPEAKER_FALLBACK_SECONDS.first,
        )
        assertEquals(expected, repository.stored.value)
        assertEquals(expected, viewModel.state.value.settings)
    }

    @Test
    fun `reset restores the defaults`() = runTest {
        val viewModel = viewModel(InMemorySettingsRepository(RemSettings(cycleMinutes = 75, headphonesOnly = true)))
        assertFalse(viewModel.state.value.isDefault)

        viewModel.resetToDefaults()
        advanceUntilIdle()

        assertTrue(viewModel.state.value.isDefault)
        assertEquals(RemSettings.DEFAULT, viewModel.state.value.settings)
    }
}

/** Stores exactly what it is given, so the test sees whether the view model clamps by itself. */
private class UncheckedSettingsRepository : SettingsRepository {
    val stored = MutableStateFlow(RemSettings.DEFAULT)

    override val settings: Flow<RemSettings> = stored

    override suspend fun current(): RemSettings = stored.value

    override suspend fun update(transform: (RemSettings) -> RemSettings): RemSettings =
        transform(stored.value).also { stored.value = it }

    override val onboardingDone: Flow<Boolean> = MutableStateFlow(true)

    override suspend fun setOnboardingDone() = Unit
}
