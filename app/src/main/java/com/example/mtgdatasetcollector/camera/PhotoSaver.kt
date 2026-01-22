package com.example.mtgdatasetcollector.camera

import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import java.io.File
import java.util.concurrent.Executor

fun takePhoto(
    imageCapture: ImageCapture,
    outFile: File,
    executor: Executor,
    onOk: () -> Unit,
    onFail: (Throwable) -> Unit
) {
    val output = ImageCapture.OutputFileOptions.Builder(outFile).build()
    imageCapture.takePicture(
        output,
        executor,
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) = onOk()
            override fun onError(exception: ImageCaptureException) = onFail(exception)
        }
    )
}
