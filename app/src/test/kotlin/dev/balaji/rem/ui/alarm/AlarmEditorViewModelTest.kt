package dev.balaji.rem.ui.alarm

import dev.balaji.rem.core.Alarm
import dev.balaji.rem.core.Barcode
import dev.balaji.rem.core.BarcodeFormat
import dev.balaji.rem.core.DeadlineReason
import dev.balaji.rem.core.RepeatDays
import dev.balaji.rem.core.ScannedCode
import dev.balaji.rem.core.WakeExplanation
import dev.balaji.rem.data.fake.InMemoryAlarmRepository
import dev.balaji.rem.data.fake.InMemoryBarcodeRepository
import dev.balaji.rem.data.fake.InMemorySettingsRepository
import dev.balaji.rem.data.fake.InMemorySleepSessionRepository
import dev.balaji.rem.ui.barcodes.BarcodeScanViewModel
import dev.balaji.rem.ui.barcodes.ScanStage
import dev.balaji.rem.ui.testing.MainDispatcherRule
import dev.balaji.rem.ui.testing.MutableClock
import dev.balaji.rem.ui.testing.keepCollecting
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AlarmEditorViewModelTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val zone = ZoneId.of("America/Toronto")

    // A Wednesday.
    private val clock = MutableClock(at("2026-01-14T23:00"), zone)

    private val barcodes = InMemoryBarcodeRepository()
    private val alarms = InMemoryAlarmRepository(barcodes = barcodes)
    private val settings = InMemorySettingsRepository()
    private val sessions = InMemorySleepSessionRepository()

    private val toothpaste = Barcode(label = "Toothpaste", format = BarcodeFormat.EAN_13, value = "4006381333931")
    private val cereal = Barcode(label = "Cereal", format = BarcodeFormat.EAN_13, value = "0036000291452")

    private fun at(local: String): Instant = LocalDateTime.parse(local).atZone(zone).toInstant()

    private fun TestScope.editor(alarmId: Long = Alarm.NEW_ID, copyFromId: Long = Alarm.NEW_ID): AlarmEditorViewModel =
        AlarmEditorViewModel(alarmId, alarms, barcodes, settings, sessions, clock, copyFromId).also {
            keepCollecting(it.state)
            advanceUntilIdle()
        }

    @Test
    fun `a new alarm starts from defaults and is saved switched on with a trimmed label`() = runTest {
        val editor = editor()
        val initial = editor.state.value
        assertFalse(initial.loading)
        assertTrue(initial.isNew)
        assertEquals(AlarmEditorViewModel.DEFAULT_TIME, initial.time)

        editor.setTime(LocalTime.of(6, 30, 59))
        editor.setLabel("  Gym  ")
        editor.toggleDay(DayOfWeek.MONDAY)
        editor.toggleDay(DayOfWeek.TUESDAY)
        editor.toggleDay(DayOfWeek.TUESDAY)
        editor.setMinRingVolumePercent(40)
        editor.setVolumeRampSeconds(0)
        editor.setVibrate(false)
        editor.setRingtone("content://media/alarm/7")
        editor.save()
        advanceUntilIdle()

        val saved = alarms.all().single()
        assertEquals(
            Alarm(
                id = saved.id,
                time = LocalTime.of(6, 30),
                label = "Gym",
                repeatDays = setOf(DayOfWeek.MONDAY),
                enabled = true,
                vibrate = false,
                ringtoneUri = "content://media/alarm/7",
                minRingVolumePercent = 40,
                volumeRampSeconds = 0,
            ),
            saved,
        )
        assertTrue(saved.id > Alarm.NEW_ID)
        assertTrue(editor.state.value.finished)
    }

    @Test
    fun `a label over the limit blocks saving`() = runTest {
        val editor = editor()

        editor.setLabel("x".repeat(AlarmEditorViewModel.MAX_LABEL_LENGTH + 1))
        advanceUntilIdle()
        assertTrue(editor.state.value.labelTooLong)
        assertFalse(editor.state.value.canSave)

        editor.save()
        advanceUntilIdle()
        assertTrue(alarms.all().isEmpty())
        assertFalse(editor.state.value.finished)

        // Surrounding spaces do not count, they are trimmed on save.
        editor.setLabel(" " + "x".repeat(AlarmEditorViewModel.MAX_LABEL_LENGTH) + " ")
        advanceUntilIdle()
        assertTrue(editor.state.value.canSave)
    }

    @Test
    fun `an existing alarm is loaded and a label edit keeps it switched off`() = runTest {
        val stored = Alarm(
            time = LocalTime.of(8, 15),
            label = "Old",
            repeatDays = RepeatDays.WEEKDAYS,
            enabled = false,
            minRingVolumePercent = 25,
            volumeRampSeconds = 120,
        )
        val id = alarms.upsert(stored)
        val editor = editor(id)

        val loaded = editor.state.value
        assertFalse(loaded.isNew)
        assertEquals(LocalTime.of(8, 15), loaded.time)
        assertEquals("Old", loaded.label)
        assertEquals(RepeatDays.WEEKDAYS, loaded.repeatDays)
        assertEquals(25, loaded.minRingVolumePercent)
        assertEquals(120, loaded.volumeRampSeconds)

        editor.setLabel("New")
        editor.save()
        advanceUntilIdle()

        assertEquals(listOf(stored.copy(id = id, label = "New")), alarms.all())
    }

    @Test
    fun `a switched off alarm says it stays off until the time or the days change`() = runTest {
        val id = alarms.upsert(Alarm(time = LocalTime.of(8, 15), enabled = false))
        val editor = editor(id)
        assertTrue(editor.state.value.staysOff)

        editor.setLabel("New")
        editor.setTime(LocalTime.of(8, 15))
        advanceUntilIdle()
        assertTrue(editor.state.value.staysOff)

        editor.setTime(LocalTime.of(8, 30))
        advanceUntilIdle()
        assertFalse(editor.state.value.staysOff)

        editor.setTime(LocalTime.of(8, 15))
        editor.toggleDay(DayOfWeek.MONDAY)
        advanceUntilIdle()
        assertFalse(editor.state.value.staysOff)
    }

    @Test
    fun `an alarm that is on, and a new one, never say they stay off`() = runTest {
        assertFalse(editor().state.value.staysOff)
        assertFalse(editor(alarms.upsert(Alarm(time = LocalTime.of(8, 15)))).state.value.staysOff)
    }

    @Test
    fun `changing the time of a switched off alarm switches it on`() = runTest {
        val id = alarms.upsert(Alarm(time = LocalTime.of(8, 15), enabled = false))
        val editor = editor(id)

        editor.setTime(LocalTime.of(8, 30))
        editor.save()
        advanceUntilIdle()

        assertEquals(Alarm(id = id, time = LocalTime.of(8, 30), enabled = true), alarms.get(id))
    }

    @Test
    fun `an unknown id is treated as a new alarm`() = runTest {
        val editor = editor(alarmId = 42)

        assertTrue(editor.state.value.isNew)
        assertFalse(editor.state.value.loading)
    }

    @Test
    fun `delete removes the stored alarm and closes`() = runTest {
        val id = alarms.upsert(Alarm(time = LocalTime.of(8, 15)))
        val editor = editor(id)

        editor.delete()
        advanceUntilIdle()

        assertNull(alarms.get(id))
        assertTrue(editor.state.value.finished)
    }

    @Test
    fun `without a session the preview is the next deadline of the draft`() = runTest {
        val editor = editor()

        val once = editor.state.value.preview!!
        assertEquals(at("2026-01-15T07:00"), once.ringAt)
        assertEquals(WakeExplanation.AtDeadline(DeadlineReason.NO_SESSION), once.explanation)
        assertFalse(once.sessionActive)

        editor.toggleDay(DayOfWeek.FRIDAY)
        advanceUntilIdle()
        assertEquals(at("2026-01-16T07:00"), editor.state.value.preview?.deadline)
    }

    @Test
    fun `with a session the preview follows the cycles, the settings and the clock`() = runTest {
        sessions.start(clock.instant())
        val editor = editor()

        val preview = editor.state.value.preview!!
        assertTrue(preview.sessionActive)
        assertEquals(at("2026-01-15T06:45"), preview.ringAt)
        assertEquals(at("2026-01-15T07:00"), preview.deadline)
        assertEquals(WakeExplanation.Cycles(cycles = 5, minutesBeforeDeadline = 15), preview.explanation)

        settings.update { it.copy(fallAsleepMinutes = 30) }
        advanceUntilIdle()
        assertEquals(WakeExplanation.Cycles(cycles = 5, minutesBeforeDeadline = 0), editor.state.value.preview?.explanation)

        // Past the deadline the draft's next occurrence is a day later, outside the session.
        clock.now = at("2026-01-15T07:30")
        editor.refresh()
        advanceUntilIdle()
        val next = editor.state.value.preview!!
        assertEquals(at("2026-01-16T07:00"), next.ringAt)
        assertEquals(WakeExplanation.AtDeadline(DeadlineReason.OUTSIDE_SESSION), next.explanation)
    }

    @Test
    fun `a new alarm keeps its barcodes until it is saved and then stores them with it`() = runTest {
        val editor = editor()

        editor.addBarcode(toothpaste)
        editor.addBarcode(cereal)
        // The scanner checks first, but a second add of the same key must not make a second row.
        editor.addBarcode(toothpaste.copy(label = "Again"))
        advanceUntilIdle()
        assertEquals(listOf(toothpaste, cereal), editor.state.value.barcodes)
        assertEquals(listOf(toothpaste, cereal), editor.currentBarcodes())
        assertTrue("nothing is stored before save", alarms.all().isEmpty())

        editor.removeBarcode(cereal)
        advanceUntilIdle()
        assertEquals(listOf(toothpaste), editor.state.value.barcodes)

        editor.save()
        advanceUntilIdle()

        val id = alarms.all().single().id
        assertEquals(listOf("Toothpaste"), barcodes.all(id).map { it.label })
    }

    @Test
    fun `leaving a new alarm without saving stores none of its barcodes`() = runTest {
        val editor = editor()
        editor.addBarcode(toothpaste)
        advanceUntilIdle()

        assertTrue(alarms.all().isEmpty())
        assertTrue(barcodes.barcodesByAlarm.first().isEmpty())
    }

    @Test
    fun `a stored alarm shows its own barcodes and adds and deletes them at once`() = runTest {
        val id = alarms.upsertWithBarcodes(Alarm(time = LocalTime.of(8, 15)), listOf(toothpaste))
        val other = alarms.upsertWithBarcodes(Alarm(time = LocalTime.of(9, 0)), listOf(toothpaste, cereal))
        val editor = editor(id)
        assertEquals(listOf("Toothpaste"), editor.state.value.barcodes.map { it.label })

        editor.addBarcode(cereal)
        advanceUntilIdle()
        assertEquals(listOf("Toothpaste", "Cereal"), barcodes.all(id).map { it.label })
        assertEquals(barcodes.all(id), editor.state.value.barcodes)

        editor.removeBarcode(editor.state.value.barcodes.first())
        advanceUntilIdle()
        assertEquals(listOf("Cereal"), barcodes.all(id).map { it.label })
        assertEquals(listOf("Cereal"), editor.state.value.barcodes.map { it.label })

        assertEquals("the other alarm keeps its own", listOf("Toothpaste", "Cereal"), barcodes.all(other).map { it.label })
    }

    @Test
    fun `renaming trims the name, ignores a blank one and works before and after the alarm is stored`() = runTest {
        val editor = editor()
        editor.addBarcode(toothpaste)
        editor.renameBarcode(toothpaste, "  Paste ")
        editor.renameBarcode(toothpaste, "   ")
        advanceUntilIdle()
        assertEquals(listOf("Paste"), editor.state.value.barcodes.map { it.label })

        val id = alarms.upsertWithBarcodes(Alarm(time = LocalTime.of(8, 15)), listOf(cereal))
        val stored = editor(id)
        stored.renameBarcode(stored.state.value.barcodes.single(), "Oats")
        advanceUntilIdle()
        assertEquals(listOf("Oats"), barcodes.all(id).map { it.label })
    }

    @Test
    fun `the scanner adds to the alarm in the editor and rejects what it already has`() = runTest {
        val editor = editor()
        editor.addBarcode(toothpaste)
        advanceUntilIdle()

        val duplicate = BarcodeScanViewModel(testOnly = false, editor)
        duplicate.onScanned(ScannedCode(BarcodeFormat.EAN_13, toothpaste.value))
        advanceUntilIdle()
        assertEquals(ScanStage.Duplicate(toothpaste), duplicate.state.value.stage)

        val scan = BarcodeScanViewModel(testOnly = false, editor)
        scan.onScanned(ScannedCode(BarcodeFormat.UPC_A, "036000291452"))
        advanceUntilIdle()
        scan.setLabel("Cereal")
        scan.save()
        advanceUntilIdle()
        assertEquals(ScanStage.Saved, scan.state.value.stage)
        assertEquals(listOf(toothpaste, cereal), editor.state.value.barcodes)

        val test = BarcodeScanViewModel(testOnly = true, editor)
        test.onScanned(ScannedCode(BarcodeFormat.EAN_13, cereal.value))
        advanceUntilIdle()
        assertEquals(cereal, (test.state.value.stage as ScanStage.Tested).match)
    }

    @Test
    fun `a duplicate starts as a new alarm with the source's settings and copies of its barcodes`() = runTest {
        val source = Alarm(
            time = LocalTime.of(5, 45),
            label = "Gym",
            repeatDays = setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY),
            enabled = false,
            vibrate = false,
            ringtoneUri = "content://media/alarm/7",
            minRingVolumePercent = 40,
            volumeRampSeconds = 90,
        )
        val sourceId = alarms.upsertWithBarcodes(source, listOf(toothpaste, cereal))
        val storedBarcodes = barcodes.all(sourceId)

        val editor = editor(copyFromId = sourceId)

        val copy = editor.state.value
        assertFalse(copy.loading)
        assertTrue(copy.isNew)
        assertEquals(LocalTime.of(5, 45), copy.time)
        assertEquals("Gym", copy.label)
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), copy.repeatDays)
        assertFalse(copy.vibrate)
        assertEquals("content://media/alarm/7", copy.ringtoneUri)
        assertEquals(40, copy.minRingVolumePercent)
        assertEquals(90, copy.volumeRampSeconds)
        assertFalse("a new alarm is switched on when saved", copy.staysOff)
        assertEquals("the barcodes wait unsaved", listOf(toothpaste, cereal), copy.barcodes)
        assertEquals(listOf(toothpaste, cereal), editor.currentBarcodes())

        assertEquals("nothing is written before save", listOf(source.copy(id = sourceId)), alarms.all())
        assertEquals(mapOf(sourceId to storedBarcodes), barcodes.barcodesByAlarm.first())
    }

    @Test
    fun `saving a duplicate stores a second alarm with its own barcodes and leaves the source alone`() = runTest {
        val source = Alarm(time = LocalTime.of(5, 45), label = "Gym", enabled = false, minRingVolumePercent = 40)
        val sourceId = alarms.upsertWithBarcodes(source, listOf(toothpaste, cereal))
        val sourceBarcodes = barcodes.all(sourceId)
        val editor = editor(copyFromId = sourceId)

        editor.setLabel("Gym again")
        editor.removeBarcode(cereal)
        editor.save()
        advanceUntilIdle()

        assertTrue(editor.state.value.finished)
        val copy = alarms.all().single { it.id != sourceId }
        assertEquals(
            source.copy(id = copy.id, label = "Gym again", enabled = true),
            copy,
        )
        val copied = barcodes.all(copy.id)
        assertEquals(listOf(toothpaste.key), copied.map { it.key })
        assertTrue("the copies are new rows", copied.none { row -> sourceBarcodes.any { it.id == row.id } })
        assertEquals(source.copy(id = sourceId), alarms.get(sourceId))
        assertEquals(sourceBarcodes, barcodes.all(sourceId))
    }

    @Test
    fun `a duplicate of an alarm without barcodes or of a missing alarm starts empty`() = runTest {
        val bare = alarms.upsert(Alarm(time = LocalTime.of(6, 0), label = "Bare"))
        val copy = editor(copyFromId = bare).state.value
        assertTrue(copy.isNew)
        assertEquals("Bare", copy.label)
        assertTrue(copy.barcodes.isEmpty())

        val missing = editor(copyFromId = 42).state.value
        assertTrue(missing.isNew)
        assertFalse(missing.loading)
        assertEquals(AlarmEditorViewModel.DEFAULT_TIME, missing.time)
        assertEquals("", missing.label)
    }

    @Test
    fun `a stored alarm is edited, not copied, when a copy source is given too`() = runTest {
        val id = alarms.upsert(Alarm(time = LocalTime.of(8, 15), label = "Mine"))
        val other = alarms.upsertWithBarcodes(Alarm(time = LocalTime.of(9, 0), label = "Other"), listOf(toothpaste))

        val editor = editor(alarmId = id, copyFromId = other)

        assertFalse(editor.state.value.isNew)
        assertEquals("Mine", editor.state.value.label)
        assertTrue(editor.state.value.barcodes.isEmpty())
    }

    @Test
    fun `the preview shows an accepted late option, also between the deadline and the late ring`() = runTest {
        // A session at midnight has boundaries 04:45 and 06:15. 06:15 is the late option for 06:00.
        clock.now = at("2026-01-15T00:00")
        val id = alarms.upsert(Alarm(time = LocalTime.of(6, 0)))
        sessions.start(clock.instant())
        sessions.acceptLate(id, at("2026-01-15T06:00"))
        val editor = editor(id)

        val preview = editor.state.value.preview!!
        assertEquals(at("2026-01-15T06:15"), preview.ringAt)
        assertEquals(WakeExplanation.LateCycles(cycles = 4, minutesAfterDeadline = 15), preview.explanation)

        clock.now = at("2026-01-15T06:05")
        editor.refresh()
        advanceUntilIdle()
        assertEquals(at("2026-01-15T06:15"), editor.state.value.preview?.ringAt)
        assertEquals(at("2026-01-15T06:00"), editor.state.value.preview?.deadline)
    }

    @Test
    fun `an accepted late option does not carry over to a new time in the draft or after saving`() = runTest {
        clock.now = at("2026-01-15T00:00")
        val id = alarms.upsert(Alarm(time = LocalTime.of(6, 0)))
        sessions.start(clock.instant())
        sessions.acceptLate(id, at("2026-01-15T06:00"))
        val editor = editor(id)

        // 06:15 is 25 minutes after 05:50, inside the late window, but the consent was for 06:00.
        editor.setTime(LocalTime.of(5, 50))
        advanceUntilIdle()

        assertEquals(at("2026-01-15T05:50"), editor.state.value.preview?.ringAt)
        assertTrue(editor.state.value.preview?.explanation is WakeExplanation.AtDeadline)
    }
}
