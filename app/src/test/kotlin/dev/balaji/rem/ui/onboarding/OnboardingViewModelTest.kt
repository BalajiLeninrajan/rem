package dev.balaji.rem.ui.onboarding

import dev.balaji.rem.data.fake.InMemorySettingsRepository
import dev.balaji.rem.ui.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val settings = InMemorySettingsRepository()

    @Test
    fun `answering the permission moves on without ending onboarding`() = runTest {
        val viewModel = OnboardingViewModel(settings)
        assertEquals(OnboardingStep.NOTIFICATIONS, viewModel.state.value.step)

        viewModel.onNotificationsAnswered()

        assertEquals(OnboardingStep.BARCODE, viewModel.state.value.step)
        assertNull(viewModel.state.value.exit)
        assertFalse(settings.onboardingDone.first())
    }

    @Test
    fun `finishing stores that onboarding is done and says where to go`() = runTest {
        val viewModel = OnboardingViewModel(settings)

        viewModel.finish(OnboardingExit.ADD_BARCODE)
        advanceUntilIdle()

        assertTrue(settings.onboardingDone.first())
        assertEquals(OnboardingExit.ADD_BARCODE, viewModel.state.value.exit)
    }
}
