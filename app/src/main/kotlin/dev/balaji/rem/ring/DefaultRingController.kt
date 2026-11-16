package dev.balaji.rem.ring

import dev.balaji.rem.core.Alarm
import dev.balaji.rem.core.Barcode
import dev.balaji.rem.core.FallbackCode
import dev.balaji.rem.core.RemSettings
import dev.balaji.rem.core.RingLimits
import dev.balaji.rem.core.ScannedCode
import dev.balaji.rem.core.ScheduledRing
import dev.balaji.rem.core.findMatch
import dev.balaji.rem.data.AlarmRepository
import dev.balaji.rem.data.BarcodeRepository
import dev.balaji.rem.data.RingStateStore
import dev.balaji.rem.data.RingingRecord
import dev.balaji.rem.data.SettingsRepository
import dev.balaji.rem.data.SleepSessionRepository
import dev.balaji.rem.schedule.AlarmBroadcast
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * Owns the one live ring. The service only plays what [command] says, and the screens
 * only show [state], so a ring can end from here without either of them being alive.
 *
 * The barcodes that can dismiss a ring are the ones registered when it started. Deleting
 * them all from the main app during a ring does not unlock the plain dismiss button.
 *
 * @param reschedule runs `AlarmScheduler.rescheduleAll`. A lambda so the scheduler is not
 *   created while this controller is being constructed.
 * @param scope outlives every caller. Cleanup after a ring runs here, because the screen
 *   that asked for a dismissal is closed by that same dismissal.
 */
