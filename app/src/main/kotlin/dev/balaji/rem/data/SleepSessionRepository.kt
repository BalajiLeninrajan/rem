package dev.balaji.rem.data

import dev.balaji.rem.core.SleepSession
import java.time.Instant
import kotlinx.coroutines.flow.Flow

/** The single active sleep session, in device-protected storage. */
interface SleepSessionRepository {
    /** Null when no session is active. */
    val session: Flow<SleepSession?>

    suspend fun current(): SleepSession?

    /** Starts a session at [startedAt], replacing any session that was active. */
    suspend fun start(startedAt: Instant): SleepSession

    /** Ends the session. Does nothing when none is active. */
    suspend fun end()
}
