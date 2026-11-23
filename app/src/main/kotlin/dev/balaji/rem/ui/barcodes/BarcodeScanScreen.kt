package dev.balaji.rem.ui.barcodes

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.balaji.rem.R
import dev.balaji.rem.core.Barcode
import dev.balaji.rem.core.BarcodeFormat
import dev.balaji.rem.core.BarcodeKey
import dev.balaji.rem.scan.BarcodeScanner
import dev.balaji.rem.scan.ScannedBarcode
import dev.balaji.rem.ui.components.ScreenScaffold
import dev.balaji.rem.ui.components.WhileResumed
import dev.balaji.rem.ui.system.SystemSettings
import dev.balaji.rem.ui.system.hasPermission
import dev.balaji.rem.ui.theme.RemTheme
import kotlin.time.Duration.Companion.seconds

private val PermissionRecheckPeriod = 2.seconds

const val MAX_BARCODE_LABEL_LENGTH = 40

/**
 * Scans one barcode for the alarm behind [target]. Adds it to the alarm, or with [testOnly]
 * only says whether the alarm has it.
 */
@Composable
fun BarcodeScanScreen(
    testOnly: Boolean,
    target: BarcodeTarget,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scan = viewModel { BarcodeScanViewModel(testOnly, target) }
    val state by scan.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.stage) {
        if (state.stage == ScanStage.Saved) onDone()
    }

    BarcodeScanContent(
        state = state,
        onScanned = { scan.onScanned(it.toScannedCode()) },
        onCameraError = scan::onCameraError,
        onLabelChange = scan::setLabel,
        onSave = scan::save,
        onScanAgain = scan::scanAgain,
        onDone = onDone,
        modifier = modifier,
    )
}

