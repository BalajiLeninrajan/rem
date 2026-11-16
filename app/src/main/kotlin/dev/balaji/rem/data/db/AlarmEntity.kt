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
    @ColumnInfo(name = "headphones_only") val headphonesOnly: Boolean,
    val vibrate: Boolean,
    @ColumnInfo(name = "ringtone_uri") val ringtoneUri: String?,
)

fun AlarmEntity.toAlarm() = Alarm(id, time, label, repeatDays, enabled, headphonesOnly, vibrate, ringtoneUri)

fun Alarm.toEntity() = AlarmEntity(id, time, label, repeatDays, enabled, headphonesOnly, vibrate, ringtoneUri)
