package dev.balaji.rem.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.balaji.rem.core.Alarm
import dev.balaji.rem.core.PlannedRing
import dev.balaji.rem.core.RemSettings
import dev.balaji.rem.core.SchedulePlan
import dev.balaji.rem.core.SleepSession
import dev.balaji.rem.core.computeWakeTime
import dev.balaji.rem.data.AlarmRepository
import dev.balaji.rem.data.BarcodeRepository
import dev.balaji.rem.data.SettingsRepository
import dev.balaji.rem.data.SleepSessionRepository
import dev.balaji.rem.ring.RingController
import dev.balaji.rem.ring.RingingState
import dev.balaji.rem.schedule.AlarmScheduler
import dev.balaji.rem.ui.reliability.reliabilityProblemCount
import dev.balaji.rem.ui.system.SystemChecks
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(
    private val alarms: AlarmRepository,
    barcodes: BarcodeRepository,
    settings: SettingsRepository,
    private val sessions: SleepSessionRepository,
    scheduler: AlarmScheduler,
    ringController: RingController,
    private val clock: Clock,
) : ViewModel() {
    private val now = MutableStateFlow(clock.instant())
    private val system = MutableStateFlow(SystemChecks())
    private val recentlyDeleted = MutableStateFlow<Alarm?>(null)
    private val onboardingOpened = MutableStateFlow(false)

    private val stored = combine(
        alarms.alarms,
        barcodes.barcodes,
        settings.settings,
        sessions.session,
        settings.onboardingDone,
    ) { storedAlarms, storedBarcodes, storedSettings, session, onboardingDone ->
        Stored(storedAlarms, storedBarcodes.size, storedSettings, session, onboardingDone)
    }

    private val live = combine(scheduler.plan, ringController.state, now, system) { plan, ringing, instant, checks ->
        Live(plan, (ringing as? RingingState.Ringing)?.alarmId, instant, checks)
    }

    private val local = combine(recentlyDeleted, onboardingOpened) { deleted, opened -> Local(deleted, opened) }

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
            alarms.delete(alarmId)
            recentlyDeleted.value = alarm
        }
    }

    fun undoDelete() {
        val alarm = recentlyDeleted.value ?: return
        recentlyDeleted.value = null
        viewModelScope.launch { alarms.upsert(alarm) }
    }

    /** The undo offer for [alarm] ended without being taken. A newer offer is left alone. */
    fun onUndoExpired(alarm: Alarm) {
        recentlyDeleted.compareAndSet(alarm, null)
    }

    fun goToSleep() {
        viewModelScope.launch { sessions.start(clock.instant()) }
    }

    fun endSleep() {
        viewModelScope.launch { sessions.end() }
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
                AlarmRow(
                    alarm = alarm,
                    nextRing = if (alarm.enabled) live.plan.forAlarm(alarm.id) else null,
                    ringing = alarm.id == live.ringingAlarmId,
                )
            },
            sleep = when {
                session != null -> SleepState.Active(session.startedAt, live.plan.firstCoveredBy(session))
                stored.alarms.any { it.enabled } -> SleepState.Ready(sleepNowPreview(live.plan, stored.settings, live.now))
                else -> SleepState.Hidden
            },
            noBarcodes = stored.barcodeCount == 0,
            reliabilityProblems = reliabilityProblemCount(live.system, stored.barcodeCount),
            onboardingNeeded = !stored.onboardingDone && !local.onboardingOpened,
            recentlyDeleted = local.recentlyDeleted,
            now = live.now,
            zone = clock.zone,
        )
    }

    private data class Stored(
        val alarms: List<Alarm>,
        val barcodeCount: Int,
        val settings: RemSettings,
        val session: SleepSession?,
        val onboardingDone: Boolean,
    )

    private data class Live(val plan: SchedulePlan, val ringingAlarmId: Long?, val now: Instant, val system: SystemChecks)

    private data class Local(val recentlyDeleted: Alarm?, val onboardingOpened: Boolean)

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

private fun SchedulePlan.firstCoveredBy(session: SleepSession): PlannedRing? =
    rings.filter { session.covers(it.deadline) }.minByOrNull { it.ringAt }

/**
 * The first ring if a session started at [now]. It reuses the deadlines of the current plan,
 * which already skip occurrences that have fired.
 */
internal fun sleepNowPreview(plan: SchedulePlan, settings: RemSettings, now: Instant): PlannedRing? {
    val session = SleepSession(now)
    return plan.rings
        .filter { session.covers(it.deadline) }
        .map { ring ->
            val wake = computeWakeTime(ring.deadline, session, settings, now)
            PlannedRing(ring.alarmId, wake.ringAt, ring.deadline, wake.explanation)
        }
        .minByOrNull { it.ringAt }
}
