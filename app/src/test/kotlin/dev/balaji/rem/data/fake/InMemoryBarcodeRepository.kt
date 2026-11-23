package dev.balaji.rem.data.fake

import dev.balaji.rem.core.Barcode
import dev.balaji.rem.core.normaliseBarcode
import dev.balaji.rem.data.BarcodeRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * Knows nothing about alarms, so it accepts any alarm id. [InMemoryAlarmRepository] calls
 * [removeAlarm] when an alarm is deleted, which stands in for the foreign key.
 *
 * @param initial barcodes by alarm id. Their ids are kept.
 */
class InMemoryBarcodeRepository(initial: Map<Long, List<Barcode>> = emptyMap()) : BarcodeRepository {
    private data class Row(val alarmId: Long, val barcode: Barcode)

    private val state = MutableStateFlow(
        initial.flatMap { (alarmId, barcodes) -> barcodes.map { Row(alarmId, it) } }.sortedBy { it.barcode.id },
    )

    override val barcodesByAlarm: Flow<Map<Long, List<Barcode>>> = state.map { rows -> rows.byAlarm() }.distinctUntilChanged()

    override fun barcodes(alarmId: Long): Flow<List<Barcode>> =
        state.map { rows -> rows.of(alarmId) }.distinctUntilChanged()

    override suspend fun all(alarmId: Long): List<Barcode> = state.value.of(alarmId)

    override suspend fun add(alarmId: Long, barcode: Barcode): Long {
        val key = normaliseBarcode(barcode.format, barcode.value)
        var id = Barcode.NEW_ID
        state.update { current ->
            val existing = current.firstOrNull { it.alarmId == alarmId && it.barcode.key == key }
            if (existing != null) {
                id = existing.barcode.id
                current.map { if (it.barcode.id == id) it.copy(barcode = it.barcode.copy(label = barcode.label)) else it }
            } else {
                id = (current.maxOfOrNull { it.barcode.id } ?: 0L) + 1
                current + Row(alarmId, Barcode(id, barcode.label, key.format, key.value))
            }
        }
        return id
    }

    override suspend fun rename(id: Long, label: String) {
        state.update { current ->
            current.map { if (it.barcode.id == id) it.copy(barcode = it.barcode.copy(label = label)) else it }
        }
    }

    override suspend fun delete(id: Long) {
        state.update { current -> current.filterNot { it.barcode.id == id } }
    }

    /** Deletes every barcode of [alarmId]. */
    fun removeAlarm(alarmId: Long) {
        state.update { current -> current.filterNot { it.alarmId == alarmId } }
    }

    private fun List<Row>.of(alarmId: Long): List<Barcode> = filter { it.alarmId == alarmId }.map { it.barcode }

    private fun List<Row>.byAlarm(): Map<Long, List<Barcode>> = groupBy({ it.alarmId }, { it.barcode })
}
