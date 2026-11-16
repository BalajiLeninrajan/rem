package dev.balaji.rem.ui.alarm

import dev.balaji.rem.core.Alarm
import dev.balaji.rem.core.DeadlineReason
import dev.balaji.rem.core.RepeatDays
import dev.balaji.rem.core.WakeExplanation
import dev.balaji.rem.data.fake.InMemoryAlarmRepository
import dev.balaji.rem.data.fake.InMemorySettingsRepository
import dev.balaji.rem.data.fake.InMemorySleepSessionRepository
import dev.balaji.rem.ui.testing.MainDispatcherRule
import dev.balaji.rem.ui.testing.MutableClock
import dev.balaji.rem.ui.testing.keepCollecting
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

    private val alarms = InMemoryAlarmRepository()
    private val settings = InMemorySettingsRepository()
    private val sessions = InMemorySleepSessionRepository()

    private fun at(local: String): Instant = LocalDateTime.parse(local).atZone(zone).toInstant()

    private fun TestScope.editor(alarmId: Long = Alarm.NEW_ID): AlarmEditorViewModel =
        AlarmEditorViewModel(alarmId, alarms, settings, sessions, clock).also {
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
        editor.setHeadphonesOnly(true)
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
                headphonesOnly = true,
                vibrate = false,
                ringtoneUri = "content://media/alarm/7",
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
        val stored = Alarm(time = LocalTime.of(8, 15), label = "Old", repeatDays = RepeatDays.WEEKDAYS, enabled = false)
        val id = alarms.upsert(stored)
        val editor = editor(id)

        val loaded = editor.state.value
        assertFalse(loaded.isNew)
        assertEquals(LocalTime.of(8, 15), loaded.time)
        assertEquals("Old", loaded.label)
        assertEquals(RepeatDays.WEEKDAYS, loaded.repeatDays)

        editor.setLabel("New")
        editor.save()
        advanceUntilIdle()

        assertEquals(listOf(stored.copy(id = id, label = "New")), alarms.all())
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
}
