package dev.balaji.rem.ring

import android.app.Notification
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import dev.balaji.rem.core.RingLimits
import dev.balaji.rem.di.appContainer
import dev.balaji.rem.ring.audio.RingPlayback
import dev.balaji.rem.schedule.scheduledRingOrNull
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service (type systemExempted) that plays the alarm. Runs before the first
 * unlock and reads only what the ring controller loaded from device-protected storage.
 *
 * It decides nothing. [RingServiceHost.command] says whether to wait, play or stop, and
 * the 30 minute limit, the persisted ring record and the dismissal rules all live in the
 * controller. The only way to stop this service is for the ring to end there.
 */
class RingingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var notifications: RingNotifications

    private var commands: Job? = null
    private var playback: RingPlayback? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var notification: Notification? = null
    private var lastStartId = 0

    private val notificationRemoved = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            notification?.let(notifications::postRinging)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifications = RingNotifications(this)
        ContextCompat.registerReceiver(
            this,
            notificationRemoved,
            IntentFilter(RingNotifications.ACTION_RINGING_NOTIFICATION_REMOVED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        // Foreground first. Audio focus, volume and playback are refused or muted without it.
        show(notifications.ringing(label = "", startedAt = null))

        val ring = intent?.scheduledRingOrNull()
        val host = appContainer.ringController as? RingServiceHost
        if (ring == null || host == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        acquireWakeLock()
        // A retried start is one whose first delivery died with the process, so it resumes like a redelivery.
        host.onServiceStarted(ring, redelivered = (flags and (START_FLAG_REDELIVERY or START_FLAG_RETRY)) != 0)
        if (commands == null) {
            commands = scope.launch { host.command.collect { follow(it) } }
        }
        // If the process dies mid-ring the system starts the service again with the same intent.
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        scope.cancel()
        stopPlayback()
        unregisterReceiver(notificationRemoved)
        notification = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        notifications.cancelRinging()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun follow(command: RingCommand) {
        when (command) {
            RingCommand.Wait -> Unit
            is RingCommand.Play -> play(command.spec)
            RingCommand.Stop -> {
                stopPlayback()
                // With the id, a start that arrived for the next ring keeps the service alive.
                stopSelf(lastStartId)
            }
        }
    }

    private fun play(spec: RingSpec) {
        if (playback?.spec == spec) return
        stopPlayback()
        show(notifications.ringing(spec.label, spec.startedAt))
        playback = RingPlayback(this, spec).also { it.start() }
    }

    private fun stopPlayback() {
        playback?.stop()
        playback = null
    }

    /**
     * Enters the foreground with [shown], or updates the notification when already there.
     * If the system refuses the foreground start, the notification is posted the plain way
     * and the ring goes on as well as it can: the full-screen intent and the vibration
     * still work.
     */
    private fun show(shown: Notification) {
        notification = shown
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    RingContract.NOTIFICATION_ID_RINGING,
                    shown,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED,
                )
            } else {
                startForeground(RingContract.NOTIFICATION_ID_RINGING, shown)
            }
        } catch (e: RuntimeException) {
            Log.e(TAG, "startForeground was refused, ringing without it", e)
            notifications.postRinging(shown)
        }
    }

    private fun acquireWakeLock() {
        val lock = wakeLock ?: getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .apply { setReferenceCounted(false) }
            .also { wakeLock = it }
        lock.acquire(WAKE_LOCK_TIMEOUT.toMillis())
    }

    private companion object {
        const val TAG = "RingingService"
        const val WAKE_LOCK_TAG = "rem:ringing"

        /** A little longer than the longest ring, so a stuck service cannot hold the CPU forever. */
        val WAKE_LOCK_TIMEOUT: Duration = RingLimits.MAX_RING.plusMinutes(1)
    }
}
