package dev.balaji.rem.testing

import dev.balaji.rem.core.FallbackCode
import dev.balaji.rem.core.ScannedCode
import dev.balaji.rem.core.ScheduledRing
import dev.balaji.rem.ring.DismissResult
import dev.balaji.rem.ring.RingController
import dev.balaji.rem.ring.RingingState
import dev.balaji.rem.ring.SnoozeResult
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow

/** A clock the test moves by hand. */
class MutableClock(var now: Instant, var currentZone: ZoneId) : Clock() {
    override fun getZone(): ZoneId = currentZone

    override fun withZone(zone: ZoneId): Clock = MutableClock(now, zone)

    override fun instant(): Instant = now
}

/** A ring controller whose state the test sets directly. It records the calls it gets. */
class FakeRingController : RingController {
    private val random = SecureRandom()

    override val state = MutableStateFlow<RingingState>(RingingState.Idle)
    override val snoozed = MutableStateFlow<List<RingingState.Snoozed>>(emptyList())

    val started = mutableListOf<ScheduledRing>()
    val resumed = mutableListOf<ScheduledRing>()
    val snoozesEnded = mutableListOf<ScheduledRing>()
    var snoozeCalls = 0
        private set
    var plainDismissals = 0
        private set
    var quietPeriodsStarted = 0
        private set

    /** What [startRinging] answers. False stands for a ring that another ring swallowed. */
    var accepts = true

    override fun startRinging(ring: ScheduledRing): Boolean {
        started += ring
        return accepts
    }

    override fun resumeRinging(ring: ScheduledRing) {
        resumed += ring
    }

    override fun endSnooze(ring: ScheduledRing) {
        snoozesEnded += ring
    }

    override suspend fun snooze(): SnoozeResult {
        snoozeCalls++
        return SnoozeResult.SNOOZED
    }

    override suspend fun dismissWithBarcode(scanned: ScannedCode) = DismissResult.NOT_RINGING

    override suspend fun dismissWithTypedCode(typed: String) = DismissResult.NOT_RINGING

    override suspend fun dismissWithoutBarcode(): DismissResult {
        plainDismissals++
        return DismissResult.DISMISSED
    }

    override fun newFallbackCode(): String = FallbackCode.generate(random)

    override fun startQuietPeriod() {
        quietPeriodsStarted++
    }

    override fun endQuietPeriod() = Unit

    override suspend fun recoverInterruptedRing() = Unit
}
