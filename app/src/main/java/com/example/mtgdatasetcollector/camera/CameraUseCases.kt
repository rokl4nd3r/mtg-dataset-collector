package com.example.mtgdatasetcollector.camera

import android.content.Context
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import com.example.mtgdatasetcollector.util.getCameraProvider
import java.util.concurrent.ExecutorService

data class BoundCamera(
    val provider: ProcessCameraProvider,
    val camera: Camera,
    val preview: Preview,
    val imageCapture: ImageCapture,
    val analysis: ImageAnalysis
)

suspend fun bindCameraWithEngine(
    ctx: Context,
    lifecycleOwner: LifecycleOwner,
    previewView: PreviewView,
    analyzerExecutor: ExecutorService,
    engine: CaptureEngine,
    cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
): BoundCamera {
    val provider = getCameraProvider(ctx)

    val rotation = previewView.display?.rotation ?: Surface.ROTATION_0

    val preview = Preview.Builder()
        .setTargetRotation(rotation)
        .build()
        .also { it.setSurfaceProvider(previewView.surfaceProvider) }

    val imageCapture = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        .setJpegQuality(90)
        .setTargetRotation(rotation)
        .build()

    val analysis = ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setTargetRotation(rotation)
        .build()

    analysis.setAnalyzer(analyzerExecutor) { image ->
        engine.analyze(image)
    }

    provider.unbindAll()
    val camera = provider.bindToLifecycle(
        lifecycleOwner,
        cameraSelector,
        preview,
        imageCapture,
        analysis
    )

    return BoundCamera(provider, camera, preview, imageCapture, analysis)
}
