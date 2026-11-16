package dev.balaji.rem.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.balaji.rem.data.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class OnboardingStep { NOTIFICATIONS, BARCODE }

/** Where the user goes when onboarding ends. */
enum class OnboardingExit { HOME, ADD_BARCODE }

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.NOTIFICATIONS,
    /** Set once onboarding is stored as done. The screen navigates when it sees this. */
    val exit: OnboardingExit? = null,
)

class OnboardingViewModel(private val settings: SettingsRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = mutableState.asStateFlow()

    /** Called when the permission dialog closes, whatever the answer. A refusal shows up as a warning on home. */
    fun onNotificationsAnswered() = mutableState.update { it.copy(step = OnboardingStep.BARCODE) }

    /** Stores that onboarding is done before the screen leaves, so home does not open it again. */
    fun finish(exit: OnboardingExit) {
        viewModelScope.launch {
            settings.setOnboardingDone()
            mutableState.update { it.copy(exit = exit) }
        }
    }
}
