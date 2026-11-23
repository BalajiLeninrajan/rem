package dev.balaji.rem.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.balaji.rem.core.Alarm
import java.time.DayOfWeek
import java.time.LocalTime

@Entity(tableName = "alarms")
data class AlarmEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    @ColumnInfo(name = "time_of_day") val time: LocalTime,
    val label: String,
    @ColumnInfo(name = "repeat_days") val repeatDays: Set<DayOfWeek>,
    val enabled: Boolean,
    val vibrate: Boolean,
    @ColumnInfo(name = "ringtone_uri") val ringtoneUri: String?,
    @ColumnInfo(name = "min_ring_volume_percent") val minRingVolumePercent: Int,
    @ColumnInfo(name = "volume_ramp_seconds") val volumeRampSeconds: Int,
)

fun AlarmEntity.toAlarm() = Alarm(
    id = id,
    time = time,
    label = label,
    repeatDays = repeatDays,
    enabled = enabled,
    vibrate = vibrate,
    ringtoneUri = ringtoneUri,
    minRingVolumePercent = minRingVolumePercent,
    volumeRampSeconds = volumeRampSeconds,
).coerced()

fun Alarm.toEntity(): AlarmEntity {
    val alarm = coerced()
    return AlarmEntity(
        id = alarm.id,
        time = alarm.time,
        label = alarm.label,
        repeatDays = alarm.repeatDays,
        enabled = alarm.enabled,
        vibrate = alarm.vibrate,
        ringtoneUri = alarm.ringtoneUri,
        minRingVolumePercent = alarm.minRingVolumePercent,
        volumeRampSeconds = alarm.volumeRampSeconds,
    )
}
