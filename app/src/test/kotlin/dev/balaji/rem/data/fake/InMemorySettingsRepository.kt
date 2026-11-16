package dev.balaji.rem.data.fake

import dev.balaji.rem.core.RemSettings
import dev.balaji.rem.data.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.updateAndGet

class InMemorySettingsRepository(
    initial: RemSettings = RemSettings.DEFAULT,
    onboardingDone: Boolean = false,
) : SettingsRepository {
    private val state = MutableStateFlow(initial.coerced())
    private val onboarding = MutableStateFlow(onboardingDone)

    override val settings: Flow<RemSettings> = state

    override suspend fun current(): RemSettings = state.value

    override suspend fun update(transform: (RemSettings) -> RemSettings): RemSettings =
        state.updateAndGet { transform(it).coerced() }

    override val onboardingDone: Flow<Boolean> = onboarding

    override suspend fun setOnboardingDone() {
        onboarding.value = true
    }
}
