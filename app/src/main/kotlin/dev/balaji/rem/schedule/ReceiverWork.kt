package dev.balaji.rem.schedule

import android.content.BroadcastReceiver
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * How long a receiver keeps its broadcast open. The system allows about 10 seconds for a
 * foreground broadcast, which is what AlarmManager sends.
 */
internal val RECEIVER_WORK_LIMIT: Duration = Duration.ofSeconds(8)

/**
 * Runs [work] on [scope] and keeps the broadcast open with `goAsync()` until it is done,
 * for at most [limit]. The limit ends the hold on the broadcast, not the work: [scope]
 * is the application scope, so slow work goes on for as long as the process lives.
 */
internal fun BroadcastReceiver.launchWork(
    scope: CoroutineScope,
    limit: Duration = RECEIVER_WORK_LIMIT,
    work: suspend () -> Unit,
) {
    // Null when onReceive is called directly instead of by the system, as unit tests do.
    val pending: BroadcastReceiver.PendingResult? = goAsync()
    val job = scope.launch { work() }
    scope.launch {
        withTimeoutOrNull(limit.toMillis()) { job.join() }
        pending?.finish()
    }
}
