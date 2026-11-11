package com.visualassistant.app.camera

import java.time.Instant

/** Capture is independent of the device and of any future AI/backend client. */
fun interface CameraSource {
    suspend fun capture(): CapturedPhoto
}

data class CapturedPhoto(
    val id: String,
    val localUri: String,
    val capturedAt: Instant,
)
