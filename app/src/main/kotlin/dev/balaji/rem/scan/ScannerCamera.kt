package dev.balaji.rem.scan

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.common.MlKit
import com.google.mlkit.vision.barcode.BarcodeScanner as MlKitScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode as MlKitBarcode
import dev.balaji.rem.core.StableScanFilter

/** A camera error CameraX does not recover from by itself. [code] is a `CameraState.ERROR_*` constant. */
internal class CameraFailure(val code: Int, cause: Throwable?) : Exception("Camera error $code", cause)

/**
 * One camera session for [BarcodeScanner]: the CameraX controller and the bundled ML Kit
 * detector. Create it, [bind] it once, [release] it once.
 *
 * Everything here runs on the main thread, the callbacks included. ML Kit detects on
 * its own worker thread and hands its results to the main executor. That executor is
 * never shut down, so a result or a cancellation that arrives after [release] has
 * somewhere to land and is dropped there.
 */
internal class ScannerCamera(
    context: Context,
    private val onBarcode: (ScannedBarcode) -> Unit,
    private val onError: (Throwable) -> Unit,
    private val onTorchAvailable: (Boolean) -> Unit,
) {
    private val appContext = context.applicationContext
    private val mainExecutor = ContextCompat.getMainExecutor(context)

    /** Created in [bind], so a detector that cannot be built is reported like any other camera error. */
    private var detector: MlKitScanner? = null

    /** Only touched on the main thread. */
    private val stableFilter = StableScanFilter()

    private var released = false

    /**
     * Null until [bind] has built it. Building it starts CameraX, which can throw, for
     * example before the first unlock, so that happens inside [bind] where a failure
     * becomes [onError]. Compose state, so the preview picks the controller up once it exists.
     */
    var controller: LifecycleCameraController? by mutableStateOf(null)
        private set

    fun bind(owner: LifecycleOwner) {
        val built = try {
            build(owner)
        } catch (e: RuntimeException) {
            onError(e)
            return
        }
        controller = built
        val ready = built.initializationFuture
        ready.addListener(
            {
                try {
                    ready.get()
                    if (!released) onReady(owner)
                } catch (e: Exception) {
                    if (!released) onError(e.cause ?: e)
                }
            },
            mainExecutor,
        )
    }

    private fun build(owner: LifecycleOwner): LifecycleCameraController {
        val created = createDetector()
        detector = created
        val built = LifecycleCameraController(appContext)
        built.cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        built.setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
        built.setImageAnalysisAnalyzer(
            mainExecutor,
            // Barcode positions are not used, so no transform to view coordinates is needed.
            MlKitAnalyzer(listOf(created), ImageAnalysis.COORDINATE_SYSTEM_ORIGINAL, mainExecutor) { result ->
                onFrame(created, result)
            },
        )
        built.bindToLifecycle(owner)
        return built
    }

    fun setTorch(enabled: Boolean) {
        if (released) return
        try {
            // Before the camera is bound the controller keeps the value and applies it later.
            controller?.enableTorch(enabled)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Torch change failed", e)
        }
    }

    fun release() {
        released = true
        try {
            controller?.clearImageAnalysisAnalyzer()
            controller?.unbind()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Camera unbind failed", e)
        }
        try {
            detector?.close()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Detector close failed", e)
        }
    }

    /**
     * ML Kit normally initialises itself from a content provider. That provider is not
     * direct boot aware, so in a process started before the first unlock it has not run
     * and the first attempt throws. Initialising by hand covers that case.
     */
    private fun createDetector(): MlKitScanner = try {
        BarcodeScanning.getClient(DETECTOR_OPTIONS)
    } catch (e: IllegalStateException) {
        Log.w(TAG, "ML Kit was not initialised, initialising it now", e)
        MlKit.initialize(appContext)
        BarcodeScanning.getClient(DETECTOR_OPTIONS)
    }

    private fun onReady(owner: LifecycleOwner) {
        val info = controller?.cameraInfo
        onTorchAvailable(info?.hasFlashUnit() == true)
        info?.cameraState?.observe(owner) { state ->
            val error = state.error ?: return@observe
            // Recoverable errors, such as another app holding the camera, are retried by CameraX.
            if (!released && error.type == CameraState.ErrorType.CRITICAL) onError(CameraFailure(error.code, error.cause))
        }
    }

    /** Runs on the main thread, once per analysed frame. */
    private fun onFrame(source: MlKitScanner, result: MlKitAnalyzer.Result) {
        if (released) return
        val seen = result.getValue(source).orEmpty().mapNotNull { barcode ->
            barcode.rawValue?.let { ScannedBarcode.fromMlKit(barcode.format, it).toScannedCode() }
        }
        val stable = stableFilter.onFrame(seen)
        stable.forEach { onBarcode(ScannedBarcode(it.format, it.rawValue)) }
    }

    private companion object {
        const val TAG = "ScannerCamera"

        val DETECTOR_OPTIONS: BarcodeScannerOptions = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                MlKitBarcode.FORMAT_EAN_13,
                MlKitBarcode.FORMAT_EAN_8,
                MlKitBarcode.FORMAT_UPC_A,
                MlKitBarcode.FORMAT_UPC_E,
                MlKitBarcode.FORMAT_QR_CODE,
                MlKitBarcode.FORMAT_CODE_128,
                MlKitBarcode.FORMAT_CODE_39,
                MlKitBarcode.FORMAT_ITF,
                MlKitBarcode.FORMAT_DATA_MATRIX,
            )
            .build()
    }
}
