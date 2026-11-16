package dev.balaji.rem.ui.alarm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.balaji.rem.core.Alarm
import dev.balaji.rem.core.RemSettings
import dev.balaji.rem.core.SleepSession
import dev.balaji.rem.core.WakeExplanation
import dev.balaji.rem.core.computeWakeTime
import dev.balaji.rem.data.AlarmRepository
import dev.balaji.rem.data.SettingsRepository
import dev.balaji.rem.data.SleepSessionRepository
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** When the alarm being edited would ring next if it were saved as it stands. */
data class RingPreview(
    val ringAt: Instant,
    val deadline: Instant,
    val explanation: WakeExplanation,
    /** True when a sleep session is running, which is when the ring can be earlier than the deadline. */
    val sessionActive: Boolean,
)

data class AlarmEditorUiState(
    val loading: Boolean = true,
    val isNew: Boolean = true,
    val time: LocalTime = AlarmEditorViewModel.DEFAULT_TIME,
    val label: String = "",
    val repeatDays: Set<DayOfWeek> = emptySet(),
    val headphonesOnly: Boolean = false,
    val vibrate: Boolean = true,
    /** Null means the bundled tone. */
    val ringtoneUri: String? = null,
    val preview: RingPreview? = null,
    val speakerFallbackSeconds: Int = RemSettings.DEFAULT.speakerFallbackSeconds,
    val now: Instant = Instant.EPOCH,
    val zone: ZoneId = ZoneOffset.UTC,
    /** True after a save or delete. The screen closes when it sees this. */
    val finished: Boolean = false,
) {
    val labelTooLong: Boolean get() = label.trim().length > AlarmEditorViewModel.MAX_LABEL_LENGTH
    val canSave: Boolean get() = !loading && !labelTooLong
}

class AlarmEditorViewModel(
    private val alarmId: Long,
    private val alarms: AlarmRepository,
    settings: SettingsRepository,
    sessions: SleepSessionRepository,
    private val clock: Clock,
) : ViewModel() {
    private val draft = MutableStateFlow(AlarmEditorUiState())
    private val now = MutableStateFlow(clock.instant())
    private var stored: Alarm? = null

    val state: StateFlow<AlarmEditorUiState> =
        combine(draft, settings.settings, sessions.session, now) { current, storedSettings, session, instant ->
            current.copy(
                preview = if (current.loading) null else preview(current, storedSettings, session, instant),
                speakerFallbackSeconds = storedSettings.speakerFallbackSeconds,
                now = instant,
                zone = clock.zone,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), AlarmEditorUiState())

    init {
        viewModelScope.launch {
            val alarm = if (alarmId == Alarm.NEW_ID) null else alarms.get(alarmId)
            stored = alarm
            draft.update {
                if (alarm == null) {
                    it.copy(loading = false, isNew = true)
                } else {
                    it.copy(
                        loading = false,
                        isNew = false,
                        time = alarm.time,
                        label = alarm.label,
                        repeatDays = alarm.repeatDays,
                        headphonesOnly = alarm.headphonesOnly,
                        vibrate = alarm.vibrate,
                        ringtoneUri = alarm.ringtoneUri,
                    )
                }
            }
        }
    }

    /** Keeps the preview's "now" current while the screen is open. */
    fun refresh() {
        now.value = clock.instant()
    }

    fun setTime(time: LocalTime) = draft.update { it.copy(time = time.withSecond(0).withNano(0)) }

    fun setLabel(label: String) = draft.update { it.copy(label = label) }

    fun toggleDay(day: DayOfWeek) = draft.update {
        it.copy(repeatDays = if (day in it.repeatDays) it.repeatDays - day else it.repeatDays + day)
    }

    fun setHeadphonesOnly(enabled: Boolean) = draft.update { it.copy(headphonesOnly = enabled) }

    fun setVibrate(enabled: Boolean) = draft.update { it.copy(vibrate = enabled) }

    fun setRingtone(uri: String?) = draft.update { it.copy(ringtoneUri = uri) }

    fun save() {
        val current = draft.value
        if (!current.canSave || current.finished) return
        viewModelScope.launch {
            val original = stored
            val scheduleChanged = original == null || original.time != current.time || original.repeatDays != current.repeatDays
            alarms.upsert(
                Alarm(
                    id = original?.id ?: Alarm.NEW_ID,
                    time = current.time,
                    label = current.label.trim(),
                    repeatDays = current.repeatDays,
                    // Saving a new time or new days means the user wants this alarm, so it is switched on.
                    enabled = scheduleChanged || original.enabled,
                    headphonesOnly = current.headphonesOnly,
                    vibrate = current.vibrate,
                    ringtoneUri = current.ringtoneUri,
                ),
            )
            draft.update { it.copy(finished = true) }
        }
    }

    fun delete() {
        viewModelScope.launch {
            stored?.let { alarms.delete(it.id) }
            draft.update { it.copy(finished = true) }
        }
    }

    private fun preview(current: AlarmEditorUiState, settings: RemSettings, session: SleepSession?, now: Instant): RingPreview {
        val activeSession = session?.takeIf { it.isActive(now) }
        val deadline = Alarm(time = current.time, repeatDays = current.repeatDays).nextDeadline(now, clock.zone)
        val wake = computeWakeTime(deadline, activeSession, settings, now)
        return RingPreview(wake.ringAt, deadline, wake.explanation, sessionActive = activeSession != null)
    }

    companion object {
        val DEFAULT_TIME: LocalTime = LocalTime.of(7, 0)
        const val MAX_LABEL_LENGTH = 40
        private const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
