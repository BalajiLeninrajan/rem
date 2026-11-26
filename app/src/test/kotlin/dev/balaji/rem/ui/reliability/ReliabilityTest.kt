package dev.balaji.rem.ui.reliability

import dev.balaji.rem.core.Alarm
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
import dev.balaji.rem.testing.MutableClock
import dev.balaji.rem.ui.testing.keepCollecting
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReliabilityTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    /** One alarm that is on and has a barcode. */
    private val covered = BarcodeCoverage(anyWithBarcode = true)

    /** One alarm that is on and has none. */
    private val uncovered = BarcodeCoverage(withoutBarcode = listOf(1))

    private fun statuses(system: SystemChecks, barcodes: BarcodeCoverage): Map<CheckId, CheckStatus> =
        reliabilityChecks(system, barcodes).associate { it.id to it.status }

    @Test
    fun `a healthy phone with a barcode passes every check`() {
        val checks = reliabilityChecks(SystemChecks(), covered)

        assertEquals(CheckId.entries, checks.map { it.id })
        assertTrue(checks.all { it.status == CheckStatus.OK })
        assertEquals(0, reliabilityProblemCount(SystemChecks(), covered))
    }

    @Test
    fun `each system value fails its own check`() {
        val broken = SystemChecks(
            notificationsEnabled = false,
            ringingChannelLoud = false,
            fullScreenIntentAllowed = false,
            exactAlarmsAllowed = false,
            doNotDisturb = DoNotDisturb.ALARMS_MUTED,
            backgroundRestricted = true,
            cameraGranted = false,
        )

        assertTrue(statuses(broken, uncovered).values.all { it == CheckStatus.PROBLEM })
        assertEquals(
            mapOf(CheckId.BACKGROUND to CheckStatus.PROBLEM),
            statuses(SystemChecks(backgroundRestricted = true), covered).filterValues { it != CheckStatus.OK },
        )
    }

    @Test
    fun `unreadable do not disturb is flagged without counting as a failure`() {
        val system = SystemChecks(doNotDisturb = DoNotDisturb.UNKNOWN)

        assertEquals(CheckStatus.UNKNOWN, statuses(system, covered)[CheckId.DO_NOT_DISTURB])
        assertEquals(0, reliabilityProblemCount(system, covered))
    }

    @Test
    fun `the home count leaves out the barcode, and the camera until an alarm has a barcode`() {
        val noCamera = SystemChecks(cameraGranted = false, exactAlarmsAllowed = false)

        assertEquals(1, reliabilityProblemCount(noCamera, uncovered))
        assertEquals(1, reliabilityProblemCount(noCamera, BarcodeCoverage()))
        assertEquals(2, reliabilityProblemCount(noCamera, covered))
        assertEquals(2, reliabilityProblemCount(noCamera, BarcodeCoverage(withoutBarcode = listOf(1), anyWithBarcode = true)))
    }

    @Test
    fun `coverage counts only alarms that are on, each with its own barcodes`() {
        val barcode = Barcode(label = "Toothpaste", format = BarcodeFormat.EAN_13, value = "4006381333931")
        val alarms = listOf(
            Alarm(id = 1, time = LocalTime.of(6, 0)),
            Alarm(id = 2, time = LocalTime.of(7, 0)),
            Alarm(id = 3, time = LocalTime.of(8, 0), enabled = false),
            Alarm(id = 4, time = LocalTime.of(9, 0)),
        )

        assertEquals(
            BarcodeCoverage(withoutBarcode = listOf(1, 4), anyWithBarcode = true),
            barcodeCoverage(alarms, mapOf(2L to listOf(barcode), 3L to emptyList<Barcode>())),
        )
        assertEquals(BarcodeCoverage(), barcodeCoverage(alarms.filterNot { it.enabled }, emptyMap()))
        assertEquals(
            CheckStatus.OK,
            statuses(SystemChecks(), barcodeCoverage(alarms.take(1), mapOf(1L to listOf(barcode))))[CheckId.BARCODE],
        )
    }

    @Test
    fun `the screen lists checks after the first read and follows each alarm's barcodes`() = runTest {
        val clock = MutableClock(Instant.parse("2026-01-14T23:00:00Z"), ZoneOffset.UTC)
        val barcodes = InMemoryBarcodeRepository()
        val alarms = InMemoryAlarmRepository(barcodes = barcodes)
        val viewModel = ReliabilityViewModel(alarms, barcodes, scheduler(clock), clock)
        keepCollecting(viewModel.state)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.checks.isEmpty())

        viewModel.refresh(SystemChecks())
        advanceUntilIdle()
        assertTrue("no alarm, nothing to stop", viewModel.state.value.checks.all { it.status == CheckStatus.OK })

        val first = alarms.upsert(Alarm(time = LocalTime.of(7, 0)))
        val second = alarms.upsert(Alarm(time = LocalTime.of(8, 0)))
        advanceUntilIdle()
        assertEquals(CheckStatus.PROBLEM, viewModel.state.value.checks.single { it.id == CheckId.BARCODE }.status)
        assertEquals(first, viewModel.state.value.alarmWithoutBarcode)

        barcodes.add(first, Barcode(label = "Toothpaste", format = BarcodeFormat.EAN_13, value = "4006381333931"))
        advanceUntilIdle()
        assertEquals(second, viewModel.state.value.alarmWithoutBarcode)

        alarms.setEnabled(second, false)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.checks.all { it.status == CheckStatus.OK })
        assertNull(viewModel.state.value.alarmWithoutBarcode)
    }

    @Test
    fun `the test alarm is announced until its time has passed`() = runTest {
        val clock = MutableClock(Instant.parse("2026-01-14T23:00:00Z"), ZoneOffset.UTC)
        val viewModel = ReliabilityViewModel(InMemoryAlarmRepository(), InMemoryBarcodeRepository(), scheduler(clock), clock)
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
    fun `a silenced ringing category fails its own check and counts on home`() {
        val silenced = SystemChecks(ringingChannelLoud = false)

        assertEquals(
            mapOf(CheckId.RINGING_CHANNEL to CheckStatus.PROBLEM),
            statuses(silenced, covered).filterValues { it != CheckStatus.OK },
        )
        assertEquals(1, reliabilityProblemCount(silenced, covered))
    }

    @Test
    fun `a test alarm that could not be set is reported and not announced`() = runTest {
        val clock = MutableClock(Instant.parse("2026-01-14T23:00:00Z"), ZoneOffset.UTC)
        val scheduler = scheduler(clock).apply { exactAlarmsAllowed = false }
        val viewModel = ReliabilityViewModel(InMemoryAlarmRepository(), InMemoryBarcodeRepository(), scheduler, clock)
        keepCollecting(viewModel.state)

        viewModel.scheduleTestAlarm()
        advanceUntilIdle()
        assertNull(viewModel.state.value.testAlarmAt)
        assertTrue(viewModel.state.value.testAlarmRefused)

        scheduler.exactAlarmsAllowed = true
        viewModel.scheduleTestAlarm()
        advanceUntilIdle()
        assertEquals(Instant.parse("2026-01-14T23:01:00Z"), viewModel.state.value.testAlarmAt)
        assertFalse(viewModel.state.value.testAlarmRefused)
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
