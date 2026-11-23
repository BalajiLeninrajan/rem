package dev.balaji.rem.ring

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.balaji.rem.RemApplication
import dev.balaji.rem.schedule.FakeAppContainer
import dev.balaji.rem.schedule.useFakeContainer
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class RingActionReceiverTest {
    private val app: RemApplication = ApplicationProvider.getApplicationContext()
    private val startedAt = Instant.parse("2026-01-15T06:45:00Z")

    private fun FakeAppContainer.ringing(alarmId: Long) {
        ringController.state.value = RingingState.Ringing(
            alarmId = alarmId,
            label = "Work",
            startedAt = startedAt,
            deadline = startedAt.plusSeconds(900),
            requiresBarcode = false,
            snoozeMinutes = 5,
            snoozesLeft = 3,
        )
    }

    @Test
    fun `snooze and dismiss act on the ring without opening anything`() = runTest {
        val container = app.useFakeContainer(backgroundScope)
        container.ringing(7)

        RingActionReceiver().onReceive(app, RingContract.actionIntent(app, RingContract.ACTION_SNOOZE, 7))
        RingActionReceiver().onReceive(app, RingContract.actionIntent(app, RingContract.ACTION_DISMISS, 7))
        runCurrent()

        assertEquals(1, container.ringController.snoozeCalls)
        assertEquals(1, container.ringController.plainDismissals)
    }

    @Test
    fun `an action shown for another alarm, or with nothing ringing, does nothing`() = runTest {
        val container = app.useFakeContainer(backgroundScope)

        RingActionReceiver().onReceive(app, RingContract.actionIntent(app, RingContract.ACTION_DISMISS, 7))
        container.ringing(8)
        RingActionReceiver().onReceive(app, RingContract.actionIntent(app, RingContract.ACTION_SNOOZE, 7))
        RingActionReceiver().onReceive(app, RingContract.actionIntent(app, RingContract.ACTION_DISMISS, 7))
        runCurrent()

        assertEquals(0, container.ringController.snoozeCalls)
        assertEquals(0, container.ringController.plainDismissals)
    }
}
