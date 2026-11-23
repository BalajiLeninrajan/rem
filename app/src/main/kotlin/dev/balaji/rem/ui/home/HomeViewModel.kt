package dev.balaji.rem.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.balaji.rem.core.Alarm
import dev.balaji.rem.core.Barcode
import dev.balaji.rem.core.LateOffer
import dev.balaji.rem.core.PlannedRing
import dev.balaji.rem.core.RemSettings
import dev.balaji.rem.core.SchedulePlan
import dev.balaji.rem.core.SleepSession
import dev.balaji.rem.core.WakeExplanation
import dev.balaji.rem.core.computeWakeTime
import dev.balaji.rem.data.AlarmRepository
import dev.balaji.rem.data.BarcodeRepository
import dev.balaji.rem.data.SettingsRepository
import dev.balaji.rem.data.SleepSessionRepository
import dev.balaji.rem.ring.RingController
import dev.balaji.rem.ring.RingingState
import dev.balaji.rem.schedule.AlarmScheduler
import dev.balaji.rem.ui.reliability.barcodeCoverage
import dev.balaji.rem.ui.reliability.reliabilityProblemCount
import dev.balaji.rem.ui.system.SystemChecks
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class HomeViewModel(
    private val alarms: AlarmRepository,
    private val barcodes: BarcodeRepository,
    private val settings: SettingsRepository,
    private val sessions: SleepSessionRepository,
    private val scheduler: AlarmScheduler,
    ringController: RingController,
    private val clock: Clock,
) : ViewModel() {
    private val now = MutableStateFlow(clock.instant())
    private val system = MutableStateFlow(SystemChecks())
    private val recentlyDeleted = MutableStateFlow<Alarm?>(null)
    private val onboardingOpened = MutableStateFlow(false)
    private val latePrompt = MutableStateFlow<PendingLatePrompt?>(null)
    private var undoExpiry: Job? = null

    /** The barcodes the alarm in [recentlyDeleted] had. Deleting an alarm deletes them, so undo puts them back. */
    @Volatile
    private var deletedBarcodes: Pair<Alarm, List<Barcode>>? = null

    private val stored = combine(
        alarms.alarms,
        barcodes.barcodesByAlarm,
        settings.settings,
        sessions.session,
        settings.onboardingDone,
    ) { storedAlarms, storedBarcodes, storedSettings, session, onboardingDone ->
        Stored(storedAlarms, storedBarcodes, storedSettings, session, onboardingDone)
    }

    private val live = combine(scheduler.plan, ringController.state, now, system) { plan, ringing, instant, checks ->
        Live(plan, (ringing as? RingingState.Ringing)?.alarmId, instant, checks)
    }

    private val local = combine(recentlyDeleted, onboardingOpened, latePrompt) { deleted, opened, prompt ->
        Local(deleted, opened, prompt)
    }

    val state: StateFlow<HomeUiState> = combine(stored, live, local) { s, l, o -> buildState(s, l, o) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), HomeUiState())

    /** The screen calls this on resume and on a timer, so times and warnings stay current. */
    fun refresh(systemChecks: SystemChecks) {
        now.value = clock.instant()
        system.value = systemChecks
    }

    fun setEnabled(alarmId: Long, enabled: Boolean) {
        viewModelScope.launch { alarms.setEnabled(alarmId, enabled) }
    }

    fun delete(alarmId: Long) {
        viewModelScope.launch {
            val alarm = alarms.get(alarmId) ?: return@launch
            deletedBarcodes = alarm to barcodes.all(alarmId)
            alarms.delete(alarmId)
            recentlyDeleted.value = alarm
            // The offer ends here and not in the screen. A snackbar that is cut short by leaving
            // the screen or rotating never reports back, and the offer would return for another round.
            undoExpiry?.cancel()
            undoExpiry = viewModelScope.launch {
                delay(UNDO_OFFER_MILLIS)
                recentlyDeleted.compareAndSet(alarm, null)
            }
        }
    }

    fun undoDelete() {
        val alarm = recentlyDeleted.value ?: return
        undoExpiry?.cancel()
        recentlyDeleted.value = null
        val ownBarcodes = deletedBarcodes?.takeIf { it.first == alarm }?.second.orEmpty()
        viewModelScope.launch { alarms.upsertWithBarcodes(alarm, ownBarcodes) }
    }

    /** The undo offer for [alarm] ended without being taken. A newer offer is left alone. */
    fun onUndoExpired(alarm: Alarm) {
        recentlyDeleted.compareAndSet(alarm, null)
    }

    /**
     * Starts the session, then asks about the late options it gives, if any. The offers come
     * from the plan as it was before the session, which has the deadlines the session covers
     * and leaves out occurrences that already fired.
     */
    fun goToSleep() {
        val startedAt = clock.instant()
        val before = scheduler.plan.value
        latePrompt.value = null
        viewModelScope.launch {
            val session = sessions.start(startedAt)
            val offers = before.lateOffers(session, settings.current())
            if (offers.isNotEmpty()) latePrompt.value = PendingLatePrompt(session.startedAt, offers, emptySet())
        }
    }

    fun endSleep() {
        latePrompt.value = null
        viewModelScope.launch { sessions.end() }
    }

    /** Ticks or unticks one row of the prompt. Stored only by [confirmLatePrompt]. */
    fun setLateChoice(alarmId: Long, accept: Boolean) {
        latePrompt.update { prompt ->
            if (prompt == null || prompt.offers.none { it.alarmId == alarmId }) {
                prompt
            } else {
                prompt.copy(accepted = if (accept) prompt.accepted + alarmId else prompt.accepted - alarmId)
            }
        }
    }

    /** Accepts the ticked rows in the session the prompt was made for. A session started or ended since gets nothing. */
    fun confirmLatePrompt() {
        val prompt = latePrompt.getAndUpdate { null } ?: return
        if (prompt.accepted.isEmpty()) return
        viewModelScope.launch {
            if (sessions.current()?.startedAt != prompt.startedAt) return@launch
            prompt.offers.filter { it.alarmId in prompt.accepted }.forEach { sessions.acceptLate(it.alarmId, it.deadline) }
        }
    }

    /** Closes the prompt and keeps every alarm time. */
    fun dismissLatePrompt() {
        latePrompt.value = null
    }

    /**
     * The card's "ring at ... instead" for the occurrence due at [deadline]. Ignored once
     * [deadline] has passed, as the card may not have caught up with the clock yet.
     */
    fun acceptLate(alarmId: Long, deadline: Instant) {
        if (deadline <= clock.instant()) return
        viewModelScope.launch { sessions.acceptLate(alarmId, deadline) }
    }

    /**
     * The card's way back to the alarm time [deadline]. Ignored once [deadline] has passed:
     * going back then would ring the alarm at once.
     */
    fun revokeLate(alarmId: Long, deadline: Instant) {
        if (deadline <= clock.instant()) return
        viewModelScope.launch { sessions.revokeLate(alarmId) }
    }

    /** Onboarding is offered once per run of the screen, so leaving it early does not loop back into it. */
    fun onOnboardingOpened() {
        onboardingOpened.value = true
    }

    private fun buildState(stored: Stored, live: Live, local: Local): HomeUiState {
        val session = stored.session?.takeIf { it.isActive(live.now) }
        return HomeUiState(
            loading = false,
            alarms = stored.alarms.map { alarm ->
                val nextRing = if (alarm.enabled) live.plan.forAlarm(alarm.id) else null
                val ringing = alarm.id == live.ringingAlarmId
                AlarmRow(
                    alarm = alarm,
                    nextRing = nextRing,
                    ringing = ringing,
                    noBarcode = stored.barcodes[alarm.id].isNullOrEmpty(),
                    late = if (ringing) null else nextRing?.lateSwitch(session, live.now),
                )
            },
            sleep = when {
                session != null -> SleepState.Active(session.startedAt, live.plan.firstCoveredBy(session))
                stored.alarms.any { it.enabled } -> SleepState.Ready(sleepNowPreview(live.plan, stored.settings, live.now))
                else -> SleepState.Hidden
            },
            ringing = live.ringingAlarmId != null,
            reliabilityProblems = reliabilityProblemCount(live.system, barcodeCoverage(stored.alarms, stored.barcodes)),
            onboardingNeeded = !stored.onboardingDone && !local.onboardingOpened,
            recentlyDeleted = local.recentlyDeleted,
            latePrompt = local.latePrompt?.takeIf { it.startedAt == session?.startedAt }?.toUi(stored.alarms),
            now = live.now,
            zone = clock.zone,
        )
    }

    private data class Stored(
        val alarms: List<Alarm>,
        val barcodes: Map<Long, List<Barcode>>,
        val settings: RemSettings,
        val session: SleepSession?,
        val onboardingDone: Boolean,
    )

    private data class Live(val plan: SchedulePlan, val ringingAlarmId: Long?, val now: Instant, val system: SystemChecks)

    private data class Local(val recentlyDeleted: Alarm?, val onboardingOpened: Boolean, val latePrompt: PendingLatePrompt?)

    /** The prompt for the session started at [startedAt], with the alarm ids ticked so far. */
    private data class PendingLatePrompt(val startedAt: Instant, val offers: List<LateOffer>, val accepted: Set<Long>) {
        /** Alarms deleted or switched off since the prompt opened are left out. */
        fun toUi(alarms: List<Alarm>): LatePrompt? {
            val byId = alarms.filter { it.enabled }.associateBy { it.id }
            val rows = offers.mapNotNull { offer ->
                val alarm = byId[offer.alarmId] ?: return@mapNotNull null
                LatePromptRow(offer.alarmId, alarm.label, offer.deadline, offer.option, offer.alarmId in accepted)
            }
            return if (rows.isEmpty()) null else LatePrompt(rows)
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L

        /** As long as a snackbar with an action stays up. */
        const val UNDO_OFFER_MILLIS = 10_000L
    }
}

