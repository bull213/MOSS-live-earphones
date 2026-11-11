package com.visualassistant.app.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.visualassistant.app.camera.CameraUiState
import com.visualassistant.app.camera.CapturedPhoto
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CameraScreenTest {
    @get:Rule val compose = createComposeRule()

    @Composable
    private fun Screen(
        state: CameraUiState = CameraUiState(initialized = true),
        granted: Boolean = true,
        blocked: Boolean = false,
        ready: Boolean = true,
        failed: Boolean = false,
        onSettings: () -> Unit = {},
        onRetake: () -> Unit = {},
        onRetryCamera: () -> Unit = {},
        question: String = "",
        onQuestionChanged: (String) -> Unit = {},
        onSend: () -> Unit = {},
        preview: @Composable () -> Unit = { Spacer(Modifier.height(80.dp)) },
        review: @Composable (CapturedPhoto) -> Unit = { Text("Test photograph") },
    ) {
        MaterialTheme {
            CameraScreen(
                state, granted, blocked, cameraReady = ready, cameraFailed = failed,
                onPermission = {}, onSettings = onSettings, onCapture = {},
                onRetake = onRetake, onRetryCamera = onRetryCamera, onRetryStorage = {},
                question = question, onQuestionChanged = onQuestionChanged, onSend = onSend,
                preview = preview,
                review = review,
            )
        }
    }

    @Test fun captureButtonDisabledDuringCapture() {
        compose.setContent { Screen(CameraUiState(initialized = true, isCapturing = true)) }
        compose.onNodeWithText("Taking your photograph…").assertIsDisplayed()
        compose.onNodeWithText("Take photo").performScrollTo().assertIsNotEnabled()
    }

    @Test fun captureButtonDisabledWhileCameraStarts() {
        compose.setContent { Screen(ready = false) }
        compose.onNodeWithText("Preparing the camera…").assertIsDisplayed()
        compose.onNodeWithText("Take photo").performScrollTo().assertIsNotEnabled()
    }

    @Test fun unavailableCameraOffersRetryAndReturnsToStarting() {
        var retries = 0
        compose.setContent {
            var failed by remember { mutableStateOf(true) }
            Screen(ready = false, failed = failed, onRetryCamera = {
                retries++
                failed = false
            })
        }
        compose.onNodeWithText("Try camera again").performScrollTo().performClick()
        assertEquals(1, retries)
        compose.onNodeWithText("Try camera again").assertDoesNotExist()
        compose.onNodeWithText("Take photo").performScrollTo().assertIsNotEnabled()
    }

    @Test fun blockedPermissionOffersSettings() {
        var clicks = 0
        compose.setContent { Screen(granted = false, blocked = true, onSettings = { clicks++ }) }
        compose.onNodeWithText("Open app settings").performScrollTo().performClick()
        assertEquals(1, clicks)
    }

    @Test fun reviewOffersRetake() {
        var clicks = 0
        val photo = CapturedPhoto("test", "file:///test.jpg", Instant.EPOCH)
        compose.setContent { Screen(CameraUiState(initialized = true, photo = photo), onRetake = { clicks++ }) }
        compose.onNodeWithText("Your photo").assertIsDisplayed()
        compose.onNodeWithText("Retake photo").performScrollTo().performClick()
        assertEquals(1, clicks)
    }

    @Test fun captureButtonAppearsBeforeTallPreview() {
        compose.setContent { Screen(preview = { Spacer(Modifier.height(1000.dp)) }) }
        compose.onNodeWithText("Take photo").assertIsDisplayed()
    }

    @Test fun retakeButtonRemainsReachableWithTallReview() {
        val photo = CapturedPhoto("test", "file:///test.jpg", Instant.EPOCH)
        compose.setContent {
            Screen(
                state = CameraUiState(initialized = true, photo = photo),
                review = { Spacer(Modifier.height(1000.dp)) },
            )
        }
        compose.onNodeWithText("Retake photo").performScrollTo().assertIsDisplayed()
    }

    @Test fun questionRequiresTextAndCallsSend() {
        var sends = 0
        val photo = CapturedPhoto("test", "file:///test.jpg", Instant.EPOCH)
        compose.setContent {
            var question by remember { mutableStateOf("") }
            Screen(
                state = CameraUiState(initialized = true, photo = photo),
                question = question,
                onQuestionChanged = { question = it },
                onSend = { sends++ },
            )
        }
        compose.onNodeWithText("Send to assistant").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Your message").performScrollTo().performTextInput("   ")
        compose.onNodeWithText("Send to assistant").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Your message").performScrollTo().performTextInput("Which button?")
        compose.onNodeWithText("Send to assistant").performScrollTo().performClick()
        assertEquals(1, sends)
    }

    @Test fun sendButtonIsDisabledWhileDeletingPhoto() {
        val photo = CapturedPhoto("test", "file:///test.jpg", Instant.EPOCH)
        compose.setContent {
            Screen(state = CameraUiState(initialized = true, photo = photo, isDeleting = true), question = "Which button?")
        }
        compose.onNodeWithText("Send to assistant").performScrollTo().assertIsNotEnabled()
    }

    @Test fun permissionButtonRemainsReachableAtLargeFontScale() {
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 2f)) {
                Screen(granted = false)
            }
        }
        compose.onNodeWithText("Allow camera").performScrollTo().assertIsDisplayed()
    }
}
