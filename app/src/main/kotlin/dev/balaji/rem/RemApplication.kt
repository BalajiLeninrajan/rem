package dev.balaji.rem

import android.app.Application
import dev.balaji.rem.di.AppContainer
import dev.balaji.rem.di.DefaultAppContainer
import kotlinx.coroutines.launch

class RemApplication : Application() {
    /** Tests may replace this with a container built from fakes. */
    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(this)
        container.alarmNotifier.ensureChannels()
        // Every process start is a reschedule trigger: a force stop or an update may have dropped the alarms.
        container.applicationScope.launch {
            container.ringController.recoverInterruptedRing()
            container.alarmScheduler.rescheduleAll()
        }
    }
}