/**
 * The card's late switch for this ring, while [session] covers it and its deadline is ahead.
 * A ring that uses its late option has a [WakeExplanation.LateCycles] explanation. After the
 * deadline there is no switch: going back to the alarm time would ring at once.
 */
private fun PlannedRing.lateSwitch(session: SleepSession?, now: Instant): LateSwitch? {
    val option = late ?: return null
    if (session == null || !session.covers(deadline) || deadline <= now) return null
    return LateSwitch(option, accepted = explanation is WakeExplanation.LateCycles)
}

private fun SchedulePlan.firstCoveredBy(session: SleepSession): PlannedRing? =
    rings.filter { session.covers(it.deadline) }.minByOrNull { it.ringAt }

/**
 * The first ring if a session started at [now]. It reuses the deadlines of the current plan,
 * which already skip occurrences that have fired. Its `late` is the option the prompt after
 * "going to sleep" would offer for it. A late ring whose deadline has passed is not covered
 * by a new session, which accepts no late option, so it would ring at once.
 */
internal fun sleepNowPreview(plan: SchedulePlan, settings: RemSettings, now: Instant): PlannedRing? {
    val session = SleepSession(now)
    return plan.rings
        .filter { session.covers(it.deadline) || it.deadline <= now }
        .map { ring ->
            val wake = computeWakeTime(ring.deadline, session, settings, now)
            PlannedRing(ring.alarmId, wake.ringAt, ring.deadline, wake.explanation, wake.late)
        }
        .minByOrNull { it.ringAt }
}
