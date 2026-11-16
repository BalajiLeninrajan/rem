package dev.balaji.rem.scan

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.balaji.rem.R

/**
 * Camera preview that reports barcodes. Used for dismissal, registration and test scans.
 *
 * The caller holds the CAMERA permission before this enters composition and gives it a
 * size through [modifier]. The camera is bound while the composable is in composition and
 * the host lifecycle is started, and released when it leaves.
 *
 * Stub. The real one uses CameraX with the bundled ML Kit model.
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
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(text = stringResource(R.string.scanner_placeholder))
    }
}
