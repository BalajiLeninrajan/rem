package dev.balaji.rem.ui.reliability

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.balaji.rem.R
import dev.balaji.rem.di.containerViewModel
import dev.balaji.rem.ui.components.ScreenScaffold
import dev.balaji.rem.ui.components.WhileResumed
import dev.balaji.rem.ui.format.clockText
import dev.balaji.rem.ui.system.SystemChecks
import dev.balaji.rem.ui.system.SystemSettings
import dev.balaji.rem.ui.system.findActivity
import dev.balaji.rem.ui.system.hasPermission
import dev.balaji.rem.ui.system.readSystemChecks
import dev.balaji.rem.ui.theme.RemTheme
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

private val RefreshPeriod = 3.seconds

/** The reliability checks with a fix button each, and the test alarm button. */
@Composable
fun ReliabilityScreen(
    onOpenBarcodes: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = containerViewModel { ReliabilityViewModel(it.barcodeRepository, it.alarmScheduler, it.clock) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    WhileResumed(RefreshPeriod) { viewModel.refresh(readSystemChecks(context)) }

    // After a denial the system may refuse to show the dialog again. Then only system settings can grant it.
    fun dialogBlocked(permission: String): Boolean {
        val activity = context.findActivity() ?: return true
        return !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
    }
    val cameraRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted && dialogBlocked(Manifest.permission.CAMERA)) SystemSettings.openAppDetails(context)
        viewModel.refresh(readSystemChecks(context))
    }
    val notificationRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted && dialogBlocked(Manifest.permission.POST_NOTIFICATIONS)) SystemSettings.openNotifications(context)
        viewModel.refresh(readSystemChecks(context))
    }

    ReliabilityContent(
        state = state,
        manufacturer = Build.MANUFACTURER.orEmpty(),
        onFix = { id ->
            when (id) {
                CheckId.NOTIFICATIONS ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        !context.hasPermission(Manifest.permission.POST_NOTIFICATIONS)
                    ) {
                        notificationRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        SystemSettings.openNotifications(context)
                    }
                CheckId.FULL_SCREEN_INTENT -> SystemSettings.openFullScreenIntent(context)
                CheckId.EXACT_ALARMS -> SystemSettings.openExactAlarms(context)
                CheckId.DO_NOT_DISTURB -> SystemSettings.openDoNotDisturb(context)
                CheckId.BACKGROUND -> SystemSettings.openAppDetails(context)
                CheckId.CAMERA -> cameraRequest.launch(Manifest.permission.CAMERA)
                CheckId.BARCODE -> onOpenBarcodes()
            }
        },
        onTestAlarm = viewModel::scheduleTestAlarm,
        onOpenWebPage = { url -> SystemSettings.openWebPage(context, url) },
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
fun ReliabilityContent(
    state: ReliabilityUiState,
    manufacturer: String,
    onFix: (CheckId) -> Unit,
    onTestAlarm: () -> Unit,
    onOpenWebPage: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenScaffold(title = stringResource(R.string.screen_reliability), onBack = onBack, modifier = modifier) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.ui_reliability_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.checks.forEach { check -> CheckRow(check, onFix = { onFix(check.id) }) }
            TestAlarmCard(state, onTestAlarm)
            ManufacturerCard(manufacturer, onOpenWebPage)
        }
    }
}

