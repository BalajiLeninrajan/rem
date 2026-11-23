package dev.balaji.rem.ring.audio

/**
 * The lowest alarm stream index the ring accepts: [percent] of the way from [min] to
 * [max], rounded up so a floor above 0 percent never lands on a muted index.
 */
internal fun volumeFloorIndex(min: Int, max: Int, percent: Int): Int {
    if (max <= min) return max
    val span = max - min
    return min + (span * percent.coerceIn(0, 100) + 99) / 100
}

/** The index to set, or null when [current] is already at or above [floor]. The floor only ever raises. */
internal fun volumeToSet(current: Int, floor: Int): Int? = floor.takeIf { current < it }

/**
 * Per-player gain of the volume ramp, [elapsedMillis] after the ring started. It starts
 * audible, not silent, and reaches full volume after [rampMillis]. 0 disables the ramp.
 */
internal fun rampGain(elapsedMillis: Long, rampMillis: Long): Float {
    if (rampMillis <= 0 || elapsedMillis >= rampMillis) return 1f
    val progress = elapsedMillis.coerceAtLeast(0).toFloat() / rampMillis
    return RAMP_START_GAIN + (1f - RAMP_START_GAIN) * progress
}

private const val RAMP_START_GAIN = 0.1f