internal class DefaultRingController(
    private val alarms: AlarmRepository,
    private val barcodes: BarcodeRepository,
    private val sessions: SleepSessionRepository,
    private val settings: SettingsRepository,
    private val ringState: RingStateStore,
    private val notifier: AlarmNotifier,
    private val launcher: RingServiceLauncher,
    private val reschedule: suspend () -> Unit,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val testAlarmLabel: String,
    private val random: SecureRandom = SecureRandom(),
) : RingController, RingServiceHost {

    private class Live(val ring: ScheduledRing, @Volatile var startedAt: Instant, val resumed: Boolean) {
        @Volatile
        var label: String = ""

        /** The barcodes registered when the ring started. Null until they are loaded. */
        @Volatile
        var registered: List<Barcode>? = null

        @Volatile
        var fallbackCode: String? = null

        @Volatile
        var timeout: Job? = null

        val isTest: Boolean get() = ring.alarmId == AlarmBroadcast.TEST_ALARM_ID

        fun record() = RingingRecord(
            alarmId = ring.alarmId,
            startedAt = startedAt,
            ringAt = ring.ringAt.truncatedTo(ChronoUnit.MILLIS),
            deadline = ring.deadline.truncatedTo(ChronoUnit.MILLIS),
        )
    }

    private enum class Ending { DISMISSED, TIMED_OUT, INTERRUPTED, CANCELLED }

    private val lock = Any()

    /** Written only under [lock], together with the two flows, so they never disagree. */
    @Volatile
    private var live: Live? = null

    private val ringingState = MutableStateFlow<RingingState>(RingingState.Idle)
    private val serviceCommand = MutableStateFlow<RingCommand>(RingCommand.Stop)

    /** Serialises the store writes of ring start, ring end and recovery. */
    private val storeMutex = Mutex()

    override val state: StateFlow<RingingState> = ringingState.asStateFlow()
    override val command: StateFlow<RingCommand> = serviceCommand.asStateFlow()

    override fun startRinging(ring: ScheduledRing) {
        val mine = claim(ring, resumed = false)
        if (mine == null) {
            if (live?.ring?.alarmId != ring.alarmId) scope.launch { attempt { disableIfOneShot(ring.alarmId) } }
            return
        }
        try {
            launcher.start(ring)
        } catch (e: RuntimeException) {
            scope.launch { finish(mine, Ending.INTERRUPTED) }
            return
        }
        scope.launch { begin(mine) }
    }

    override fun onServiceStarted(ring: ScheduledRing, redelivered: Boolean) {
        if (!redelivered) return
        val mine = claim(ring, resumed = true) ?: return
        scope.launch { begin(mine) }
    }

    override suspend fun dismissWithBarcode(scanned: ScannedCode): DismissResult {
        val mine = live ?: return DismissResult.NOT_RINGING
        val registered = mine.registered ?: return DismissResult.REJECTED
        if (registered.findMatch(scanned) == null) return DismissResult.REJECTED
        return dismiss(mine)
    }

    override suspend fun dismissWithTypedCode(typed: String): DismissResult {
        val mine = live ?: return DismissResult.NOT_RINGING
        val expected = mine.fallbackCode ?: return DismissResult.REJECTED
        if (!FallbackCode.matches(expected, typed)) return DismissResult.REJECTED
        return dismiss(mine)
    }

    override suspend fun dismissWithoutBarcode(): DismissResult {
        val mine = live ?: return DismissResult.NOT_RINGING
        if (mine.registered?.isEmpty() != true) return DismissResult.REJECTED
        return dismiss(mine)
    }

    override fun newFallbackCode(): String {
        val code = FallbackCode.generate(random)
        live?.fallbackCode = code
        return code
    }

    override suspend fun recoverInterruptedRing() {
        attempt {
            val record = ringState.currentRinging() ?: return@attempt
            if (isLive(record)) return@attempt
            // After a process death the system may redeliver the service start. Give it a moment to claim the ring.
            delay(RESUME_GRACE.toMillis())
            storeMutex.withLock { reportInterrupted(record) }
        }
    }

    private suspend fun reportInterrupted(record: RingingRecord) {
        if (isLive(record) || !ringState.clearRinging(record)) return
        val label = labelOf(record.alarmId, loadOr(null) { alarms.get(record.alarmId) })
        attempt { disableIfOneShot(record.alarmId) }
        notifier.notifyMissed(MissedAlarm(record.alarmId, label, record.deadline, MissedReason.RING_INTERRUPTED))
    }

    private fun claim(ring: ScheduledRing, resumed: Boolean): Live? {
        synchronized(lock) {
            if (live != null) return null
            val claimed = Live(ring, clock.instant().truncatedTo(ChronoUnit.MILLIS), resumed)
            claimed.label = labelOf(ring.alarmId, alarm = null)
            live = claimed
            // Ringing from this moment, so the screens never see Idle between the broadcast and the loaded alarm.
            ringingState.value = claimed.toState(requiresBarcode = true)
            serviceCommand.value = RingCommand.Wait
            return claimed
        }
    }

    private fun release(mine: Live): Boolean {
        synchronized(lock) {
            if (live !== mine) return false
            live = null
            ringingState.value = RingingState.Idle
            serviceCommand.value = RingCommand.Stop
            return true
        }
    }

    private suspend fun begin(mine: Live) {
        storeMutex.withLock {
            if (live !== mine) return
            val spec = load(mine)
            if (spec == null) {
                if (release(mine)) cleanUp(mine, Ending.CANCELLED)
                return
            }
            if (!publish(mine, spec)) return
            val remaining = RingLimits.MAX_RING.minus(Duration.between(mine.startedAt, clock.instant()))
            mine.timeout = scope.launch {
                delay(remaining.toMillis().coerceAtLeast(0))
                finish(mine, Ending.TIMED_OUT)
            }
        }
    }

    /**
     * Loads what the ring needs and persists its record. Null when the ring must not go
     * on: its alarm was deleted or disabled, or a resumed ring has no record left.
     *
     * A storage failure must not silence an alarm, so every failed read falls back to a
     * default that rings.
     */
    private suspend fun load(mine: Live): RingSpec? {
        val id = mine.ring.alarmId
        val alarm = if (mine.isTest) null else loadOr(Alarm(id = id, time = LocalTime.MIDNIGHT)) { alarms.get(id) }
        if (!mine.isTest && alarm?.enabled != true) return null
        if (!adoptRecord(mine)) return null
        mine.label = labelOf(id, alarm)
        mine.registered = loadOr(emptyList()) { barcodes.all() }
        return RingSpec(
            alarmId = id,
            label = mine.label,
            ringtoneUri = alarm?.ringtoneUri,
            vibrate = alarm?.vibrate ?: true,
            headphonesOnly = alarm?.headphonesOnly ?: false,
            settings = loadOr(RemSettings.DEFAULT) { settings.current() },
            startedAt = mine.startedAt,
        )
    }

    private fun publish(mine: Live, spec: RingSpec): Boolean {
        synchronized(lock) {
            if (live !== mine) return false
            ringingState.value = mine.toState(requiresBarcode = !mine.registered.isNullOrEmpty())
            serviceCommand.value = RingCommand.Play(spec)
            return true
        }
    }

    /**
     * Makes the persisted record describe [mine]. A new ring writes its record. A resumed
     * ring keeps the start time of the record it continues, and is dropped when that
     * record is gone, because then the ring already ended or was reported as missed.
     */
    private suspend fun adoptRecord(mine: Live): Boolean {
        if (!mine.resumed) {
            attempt { ringState.setRinging(mine.record()) }
            return true
        }
        val record = loadOr(null) { ringState.currentRinging() }?.takeIf { isLive(it) } ?: return false
        mine.startedAt = record.startedAt
        return true
    }

    private suspend fun dismiss(mine: Live): DismissResult =
        if (finish(mine, Ending.DISMISSED)) DismissResult.DISMISSED else DismissResult.NOT_RINGING

    /** Stops the ring at once, then waits for the cleanup. False when [mine] had already ended. */
    private suspend fun finish(mine: Live, ending: Ending): Boolean {
        if (!release(mine)) return false
        scope.launch { storeMutex.withLock { cleanUp(mine, ending) } }.join()
        return true
    }

    private suspend fun cleanUp(mine: Live, ending: Ending) {
        mine.timeout?.cancel()
        val ring = mine.ring
        attempt { ringState.clearRinging(mine.record()) }
        if (ending != Ending.CANCELLED) attempt { disableIfOneShot(ring.alarmId) }
        when (ending) {
            Ending.DISMISSED -> if (!mine.isTest) attempt { endSessionCovering(ring.deadline) }
            Ending.TIMED_OUT -> attempt { notifyMissed(mine, MissedReason.RING_TIMED_OUT) }
            Ending.INTERRUPTED -> attempt { notifyMissed(mine, MissedReason.RING_INTERRUPTED) }
            Ending.CANCELLED -> Unit
        }
        attempt { reschedule() }
    }

    private suspend fun endSessionCovering(deadline: Instant) {
        if (sessions.current()?.covers(deadline) == true) sessions.end()
    }

    private fun notifyMissed(mine: Live, reason: MissedReason) {
        notifier.notifyMissed(MissedAlarm(mine.ring.alarmId, mine.label, mine.ring.deadline, reason))
    }

    /** The test alarm has no stored alarm, so this does nothing for it. */
    private suspend fun disableIfOneShot(alarmId: Long) {
        val alarm = alarms.get(alarmId) ?: return
        if (alarm.isOneShot && alarm.enabled) alarms.setEnabled(alarmId, false)
    }

    private fun isLive(record: RingingRecord): Boolean {
        val ring = live?.ring ?: return false
        return ring.alarmId == record.alarmId && ring.deadline.toEpochMilli() == record.deadline.toEpochMilli()
    }

    private fun labelOf(alarmId: Long, alarm: Alarm?): String =
        if (alarmId == AlarmBroadcast.TEST_ALARM_ID) testAlarmLabel else alarm?.label.orEmpty()

    private fun Live.toState(requiresBarcode: Boolean) = RingingState.Ringing(
        alarmId = ring.alarmId,
        label = label,
        startedAt = startedAt,
        deadline = ring.deadline,
        requiresBarcode = requiresBarcode,
    )

    private suspend fun <T> loadOr(default: T, block: suspend () -> T): T =
        try {
            withTimeout(LOAD_TIMEOUT.toMillis()) { block() }
        } catch (e: TimeoutCancellationException) {
            default
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            default
        }

    /** Runs one step. A failing step must not keep the next ones from running. */
    private suspend fun attempt(step: suspend () -> Unit) {
        try {
            step()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Nothing can be done about it here. The remaining steps still run.
        }
    }

    private companion object {
        val LOAD_TIMEOUT: Duration = Duration.ofSeconds(5)
        val RESUME_GRACE: Duration = Duration.ofSeconds(2)
    }
}
