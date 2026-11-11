package com.visualassistant.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import com.visualassistant.app.camera.CameraAvailability
import com.visualassistant.app.camera.CaptureStore
import com.visualassistant.app.camera.MockCamera

/** Debug/test binding; the regular app always selects PhoneCameraBinding. */
internal class MockCameraBinding(private val camera: MockCamera) : CameraBinding {
    override val requiresPhoneCameraPermission = false

    @Composable
    override fun Preview(
        store: CaptureStore,
        retry: Int,
        onAvailability: (CameraAvailability) -> Unit,
        modifier: Modifier,
    ) {
        val callback by rememberUpdatedState(onAvailability)
        val availability by camera.availability.collectAsState()
        LaunchedEffect(retry) { if (retry > 0) camera.reconnect() }
        LaunchedEffect(availability) { callback(availability) }
        DisposableEffect(camera) { onDispose { callback(CameraAvailability.Starting) } }
        Box(modifier) { Text("Simulated camera preview") }
    }
}
