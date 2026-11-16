package dev.balaji.rem

import android.app.Application
import androidx.camera.camera2.Camera2Config
import androidx.camera.core.CameraXConfig
import dev.balaji.rem.di.AppContainer
import dev.balaji.rem.di.DefaultAppContainer
import kotlinx.coroutines.launch

class RemApplication : Application(), CameraXConfig.Provider {
    /** Tests may replace this with a container built from fakes. */
    lateinit var container: AppContainer

    /**
     * Without this CameraX looks its configuration up in the manifest of a service that is
     * not direct boot aware. Before the first unlock that lookup finds nothing and CameraX
     * throws, which would take the ringing scanner down.
     */
    override fun getCameraXConfig(): CameraXConfig = Camera2Config.defaultConfig()

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
