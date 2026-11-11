package com.visualassistant.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.visualassistant.app.camera.CameraAvailability
import com.visualassistant.app.camera.CaptureStore

/** Connects a camera source and its preview to the shared camera route. */
internal interface CameraBinding {
    val requiresPhoneCameraPermission: Boolean

    @Composable
    fun Preview(
        store: CaptureStore,
        retry: Int,
        onAvailability: (CameraAvailability) -> Unit,
        modifier: Modifier,
    )
}

internal object PhoneCameraBinding : CameraBinding {
    override val requiresPhoneCameraPermission = true

    @Composable
    override fun Preview(
        store: CaptureStore,
        retry: Int,
        onAvailability: (CameraAvailability) -> Unit,
        modifier: Modifier,
    ) = CameraPreview(store, retry, onAvailability, modifier)
}
