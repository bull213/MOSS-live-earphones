package com.visualassistant.app.camera

import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor

class PhoneCameraSource(
    private val imageCapture: ImageCapture,
    private val store: CaptureStore,
    private val rotation: () -> Int,
) : CameraSource {
    override suspend fun capture(): CapturedPhoto = FilePhotoCapture(store).capture { file, onSaved, onFailure ->
        imageCapture.targetRotation = rotation()
        imageCapture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            Dispatchers.IO.asExecutor(),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) = onSaved()
                override fun onError(exception: ImageCaptureException) = onFailure(exception)
            },
        )
    }
}
