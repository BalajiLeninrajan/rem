package dev.balaji.rem.data

import dev.balaji.rem.core.SleepSession
import java.time.Instant
import kotlinx.coroutines.flow.Flow

/** The single active sleep session, in device-protected storage. */
interface SleepSessionRepository {
    /** Null when no session is active. */
    val session: Flow<SleepSession?>

    suspend fun current(): SleepSession?

    /** Starts a session at [startedAt] with no late option accepted, replacing any session that was active. */
    suspend fun start(startedAt: Instant): SleepSession

    /**
     * Accepts the late option of [alarmId] in the active session (see `LateOption` in :core),
     * so its covered occurrence rings at the first cycle boundary after its deadline. Does
     * nothing when no session is active.
     */
    suspend fun acceptLate(alarmId: Long)

    /** Takes back [acceptLate] for [alarmId], so it rings at its deadline again. Does nothing when not accepted. */
    suspend fun revokeLate(alarmId: Long)

    /** Ends the session and forgets its accepted late options. Does nothing when none is active. */
    suspend fun end()
}
