package dev.balaji.rem.ui.alarm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.balaji.rem.core.Alarm
import dev.balaji.rem.core.Barcode
import dev.balaji.rem.core.RemSettings
import dev.balaji.rem.core.SleepSession
import dev.balaji.rem.core.WakeExplanation
import dev.balaji.rem.core.computeWakeTime
import dev.balaji.rem.data.AlarmRepository
import dev.balaji.rem.data.BarcodeRepository
import dev.balaji.rem.data.SettingsRepository
import dev.balaji.rem.data.SleepSessionRepository
import dev.balaji.rem.ui.barcodes.BarcodeTarget
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
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
    val vibrate: Boolean = true,
    /** Null means the bundled tone. */
    val ringtoneUri: String? = null,
    val minRingVolumePercent: Int = Alarm.DEFAULT_MIN_RING_VOLUME_PERCENT,
    /** 0 disables the ramp. */
    val volumeRampSeconds: Int = Alarm.DEFAULT_VOLUME_RAMP_SECONDS,
    /**
     * The barcodes that stop this alarm, oldest first. For a stored alarm these are the
     * stored ones. For a new alarm they wait in the editor, with [Barcode.NEW_ID], until save.
     */
    val barcodes: List<Barcode> = emptyList(),
    val preview: RingPreview? = null,
    /** True when the stored alarm is off and saving the draft as it stands keeps it off. Then nothing would ring. */
    val staysOff: Boolean = false,
    val now: Instant = Instant.EPOCH,
    val zone: ZoneId = ZoneOffset.UTC,
    /** True after a save or delete. The screen closes when it sees this. */
    val finished: Boolean = false,
) {
    val labelTooLong: Boolean get() = label.trim().length > AlarmEditorViewModel.MAX_LABEL_LENGTH
    val canSave: Boolean get() = !loading && !labelTooLong
}

/**
 * Edits one alarm, or creates one when there is no stored alarm with [alarmId].
 *
 * A new alarm with a [copyFromId] is a duplicate. It starts with that alarm's time, label,
 * days and sound, and with copies of its barcodes as unsaved barcodes. Nothing is stored
 * until [save]. A [copyFromId] that names no stored alarm gives the defaults.
 *
 * It is also the [BarcodeTarget] of the scan screen opened from the editor. A barcode added
 * to or deleted from a stored alarm is written at once. A new alarm keeps its barcodes in
 * the editor, and [save] stores them together with the alarm in one transaction.
 */