@Composable
private fun CheckRow(check: ReliabilityCheck, onFix: () -> Unit) {
    val text = check.id.text()
    val title = stringResource(text.title)
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (check.status == CheckStatus.PROBLEM) colors.errorContainer else colors.surfaceContainer,
        ),
    ) {
        Row(modifier = Modifier.padding(16.dp)) {
            Icon(
                imageVector = when (check.status) {
                    CheckStatus.OK -> Icons.Filled.CheckCircle
                    CheckStatus.PROBLEM -> Icons.Filled.Error
                    CheckStatus.UNKNOWN -> Icons.Filled.Info
                },
                contentDescription = stringResource(
                    when (check.status) {
                        CheckStatus.OK -> R.string.ui_check_status_ok
                        CheckStatus.PROBLEM -> R.string.ui_check_status_problem
                        CheckStatus.UNKNOWN -> R.string.ui_check_status_unknown
                    },
                ),
                tint = when (check.status) {
                    CheckStatus.OK -> colors.primary
                    CheckStatus.PROBLEM -> colors.error
                    CheckStatus.UNKNOWN -> colors.onSurfaceVariant
                },
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = stringResource(
                        when (check.status) {
                            CheckStatus.OK -> text.ok
                            CheckStatus.PROBLEM -> text.problem
                            CheckStatus.UNKNOWN -> text.unknown
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (check.status != CheckStatus.OK) {
                    val fixLabel = stringResource(text.fix)
                    val fixDescription = stringResource(R.string.ui_check_fix_description, fixLabel, title)
                    FilledTonalButton(
                        onClick = onFix,
                        modifier = Modifier.padding(top = 8.dp).semantics { contentDescription = fixDescription },
                    ) {
                        Text(fixLabel)
                    }
                }
            }
        }
    }
}

@Composable
private fun TestAlarmCard(state: ReliabilityUiState, onTestAlarm: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = stringResource(R.string.ui_test_alarm_title), style = MaterialTheme.typography.titleMedium)
            val testAt = state.testAlarmAt
            Text(
                text = if (testAt == null) {
                    stringResource(R.string.ui_test_alarm_body)
                } else {
                    stringResource(R.string.ui_test_alarm_scheduled, clockText(testAt, state.zone))
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onTestAlarm, enabled = testAt == null) {
                Icon(imageVector = Icons.Filled.Alarm, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.ui_test_alarm_button))
            }
        }
    }
}

/** Brand-specific battery advice when the brand is known, the general pointer to dontkillmyapp.com otherwise. */
@Composable
private fun ManufacturerCard(manufacturer: String, onOpenWebPage: (String) -> Unit) {
    val advice = ManufacturerAdvice.forManufacturer(manufacturer)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp)) {
            Text(text = stringResource(R.string.ui_oem_title), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(advice?.advice ?: R.string.ui_oem_generic),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            TextButton(onClick = { onOpenWebPage(advice?.url ?: ManufacturerAdvice.DONT_KILL_MY_APP_URL) }) {
                Text(stringResource(R.string.ui_oem_link))
            }
        }
    }
}

private class CheckText(
    @StringRes val title: Int,
    @StringRes val ok: Int,
    @StringRes val problem: Int,
    @StringRes val fix: Int = R.string.ui_action_open_settings,
    @StringRes val unknown: Int = problem,
)

private fun CheckId.text(): CheckText = when (this) {
    CheckId.NOTIFICATIONS -> CheckText(
        title = R.string.ui_check_notifications,
        ok = R.string.ui_check_notifications_ok,
        problem = R.string.ui_check_notifications_problem,
        fix = R.string.ui_check_fix_allow,
    )
    CheckId.FULL_SCREEN_INTENT -> CheckText(
        title = R.string.ui_check_full_screen,
        ok = R.string.ui_check_full_screen_ok,
        problem = R.string.ui_check_full_screen_problem,
    )
    CheckId.EXACT_ALARMS -> CheckText(
        title = R.string.ui_check_exact_alarms,
        ok = R.string.ui_check_exact_alarms_ok,
        problem = R.string.ui_check_exact_alarms_problem,
    )
    CheckId.DO_NOT_DISTURB -> CheckText(
        title = R.string.ui_check_dnd,
        ok = R.string.ui_check_dnd_ok,
        problem = R.string.ui_check_dnd_problem,
        unknown = R.string.ui_check_dnd_unknown,
    )
    CheckId.BACKGROUND -> CheckText(
        title = R.string.ui_check_background,
        ok = R.string.ui_check_background_ok,
        problem = R.string.ui_check_background_problem,
    )
    CheckId.CAMERA -> CheckText(
        title = R.string.ui_check_camera,
        ok = R.string.ui_check_camera_ok,
        problem = R.string.ui_check_camera_problem,
        fix = R.string.ui_check_fix_allow,
    )
    CheckId.BARCODE -> CheckText(
        title = R.string.ui_check_barcode,
        ok = R.string.ui_check_barcode_ok,
        problem = R.string.ui_check_barcode_problem,
        fix = R.string.ui_home_warning_no_barcode_action,
    )
}

@Preview
@Composable
private fun ReliabilityPreview() {
    RemTheme {
        ReliabilityContent(
            state = ReliabilityUiState(
                checks = reliabilityChecks(SystemChecks(fullScreenIntentAllowed = false), barcodeCount = 0),
                testAlarmAt = Instant.parse("2026-01-14T23:01:00Z"),
            ),
            manufacturer = "samsung",
            onFix = {},
            onTestAlarm = {},
            onOpenWebPage = {},
            onBack = {},
        )
    }
}
