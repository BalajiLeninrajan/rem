package dev.balaji.rem.data.store

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.balaji.rem.core.BarcodeFormat
import dev.balaji.rem.core.BarcodeKey
import dev.balaji.rem.core.ScheduledRing
import dev.balaji.rem.data.RingStateStore
import dev.balaji.rem.data.RingingRecord
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Layout of the file: the ringing record is four long keys that exist together or not at
 * all, one boolean that counts as true when it is absent, and one JSON string of barcodes
 * that counts as no barcodes when it is absent. Each fired deadline is one long key named
 * `fired_<alarm id>`, and the scheduled rings are one JSON string because their order is kept.
 */
class DataStoreRingStateStore(private val store: DataStore<Preferences>) : RingStateStore {
    override val ringing: Flow<RingingRecord?> = store.data.map { it.ringingRecord() }.distinctUntilChanged()

    override suspend fun currentRinging(): RingingRecord? = store.data.first().ringingRecord()

    override suspend fun setRinging(record: RingingRecord) {
        store.edit {
            it[RINGING_ALARM_ID] = record.alarmId
            it[RINGING_STARTED_AT] = record.startedAt.toEpochMilli()
            it[RINGING_RING_AT] = record.ringAt.toEpochMilli()
            it[RINGING_DEADLINE] = record.deadline.toEpochMilli()
            it[RINGING_REQUIRES_BARCODE] = record.requiresBarcode
            if (record.barcodes.isEmpty()) {
                it.remove(RINGING_BARCODES)
            } else {
                it[RINGING_BARCODES] = encodeBarcodes(record.barcodes)
            }
        }
    }

    override suspend fun clearRinging(expected: RingingRecord): Boolean {
        var cleared = false
        store.edit {
            cleared = it.ringingRecord() == expected.atMillis()
            if (cleared) it.removeRingingRecord()
        }
        return cleared
    }

    override suspend fun lastFired(): Map<Long, Instant> = store.data.first().firedDeadlines()

    override suspend fun markFired(alarmId: Long, deadline: Instant) {
        val key = firedKey(alarmId)
        val millis = deadline.toEpochMilli()
        store.edit { prefs ->
            val known = prefs[key]
            if (known == null || known < millis) prefs[key] = millis
        }
    }

    override suspend fun removeFired(alarmId: Long) {
        store.edit { it.remove(firedKey(alarmId)) }
    }

    override suspend fun retainFired(alarmIds: Set<Long>) {
        store.edit { prefs ->
            prefs.firedDeadlines().keys.filterNot { it in alarmIds }.forEach { prefs.remove(firedKey(it)) }
        }
    }

    override suspend fun scheduled(): List<ScheduledRing> = store.data.first()[SCHEDULED]?.let { decodeRings(it) }.orEmpty()

    override suspend fun setScheduled(rings: List<ScheduledRing>) {
        val encoded = Json.encodeToString(rings.map { StoredRing(it.alarmId, it.ringAt.toEpochMilli(), it.deadline.toEpochMilli()) })
        store.edit { it[SCHEDULED] = encoded }
    }

    @Serializable
    private data class StoredRing(val alarmId: Long, val ringAtMs: Long, val deadlineMs: Long)

    @Serializable
    private data class StoredBarcode(val format: String, val value: String)

    private companion object {
        val RINGING_ALARM_ID = longPreferencesKey("ringing_alarm_id")
        val RINGING_STARTED_AT = longPreferencesKey("ringing_started_at_ms")
        val RINGING_RING_AT = longPreferencesKey("ringing_ring_at_ms")
        val RINGING_DEADLINE = longPreferencesKey("ringing_deadline_ms")
        val RINGING_REQUIRES_BARCODE = booleanPreferencesKey("ringing_requires_barcode")
        val RINGING_BARCODES = stringPreferencesKey("ringing_barcodes")
        val SCHEDULED = stringPreferencesKey("scheduled_rings")
        const val FIRED_PREFIX = "fired_"

        fun firedKey(alarmId: Long) = longPreferencesKey("$FIRED_PREFIX$alarmId")

        fun Preferences.ringingRecord(): RingingRecord? {
            return RingingRecord(
                alarmId = this[RINGING_ALARM_ID] ?: return null,
                startedAt = Instant.ofEpochMilli(this[RINGING_STARTED_AT] ?: return null),
                ringAt = Instant.ofEpochMilli(this[RINGING_RING_AT] ?: return null),
                deadline = Instant.ofEpochMilli(this[RINGING_DEADLINE] ?: return null),
                requiresBarcode = this[RINGING_REQUIRES_BARCODE] ?: true,
                barcodes = this[RINGING_BARCODES]?.let { decodeBarcodes(it) }.orEmpty(),
            )
        }

        fun MutablePreferences.removeRingingRecord() {
            remove(RINGING_ALARM_ID)
            remove(RINGING_STARTED_AT)
            remove(RINGING_RING_AT)
            remove(RINGING_DEADLINE)
            remove(RINGING_REQUIRES_BARCODE)
            remove(RINGING_BARCODES)
        }

        fun RingingRecord.atMillis() = copy(
            startedAt = startedAt.truncatedTo(ChronoUnit.MILLIS),
            ringAt = ringAt.truncatedTo(ChronoUnit.MILLIS),
            deadline = deadline.truncatedTo(ChronoUnit.MILLIS),
        )

        fun Preferences.firedDeadlines(): Map<Long, Instant> = buildMap {
            for ((key, value) in asMap()) {
                if (!key.name.startsWith(FIRED_PREFIX) || value !is Long) continue
                val alarmId = key.name.removePrefix(FIRED_PREFIX).toLongOrNull() ?: continue
                put(alarmId, Instant.ofEpochMilli(value))
            }
        }

        fun encodeBarcodes(barcodes: List<BarcodeKey>): String =
            Json.encodeToString(barcodes.map { StoredBarcode(it.format.name, it.value) })

        /** An unreadable list counts as empty, and a format this version does not know is left out. */
        fun decodeBarcodes(encoded: String): List<BarcodeKey> = try {
            Json.decodeFromString<List<StoredBarcode>>(encoded).mapNotNull { stored ->
                BarcodeFormat.entries.firstOrNull { it.name == stored.format }?.let { BarcodeKey(it, stored.value) }
            }
        } catch (_: SerializationException) {
            emptyList()
        }

        /** An unreadable list counts as empty. The next reschedule writes a fresh one. */
        fun decodeRings(encoded: String): List<ScheduledRing> = try {
            Json.decodeFromString<List<StoredRing>>(encoded)
                .map { ScheduledRing(it.alarmId, Instant.ofEpochMilli(it.ringAtMs), Instant.ofEpochMilli(it.deadlineMs)) }
        } catch (_: SerializationException) {
            emptyList()
        }
    }
}
