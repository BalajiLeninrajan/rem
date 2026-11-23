package dev.balaji.rem.core

enum class BarcodeFormat { EAN_13, EAN_8, UPC_A, UPC_E, ITF, CODE_39, CODE_128, QR_CODE, DATA_MATRIX, OTHER }

/** A barcode exactly as the scanner reported it. */
data class ScannedCode(val format: BarcodeFormat, val rawValue: String) {
    fun normalised(): BarcodeKey = normaliseBarcode(format, rawValue)
}

/** Normalised identity of a barcode. Two scans are the same barcode when their keys are equal. */
data class BarcodeKey(val format: BarcodeFormat, val value: String) {
    /** A blank scan identifies nothing and must not be registered or matched. */
    val isUsable: Boolean get() = value.isNotEmpty()
}

/**
 * A barcode the user registered for dismissal. Every stored barcode belongs to one alarm,
 * and only a ring of that alarm accepts it. Which alarm is a storage matter, so it is not
 * a field here: an alarm that is still being edited holds barcodes that are not stored yet.
 *
 * @property id 0 until saved.
 * @property format the normalised format, see [normaliseBarcode].
 * @property value the normalised value.
 */
data class Barcode(val id: Long = NEW_ID, val label: String, val format: BarcodeFormat, val value: String) {
    val key: BarcodeKey get() = BarcodeKey(format, value)

    companion object {
        const val NEW_ID = 0L

        fun from(label: String, scanned: ScannedCode): Barcode {
            val key = scanned.normalised()
            return Barcode(label = label, format = key.format, value = key.value)
        }
    }
}

/**
 * Normalises a scan so the same physical barcode always gives the same key.
 *
 * - UPC-A and EAN-13 share one key space. A 12 digit UPC-A is the 13 digit EAN-13 with a
 *   leading 0, and scanners report either, so both become EAN-13 with 13 digits.
 * - Numeric formats drop all whitespace.
 * - Code 39 has no lower case letters, so it is upper-cased.
 * - Everything else (QR, Data Matrix, Code 128) is case sensitive and only loses
 *   surrounding whitespace.
 */
fun normaliseBarcode(format: BarcodeFormat, rawValue: String): BarcodeKey = when (format) {
    BarcodeFormat.UPC_A, BarcodeFormat.EAN_13 -> {
        val digits = rawValue.withoutWhitespace()
        BarcodeKey(BarcodeFormat.EAN_13, if (digits.length == UPC_A_LENGTH) "0$digits" else digits)
    }
    BarcodeFormat.EAN_8, BarcodeFormat.UPC_E, BarcodeFormat.ITF -> BarcodeKey(format, rawValue.withoutWhitespace())
    BarcodeFormat.CODE_39 -> BarcodeKey(format, rawValue.trim().uppercase())
    BarcodeFormat.CODE_128, BarcodeFormat.QR_CODE, BarcodeFormat.DATA_MATRIX, BarcodeFormat.OTHER ->
        BarcodeKey(format, rawValue.trim())
}

/** The barcode in this collection that [scanned] is, or null. */
fun Collection<Barcode>.findMatch(scanned: ScannedCode): Barcode? {
    val key = scanned.normalised()
    if (!key.isUsable) return null
    return firstOrNull { it.key == key }
}

private const val UPC_A_LENGTH = 12

private fun String.withoutWhitespace(): String = filterNot(Char::isWhitespace)
