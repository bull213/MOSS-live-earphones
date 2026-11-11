package com.visualassistant.app.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.visualassistant.app.R
import com.visualassistant.app.camera.CameraUiState
import com.visualassistant.app.camera.CaptureProblem
import com.visualassistant.app.camera.CapturedPhoto

@Composable
internal fun CameraScreen(
    state: CameraUiState, permissionGranted: Boolean, permissionBlocked: Boolean,
    cameraReady: Boolean, cameraFailed: Boolean, onPermission: () -> Unit,
    onSettings: () -> Unit, onCapture: () -> Unit, onRetake: () -> Unit,
    onRetryCamera: () -> Unit, onRetryStorage: () -> Unit,
    question: String, onQuestionChanged: (String) -> Unit,
    onSend: () -> Unit, preview: @Composable () -> Unit,
    review: @Composable (CapturedPhoto) -> Unit = { PhotoReview(it) },
    mossActive: Boolean = false,
) {
    val focusManager = LocalFocusManager.current
    if (state.initialized && permissionGranted && state.photo == null) {
        CameraCaptureContent(state, cameraReady, cameraFailed, onCapture, onRetryCamera, preview)
        return
    }
    Scaffold { insets ->
        Column(Modifier.fillMaxSize().padding(insets).imePadding()
            .verticalScroll(rememberScrollState()).padding(20.dp).animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(stringResource(if (state.photo != null) R.string.review_title else R.string.camera_title),
                style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
            if (state.photo != null) {
                Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Box(Modifier.fillMaxWidth()) { review(state.photo) }
                }
                SecondaryButton(stringResource(R.string.retake), !state.isDeleting, onRetake)
                state.problem?.let { ProblemText(it) }
                if (state.isDeleting) StatusText(stringResource(R.string.clearing))
                if (!mossActive) {
                    Text(stringResource(R.string.review_question_title), style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(value = question, onValueChange = onQuestionChanged,
                        enabled = !state.isDeleting,
                        label = { Text(stringResource(R.string.chat_composer_label)) },
                        placeholder = { Text(stringResource(R.string.question_example)) },
                        minLines = 3, maxLines = 6, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth())
                    InfoBanner(stringResource(R.string.photo_ai_disclosure))
                }
                val buttonLabel = when {
                    mossActive -> "Use with MOSS Live"
                    question.isNotBlank() -> stringResource(R.string.camera_send)
                    else -> "Add to Discussion"
                }
                LargeButton(
                    buttonLabel,
                    enabled = !state.isDeleting,
                    onClick = { focusManager.clearFocus(); onSend() })
            } else if (!state.initialized) {
                if (state.problem != null) {
                    ProblemText(state.problem)
                    LargeButton(stringResource(R.string.retry_storage), onClick = onRetryStorage)
                } else {
                    StatusText(stringResource(R.string.preparing)); CircularProgressIndicator()
                }
            } else {
                InfoBanner(stringResource(if (permissionBlocked) R.string.permission_denied else R.string.permission_explanation))
                LargeButton(stringResource(if (permissionBlocked) R.string.open_settings else R.string.allow_camera),
                    onClick = if (permissionBlocked) onSettings else onPermission)
            }
        }
    }
}

@Composable
private fun CameraCaptureContent(
    state: CameraUiState,
    cameraReady: Boolean,
    cameraFailed: Boolean,
    onCapture: () -> Unit,
    onRetryCamera: () -> Unit,
    preview: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF101C2C))) {
        val previewGap = (maxHeight - 360.dp).coerceAtLeast(120.dp)
        preview()
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(color = Color(0xE6172B4D), shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.camera_title), color = Color.White,
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.semantics { heading() })
                    Text(stringResource(R.string.camera_instruction), color = Color.White,
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(previewGap))
            Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when {
                        state.isCapturing -> StatusText(stringResource(R.string.capturing))
                        cameraFailed -> StatusText(stringResource(R.string.camera_unavailable), isError = true)
                        !cameraReady -> StatusText(stringResource(R.string.preparing))
                    }
                    state.problem?.let { ProblemText(it) }
                    if (cameraFailed && !state.isCapturing) {
                        LargeButton(stringResource(R.string.retry_camera), onClick = onRetryCamera)
                    } else {
                        LargeButton(stringResource(R.string.take_photo), cameraReady && !state.isCapturing, onCapture)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProblemText(problem: CaptureProblem) {
    val message = when (problem) {
        CaptureProblem.CAPTURE -> R.string.capture_failed
        CaptureProblem.INTERRUPTED -> R.string.capture_interrupted
        CaptureProblem.STORAGE -> R.string.storage_failed
    }
    StatusText(stringResource(message), isError = true)
}
