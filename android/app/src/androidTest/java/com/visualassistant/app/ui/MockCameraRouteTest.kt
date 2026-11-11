package com.visualassistant.app.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.visualassistant.app.camera.CameraAvailability
import com.visualassistant.app.camera.CameraViewModel
import com.visualassistant.app.camera.CaptureProblem
import com.visualassistant.app.camera.CaptureStore
import com.visualassistant.app.camera.MockCamera
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MockCameraRouteTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var directory: File? = null

    @After fun removeOnlyTestCaptures() {
        directory?.let { dir ->
            CaptureStore(dir).prepare()
            assertTrue(dir.delete())
        }
    }

    private fun syntheticJpeg(): ByteArray {
        val bitmap = Bitmap.createBitmap(20, 12, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(Color.BLUE)
            ByteArrayOutputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    @Test fun sharedRouteHandlesFailureCaptureDisconnectionAndRetry() {
        val dir = File(compose.activity.cacheDir, "mock-route-${UUID.randomUUID()}")
        directory = dir
        val store = CaptureStore(dir)
        val viewModel = CameraViewModel(store)
        val camera = MockCamera(store, syntheticJpeg())
        val binding = MockCameraBinding(camera)
        camera.failNextCapture()
        compose.setContent {
            MaterialTheme {
                var question by androidx.compose.runtime.remember { mutableStateOf("") }
                CameraRoute(compose.activity, viewModel, store, question, { question = it }, binding)
            }
        }
        compose.waitUntil(10_000) { viewModel.state.value.initialized }
        compose.onNodeWithText("Take photo").performScrollTo().performClick()
        compose.waitUntil(10_000) { viewModel.state.value.problem == CaptureProblem.CAPTURE }
        compose.onNodeWithText("The photograph could not be taken. Hold the phone steady and try again.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Take photo").performScrollTo().performClick()
        compose.waitUntil(10_000) { viewModel.state.value.photo != null }
        compose.onNodeWithText("Your photo").assertIsDisplayed()
        compose.onNodeWithText("Your message").performScrollTo().performTextInput("Which button?")
        compose.onNodeWithText("Retake photo").performScrollTo().performClick()
        compose.waitUntil(10_000) { viewModel.state.value.photo == null && !viewModel.state.value.isDeleting }
        compose.runOnIdle { camera.disconnect() }
        compose.onNodeWithText("Try camera again").performScrollTo().performClick()
        compose.waitUntil(10_000) { camera.availability.value is CameraAvailability.Ready }
        compose.onNodeWithText("Take photo").performScrollTo().performClick()
        compose.waitUntil(10_000) { viewModel.state.value.photo != null }
        compose.onNodeWithText("Your photo").assertIsDisplayed()
    }
}
