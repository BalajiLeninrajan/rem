package dev.balaji.rem.data

import dev.balaji.rem.core.RemSettings
import kotlinx.coroutines.flow.Flow

/** Global settings, in device-protected storage. Values are always inside their ranges. */
interface SettingsRepository {
    val settings: Flow<RemSettings>

    suspend fun current(): RemSettings

    /** Applies [transform] atomically, stores the coerced result and returns it. */
    suspend fun update(transform: (RemSettings) -> RemSettings): RemSettings

    /** False until the first-run screen has been completed. */
    val onboardingDone: Flow<Boolean>

    suspend fun setOnboardingDone()
}
