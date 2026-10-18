package com.cameralivepro.app.camera

import android.content.Context
import android.hardware.camera2.CaptureRequest
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object CameraBinder {
    val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "clp-analysis") }

    @OptIn(ExperimentalCamera2Interop::class)
    fun bind(
        context: Context, owner: LifecycleOwner, view: PreviewView, front: Boolean,
        size: Size, fpsRange: Range<Int>, analyzer: ImageAnalysis.Analyzer,
        onResult: (Camera?, Throwable?) -> Unit,
    ) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                val ratio = if (size.width * 3 == size.height * 4) AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                            else AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
                fun selector(s: Size) = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(ratio)
                    .setResolutionStrategy(ResolutionStrategy(s, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER)).build()

                val previewB = Preview.Builder()
                    .setResolutionSelector(selector(Size(1280, 720).takeIf { size.width > 1280 } ?: size))
                    .setTargetRotation(Surface.ROTATION_90)
                val analysisB = ImageAnalysis.Builder()
                    .setResolutionSelector(selector(size))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .setTargetRotation(Surface.ROTATION_90)
                Camera2Interop.Extender(previewB).setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
                Camera2Interop.Extender(analysisB).setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)

                val preview = previewB.build().also { it.setSurfaceProvider(view.surfaceProvider) }
                val analysis = analysisB.build().also { it.setAnalyzer(analysisExecutor, analyzer) }
                val sel = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                provider.unbindAll()
                onResult(provider.bindToLifecycle(owner, sel, preview, analysis), null)
            } catch (t: Throwable) {
                onResult(null, t)
            }
        }, ContextCompat.getMainExecutor(context))
    }
}
