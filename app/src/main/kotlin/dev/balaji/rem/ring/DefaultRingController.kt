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
 * A snooze ends the ringing stretch but not the ring. Its record moves to the snoozed
 * rings in the store, an alarm clock entry of its own brings it back, and the ring that
 * comes back starts a new stretch with its own 30 minutes. Only a dismissal ends the
 * session or disables a one-shot alarm. While a ring is snoozed other alarms ring as
 * usual. A snooze that comes due during another ring waits for that ring to end.
 *
 * @param watchdog an alarm clock entry kept a minute ahead while a ring is live. It
 *   brings the ring back when the process dies, and after a reboot.
 * @param snoozeAlarms the alarm clock entry of each snoozed ring.
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
    private val snoozeAlarms: SnoozeAlarms,
    private val reschedule: suspend () -> Unit,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val testAlarmLabel: String,
    private val random: SecureRandom = SecureRandom(),
) : RingController, RingServiceHost {

    /** Where a ring comes from. */
    private enum class Origin {
        /** The alarm's own broadcast. */
        FIRED,

        /** Picked up again after a process death. It continues the ringing record. */
        RESUMED,

        /** The end of a snooze. It continues the snoozed record with a new stretch. */
        SNOOZE_ENDED,
    }

    private class Live(val ring: ScheduledRing, @Volatile var startedAt: Instant, val origin: Origin) {
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

        /** How many times this ring was snoozed before this stretch. */
        @Volatile
        var snoozes: Int = 0

        /** From the alarm, once it is loaded. */
        @Volatile
        var snoozeMinutes: Int = 0

        @Volatile
        var snoozeLimit: Int = 0

        /** What the cleanup of a snooze persists. Set before the ring is released. */
        @Volatile
        var snoozedRecord: RingingRecord? = null

        val snoozesLeft: Int get() = (snoozeLimit - snoozes).coerceAtLeast(0)

        val isTest: Boolean get() = ring.alarmId == AlarmBroadcast.TEST_ALARM_ID

        fun record() = RingingRecord(
            alarmId = ring.alarmId,
            startedAt = startedAt,
            ringAt = ring.ringAt.truncatedTo(ChronoUnit.MILLIS),
            deadline = ring.deadline.truncatedTo(ChronoUnit.MILLIS),
            requiresBarcode = requiresBarcode,
            barcodes = recordedBarcodes,
            snoozes = snoozes,
        )
    }

    /** A snoozed ring as the screens show it. [snoozeLimit] is the alarm's, for the count of snoozes left. */
    private class Snooze(val record: RingingRecord, val label: String, val snoozeLimit: Int) {
        val until: Instant = checkNotNull(record.snoozedUntil)

        fun toState() = RingingState.Snoozed(
            alarmId = record.alarmId,
            label = label,
            deadline = record.deadline,
            until = until,
            snoozesLeft = (snoozeLimit - record.snoozes).coerceAtLeast(0),
            requiresBarcode = record.requiresBarcode,
        )
    }

    private enum class Ending { DISMISSED, SNOOZED, TIMED_OUT, INTERRUPTED, CANCELLED }

    private val lock = Any()

    /** Written only under [lock], together with the flows, so they never disagree. */
    @Volatile
    private var live: Live? = null

    /** Snoozed rings by alarm id, as the screens show them. The store holds the truth. Only touched under [lock]. */
    private var snoozes: Map<Long, Snooze> = emptyMap()

    /** The alarm id of the snoozed ring the latest fallback code was made for, with that code. Only touched under [lock]. */
    private var snoozedFallback: Pair<Long, String>? = null

    private val ringingState = MutableStateFlow<RingingState>(RingingState.Idle)
    private val snoozedState = MutableStateFlow<List<RingingState.Snoozed>>(emptyList())
    private val serviceCommand = MutableStateFlow<RingCommand>(RingCommand.Stop)

    /** Serialises the store writes of ring start, ring end and recovery. */
    private val storeMutex = Mutex()

    override val state: StateFlow<RingingState> = ringingState.asStateFlow()
    override val snoozed: StateFlow<List<RingingState.Snoozed>> = snoozedState.asStateFlow()
    override val command: StateFlow<RingCommand> = serviceCommand.asStateFlow()

    override fun startRinging(ring: ScheduledRing): Boolean {
        val mine = claim(ring, Origin.FIRED)
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
        val mine = claim(ring, Origin.RESUMED) ?: return
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
        val mine = claim(ring, Origin.RESUMED) ?: return
        scope.launch { begin(mine) }
    }

    override fun endSnooze(ring: ScheduledRing) {
        // While another alarm rings this does nothing. The end of that ring sets the entry again, see ringDueSnooze.
        val mine = claim(ring, Origin.SNOOZE_ENDED) ?: return
        try {
            launcher.start(ring)
        } catch (e: RuntimeException) {
            scope.launch {
                if (!release(mine)) return@launch
                storeMutex.withLock {
                    val record = loadOr(null) { ringState.currentSnoozed() }?.firstOrNull { it.describes(ring) }
                    if (record != null) attempt { reportLostSnooze(record) }
                }
                attempt { reschedule() }
            }
            return
        }
        scope.launch { begin(mine) }
    }

    override suspend fun snooze(): SnoozeResult {
        val mine = live ?: return SnoozeResult.NOT_RINGING
        if (mine.spec == null || mine.snoozesLeft <= 0) return SnoozeResult.REJECTED
        val until = clock.instant().truncatedTo(ChronoUnit.MILLIS).plus(Duration.ofMinutes(mine.snoozeMinutes.toLong()))
        // The entry comes first. A snooze that could not ring again must not stop the alarm.
        if (!setSnoozeEntry(mine.ring, until)) return SnoozeResult.REJECTED
        val snoozed = mine.record().copy(snoozes = mine.snoozes + 1, snoozedUntil = until)
        synchronized(lock) {
            if (live !== mine) {
                cancelSnoozeEntry(mine.ring.alarmId)
                return SnoozeResult.NOT_RINGING
            }
            mine.snoozedRecord = snoozed
            live = null
            snoozes = snoozes + (snoozed.alarmId to Snooze(snoozed, mine.label, mine.snoozeLimit))
            snoozedFallback = null
            publishStates()
            serviceCommand.value = RingCommand.Stop
        }
        scope.launch { storeMutex.withLock { cleanUp(mine, Ending.SNOOZED) } }.join()
        return SnoozeResult.SNOOZED
    }

    override suspend fun dismissWithBarcode(scanned: ScannedCode): DismissResult {
        val mine = live ?: return dismissSnoozed { target ->
            val id = target.record.alarmId
            val now = if (id == AlarmBroadcast.TEST_ALARM_ID) emptyList() else loadOr(emptyList()) { barcodes.all(id) }
            val started = target.record.barcodes.map { Barcode(label = "", format = it.format, value = it.value) }
            (started + now).findMatch(scanned) != null
        }
        val registered = mine.registered ?: return DismissResult.REJECTED
        if (registered.findMatch(scanned) == null) return DismissResult.REJECTED
        return dismiss(mine)
    }

    override suspend fun dismissWithTypedCode(typed: String): DismissResult {
        val mine = live ?: return dismissSnoozed { target ->
            val expected = synchronized(lock) { snoozedFallback }
            expected != null && expected.first == target.record.alarmId && FallbackCode.matches(expected.second, typed)
        }
        val expected = mine.fallbackCode ?: return DismissResult.REJECTED
        if (!FallbackCode.matches(expected, typed)) return DismissResult.REJECTED
        return dismiss(mine)
    }

    override suspend fun dismissWithoutBarcode(): DismissResult {
        val mine = live ?: return dismissSnoozed { target -> !target.record.requiresBarcode }
        if (mine.requiresBarcode) return DismissResult.REJECTED
        return dismiss(mine)
    }

    override fun newFallbackCode(): String {
        val code = FallbackCode.generate(random)
        synchronized(lock) {
            val mine = live
            if (mine != null) {
                mine.fallbackCode = code
            } else {
                snoozedFallback = shownSnooze()?.let { it.record.alarmId to code }
            }
        }
        return code
    }

    /**
     * Dismisses the snoozed ring that [state] shows, if [accepts] says the user did what
     * it needs. Its entry, notification and record go, and the bookkeeping of a dismissal
     * follows: a one-shot alarm is disabled and the covering session ends.
     */
    private suspend fun dismissSnoozed(accepts: suspend (Snooze) -> Boolean): DismissResult {
        val target = synchronized(lock) { shownSnooze() } ?: return DismissResult.NOT_RINGING
        if (!accepts(target)) return DismissResult.REJECTED
        val id = target.record.alarmId
        synchronized(lock) {
            // It started ringing again in the meantime, or was dismissed already.
            if (live != null || snoozes[id] !== target) return DismissResult.NOT_RINGING
            snoozes = snoozes - id
            if (snoozedFallback?.first == id) snoozedFallback = null
            publishStates()
        }
        scope.launch {
            storeMutex.withLock {
                cancelSnoozeEntry(id)
                attempt { notifier.cancelSnoozed(id) }
                attempt { ringState.clearSnoozed(target.record) }
                attempt { disableIfOneShot(id, target.record.deadline) }
                if (id != AlarmBroadcast.TEST_ALARM_ID) attempt { endSessionCovering(target.record.deadline) }
                attempt { reschedule() }
            }
        }.join()
        return DismissResult.DISMISSED
    }

    /** The snoozed ring that comes due first. Call with [lock] held. */
    private fun shownSnooze(): Snooze? = snoozes.values.minByOrNull { it.until }

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
        attempt { recoverRinging() }
        attempt { recoverSnoozes() }
    }

    private suspend fun recoverRinging() {
        val record = ringState.currentRinging() ?: return
        if (isLive(record)) return
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

    /**
     * Shows the persisted snoozes and sets their entries again, which a reboot wiped. A
     * snooze whose time has passed comes back a few seconds from now, through its entry
     * like the others. One more than 30 minutes overdue is reported as missed instead,
     * like a ring whose 30 minutes ran out.
     */
    private suspend fun recoverSnoozes() {
        val records = ringState.currentSnoozed()
        if (records.isEmpty()) return
        showSnoozes(records)
        // The entry of an overdue snooze may be firing right now. Give it a moment to claim the ring.
        if (records.any { checkNotNull(it.snoozedUntil) <= clock.instant() }) delay(RESUME_GRACE.toMillis())
        storeMutex.withLock {
            val now = clock.instant()
            for (record in ringState.currentSnoozed()) {
                val until = record.snoozedUntil ?: continue
                if (isLive(record)) continue
                val owed = now < until.plus(RingLimits.MAX_RING)
                val at = if (until > now) until else now.plus(RESUME_DELAY)
                if (owed && setSnoozeEntry(record.toRing(), at)) continue
                attempt { reportLostSnooze(record) }
            }
        }
    }

    /** Puts snoozes from the store on the screens and in the notifications, unless this process knows them already. */
    private suspend fun showSnoozes(records: List<RingingRecord>) {
        val loaded = records.map { record ->
            val id = record.alarmId
            val alarm = if (id == AlarmBroadcast.TEST_ALARM_ID) null else loadOr(null) { alarms.get(id) }
            Snooze(record, labelOf(id, alarm), (alarm ?: Alarm(id = id, time = LocalTime.MIDNIGHT)).snoozeLimit)
        }
        val added = synchronized(lock) {
            val liveId = live?.ring?.alarmId
            val fresh = loaded.filter { it.record.alarmId != liveId && it.record.alarmId !in snoozes }
            snoozes = snoozes + fresh.associateBy { it.record.alarmId }
            publishStates()
            fresh
        }
        added.forEach { attempt { notifier.notifySnoozed(SnoozedAlarm(it.record.alarmId, it.label, it.until)) } }
    }

    /**
     * Ends a snoozed ring that can no longer ring again: its entry could not be set, or it
     * is long overdue. Call with [storeMutex] held.
     */
    private suspend fun reportLostSnooze(record: RingingRecord) {
        if (isLive(record) || !ringState.clearSnoozed(record)) return
        val id = record.alarmId
        synchronized(lock) {
            if (snoozes[id]?.record == record) {
                snoozes = snoozes - id
                publishStates()
            }
        }
        cancelSnoozeEntry(id)
        attempt { notifier.cancelSnoozed(id) }
        val label = labelOf(id, loadOr(null) { alarms.get(id) })
        attempt { settle(id, record.deadline) }
        notifier.notifyMissed(MissedAlarm(id, label, record.deadline, MissedReason.RING_INTERRUPTED))
    }

    /**
     * A snooze that came due while another alarm rang rings now that nothing rings. It goes
     * through its own entry, set to fire at once, so the service starts with the alarm
     * clock allowance as for every other ring. Call with [storeMutex] held.
     */
    private suspend fun ringDueSnooze() {
        if (live != null) return
        val now = clock.instant()
        val due = loadOr(emptyList()) { ringState.currentSnoozed() }
            .filter { checkNotNull(it.snoozedUntil) <= now }
            .minByOrNull { checkNotNull(it.snoozedUntil) } ?: return
        if (!setSnoozeEntry(due.toRing(), now)) reportLostSnooze(due)
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

    private fun claim(ring: ScheduledRing, origin: Origin): Live? {
        synchronized(lock) {
            if (live != null) return null
            val claimed = Live(ring, clock.instant().truncatedTo(ChronoUnit.MILLIS), origin)
            val snoozed = snoozes[ring.alarmId]
            claimed.label = snoozed?.label ?: labelOf(ring.alarmId, alarm = null)
            live = claimed
            // The snooze is over. If its record turns out to be gone, it was dismissed and nothing rings.
            if (origin == Origin.SNOOZE_ENDED) snoozes = snoozes - ring.alarmId
            // Ringing from this moment, so the screens never see Idle between the broadcast and the loaded alarm.
            publishStates()
            serviceCommand.value = RingCommand.Wait
            return claimed
        }
    }

    private fun release(mine: Live): Boolean {
        synchronized(lock) {
            if (live !== mine) return false
            live = null
            publishStates()
            serviceCommand.value = RingCommand.Stop
            return true
        }
    }

    /** Publishes the live ring, or else the snoozed ring that comes due first, and the snoozed list. Call with [lock] held. */
    private fun publishStates() {
        val waiting = snoozes.values.sortedBy { it.until }.map { it.toState() }
        snoozedState.value = waiting
        ringingState.value = live?.toState() ?: waiting.firstOrNull() ?: RingingState.Idle
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
            // A ring of this alarm replaces its snooze, if it had one. A snooze that ended has fired already.
            cancelSnoozeEntry(mine.ring.alarmId)
            attempt { notifier.cancelSnoozed(mine.ring.alarmId) }
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
     * on: its alarm was deleted or disabled, or a resumed or snoozed ring has no record
     * left. A resumed ring and a ring after a snooze do not ask whether the alarm is still
     * enabled. The record says the ring is owed, and switching the alarm off in the app
     * must not silence it.
     *
     * A storage failure must not silence an alarm, so every failed read falls back to a
     * default that rings. Barcodes that cannot be read leave no plain stop button: the
     * typed code is then the way out. The test alarm rings with the defaults of [Alarm].
     */
    private suspend fun load(mine: Live): RingSpec? {
        val id = mine.ring.alarmId
        val alarm = if (mine.isTest) null else loadOr(Alarm(id = id, time = LocalTime.MIDNIGHT)) { alarms.get(id) }
        if (!mine.isTest && mine.origin == Origin.FIRED && alarm?.enabled != true) return null
        val registered = if (mine.isTest) emptyList() else loadBarcodes(id)
        mine.registered = registered.orEmpty()
        mine.recordedBarcodes = registered.orEmpty().map { it.key }
        mine.requiresBarcode = registered == null || registered.isNotEmpty()
        if (!adoptRecord(mine)) return null
        mine.label = labelOf(id, alarm)
        val sound = alarm ?: Alarm(id = id, time = LocalTime.MIDNIGHT)
        mine.snoozeMinutes = sound.snoozeMinutes
        mine.snoozeLimit = sound.snoozeLimit
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
            requiresBarcode = mine.requiresBarcode,
            snoozeMinutes = mine.snoozeMinutes,
            snoozesLeft = mine.snoozesLeft,
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
            snoozes = snoozes - mine.ring.alarmId
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
        publishStates()
        mine.spec?.let { serviceCommand.value = RingCommand.Play(it, quiet = mine.quietUntil != null) }
    }

    /**
     * Makes the persisted record describe [mine]. A new ring writes its record. A resumed
     * ring keeps the start time, the barcode rule and the snooze count of the record it
     * continues, also takes the barcodes that record lists, and is dropped when that record
     * is gone, because then the ring already ended or was reported as missed. A ring after
     * a snooze does the same with the snoozed record, except that its stretch starts now,
     * and writes the ringing record, which drops the snoozed one.
     */
    private suspend fun adoptRecord(mine: Live): Boolean {
        when (mine.origin) {
            Origin.FIRED -> Unit
            Origin.RESUMED -> {
                val record = loadOr(null) { ringState.currentRinging() }?.takeIf { isLive(it) } ?: return false
                mine.startedAt = record.startedAt
                continueRecord(mine, record)
                return true
            }
            Origin.SNOOZE_ENDED -> {
                // Unreadable snoozes leave the ring to its alarm's barcodes, so a storage failure does not silence it.
                val snoozed = loadOr(null) { ringState.currentSnoozed() }
                val record = snoozed?.firstOrNull { it.describes(mine.ring) }
                if (snoozed != null && record == null) return false
                if (record != null) continueRecord(mine, record)
            }
        }
        attempt { ringState.setRinging(mine.record()) }
        return true
    }

    private fun continueRecord(mine: Live, record: RingingRecord) {
        mine.requiresBarcode = record.requiresBarcode
        mine.recordedBarcodes = record.barcodes
        mine.snoozes = record.snoozes
        val started = record.barcodes.map { Barcode(label = "", format = it.format, value = it.value) }
        mine.registered = (mine.registered.orEmpty() + started).distinctBy { it.key }
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
        if (ending != Ending.SNOOZED) attempt { ringState.clearRinging(mine.record()) }
        when (ending) {
            Ending.SNOOZED -> {
                val snoozed = checkNotNull(mine.snoozedRecord)
                attempt { ringState.snooze(mine.record(), snoozed) }
                attempt { notifier.notifySnoozed(SnoozedAlarm(ring.alarmId, mine.label, checkNotNull(snoozed.snoozedUntil))) }
            }
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
        attempt { ringDueSnooze() }
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

    private fun setSnoozeEntry(ring: ScheduledRing, at: Instant): Boolean =
        try {
            snoozeAlarms.setSnooze(ring, at)
        } catch (e: RuntimeException) {
            false
        }

    private fun cancelSnoozeEntry(alarmId: Long) {
        try {
            snoozeAlarms.cancelSnooze(alarmId)
        } catch (e: RuntimeException) {
            // An entry left behind finds no snoozed record when it fires, and nothing rings.
        }
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
        snoozeMinutes = if (spec == null) 0 else snoozeMinutes,
        snoozesLeft = if (spec == null) 0 else snoozesLeft,
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
