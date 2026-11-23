package dev.balaji.rem.di

import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * System time in the current system zone. `Clock.systemDefaultZone()` captures the zone
 * once, which would leave a long-lived process computing deadlines in the old zone after
 * the user travels. This one asks for the zone on every call.
 */
object SystemZoneClock : Clock() {
    override fun getZone(): ZoneId = ZoneId.systemDefault()

    override fun withZone(zone: ZoneId): Clock = Clock.system(zone)

    override fun instant(): Instant = Instant.now()
}