class AlarmEditorViewModel(
    private val alarmId: Long,
    private val alarms: AlarmRepository,
    private val barcodes: BarcodeRepository,
    settings: SettingsRepository,
    sessions: SleepSessionRepository,
    private val clock: Clock,
    private val copyFromId: Long = Alarm.NEW_ID,
) : ViewModel(), BarcodeTarget {
    private val draft = MutableStateFlow(AlarmEditorUiState())
    private val now = MutableStateFlow(clock.instant())
    @Volatile
    private var stored: Alarm? = null

    /** Completes with the stored alarm, or null for a new one, once loading is done. */
    private val loaded = CompletableDeferred<Alarm?>()
    private val storedAlarmId = MutableStateFlow<Long?>(null)

    /** Barcodes of a new alarm. They are stored by [save]. */
    private val pending = MutableStateFlow<List<Barcode>>(emptyList())

    private val barcodeList: Flow<List<Barcode>> =
        combine(storedAlarmId, pending, barcodes.barcodesByAlarm) { id, waiting, byAlarm ->
            if (id == null) waiting else byAlarm[id].orEmpty()
        }

    val state: StateFlow<AlarmEditorUiState> =
        combine(
            draft,
            settings.settings,
            sessions.session,
            now,
            barcodeList,
        ) { current, storedSettings, session, instant, list ->
            current.copy(
                barcodes = list,
                preview = if (current.loading) null else preview(current, storedSettings, session, instant),
                staysOff = stored?.let { !it.enabled && !scheduleChanged(it, current) } ?: false,
                now = instant,
                zone = clock.zone,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), AlarmEditorUiState())

    init {
        viewModelScope.launch {
            val alarm = if (alarmId == Alarm.NEW_ID) null else alarms.get(alarmId)
            val source = if (alarm != null || copyFromId == Alarm.NEW_ID) null else alarms.get(copyFromId)
            if (source != null) {
                pending.value = barcodes.all(source.id).map { it.copy(id = Barcode.NEW_ID) }
            }
            stored = alarm
            storedAlarmId.value = alarm?.id
            draft.update {
                // A duplicate starts from its source, but it is still a new alarm.
                val from = alarm ?: source
                if (from == null) {
                    it.copy(loading = false, isNew = true)
                } else {
                    it.copy(
                        loading = false,
                        isNew = alarm == null,
                        time = from.time,
                        label = from.label,
                        repeatDays = from.repeatDays,
                        vibrate = from.vibrate,
                        ringtoneUri = from.ringtoneUri,
                        minRingVolumePercent = from.minRingVolumePercent,
                        volumeRampSeconds = from.volumeRampSeconds,
                    )
                }
            }
            loaded.complete(alarm)
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

    fun setVibrate(enabled: Boolean) = draft.update { it.copy(vibrate = enabled) }

    fun setRingtone(uri: String?) = draft.update { it.copy(ringtoneUri = uri) }

    fun setMinRingVolumePercent(value: Int) =
        draft.update { it.copy(minRingVolumePercent = value.coerceIn(Alarm.MIN_RING_VOLUME_PERCENT)) }

    fun setVolumeRampSeconds(value: Int) =
        draft.update { it.copy(volumeRampSeconds = value.coerceIn(Alarm.VOLUME_RAMP_SECONDS)) }

    fun save() {
        val current = draft.value
        if (!current.canSave || current.finished) return
        viewModelScope.launch {
            val original = stored
            val scheduleChanged = original == null || scheduleChanged(original, current)
            val alarm = Alarm(
                id = original?.id ?: Alarm.NEW_ID,
                time = current.time,
                label = current.label.trim(),
                repeatDays = current.repeatDays,
                // Saving a new time or new days means the user wants this alarm, so it is switched on.
                enabled = scheduleChanged || original.enabled,
                vibrate = current.vibrate,
                ringtoneUri = current.ringtoneUri,
                minRingVolumePercent = current.minRingVolumePercent,
                volumeRampSeconds = current.volumeRampSeconds,
            )
            if (original == null) alarms.upsertWithBarcodes(alarm, pending.value) else alarms.upsert(alarm)
            draft.update { it.copy(finished = true) }
        }
    }

    fun delete() {
        viewModelScope.launch {
            stored?.let { alarms.delete(it.id) }
            draft.update { it.copy(finished = true) }
        }
    }

    override suspend fun currentBarcodes(): List<Barcode> {
        val alarm = loaded.await()
        return if (alarm == null) pending.value else barcodes.all(alarm.id)
    }

    override suspend fun addBarcode(barcode: Barcode) {
        val alarm = loaded.await()
        if (alarm != null) {
            barcodes.add(alarm.id, barcode)
        } else {
            pending.update { list ->
                if (list.any { it.key == barcode.key }) list else list + barcode.copy(id = Barcode.NEW_ID)
            }
        }
    }

    /** Renames [barcode], at once for a stored alarm. A blank label is ignored, every barcode keeps a name. */
    fun renameBarcode(barcode: Barcode, label: String) {
        val trimmed = label.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            if (loaded.await() != null) {
                barcodes.rename(barcode.id, trimmed)
            } else {
                pending.update { list -> list.map { if (it.key == barcode.key) it.copy(label = trimmed) else it } }
            }
        }
    }

    /** Deletes [barcode] from the alarm, at once for a stored alarm. */
    fun removeBarcode(barcode: Barcode) {
        viewModelScope.launch {
            if (loaded.await() != null) {
                barcodes.delete(barcode.id)
            } else {
                pending.update { list -> list.filterNot { it.key == barcode.key } }
            }
        }
    }

    private fun scheduleChanged(original: Alarm, current: AlarmEditorUiState): Boolean =
        original.time != current.time || original.repeatDays != current.repeatDays

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
