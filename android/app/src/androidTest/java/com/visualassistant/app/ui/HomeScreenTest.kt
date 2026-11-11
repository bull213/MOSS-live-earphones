package com.visualassistant.app.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import org.junit.Rule
import org.junit.Test

class HomeScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun getHelpOpensCameraAndBackReturnsHome() {
        compose.setContent {
            VisualAssistantTheme {
                AssistantNavigation { Text("Camera destination") }
            }
        }
        compose.onNodeWithText("What would you like help with?").assertIsDisplayed()
        compose.onNodeWithText("Camera destination").assertDoesNotExist()
        compose.onNodeWithText("Take a photo").performScrollTo().performClick()
        compose.onNodeWithText("Camera destination").assertIsDisplayed()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Take a photo").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Camera destination").assertDoesNotExist()
    }

    @Test fun getHelpRemainsReachableAtLargeFontScale() {
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 2f)) {
                VisualAssistantTheme { HomeScreen(onGetHelp = {}) }
            }
        }
        compose.onNodeWithText("What would you like help with?").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Take a photo").performScrollTo().assertIsDisplayed()
    }

    @Test fun cameraBackHandlerTakesPriorityBeforeReturningHome() {
        var reviewing by mutableStateOf(true)
        compose.setContent {
            VisualAssistantTheme {
                AssistantNavigation {
                    BackHandler(enabled = reviewing) { reviewing = false }
                    Text(if (reviewing) "Review" else "Camera preview")
                }
            }
        }
        compose.onNodeWithText("Take a photo").performScrollTo().performClick()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Camera preview").assertIsDisplayed()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Take a photo").performScrollTo().assertIsDisplayed()
    }
}
