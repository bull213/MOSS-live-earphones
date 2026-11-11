package com.visualassistant.app.ui

import android.view.Surface
import android.view.View
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.visualassistant.app.camera.CameraAvailability
import com.visualassistant.app.camera.CaptureStore
import com.visualassistant.app.camera.PhoneCameraSource

@Composable
internal fun CameraPreview(
    store: CaptureStore,
    retry: Int,
    onAvailability: (CameraAvailability) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val availabilityCallback = rememberUpdatedState(onAvailability)
    val view = remember(context) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }
    AndroidView(factory = { view }, modifier = modifier)

    DisposableEffect(view, lifecycleOwner, store, retry) {
        var disposed = false
        var availability: CameraAvailability = CameraAvailability.Starting
        var provider: ProcessCameraProvider? = null
        var camera: Camera? = null
        fun publish(next: CameraAvailability) {
            availability = next
            availabilityCallback.value(next)
        }
        val preview = Preview.Builder().build()
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
        val source = PhoneCameraSource(capture, store) {
            view.display?.rotation ?: Surface.ROTATION_0
        }
        val observer = Observer<CameraState> { state ->
            if (!disposed) {
                publish(when {
                    state.error != null -> CameraAvailability.Unavailable
                    state.type == CameraState.Type.OPEN -> CameraAvailability.Ready(source)
                    // Closing after an error must not hide the retry action.
                    availability == CameraAvailability.Unavailable -> CameraAvailability.Unavailable
                    else -> CameraAvailability.Starting
                })
            }
        }
        publish(CameraAvailability.Starting)
        val future = ProcessCameraProvider.getInstance(context.applicationContext)
        future.addListener({
            if (!disposed) {
                try {
                    val resolvedProvider = future.get()
                    provider = resolvedProvider
                    preview.setSurfaceProvider(view.surfaceProvider)
                    val boundCamera = resolvedProvider.bindToLifecycle(
                        lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture,
                    )
                    camera = boundCamera
                    boundCamera.cameraInfo.cameraState.observe(lifecycleOwner, observer)
                } catch (_: Exception) {
                    publish(CameraAvailability.Unavailable)
                }
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            disposed = true
            camera?.cameraInfo?.cameraState?.removeObserver(observer)
            provider?.unbind(preview, capture)
            publish(CameraAvailability.Starting)
        }
    }
}
