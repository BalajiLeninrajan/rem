package dev.balaji.rem.ring

import dev.balaji.rem.core.FallbackCode
import dev.balaji.rem.core.ScannedCode
import dev.balaji.rem.core.ScheduledRing
import java.security.SecureRandom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Stand-in controller that never rings. */
class IdleRingController : RingController {
    private val random = SecureRandom()

    override val state: StateFlow<RingingState> = MutableStateFlow(RingingState.Idle)

    override fun startRinging(ring: ScheduledRing): Boolean = false

    override fun resumeRinging(ring: ScheduledRing) = Unit

    override suspend fun dismissWithBarcode(scanned: ScannedCode): DismissResult = DismissResult.NOT_RINGING

    override suspend fun dismissWithTypedCode(typed: String): DismissResult = DismissResult.NOT_RINGING

    override suspend fun dismissWithoutBarcode(): DismissResult = DismissResult.NOT_RINGING

    override fun newFallbackCode(): String = FallbackCode.generate(random)

    /** How often a screen asked for a quiet period. */
    var quietPeriodsStarted = 0
        private set

    override fun startQuietPeriod() {
        quietPeriodsStarted++
    }

    override fun endQuietPeriod() = Unit

    override suspend fun recoverInterruptedRing() = Unit
}
