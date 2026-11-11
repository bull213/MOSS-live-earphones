package com.visualassistant.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import com.visualassistant.app.camera.CameraViewModel
import com.visualassistant.app.camera.CaptureStore
import com.visualassistant.app.chat.AndroidThumbnailProvider
import com.visualassistant.app.chat.ChatKind
import com.visualassistant.app.chat.ChatViewModel
import com.visualassistant.app.chat.ConversationDatabase
import com.visualassistant.app.chat.ConversationRepository
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ConversationHistoryFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val id = UUID.randomUUID().toString()
    private val dbName = "history-ui-$id.db"
    private var database: ConversationDatabase? = null
    private var testRoot: File? = null

    @After fun cleanup() {
        database?.close()
        compose.activity.deleteDatabase(dbName)
        testRoot?.listFiles()?.forEach { child ->
            if (child.isDirectory) { child.listFiles()?.forEach { it.delete() }; child.delete() } else child.delete()
        }
        testRoot?.delete()
    }

    @Test fun historyOpensAndDeletesSavedConversations() {
        val context = compose.activity.applicationContext
        val root = File(context.cacheDir, "history-ui-$id")
        testRoot = root
        val cameraStore = CaptureStore(File(root, "captures"))
        val db = Room.databaseBuilder(context, ConversationDatabase::class.java, dbName).build()
        database = db
        val repository = ConversationRepository(db, File(root, "photos"), context.contentResolver)
        val chat = ChatViewModel({ _, _, _ -> "Assistant reply" }, AndroidThumbnailProvider(context.contentResolver), store = repository)
        val camera = CameraViewModel(cameraStore)
        compose.setContent {
            MaterialTheme { ChatRoute(compose.activity, camera, chat, cameraStore) }
        }
        compose.waitUntil(10_000) { !chat.state.value.loading }
        compose.onNodeWithText("Your message").performTextInput("First question")
        compose.onNodeWithText("Send").performClick()
        compose.waitUntil(10_000) { chat.state.value.messages.any { it.kind == ChatKind.ASSISTANT && it.status == com.visualassistant.app.chat.ChatStatus.READY } }
        val firstId = chat.state.value.sessionId
        compose.onNodeWithText("Save chat").performClick()
        compose.waitUntil(10_000) { chat.state.value.saved && !chat.state.value.loading }
        compose.onNodeWithText("Menu").performClick()
        compose.onNodeWithText("Saved chats").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("saved-$firstId").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("New conversation").performScrollTo().performClick()
        compose.onNodeWithText("Start new session").performClick()
        compose.waitUntil(10_000) { !chat.state.value.loading && chat.state.value.sessionId != firstId }
        val secondId = chat.state.value.sessionId
        compose.onNodeWithText("Your message").performTextInput("Second question")
        compose.onNodeWithText("Send").performClick()
        compose.waitUntil(10_000) { chat.state.value.messages.any { it.kind == ChatKind.ASSISTANT && it.status == com.visualassistant.app.chat.ChatStatus.READY } }
        assertTrue(chat.state.value.recentMessages.any { it.text == "First question" })
        compose.onNodeWithText("Menu").performClick()
        compose.onNodeWithTag("saved-$firstId").performScrollTo().performClick()
        compose.waitUntil(10_000) { !chat.state.value.loading && chat.state.value.sessionId == firstId }
        assertTrue(chat.state.value.viewingSaved)
        compose.onNodeWithText("Menu").performClick()
        compose.onNodeWithText("Recent Memory").performScrollTo().performClick()
        compose.waitUntil(10_000) { !chat.state.value.loading && !chat.state.value.viewingSaved && chat.state.value.sessionId == secondId }
        compose.onNodeWithText("Menu").performClick()
        compose.onNodeWithTag("saved-$firstId").performScrollTo().performClick()
        compose.waitUntil(10_000) { !chat.state.value.loading && chat.state.value.viewingSaved }
        compose.onNodeWithText("Menu").performClick()
        compose.onAllNodesWithText("Delete")[0].performScrollTo().performClick()
        compose.onNodeWithText("Delete conversation?").assertIsDisplayed()
        compose.onNodeWithText("Delete conversation").performClick()
        compose.waitUntil(10_000) { !chat.state.value.loading && chat.state.value.history.none { it.id == firstId } }
        compose.onNodeWithText("Settings").performScrollTo().performClick()
        compose.onNodeWithText("Privacy").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Recent memory").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Back to chat").performScrollTo().performClick()
        compose.onNodeWithText("Menu").assertIsDisplayed()
    }
}
