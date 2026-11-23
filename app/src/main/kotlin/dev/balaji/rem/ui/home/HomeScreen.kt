@file:OptIn(ExperimentalMaterial3Api::class)

package dev.balaji.rem.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.balaji.rem.R
import dev.balaji.rem.core.Alarm
import dev.balaji.rem.core.PlannedRing
import dev.balaji.rem.core.RepeatDays
import dev.balaji.rem.core.WakeExplanation
import dev.balaji.rem.di.containerViewModel
import dev.balaji.rem.ring.RingContract
import dev.balaji.rem.ui.components.CenteredContent
import dev.balaji.rem.ui.components.WarningBanner
import dev.balaji.rem.ui.components.WhileResumed
import dev.balaji.rem.ui.system.readSystemChecks
import dev.balaji.rem.ui.theme.RemTheme
import java.time.Instant
import java.time.LocalTime
import kotlin.time.Duration.Companion.seconds

private val TwoPaneMinWidth = 720.dp
private val SidePaneWidth = 380.dp
private val RefreshPeriod = 15.seconds

/**
 * Alarm list with toggles and computed ring times, the going-to-sleep button, session
 * state and warnings. Calls [onOpenOnboarding] on first run.
 */
@Composable
fun HomeScreen(
    onAddAlarm: () -> Unit,
    onEditAlarm: (alarmId: Long) -> Unit,
    onDuplicateAlarm: (alarmId: Long) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenReliability: () -> Unit,
    onOpenOnboarding: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = containerViewModel {
        HomeViewModel(
            alarms = it.alarmRepository,
            barcodes = it.barcodeRepository,
            settings = it.settingsRepository,
            sessions = it.sleepSessionRepository,
            scheduler = it.alarmScheduler,
            ringController = it.ringController,
            clock = it.clock,
        )
    }
    val state by viewModel.state.collectAsStateWithLifecycle()

    val context = LocalContext.current
    WhileResumed(RefreshPeriod) { viewModel.refresh(readSystemChecks(context)) }

    LaunchedEffect(state.onboardingNeeded) {
        if (state.onboardingNeeded) {
            viewModel.onOnboardingOpened()
            onOpenOnboarding()
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val deleted = state.recentlyDeleted
    val deletedMessage = stringResource(R.string.ui_home_alarm_deleted)
    val undoLabel = stringResource(R.string.ui_action_undo)
    LaunchedEffect(deleted) {
        if (deleted != null) {
            val result = snackbarHostState.showSnackbar(
                message = deletedMessage,
                actionLabel = undoLabel,
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) viewModel.undoDelete() else viewModel.onUndoExpired(deleted)
        }
    }

    HomeContent(
        state = state,
        snackbarHostState = snackbarHostState,
        onAddAlarm = onAddAlarm,
        onEditAlarm = onEditAlarm,
        onDuplicateAlarm = onDuplicateAlarm,
        onEnabledChange = viewModel::setEnabled,
        onDeleteAlarm = viewModel::delete,
        onGoToSleep = viewModel::goToSleep,
        onEndSleep = viewModel::endSleep,
        onOpenSettings = onOpenSettings,
        onOpenReliability = onOpenReliability,
        onOpenRinging = { context.startActivity(RingContract.activityIntent(context)) },
        modifier = modifier,
    )
}

@Composable
fun HomeContent(
    state: HomeUiState,
    snackbarHostState: SnackbarHostState,
    onAddAlarm: () -> Unit,
    onEditAlarm: (alarmId: Long) -> Unit,
    onDuplicateAlarm: (alarmId: Long) -> Unit,
    onEnabledChange: (alarmId: Long, enabled: Boolean) -> Unit,
    onDeleteAlarm: (alarmId: Long) -> Unit,
    onGoToSleep: () -> Unit,
    onEndSleep: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenReliability: () -> Unit,
    onOpenRinging: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_home)) },
                actions = {
                    IconButton(onClick = onOpenReliability) {
                        Icon(Icons.Filled.Security, contentDescription = stringResource(R.string.screen_reliability))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.screen_settings))
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddAlarm,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.ui_home_add_alarm)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        val header: @Composable () -> Unit = {
            HomeHeader(state, onGoToSleep, onEndSleep, onOpenReliability, onOpenRinging)
        }
        BoxWithConstraints(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            if (state.loading) {
                // Nothing is drawn until the stored alarms arrive, so the empty state does not flash.
            } else if (maxWidth >= TwoPaneMinWidth && state.hasHeader) {
                Row {
                    Column(
                        modifier = Modifier
                            .width(SidePaneWidth)
                            .verticalScroll(rememberScrollState())
                            .padding(start = 16.dp, top = 8.dp, bottom = 16.dp),
                    ) {
                        header()
                    }
                    AlarmList(state, null, onEditAlarm, onDuplicateAlarm, onEnabledChange, onDeleteAlarm, Modifier.weight(1f))
                }
            } else {
                CenteredContent {
                    AlarmList(state, header, onEditAlarm, onDuplicateAlarm, onEnabledChange, onDeleteAlarm)
                }
            }
        }
    }
}

