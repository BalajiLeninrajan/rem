package dev.balaji.rem.scan

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.common.MlKit
import com.google.mlkit.vision.barcode.BarcodeScanner as MlKitScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode as MlKitBarcode
import dev.balaji.rem.core.StableScanFilter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** A camera error CameraX does not recover from by itself. [code] is a `CameraState.ERROR_*` constant. */
internal class CameraFailure(val code: Int, cause: Throwable?) : Exception("Camera error $code", cause)

/**
 * One camera session for [BarcodeScanner]: the CameraX controller, the bundled ML Kit
 * detector and the analysis thread. Create it, [bind] it once, [release] it once.
 *
 * All three callbacks run on the main thread.
 */
internal class ScannerCamera(
    context: Context,
    private val onBarcode: (ScannedBarcode) -> Unit,
    private val onError: (Throwable) -> Unit,
    private val onTorchAvailable: (Boolean) -> Unit,
) {
    private val appContext = context.applicationContext
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    /** Created in [bind], so a detector that cannot be built is reported like any other camera error. */
    private var detector: MlKitScanner? = null

    /** Only touched on the analysis thread. */
    private val stableFilter = StableScanFilter()

    @Volatile
    private var released = false

    val controller = LifecycleCameraController(context)

    fun bind(owner: LifecycleOwner) {
        try {
            val created = createDetector()
            detector = created
            controller.cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            controller.setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
            controller.setImageAnalysisAnalyzer(
                analysisExecutor,
                // Barcode positions are not used, so no transform to view coordinates is needed.
                MlKitAnalyzer(listOf(created), ImageAnalysis.COORDINATE_SYSTEM_ORIGINAL, analysisExecutor) { result ->
                    onFrame(created, result)
                },
            )
            controller.bindToLifecycle(owner)
        } catch (e: RuntimeException) {
            onError(e)
            return
        }
        val ready = controller.initializationFuture
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

    fun setTorch(enabled: Boolean) {
        if (released) return
        try {
            // Before the camera is bound the controller keeps the value and applies it later.
            controller.enableTorch(enabled)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Torch change failed", e)
        }
    }

    fun release() {
        released = true
        try {
            controller.clearImageAnalysisAnalyzer()
            controller.unbind()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Camera unbind failed", e)
        }
        detector?.close()
        analysisExecutor.shutdown()
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
        val info = controller.cameraInfo
        onTorchAvailable(info?.hasFlashUnit() == true)
        info?.cameraState?.observe(owner) { state ->
            val error = state.error ?: return@observe
            // Recoverable errors, such as another app holding the camera, are retried by CameraX.
            if (!released && error.type == CameraState.ErrorType.CRITICAL) onError(CameraFailure(error.code, error.cause))
        }
    }

    /** Runs on the analysis thread, once per analysed frame. */
    private fun onFrame(source: MlKitScanner, result: MlKitAnalyzer.Result) {
        if (released) return
        val seen = result.getValue(source).orEmpty().mapNotNull { barcode ->
            barcode.rawValue?.let { ScannedBarcode.fromMlKit(barcode.format, it).toScannedCode() }
        }
        val stable = stableFilter.onFrame(seen)
        if (stable.isEmpty()) return
        mainExecutor.execute {
            if (!released) stable.forEach { onBarcode(ScannedBarcode(it.format, it.rawValue)) }
        }
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