@Composable
fun BarcodeScanContent(
    state: BarcodeScanUiState,
    onScanned: (ScannedBarcode) -> Unit,
    onCameraError: () -> Unit,
    onLabelChange: (String) -> Unit,
    onSave: () -> Unit,
    onScanAgain: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var torchOn by remember { mutableStateOf(false) }
    var torchAvailable by remember { mutableStateOf(false) }
    val scanning = state.stage == ScanStage.Scanning

    ScreenScaffold(
        title = stringResource(if (state.testOnly) R.string.ui_barcodes_test_scan else R.string.screen_barcode_scan),
        onBack = onDone,
        modifier = modifier,
        actions = {
            if (scanning && torchAvailable) {
                IconButton(onClick = { torchOn = !torchOn }) {
                    Icon(
                        imageVector = if (torchOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                        contentDescription = stringResource(
                            if (torchOn) R.string.ui_scan_torch_off else R.string.ui_scan_torch_on,
                        ),
                    )
                }
            }
        },
    ) {
        when (val stage = state.stage) {
            ScanStage.Scanning -> ScannerPane(
                cameraFailed = state.cameraFailed,
                torchOn = torchOn,
                onScanned = onScanned,
                onCameraError = onCameraError,
                onTorchAvailable = { torchAvailable = it },
                onRetry = onScanAgain,
            )
            is ScanStage.Captured -> CapturedPane(stage, onLabelChange, onSave, onScanAgain)
            is ScanStage.Duplicate -> ResultPane(
                icon = Icons.Filled.Info,
                title = stringResource(R.string.ui_scan_duplicate_title, stage.existing.label),
                body = barcodeDescription(stage.existing.format, stage.existing.value),
            ) {
                ResultButtons(stringResource(R.string.ui_scan_another), onScanAgain, stringResource(R.string.ui_action_done), onDone)
            }
            is ScanStage.Tested -> {
                val match = stage.match
                ResultPane(
                    icon = if (match != null) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                    title = if (match != null) {
                        stringResource(R.string.ui_scan_test_match, match.label)
                    } else {
                        stringResource(R.string.ui_scan_test_no_match)
                    },
                    body = barcodeDescription(stage.key.format, stage.key.value),
                ) {
                    ResultButtons(stringResource(R.string.ui_scan_again), onScanAgain, stringResource(R.string.ui_action_done), onDone)
                }
            }
            ScanStage.Saved -> Unit
        }
    }
}

@Composable
private fun ScannerPane(
    cameraFailed: Boolean,
    torchOn: Boolean,
    onScanned: (ScannedBarcode) -> Unit,
    onCameraError: () -> Unit,
    onTorchAvailable: (Boolean) -> Unit,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    var cameraGranted by remember { mutableStateOf(context.hasPermission(Manifest.permission.CAMERA)) }
    // The permission can also be granted in system settings while this screen is in the background.
    WhileResumed(PermissionRecheckPeriod) { cameraGranted = context.hasPermission(Manifest.permission.CAMERA) }
    val permissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraGranted = granted
    }

    when {
        !cameraGranted -> ResultPane(
            icon = Icons.Filled.Warning,
            title = stringResource(R.string.ui_barcodes_camera_rationale_title),
            body = stringResource(R.string.ui_barcodes_camera_rationale),
        ) {
            ResultButtons(
                secondaryLabel = stringResource(R.string.ui_action_open_settings),
                onSecondary = { SystemSettings.openAppDetails(context) },
                primaryLabel = stringResource(R.string.ui_scan_allow_camera),
                onPrimary = { permissionRequest.launch(Manifest.permission.CAMERA) },
            )
        }
        cameraFailed -> ResultPane(
            icon = Icons.Filled.Warning,
            title = stringResource(R.string.ui_scan_camera_failed_title),
            body = stringResource(R.string.ui_scan_camera_failed),
        ) {
            Button(onClick = onRetry) { Text(stringResource(R.string.ui_action_retry)) }
        }
        else -> Column(modifier = Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            BarcodeScanner(
                onBarcode = onScanned,
                modifier = Modifier.weight(1f).fillMaxWidth().clip(MaterialTheme.shapes.large),
                torchEnabled = torchOn,
                onCameraError = { onCameraError() },
                onTorchAvailable = onTorchAvailable,
            )
            Text(
                text = stringResource(R.string.ui_scan_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

@Composable
private fun CapturedPane(
    stage: ScanStage.Captured,
    onLabelChange: (String) -> Unit,
    onSave: () -> Unit,
    onScanAgain: () -> Unit,
) {
    ResultPane(
        icon = Icons.Filled.CheckCircle,
        title = stringResource(R.string.ui_scan_captured_title),
        body = barcodeDescription(stage.key.format, stage.key.value),
    ) {
        OutlinedTextField(
            value = stage.label,
            onValueChange = { if (it.length <= MAX_BARCODE_LABEL_LENGTH) onLabelChange(it) },
            label = { Text(stringResource(R.string.ui_barcodes_label)) },
            placeholder = { Text(stringResource(R.string.ui_scan_label_placeholder)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onScanAgain) { Text(stringResource(R.string.ui_scan_again)) }
            Button(onClick = onSave, enabled = stage.canSave) { Text(stringResource(R.string.ui_action_save)) }
        }
    }
}

/** A centered icon, title and body with the actions for that result under them. */
@Composable
private fun ResultPane(
    icon: ImageVector,
    title: String,
    body: String,
    actions: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(text = title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        actions()
    }
}

@Composable
private fun ResultButtons(secondaryLabel: String, onSecondary: () -> Unit, primaryLabel: String, onPrimary: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onSecondary) { Text(secondaryLabel) }
        Button(onClick = onPrimary) { Text(primaryLabel) }
    }
}

@Composable
private fun ScanPreviewContent(stage: ScanStage, testOnly: Boolean = false) {
    RemTheme {
        BarcodeScanContent(
            state = BarcodeScanUiState(testOnly = testOnly, stage = stage),
            onScanned = {},
            onCameraError = {},
            onLabelChange = {},
            onSave = {},
            onScanAgain = {},
            onDone = {},
        )
    }
}

@Preview
@Composable
private fun ScanCapturedPreview() =
    ScanPreviewContent(ScanStage.Captured(BarcodeKey(BarcodeFormat.EAN_13, "4006381333931"), label = "Toothpaste"))

@Preview
@Composable
private fun ScanTestedPreview() = ScanPreviewContent(
    stage = ScanStage.Tested(
        key = BarcodeKey(BarcodeFormat.EAN_13, "4006381333931"),
        match = Barcode(1, "Toothpaste", BarcodeFormat.EAN_13, "4006381333931"),
    ),
    testOnly = true,
)
