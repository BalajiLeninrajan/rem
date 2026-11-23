package dev.balaji.rem.data.store

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import dev.balaji.rem.core.RemSettings
import dev.balaji.rem.data.SettingsRepository
import dev.balaji.rem.data.contract.SettingsRepositoryContract
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DataStoreSettingsRepositoryTest : SettingsRepositoryContract() {
    @get:Rule
    val stores = PreferencesStoreRule()

    override fun createRepository(): SettingsRepository = DataStoreSettingsRepository(stores.open())

    @Test
    fun `settings and the onboarding flag survive a restart`() = runTest {
        val file = stores.newStoreFile()
        val before = DataStoreSettingsRepository(stores.open(file))
        val saved = before.update { it.copy(fallAsleepMinutes = 5, headphonesOnly = true) }
        before.setOnboardingDone()
        stores.close(file)

        val after = DataStoreSettingsRepository(stores.open(file))

        assertEquals(saved, after.current())
        assertTrue(after.onboardingDone.first())
    }

    @Test
    fun `a stored value outside its range is coerced when read`() = runTest {
        val store = stores.open()
        store.edit { it[intPreferencesKey("cycle_minutes")] = 5000 }

        val repository = DataStoreSettingsRepository(store)

        assertEquals(RemSettings.DEFAULT.copy(cycleMinutes = 120), repository.current())
        assertEquals(RemSettings.DEFAULT.copy(cycleMinutes = 120), repository.settings.first())
    }

    @Test
    fun `setting the onboarding flag keeps the settings`() = runTest {
        val repository = createRepository()
        val saved = repository.update { it.copy(earlyWindowMinutes = 45) }

        repository.setOnboardingDone()

        assertEquals(saved, repository.current())
    }
}
