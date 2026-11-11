package com.visualassistant.app.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.visualassistant.app.camera.CameraAvailability
import com.visualassistant.app.camera.CameraViewModel
import com.visualassistant.app.camera.CapturedPhoto
import com.visualassistant.app.camera.CaptureStore

@Composable
internal fun CameraRoute(
    activity: ComponentActivity,
    viewModel: CameraViewModel,
    store: CaptureStore,
    question: String,
    onQuestionChanged: (String) -> Unit,
    cameraBinding: CameraBinding = PhoneCameraBinding,
    mossActive: Boolean = false,
    onChatSubmit: ((CapturedPhoto, String) -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val permission = rememberCameraPermissionState(activity)
    val lifecycle = activity.lifecycle
    var foreground by remember(lifecycle) {
        mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    var availability by remember(cameraBinding) { mutableStateOf<CameraAvailability>(CameraAvailability.Starting) }
    var retry by remember(cameraBinding) { mutableIntStateOf(0) }
    val cameraPermissionGranted = !cameraBinding.requiresPhoneCameraPermission ||
        permission.status == CameraPermissionStatus.Granted
    val photo = state.photo
    val retakePhoto = { viewModel.retake() }

    DisposableEffect(lifecycle, permission, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    permission.refresh()
                    foreground = true
                }
                Lifecycle.Event.ON_STOP -> {
                    foreground = false
                    viewModel.cameraPaused()
                }
                else -> Unit
            }
        }
        permission.refresh()
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    BackHandler(enabled = state.photo != null || state.isCapturing) {
        if (photo != null) retakePhoto() else viewModel.cameraPaused()
    }
    CameraScreen(
        state = state,
        permissionGranted = cameraPermissionGranted,
        permissionBlocked = cameraBinding.requiresPhoneCameraPermission &&
            permission.status == CameraPermissionStatus.SettingsRequired,
        cameraReady = foreground && availability is CameraAvailability.Ready,
        cameraFailed = availability == CameraAvailability.Unavailable,
        onPermission = permission.request,
        onSettings = permission.openSettings,
        onCapture = {
            if (foreground && cameraPermissionGranted) {
                (availability as? CameraAvailability.Ready)?.source?.let(viewModel::capture)
            }
        },
        onRetake = retakePhoto,
        onRetryCamera = { retry++ },
        onRetryStorage = viewModel::prepare,
        question = question,
        onQuestionChanged = onQuestionChanged,
        mossActive = mossActive,
        onSend = {
            state.photo?.let { captured ->
                onChatSubmit?.invoke(captured, question.trim())
            }
        },
        preview = {
            if (foreground) {
                cameraBinding.Preview(
                    store = store,
                    retry = retry,
                    onAvailability = { availability = it },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        },
    )
}
