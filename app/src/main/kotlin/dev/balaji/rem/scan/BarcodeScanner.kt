package dev.balaji.rem.scan

import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Camera preview that reports barcodes. Used for dismissal, registration and test scans.
 *
 * The caller holds the CAMERA permission before this enters composition and gives it a
 * size through [modifier]. The camera is bound while the composable is in composition and
 * the host lifecycle is started, and released when it leaves.
 *
 * It uses CameraX with the bundled ML Kit model, so it works offline and before the first
 * unlock. It reads EAN-13, EAN-8, UPC-A, UPC-E, QR, Code 128, Code 39, ITF and Data Matrix.
 *
 * @param onBarcode called on the main thread once a barcode has been seen in 2 consecutive
 *   frames (`StableScanFilter` in :core). The same barcode is reported again only after a
 *   frame without it, so holding a code in view gives one call.
 * @param torchEnabled turns the torch on or off. Ignored on devices without a flash.
 * @param onCameraError called on the main thread when the camera cannot be opened or
 *   stops working. The preview stays empty after that.
 * @param onTorchAvailable called on the main thread with whether the bound camera has a
 *   flash, so the caller can hide its torch toggle.
 */
@Composable
fun BarcodeScanner(
    onBarcode: (ScannedBarcode) -> Unit,
    modifier: Modifier = Modifier,
    torchEnabled: Boolean = false,
    onCameraError: (Throwable) -> Unit = {},
    onTorchAvailable: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnBarcode by rememberUpdatedState(onBarcode)
    val currentOnCameraError by rememberUpdatedState(onCameraError)
    val currentOnTorchAvailable by rememberUpdatedState(onTorchAvailable)

    // A released camera cannot be bound again, so a new lifecycle owner gets a new one.
    val camera = remember(context, lifecycleOwner) {
        ScannerCamera(
            context = context,
            onBarcode = { currentOnBarcode(it) },
            onError = { currentOnCameraError(it) },
            onTorchAvailable = { currentOnTorchAvailable(it) },
        )
    }
    DisposableEffect(camera) {
        camera.bind(lifecycleOwner)
        onDispose { camera.release() }
    }
    LaunchedEffect(camera, torchEnabled) {
        camera.setTorch(torchEnabled)
    }

    AndroidView(
        factory = { viewContext ->
            PreviewView(viewContext).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        },
        update = { preview ->
            if (preview.controller !== camera.controller) preview.controller = camera.controller
        },
        modifier = modifier,
    )
}
