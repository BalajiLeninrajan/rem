package dev.balaji.rem.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.balaji.rem.R
import dev.balaji.rem.di.containerViewModel
import dev.balaji.rem.ui.theme.RemTheme

private val OnboardingMaxWidth = 480.dp

/**
 * First run: explains why notifications are needed and requests the permission, then
 * explains barcodes and points to adding the first alarm, where they are added.
 *
 * @param onDone closes onboarding and returns to home.
 * @param onAddAlarm closes onboarding and opens the editor for a new alarm.
 */
@Composable
fun OnboardingScreen(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    onAddAlarm: () -> Unit = onDone,
) {
    val viewModel = containerViewModel { OnboardingViewModel(it.settingsRepository) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    val permissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.onNotificationsAnswered()
    }

    // Leaving with back counts as "not now", so the user is not sent here again on every launch.
    BackHandler(enabled = state.exit == null) { viewModel.finish(OnboardingExit.HOME) }
    LaunchedEffect(state.exit) {
        when (state.exit) {
            OnboardingExit.HOME -> onDone()
            OnboardingExit.ADD_ALARM -> onAddAlarm()
            null -> Unit
        }
    }

    OnboardingContent(
        step = state.step,
        onAllowNotifications = {
            // Below Android 13 notifications are on without a runtime permission.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                viewModel.onNotificationsAnswered()
            }
        },
        onAddAlarm = { viewModel.finish(OnboardingExit.ADD_ALARM) },
        onSkip = { viewModel.finish(OnboardingExit.HOME) },
        modifier = modifier,
    )
}

@Composable
fun OnboardingContent(
    step: OnboardingStep,
    onAllowNotifications: () -> Unit,
    onAddAlarm: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize()) {
        when (step) {
            OnboardingStep.NOTIFICATIONS -> OnboardingPage(
                icon = Icons.Filled.Notifications,
                title = stringResource(R.string.ui_onboarding_notifications_title),
                body = stringResource(R.string.ui_onboarding_notifications_body),
                primaryLabel = stringResource(R.string.ui_onboarding_notifications_allow),
                onPrimary = onAllowNotifications,
                onSkip = onSkip,
            )
            OnboardingStep.BARCODE -> OnboardingPage(
                icon = Icons.Filled.QrCodeScanner,
                title = stringResource(R.string.ui_onboarding_barcode_title),
                body = stringResource(R.string.ui_onboarding_barcode_body),
                primaryLabel = stringResource(R.string.ui_onboarding_add_alarm),
                onPrimary = onAddAlarm,
                onSkip = onSkip,
            )
        }
    }
}

@Composable
private fun OnboardingPage(
    icon: ImageVector,
    title: String,
    body: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    onSkip: () -> Unit,
) {
    // The column wraps its content, so the box centers it when it fits and it scrolls when it does not.
    Box(modifier = Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
        OnboardingPageBody(icon, title, body, primaryLabel, onPrimary, onSkip)
    }
}

@Composable
private fun OnboardingPageBody(
    icon: ImageVector,
    title: String,
    body: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    onSkip: () -> Unit,
) {
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = OnboardingMaxWidth),
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = OnboardingMaxWidth),
        )
        Button(onClick = onPrimary, modifier = Modifier.widthIn(max = OnboardingMaxWidth).fillMaxWidth()) {
            Text(primaryLabel)
        }
        TextButton(onClick = onSkip) { Text(stringResource(R.string.ui_onboarding_skip)) }
    }
}

@Preview
@Composable
private fun OnboardingNotificationsPreview() {
    RemTheme {
        OnboardingContent(OnboardingStep.NOTIFICATIONS, onAllowNotifications = {}, onAddAlarm = {}, onSkip = {})
    }
}

@Preview
@Composable
private fun OnboardingBarcodePreview() {
    RemTheme {
        OnboardingContent(OnboardingStep.BARCODE, onAllowNotifications = {}, onAddAlarm = {}, onSkip = {})
    }
}
