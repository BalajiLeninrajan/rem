package dev.balaji.rem.core

/**
 * Accepts a barcode only after it shows up in [requiredFrames] consecutive camera frames,
 * because single-frame misreads of EAN and UPC codes happen.
 *
 * A code is reported once, on the frame where it becomes stable. It is reported again only
 * after at least one frame without it. Frames are compared by normalised key, so a code
 * that flips between UPC-A and EAN-13 still counts as the same code.
 *
 * Not thread safe. Feed it from one analyzer thread.
 */
class StableScanFilter(private val requiredFrames: Int = 2) {
    private var streaks: Map<BarcodeKey, Int> = emptyMap()

    init {
        require(requiredFrames >= 1) { "requiredFrames must be at least 1, was $requiredFrames" }
    }

    /** Call once per analysed frame, with an empty collection when the frame has no codes. */
    fun onFrame(codes: Collection<ScannedCode>): List<ScannedCode> {
        val byKey = codes.associateBy { it.normalised() }.filterKeys { it.isUsable }
        streaks = byKey.mapValues { (key, _) -> (streaks[key] ?: 0) + 1 }
        return byKey.filterKeys { streaks[it] == requiredFrames }.values.toList()
    }
}
