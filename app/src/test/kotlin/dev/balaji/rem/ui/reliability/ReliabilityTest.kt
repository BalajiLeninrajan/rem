package dev.balaji.rem.ui.reliability

import dev.balaji.rem.core.Barcode
import dev.balaji.rem.core.BarcodeFormat
import dev.balaji.rem.data.fake.InMemoryAlarmRepository
import dev.balaji.rem.data.fake.InMemoryBarcodeRepository
import dev.balaji.rem.data.fake.InMemorySettingsRepository
import dev.balaji.rem.data.fake.InMemorySleepSessionRepository
import dev.balaji.rem.ui.system.DoNotDisturb
import dev.balaji.rem.ui.system.SystemChecks
import dev.balaji.rem.ui.testing.FakeAlarmScheduler
import dev.balaji.rem.ui.testing.MainDispatcherRule
import dev.balaji.rem.ui.testing.MutableClock
import dev.balaji.rem.ui.testing.keepCollecting
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReliabilityTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private fun statuses(system: SystemChecks, barcodeCount: Int): Map<CheckId, CheckStatus> =
        reliabilityChecks(system, barcodeCount).associate { it.id to it.status }

    @Test
    fun `a healthy phone with a barcode passes every check`() {
        val checks = reliabilityChecks(SystemChecks(), barcodeCount = 1)

        assertEquals(CheckId.entries, checks.map { it.id })
        assertTrue(checks.all { it.status == CheckStatus.OK })
        assertEquals(0, reliabilityProblemCount(SystemChecks(), barcodeCount = 1))
    }

    @Test
    fun `each system value fails its own check`() {
        val broken = SystemChecks(
            notificationsEnabled = false,
            fullScreenIntentAllowed = false,
            exactAlarmsAllowed = false,
            doNotDisturb = DoNotDisturb.ALARMS_MUTED,
            backgroundRestricted = true,
            cameraGranted = false,
        )

        assertTrue(statuses(broken, barcodeCount = 0).values.all { it == CheckStatus.PROBLEM })
        assertEquals(
            mapOf(CheckId.BACKGROUND to CheckStatus.PROBLEM),
            statuses(SystemChecks(backgroundRestricted = true), barcodeCount = 1).filterValues { it != CheckStatus.OK },
        )
    }

    @Test
    fun `unreadable do not disturb is flagged without counting as a failure`() {
        val system = SystemChecks(doNotDisturb = DoNotDisturb.UNKNOWN)

        assertEquals(CheckStatus.UNKNOWN, statuses(system, barcodeCount = 1)[CheckId.DO_NOT_DISTURB])
        assertEquals(0, reliabilityProblemCount(system, barcodeCount = 1))
    }

    @Test
    fun `the home count leaves out the barcode, and the camera until there is a barcode`() {
        val noCamera = SystemChecks(cameraGranted = false, exactAlarmsAllowed = false)

        assertEquals(1, reliabilityProblemCount(noCamera, barcodeCount = 0))
        assertEquals(2, reliabilityProblemCount(noCamera, barcodeCount = 3))
    }

    @Test
    fun `the screen lists checks after the first read and follows the barcodes`() = runTest {
        val clock = MutableClock(Instant.parse("2026-01-14T23:00:00Z"), ZoneOffset.UTC)
        val barcodes = InMemoryBarcodeRepository()
        val viewModel = ReliabilityViewModel(barcodes, scheduler(clock), clock)
        keepCollecting(viewModel.state)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.checks.isEmpty())

        viewModel.refresh(SystemChecks())
        advanceUntilIdle()
        assertEquals(CheckStatus.PROBLEM, viewModel.state.value.checks.single { it.id == CheckId.BARCODE }.status)

        barcodes.add(Barcode(label = "Toothpaste", format = BarcodeFormat.EAN_13, value = "4006381333931"))
        advanceUntilIdle()
        assertTrue(viewModel.state.value.checks.all { it.status == CheckStatus.OK })
    }

    @Test
    fun `the test alarm is announced until its time has passed`() = runTest {
        val clock = MutableClock(Instant.parse("2026-01-14T23:00:00Z"), ZoneOffset.UTC)
        val viewModel = ReliabilityViewModel(InMemoryBarcodeRepository(), scheduler(clock), clock)
        keepCollecting(viewModel.state)

        viewModel.scheduleTestAlarm()
        advanceUntilIdle()
        assertEquals(Instant.parse("2026-01-14T23:01:00Z"), viewModel.state.value.testAlarmAt)

        clock.now = Instant.parse("2026-01-14T23:01:05Z")
        viewModel.refresh(SystemChecks())
        advanceUntilIdle()
        assertNull(viewModel.state.value.testAlarmAt)
    }

    @Test
    fun `manufacturer advice matches brands in any case and links to their page`() {
        assertEquals(ManufacturerAdvice.SAMSUNG, ManufacturerAdvice.forManufacturer("samsung"))
        assertEquals(ManufacturerAdvice.HUAWEI, ManufacturerAdvice.forManufacturer("HONOR"))
        assertEquals(ManufacturerAdvice.XIAOMI, ManufacturerAdvice.forManufacturer(" Redmi "))
        assertEquals("https://dontkillmyapp.com/xiaomi", ManufacturerAdvice.XIAOMI.url)
        assertNull(ManufacturerAdvice.forManufacturer("Google"))
        assertNull(ManufacturerAdvice.forManufacturer(""))
    }

    private fun scheduler(clock: MutableClock) = FakeAlarmScheduler(
        InMemoryAlarmRepository(),
        InMemorySleepSessionRepository(),
        InMemorySettingsRepository(),
        clock,
    )
}