/** False when the header would draw nothing, so a wide window does not get an empty side pane. */
private val HomeUiState.hasHeader: Boolean
    get() = ringing || reliabilityProblems > 0 || sleep != SleepState.Hidden

/** Warnings first, then the sleep control. Shared by the single column and the side pane. */
@Composable
private fun HomeHeader(
    state: HomeUiState,
    onGoToSleep: () -> Unit,
    onEndSleep: () -> Unit,
    onOpenReliability: () -> Unit,
    onOpenRinging: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.ringing) {
            WarningBanner(
                message = stringResource(R.string.ui_home_warning_ringing),
                actionLabel = stringResource(R.string.ui_home_warning_ringing_action),
                onAction = onOpenRinging,
            )
        }
        if (state.reliabilityProblems > 0) {
            WarningBanner(
                message = pluralStringResource(
                    R.plurals.ui_home_warning_reliability,
                    state.reliabilityProblems,
                    state.reliabilityProblems,
                ),
                actionLabel = stringResource(R.string.ui_home_warning_reliability_action),
                onAction = onOpenReliability,
            )
        }
        SleepCard(state.sleep, state.now, state.zone, onGoToSleep, onEndSleep)
    }
}

@Composable
private fun AlarmList(
    state: HomeUiState,
    header: (@Composable () -> Unit)?,
    onEditAlarm: (alarmId: Long) -> Unit,
    onDuplicateAlarm: (alarmId: Long) -> Unit,
    onEnabledChange: (alarmId: Long, enabled: Boolean) -> Unit,
    onDeleteAlarm: (alarmId: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        // The bottom padding keeps the last card clear of the floating button.
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (header != null) {
            item(key = "header") { header() }
        }
        if (state.alarms.isEmpty()) {
            item(key = "empty") { EmptyAlarms() }
        }
        items(state.alarms, key = { it.alarm.id }) { row ->
            AlarmCard(
                row = row,
                now = state.now,
                zone = state.zone,
                onClick = { onEditAlarm(row.alarm.id) },
                onEnabledChange = { enabled -> onEnabledChange(row.alarm.id, enabled) },
                onDuplicate = { onDuplicateAlarm(row.alarm.id) },
                onDelete = { onDeleteAlarm(row.alarm.id) },
            )
        }
    }
}

@Composable
private fun EmptyAlarms() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Alarm,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(text = stringResource(R.string.ui_home_empty_title), style = MaterialTheme.typography.titleLarge)
        Text(
            text = stringResource(R.string.ui_home_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

private fun previewState(): HomeUiState {
    val now = Instant.parse("2026-01-14T23:00:00Z")
    val ring = PlannedRing(
        alarmId = 1,
        ringAt = Instant.parse("2026-01-15T06:45:00Z"),
        deadline = Instant.parse("2026-01-15T07:00:00Z"),
        explanation = WakeExplanation.Cycles(cycles = 5, minutesBeforeDeadline = 15),
    )
    return HomeUiState(
        loading = false,
        alarms = listOf(
            AlarmRow(
                Alarm(id = 1, time = LocalTime.of(7, 0), label = "Work", repeatDays = RepeatDays.WEEKDAYS),
                ring,
                noBarcode = true,
            ),
            AlarmRow(Alarm(id = 2, time = LocalTime.of(9, 30), enabled = false), nextRing = null),
        ),
        sleep = SleepState.Active(startedAt = now, tonight = ring),
        reliabilityProblems = 1,
        now = now,
    )
}

@Composable
private fun HomePreviewContent(state: HomeUiState) {
    RemTheme {
        HomeContent(
            state = state,
            snackbarHostState = remember { SnackbarHostState() },
            onAddAlarm = {},
            onEditAlarm = {},
            onDuplicateAlarm = {},
            onEnabledChange = { _, _ -> },
            onDeleteAlarm = {},
            onGoToSleep = {},
            onEndSleep = {},
            onOpenSettings = {},
            onOpenReliability = {},
            onOpenRinging = {},
        )
    }
}

@Preview
@Composable
private fun HomePreview() = HomePreviewContent(previewState())

@Preview
@Composable
private fun HomeEmptyPreview() = HomePreviewContent(HomeUiState(loading = false))

@Preview(widthDp = 900, heightDp = 420)
@Composable
private fun HomeTwoPanePreview() = HomePreviewContent(previewState())
