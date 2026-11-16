package dev.balaji.rem.schedule

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.balaji.rem.RemApplication
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class RescheduleReceiverTest {
    private val app: RemApplication = ApplicationProvider.getApplicationContext()

    @Test
    fun `every handled action reschedules and none starts a ring`() = runTest {
        val container = app.useFakeContainer(backgroundScope)

        RescheduleReceiver.ACTIONS.forEach { action ->
            RescheduleReceiver().onReceive(app, Intent(action))
            runCurrent()
        }

        assertEquals(RescheduleReceiver.ACTIONS.size, container.alarmScheduler.reschedules.size)
        assertTrue(container.ringController.started.isEmpty())
    }

    @Test
    fun `an action outside the list is ignored`() = runTest {
        val container = app.useFakeContainer(backgroundScope)

        RescheduleReceiver().onReceive(app, Intent(Intent.ACTION_SCREEN_ON))
        RescheduleReceiver().onReceive(app, Intent())
        runCurrent()

        assertTrue(container.alarmScheduler.reschedules.isEmpty())
    }

    @Test
    fun `the manifest filter delivers every handled action to the receiver`() {
        RescheduleReceiver.ACTIONS.forEach { action ->
            val receivers = app.packageManager.queryBroadcastReceivers(Intent(action).setPackage(app.packageName), 0)

            assertEquals(action, listOf(RescheduleReceiver::class.java.name), receivers.map { it.activityInfo.name })
        }
    }
}
