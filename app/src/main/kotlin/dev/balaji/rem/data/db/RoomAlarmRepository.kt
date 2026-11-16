package dev.balaji.rem.data.db

import dev.balaji.rem.core.Alarm
import dev.balaji.rem.data.AlarmRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class RoomAlarmRepository(private val dao: AlarmDao) : AlarmRepository {
    // Room emits after every write to the table, including a write that changed nothing.
    override val alarms: Flow<List<Alarm>> =
        dao.observeAll().map { rows -> rows.map(AlarmEntity::toAlarm) }.distinctUntilChanged()

    override suspend fun all(): List<Alarm> = dao.all().map(AlarmEntity::toAlarm)

    override suspend fun get(id: Long): Alarm? = dao.get(id)?.toAlarm()

    override suspend fun upsert(alarm: Alarm): Long = dao.insertOrReplace(alarm.toEntity())

    override suspend fun setEnabled(id: Long, enabled: Boolean) = dao.setEnabled(id, enabled)

    override suspend fun delete(id: Long) = dao.delete(id)
}
