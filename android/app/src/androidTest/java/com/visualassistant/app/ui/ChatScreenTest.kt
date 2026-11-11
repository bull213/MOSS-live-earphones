package com.visualassistant.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.visualassistant.app.chat.ChatKind
import com.visualassistant.app.chat.ChatMessage
import com.visualassistant.app.chat.ChatSource
import com.visualassistant.app.chat.ChatUiState
import com.visualassistant.app.voice.SpeechPhase
import com.visualassistant.app.voice.SpeechState
import org.junit.Rule
import org.junit.Test

class ChatScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun demoAndMessagesAreVisibleAndTextCanBeSavedWithoutAPhoto() {
        val messages = listOf(
            ChatMessage(sessionId = "s", turnId = "t", kind = ChatKind.TEXT, source = ChatSource.PHONE, text = "Which button?"),
            ChatMessage(sessionId = "s", turnId = "t", kind = ChatKind.ASSISTANT, source = ChatSource.ASSISTANT, text = "This is a simulated answer."),
        )
        compose.setContent {
            MaterialTheme {
                ChatScreen(ChatUiState(messages = messages, draft = "Help"), hasPhoto = false,
                    openingCamera = false, storageFailed = false, onDraftChanged = {},
                    onPhoto = {}, onSend = {}, onNewSession = {})
            }
        }
        compose.onNodeWithText("Simulated demo").assertIsDisplayed()
        compose.onNodeWithText("Which button?").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("This is a simulated answer.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Send").assertIsEnabled()
    }

    @Test fun composerControlsRemainVisibleWithLargerText() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.5f)) {
                MaterialTheme {
                    ChatScreen(ChatUiState(draft = "Help"), hasPhoto = false,
                        openingCamera = false, storageFailed = false, onDraftChanged = {},
                        onPhoto = {}, onSend = {}, onNewSession = {})
                }
            }
        }
        compose.onNodeWithText("Photo").assertIsDisplayed()
        compose.onNodeWithText("Voice").assertIsDisplayed()
        compose.onNodeWithText("Send").assertIsDisplayed()
    }

    @Test fun listeningShowsStopControl() {
        compose.setContent {
            MaterialTheme {
                ChatScreen(ChatUiState(sessionId = "s"), hasPhoto = false, openingCamera = false,
                    storageFailed = false, onDraftChanged = {}, onPhoto = {}, onSend = {}, onNewSession = {},
                    speech = SpeechState(phase = SpeechPhase.LISTENING), voiceAvailable = true)
            }
        }
        compose.onNodeWithText("Stop voice").assertIsEnabled()
    }

    @Test fun deniedMicrophonePermissionOffersAppSettings() {
        var opened = false
        compose.setContent {
            MaterialTheme {
                ChatScreen(ChatUiState(), hasPhoto = false, openingCamera = false, storageFailed = false,
                    onDraftChanged = {}, onPhoto = {}, onSend = {}, onNewSession = {}, voiceAvailable = true,
                    microphonePermission = CameraPermissionStatus.SettingsRequired, microphoneRequested = true,
                    onVoiceSettings = { opened = true })
            }
        }
        compose.onNodeWithText("Microphone access is off. Enable it in app settings to use voice.").assertIsDisplayed()
        compose.onNodeWithText("Open app settings").performClick()
        compose.runOnIdle { org.junit.Assert.assertTrue(opened) }
    }
}
