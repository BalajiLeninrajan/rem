package dev.balaji.rem.ring

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Foreground service (type systemExempted) that plays the alarm. Runs before the first unlock.
 *
 * Stub. Nothing starts it yet, because the stub ring controller never rings.
 */
class RingingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
