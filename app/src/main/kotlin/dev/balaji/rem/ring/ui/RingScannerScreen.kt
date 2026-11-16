package dev.balaji.rem.ring.ui

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.balaji.rem.R
import dev.balaji.rem.di.containerViewModel
import dev.balaji.rem.scan.BarcodeScanner

/** Scans a barcode to dismiss the ring. */
@Composable
fun RingScannerScreen(
    onOpenTypedFallback: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val viewModel = containerViewModel { RingScannerViewModel(it.ringController) }

    var hasPermission by remember { mutableStateOf(context.hasCameraPermission()) }
    var cameraFailed by remember { mutableStateOf(false) }
    var cameraAttempt by remember { mutableIntStateOf(0) }
    var torchOn by rememberSaveable { mutableStateOf(false) }
    var torchAvailable by remember { mutableStateOf(false) }

    val retry: () -> Unit = {
        hasPermission = context.hasCameraPermission()
        cameraFailed = false
        cameraAttempt++
    }
    val permissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
    }
    // The permission is normally granted when a barcode is registered. This covers a later revocation.
    LaunchedEffect(Unit) {
        if (!hasPermission) permissionRequest.launch(Manifest.permission.CAMERA)
    }
    // The permission dialog and the keyguard both pause this screen, so look again when it comes back.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hasPermission = context.hasCameraPermission()
    }

    val scanning = hasPermission && !cameraFailed
    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        if (scanning) {
            key(cameraAttempt) {
                BarcodeScanner(
                    onBarcode = viewModel::onBarcode,
                    modifier = Modifier.fillMaxSize(),
                    torchEnabled = torchOn,
                    onCameraError = { cameraFailed = true },
                    onTorchAvailable = { torchAvailable = it },
                )
            }
        }
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            if (scanning) {
                ScannerMessage(
                    text = viewModel.rejected?.let { stringResource(R.string.ring_scanner_rejected, it.rawValue.take(24)) }
                        ?: stringResource(R.string.ring_scanner_instruction),
                    isError = viewModel.rejected != null,
                )
            } else {
                ScannerProblem(
                    message = stringResource(
                        if (hasPermission) R.string.ring_scanner_camera_failed else R.string.ring_scanner_needs_permission,
                    ),
                    primaryLabel = stringResource(
                        if (hasPermission) R.string.ring_scanner_try_again else R.string.ring_scanner_allow_camera,
                    ),
                    onPrimary = { if (hasPermission) retry() else permissionRequest.launch(Manifest.permission.CAMERA) },
                    onUnlock = activity?.takeIf { context.isKeyguardLocked() }?.let { locked -> { locked.requestUnlock(retry) } },
                    onOpenTypedFallback = onOpenTypedFallback,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(onClick = onBack) {
                    Text(stringResource(R.string.ring_scanner_back))
                }
                if (scanning && torchAvailable) {
                    FilledTonalButton(onClick = { torchOn = !torchOn }) {
                        Text(stringResource(if (torchOn) R.string.ring_scanner_torch_off else R.string.ring_scanner_torch_on))
                    }
                }
                FilledTonalButton(onClick = onOpenTypedFallback) {
                    Text(stringResource(R.string.ring_cant_scan))
                }
            }
        }
    }
}

/** The instruction, or the reason the last scan did not stop the alarm. */
@Composable
private fun ScannerMessage(text: String, isError: Boolean) {
    Surface(
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(16.dp),
        )
    }
}

/** Shown in place of the preview when the camera cannot be used. Every way out stays one tap away. */
@Composable
private fun ScannerProblem(
    message: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    onUnlock: (() -> Unit)?,
    onOpenTypedFallback: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(shape = MaterialTheme.shapes.large, modifier = modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = message, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Button(onClick = onOpenTypedFallback, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.ring_scanner_type_instead))
            }
            OutlinedButton(onClick = onPrimary, modifier = Modifier.fillMaxWidth()) {
                Text(primaryLabel)
            }
            if (onUnlock != null) {
                OutlinedButton(onClick = onUnlock, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.ring_scanner_unlock))
                }
            }
        }
    }
}

private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun Context.isKeyguardLocked(): Boolean =
    getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

/** Asks the user to unlock. The alarm keeps ringing behind the credential prompt. */
private fun Activity.requestUnlock(onUnlocked: () -> Unit) {
    val callback = object : KeyguardManager.KeyguardDismissCallback() {
        override fun onDismissSucceeded() = onUnlocked()
    }
    getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, callback)
}
