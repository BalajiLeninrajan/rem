package dev.balaji.rem.data.store

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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
 * all, one boolean that counts as true when it is absent, one JSON string of barcodes
 * that counts as no barcodes when it is absent, and one int of snoozes that counts as 0
 * when it is absent. The snoozed rings are one JSON string. Each fired deadline is one
 * long key named `fired_<alarm id>`, and the scheduled rings are one JSON string because
 * their order is kept.
 */
class DataStoreRingStateStore(private val store: DataStore<Preferences>) : RingStateStore {
    override val ringing: Flow<RingingRecord?> = store.data.map { it.ringingRecord() }.distinctUntilChanged()

    override suspend fun currentRinging(): RingingRecord? = store.data.first().ringingRecord()

    override val snoozed: Flow<List<RingingRecord>> = store.data.map { it.snoozedRecords() }.distinctUntilChanged()

    override suspend fun setRinging(record: RingingRecord) {
        store.edit {
            it.putSnoozed(it.snoozedRecords().filterNot { snoozed -> snoozed.alarmId == record.alarmId })
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
            if (record.snoozes == 0) it.remove(RINGING_SNOOZES) else it[RINGING_SNOOZES] = record.snoozes
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

    override suspend fun currentSnoozed(): List<RingingRecord> = store.data.first().snoozedRecords()

    override suspend fun snooze(ringing: RingingRecord, snoozed: RingingRecord) {
        store.edit {
            if (it.ringingRecord() == ringing.atMillis()) it.removeRingingRecord()
            it.putSnoozed(it.snoozedRecords().filterNot { old -> old.alarmId == snoozed.alarmId } + snoozed.atMillis())
        }
    }

    override suspend fun clearSnoozed(expected: RingingRecord): Boolean {
        var cleared = false
        store.edit {
            val records = it.snoozedRecords()
            cleared = expected.atMillis() in records
            if (cleared) it.putSnoozed(records - expected.atMillis())
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

    @Serializable
    private data class StoredSnooze(
        val alarmId: Long,
        val startedAtMs: Long,
        val ringAtMs: Long,
        val deadlineMs: Long,
        val requiresBarcode: Boolean,
        val barcodes: List<StoredBarcode>,
        val snoozes: Int,
        val snoozedUntilMs: Long,
    )

    private companion object {
        val RINGING_ALARM_ID = longPreferencesKey("ringing_alarm_id")
        val RINGING_STARTED_AT = longPreferencesKey("ringing_started_at_ms")
        val RINGING_RING_AT = longPreferencesKey("ringing_ring_at_ms")
        val RINGING_DEADLINE = longPreferencesKey("ringing_deadline_ms")
        val RINGING_REQUIRES_BARCODE = booleanPreferencesKey("ringing_requires_barcode")
        val RINGING_BARCODES = stringPreferencesKey("ringing_barcodes")
        val RINGING_SNOOZES = intPreferencesKey("ringing_snoozes")
        val SNOOZED = stringPreferencesKey("snoozed_rings")
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
                snoozes = this[RINGING_SNOOZES] ?: 0,
            )
        }

        /** An unreadable list counts as empty. A snooze lost that way does not ring again. */
        fun Preferences.snoozedRecords(): List<RingingRecord> {
            val encoded = this[SNOOZED] ?: return emptyList()
            return try {
                Json.decodeFromString<List<StoredSnooze>>(encoded).map { stored ->
                    RingingRecord(
                        alarmId = stored.alarmId,
                        startedAt = Instant.ofEpochMilli(stored.startedAtMs),
                        ringAt = Instant.ofEpochMilli(stored.ringAtMs),
                        deadline = Instant.ofEpochMilli(stored.deadlineMs),
                        requiresBarcode = stored.requiresBarcode,
                        barcodes = stored.barcodes.mapNotNull { it.toKey() },
                        snoozes = stored.snoozes,
                        snoozedUntil = Instant.ofEpochMilli(stored.snoozedUntilMs),
                    )
                }
            } catch (_: SerializationException) {
                emptyList()
            }
        }

        /** Records without [RingingRecord.snoozedUntil] are not snoozed and are left out. */
        fun MutablePreferences.putSnoozed(records: List<RingingRecord>) {
            val stored = records.mapNotNull { record ->
                StoredSnooze(
                    alarmId = record.alarmId,
                    startedAtMs = record.startedAt.toEpochMilli(),
                    ringAtMs = record.ringAt.toEpochMilli(),
                    deadlineMs = record.deadline.toEpochMilli(),
                    requiresBarcode = record.requiresBarcode,
                    barcodes = record.barcodes.map { StoredBarcode(it.format.name, it.value) },
                    snoozes = record.snoozes,
                    snoozedUntilMs = record.snoozedUntil?.toEpochMilli() ?: return@mapNotNull null,
                )
            }
            if (stored.isEmpty()) remove(SNOOZED) else this[SNOOZED] = Json.encodeToString(stored)
        }

        fun MutablePreferences.removeRingingRecord() {
            remove(RINGING_ALARM_ID)
            remove(RINGING_STARTED_AT)
            remove(RINGING_RING_AT)
            remove(RINGING_DEADLINE)
            remove(RINGING_REQUIRES_BARCODE)
            remove(RINGING_BARCODES)
            remove(RINGING_SNOOZES)
        }

        fun RingingRecord.atMillis() = copy(
            startedAt = startedAt.truncatedTo(ChronoUnit.MILLIS),
            ringAt = ringAt.truncatedTo(ChronoUnit.MILLIS),
            deadline = deadline.truncatedTo(ChronoUnit.MILLIS),
            snoozedUntil = snoozedUntil?.truncatedTo(ChronoUnit.MILLIS),
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
            Json.decodeFromString<List<StoredBarcode>>(encoded).mapNotNull { it.toKey() }
        } catch (_: SerializationException) {
            emptyList()
        }

        fun StoredBarcode.toKey(): BarcodeKey? =
            BarcodeFormat.entries.firstOrNull { it.name == format }?.let { BarcodeKey(it, value) }

        /** An unreadable list counts as empty. The next reschedule writes a fresh one. */
        fun decodeRings(encoded: String): List<ScheduledRing> = try {
            Json.decodeFromString<List<StoredRing>>(encoded)
                .map { ScheduledRing(it.alarmId, Instant.ofEpochMilli(it.ringAtMs), Instant.ofEpochMilli(it.deadlineMs)) }
        } catch (_: SerializationException) {
            emptyList()
        }
    }
}
