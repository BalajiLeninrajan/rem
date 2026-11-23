package dev.balaji.rem.ring

import dev.balaji.rem.core.Alarm
import dev.balaji.rem.core.Barcode
import dev.balaji.rem.core.BarcodeKey
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
import kotlinx.coroutines.cancelAndJoin
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
 * The barcodes that can dismiss a ring are the ones registered on its alarm when it
 * started. Deleting them all from the main app during a ring does not unlock the plain
 * dismiss button. A ring picked up again after a process death keeps what its record says
 * about that, and takes the barcodes its record lists as well as the ones on its alarm
 * now, so deleting the alarm during the ring leaves the scanner working. The test alarm
 * has no stored alarm and needs no barcode.
 *
 * A ring that ends without a dismissal while its deadline is still ahead stays owed: its
 * fired record is removed and a one-shot alarm stays on, so the next reschedule plans the
 * deadline again.
 *
 * While the user scans or types the code, the ring can be quiet for a while
 * ([RingLimits.QUIET_PERIOD]). That lives only in memory: a ring picked up again after a
 * process death starts loud, and the 30 minute limit and the watchdog do not change.
 *
 * @param watchdog an alarm clock entry kept a minute ahead while a ring is live. It
 *   brings the ring back when the process dies, and after a reboot.
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
    private val watchdog: RingWatchdog,
    private val reschedule: suspend () -> Unit,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val testAlarmLabel: String,
    private val random: SecureRandom = SecureRandom(),
) : RingController, RingServiceHost {

    private class Live(val ring: ScheduledRing, @Volatile var startedAt: Instant, val resumed: Boolean) {
        @Volatile
        var label: String = ""

        /** The barcodes this ring takes. Null until they are loaded. */
        @Volatile
        var registered: List<Barcode>? = null

        /** What the persisted record lists as the barcodes of the ring's start. */
        @Volatile
        var recordedBarcodes: List<BarcodeKey> = emptyList()

        /** False only when the ring started with no barcode on its alarm. Then the plain button works. */
        @Volatile
        var requiresBarcode: Boolean = true

        @Volatile
        var rearm: Job? = null

        @Volatile
        var fallbackCode: String? = null

        @Volatile
        var timeout: Job? = null

        /** What the service plays. Null until the alarm is loaded. */
        @Volatile
        var spec: RingSpec? = null

        /** When the quiet period ends, or null while the ring is loud. */
        @Volatile
        var quietUntil: Instant? = null

        /** Counts quiet periods, so the timer of an earlier one cannot end a later one. Only touched under the lock. */
        var quietPeriod: Int = 0

        @Volatile
        var quietTimer: Job? = null

        val isTest: Boolean get() = ring.alarmId == AlarmBroadcast.TEST_ALARM_ID

        fun record() = RingingRecord(
            alarmId = ring.alarmId,
            startedAt = startedAt,
            ringAt = ring.ringAt.truncatedTo(ChronoUnit.MILLIS),
            deadline = ring.deadline.truncatedTo(ChronoUnit.MILLIS),
            requiresBarcode = requiresBarcode,
            barcodes = recordedBarcodes,
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

    override fun startRinging(ring: ScheduledRing): Boolean {
        val mine = claim(ring, resumed = false)
        if (mine == null) {
            // The deadline broadcast of the alarm that is already ringing early.
            if (live?.ring?.let { it.alarmId == ring.alarmId && sameInstant(it.deadline, ring.deadline) } == true) return true
            scope.launch { attempt { reportSwallowed(ring) } }
            return false
        }
        try {
            launcher.start(ring)
        } catch (e: RuntimeException) {
            scope.launch { finish(mine, Ending.INTERRUPTED) }
            return false
        }
        scope.launch { begin(mine) }
        return true
    }

    override fun resumeRinging(ring: ScheduledRing) {
        val mine = claim(ring, resumed = true) ?: return
        try {
            launcher.start(ring)
        } catch (e: RuntimeException) {
            scope.launch {
                if (!release(mine)) return@launch
                storeMutex.withLock {
                    val record = loadOr(null) { ringState.currentRinging() }
                    if (record != null && record.describes(ring)) attempt { reportInterrupted(record) }
                }
                attempt { reschedule() }
            }
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
        if (mine.requiresBarcode) return DismissResult.REJECTED
        return dismiss(mine)
    }

    override fun newFallbackCode(): String {
        val code = FallbackCode.generate(random)
        live?.fallbackCode = code
        return code
    }

    override fun startQuietPeriod() {
        synchronized(lock) {
            val mine = live ?: return
            val period = ++mine.quietPeriod
            mine.quietUntil = clock.instant().plus(RingLimits.QUIET_PERIOD).truncatedTo(ChronoUnit.MILLIS)
            mine.quietTimer?.cancel()
            mine.quietTimer = scope.launch {
                delay(RingLimits.QUIET_PERIOD.toMillis())
                synchronized(lock) {
                    if (live === mine && mine.quietPeriod == period) endQuiet(mine)
                }
            }
            publishState(mine)
        }
    }

    override fun endQuietPeriod() {
        synchronized(lock) {
            val mine = live ?: return
            mine.quietTimer?.cancel()
            endQuiet(mine)
        }
    }

    /** Call with [lock] held and [mine] live. */
    private fun endQuiet(mine: Live) {
        if (mine.quietUntil == null) return
        mine.quietPeriod++
        mine.quietUntil = null
        publishState(mine)
    }

    override suspend fun recoverInterruptedRing() {
        attempt {
            val record = ringState.currentRinging() ?: return@attempt
            if (isLive(record)) return@attempt
            // After a process death the system may redeliver the service start. Give it a moment to claim the ring.
            delay(RESUME_GRACE.toMillis())
            storeMutex.withLock {
                if (isLive(record) || ringState.currentRinging() != record) return@withLock
                // Nothing may ring from the boot path, so the ring comes back through an alarm clock entry.
                val now = clock.instant()
                val owed = now < record.startedAt.plus(RingLimits.MAX_RING)
                if (owed && armWatchdog(record.toRing(), now.plus(RESUME_DELAY))) return@withLock
                reportInterrupted(record)
            }
        }
    }

    /** Ends a ring that nothing is playing any more. Call with [storeMutex] held. */
    private suspend fun reportInterrupted(record: RingingRecord) {
        if (isLive(record) || !ringState.clearRinging(record)) return
        val label = labelOf(record.alarmId, loadOr(null) { alarms.get(record.alarmId) })
        attempt { settle(record.alarmId, record.deadline) }
        notifier.notifyMissed(MissedAlarm(record.alarmId, label, record.deadline, MissedReason.RING_INTERRUPTED))
    }

    /**
     * A ring that could not start because another one is live. With its deadline still
     * ahead the receiver leaves it unfired and the reschedule plans the deadline. With
     * the deadline passed it is over, so say so.
     */
    private suspend fun reportSwallowed(ring: ScheduledRing) {
        if (ring.alarmId == AlarmBroadcast.TEST_ALARM_ID || ring.deadline > clock.instant()) return
        val alarm = alarms.get(ring.alarmId)?.takeIf { it.enabled } ?: return
        notifier.notifyMissed(MissedAlarm(alarm.id, alarm.label, ring.deadline, MissedReason.OTHER_ALARM_RINGING))
        disableIfOneShot(ring.alarmId, ring.deadline)
    }

    private fun claim(ring: ScheduledRing, resumed: Boolean): Live? {
        synchronized(lock) {
            if (live != null) return null
            val claimed = Live(ring, clock.instant().truncatedTo(ChronoUnit.MILLIS), resumed)
            claimed.label = labelOf(ring.alarmId, alarm = null)
            live = claimed
            // Ringing from this moment, so the screens never see Idle between the broadcast and the loaded alarm.
            ringingState.value = claimed.toState()
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
                // Capped, so a clock that moved back cannot make a ring last longer than the limit.
                delay(remaining.toMillis().coerceIn(0, RingLimits.MAX_RING.toMillis()))
                finish(mine, Ending.TIMED_OUT)
            }
            mine.rearm = scope.launch {
                while (true) {
                    armWatchdog(mine.ring, clock.instant().plus(WATCHDOG_LEAD))
                    delay(WATCHDOG_INTERVAL.toMillis())
                }
            }
        }
    }

    /**
     * Loads what the ring needs and persists its record. Null when the ring must not go
     * on: its alarm was deleted or disabled, or a resumed ring has no record left. A
     * resumed ring does not ask whether its alarm is still enabled. Its record says the
     * ring is owed, and switching the alarm off in the app must not silence it.
     *
     * A storage failure must not silence an alarm, so every failed read falls back to a
     * default that rings. Barcodes that cannot be read leave no plain stop button: the
     * typed code is then the way out. The test alarm rings with the defaults of [Alarm].
     */
    private suspend fun load(mine: Live): RingSpec? {
        val id = mine.ring.alarmId
        val alarm = if (mine.isTest) null else loadOr(Alarm(id = id, time = LocalTime.MIDNIGHT)) { alarms.get(id) }
        if (!mine.isTest && !mine.resumed && alarm?.enabled != true) return null
        val registered = if (mine.isTest) emptyList() else loadBarcodes(id)
        mine.registered = registered.orEmpty()
        mine.recordedBarcodes = registered.orEmpty().map { it.key }
        mine.requiresBarcode = registered == null || registered.isNotEmpty()
        if (!adoptRecord(mine)) return null
        mine.label = labelOf(id, alarm)
        val sound = alarm ?: Alarm(id = id, time = LocalTime.MIDNIGHT)
        val global = loadOr(RemSettings.DEFAULT) { settings.current() }
        return RingSpec(
            alarmId = id,
            label = mine.label,
            ringtoneUri = sound.ringtoneUri,
            vibrate = sound.vibrate,
            minRingVolumePercent = sound.minRingVolumePercent,
            volumeRampSeconds = sound.volumeRampSeconds,
            headphonesOnly = global.headphonesOnly,
            speakerFallbackSeconds = global.speakerFallbackSeconds,
            startedAt = mine.startedAt,
        )
    }

    /** The barcodes of [alarmId]. Null when they could not be read, even at the second try. */
    private suspend fun loadBarcodes(alarmId: Long): List<Barcode>? {
        repeat(BARCODE_READS) {
            val read: List<Barcode>? = loadOr(null) { barcodes.all(alarmId) }
            if (read != null) return read
        }
        return null
    }

    private fun publish(mine: Live, spec: RingSpec): Boolean {
        synchronized(lock) {
            if (live !== mine) return false
            mine.spec = spec
            publishState(mine)
            return true
        }
    }

    /**
     * Shows [mine] on the screens and, once its alarm is loaded, tells the service what to
     * play. A quiet period that starts before then is applied by [publish]. Call with
     * [lock] held and [mine] live.
     */
    private fun publishState(mine: Live) {
        ringingState.value = mine.toState()
        mine.spec?.let { serviceCommand.value = RingCommand.Play(it, quiet = mine.quietUntil != null) }
    }

    /**
     * Makes the persisted record describe [mine]. A new ring writes its record. A resumed
     * ring keeps the start time and the barcode rule of the record it continues, also takes
     * the barcodes that record lists, and is dropped when that record is gone, because then
     * the ring already ended or was reported as missed.
     */
    private suspend fun adoptRecord(mine: Live): Boolean {
        if (!mine.resumed) {
            attempt { ringState.setRinging(mine.record()) }
            return true
        }
        val record = loadOr(null) { ringState.currentRinging() }?.takeIf { isLive(it) } ?: return false
        mine.startedAt = record.startedAt
        mine.requiresBarcode = record.requiresBarcode
        mine.recordedBarcodes = record.barcodes
        val started = record.barcodes.map { Barcode(label = "", format = it.format, value = it.value) }
        mine.registered = (mine.registered.orEmpty() + started).distinctBy { it.key }
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
        mine.quietTimer?.cancel()
        // Joined, so a re-arm that is under way cannot land after the cancel below.
        mine.rearm?.cancelAndJoin()
        if (live == null) attempt { watchdog.cancel() }
        val ring = mine.ring
        attempt { ringState.clearRinging(mine.record()) }
        when (ending) {
            Ending.DISMISSED -> {
                attempt { disableIfOneShot(ring.alarmId, ring.deadline) }
                if (!mine.isTest) attempt { endSessionCovering(ring.deadline) }
            }
            Ending.TIMED_OUT -> {
                attempt { settle(ring.alarmId, ring.deadline) }
                attempt { notifyMissed(mine, MissedReason.RING_TIMED_OUT) }
            }
            Ending.INTERRUPTED -> {
                attempt { settle(ring.alarmId, ring.deadline) }
                attempt { notifyMissed(mine, MissedReason.RING_INTERRUPTED) }
            }
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

    /**
     * Bookkeeping for a ring that ended without a dismissal. While the deadline is still
     * ahead the occurrence is owed: forget that it fired and leave a one-shot alarm on,
     * so it rings at the deadline. Once the deadline has passed the occurrence is over.
     */
    private suspend fun settle(alarmId: Long, deadline: Instant) {
        if (alarmId == AlarmBroadcast.TEST_ALARM_ID) return
        if (deadline > clock.instant()) {
            val fired = ringState.lastFired()[alarmId]
            if (fired != null && sameInstant(fired, deadline)) ringState.removeFired(alarmId)
        } else {
            disableIfOneShot(alarmId, deadline)
        }
    }

    /**
     * Switches a one-shot alarm off once its occurrence at [deadline] is over. An alarm
     * that was edited to another time while it rang has a new occurrence and stays on.
     * The test alarm has no stored alarm, so this does nothing for it.
     */
    private suspend fun disableIfOneShot(alarmId: Long, deadline: Instant) {
        val alarm = alarms.get(alarmId) ?: return
        if (!alarm.isOneShot || !alarm.enabled) return
        if (!sameInstant(alarm.nextDeadline(deadline.minusMillis(1), clock.zone), deadline)) return
        alarms.setEnabled(alarmId, false)
    }

    private fun armWatchdog(ring: ScheduledRing, at: Instant): Boolean =
        try {
            watchdog.arm(ring, at)
        } catch (e: RuntimeException) {
            false
        }

    private fun isLive(record: RingingRecord): Boolean {
        val ring = live?.ring ?: return false
        return record.describes(ring)
    }

    private fun RingingRecord.describes(ring: ScheduledRing): Boolean =
        ring.alarmId == alarmId && sameInstant(ring.deadline, deadline)

    private fun RingingRecord.toRing() = ScheduledRing(alarmId, ringAt, deadline)

    /** Instants are stored and sent in intents with millisecond precision. */
    private fun sameInstant(a: Instant, b: Instant): Boolean = a.toEpochMilli() == b.toEpochMilli()

    private fun labelOf(alarmId: Long, alarm: Alarm?): String =
        if (alarmId == AlarmBroadcast.TEST_ALARM_ID) testAlarmLabel else alarm?.label.orEmpty()

    /** Until the alarm is loaded the ring counts as needing a barcode, so no plain stop button shows too early. */
    private fun Live.toState() = RingingState.Ringing(
        alarmId = ring.alarmId,
        label = label,
        startedAt = startedAt,
        deadline = ring.deadline,
        requiresBarcode = spec == null || requiresBarcode,
        quietUntil = quietUntil,
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

        /** How soon after a process start a ring that was cut off comes back. */
        val RESUME_DELAY: Duration = Duration.ofSeconds(5)

        /** The watchdog entry is pushed this far ahead, this often, while a ring is live. */
        val WATCHDOG_LEAD: Duration = Duration.ofSeconds(60)
        val WATCHDOG_INTERVAL: Duration = Duration.ofSeconds(20)

        const val BARCODE_READS = 2
    }
}
