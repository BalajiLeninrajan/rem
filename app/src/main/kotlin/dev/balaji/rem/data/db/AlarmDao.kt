package dev.balaji.rem.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AlarmDao {
    @Query("SELECT * FROM alarms ORDER BY time_of_day, id")
    fun observeAll(): Flow<List<AlarmEntity>>

    @Query("SELECT * FROM alarms ORDER BY time_of_day, id")
    suspend fun all(): List<AlarmEntity>

    @Query("SELECT * FROM alarms WHERE id = :id")
    suspend fun get(id: Long): AlarmEntity?

    /** An id of 0 gets a generated id. Returns the row id, which is the alarm id. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(alarm: AlarmEntity): Long

    @Query("UPDATE alarms SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("DELETE FROM alarms WHERE id = :id")
    suspend fun delete(id: Long)
}
