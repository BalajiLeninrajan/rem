package dev.balaji.rem.data.fake

import dev.balaji.rem.core.Barcode
import dev.balaji.rem.data.BarcodeRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

class InMemoryBarcodeRepository(initial: List<Barcode> = emptyList()) : BarcodeRepository {
    private val state = MutableStateFlow(initial)

    override val barcodes: Flow<List<Barcode>> = state

    override suspend fun all(): List<Barcode> = state.value

    override suspend fun add(barcode: Barcode): Long {
        var id = Barcode.NEW_ID
        state.update { current ->
            val existing = current.firstOrNull { it.key == barcode.key }
            if (existing != null) {
                id = existing.id
                current.map { if (it.id == id) it.copy(label = barcode.label) else it }
            } else {
                id = (current.maxOfOrNull { it.id } ?: 0L) + 1
                current + barcode.copy(id = id)
            }
        }
        return id
    }

    override suspend fun rename(id: Long, label: String) {
        state.update { current -> current.map { if (it.id == id) it.copy(label = label) else it } }
    }

    override suspend fun delete(id: Long) {
        state.update { current -> current.filterNot { it.id == id } }
    }
}
