package dev.balaji.rem.ring

import java.time.Instant

enum class MissedReason {
    /** The scheduled time passed and the alarm never fired, for example the device was off. */
    NOT_FIRED,

    /** The alarm was ringing when the process died or the device rebooted. */
    RING_INTERRUPTED,

    /** The alarm rang for 30 minutes without being dismissed. */
    RING_TIMED_OUT,

    /** The alarm came due while another alarm was ringing, so it never got a ring of its own. */
    OTHER_ALARM_RINGING,
}

/** @property label the alarm label, possibly empty. */
data class MissedAlarm(val alarmId: Long, val label: String, val deadline: Instant, val reason: MissedReason)

/** Notification work that packages other than `ring` need. */
interface AlarmNotifier {
    /**
     * Creates the [RingContract.CHANNEL_RINGING] and [RingContract.CHANNEL_MISSED]
     * channels. Called from `Application.onCreate`, so the reliability screen can inspect
     * them. Safe to call again.
     */
    fun ensureChannels()

    /** Posts a missed-alarm notification that opens the reliability screen. One notification per alarm id. */
    fun notifyMissed(missed: MissedAlarm)
}
