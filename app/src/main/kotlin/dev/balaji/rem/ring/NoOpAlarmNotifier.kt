package dev.balaji.rem.ring

/** Stand-in notifier that posts nothing. */
class NoOpAlarmNotifier : AlarmNotifier {
    override fun ensureChannels() = Unit

    override fun notifyMissed(missed: MissedAlarm) = Unit
}
