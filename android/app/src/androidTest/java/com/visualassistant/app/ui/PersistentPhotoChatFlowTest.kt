package com.visualassistant.app.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import com.visualassistant.app.camera.CameraViewModel
import com.visualassistant.app.camera.CaptureStore
import com.visualassistant.app.camera.MockCamera
import com.visualassistant.app.chat.AndroidThumbnailProvider
import com.visualassistant.app.chat.ChatKind
import com.visualassistant.app.chat.ChatStatus
import com.visualassistant.app.chat.ChatViewModel
import com.visualassistant.app.chat.ConversationDatabase
import com.visualassistant.app.chat.ConversationRepository
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PersistentPhotoChatFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val id = UUID.randomUUID().toString()
    private val dbName = "photo-chat-$id.db"
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

    @Test fun mockPhotoAndReplyReturnAfterViewModelRestart() {
        val context = compose.activity.applicationContext
        val root = File(context.cacheDir, "photo-chat-$id")
        testRoot = root
        val captures = CaptureStore(File(root, "captures"))
        val db = Room.databaseBuilder(context, ConversationDatabase::class.java, dbName).build()
        database = db
        val repository = ConversationRepository(db, File(root, "photos"), context.contentResolver)
        fun newChat() = ChatViewModel({ _, _, _ -> "The photo shows a television remote." },
            AndroidThumbnailProvider(context.contentResolver), store = repository)
        val camera = CameraViewModel(captures)
        val bitmap = Bitmap.createBitmap(100, 70, Bitmap.Config.ARGB_8888)
        val jpeg = try {
            bitmap.eraseColor(Color.BLUE)
            ByteArrayOutputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
                output.toByteArray()
            }
        } finally { bitmap.recycle() }
        val binding = MockCameraBinding(MockCamera(captures, jpeg))
        var chat by mutableStateOf(newChat())
        compose.setContent { MaterialTheme { ChatRoute(compose.activity, camera, chat, captures, binding) } }
        compose.waitUntil(10_000) { !chat.state.value.loading && camera.state.value.initialized }
        compose.onNodeWithText("Photo").performClick()
        compose.onNodeWithText("Take photo").performScrollTo().performClick()
        compose.waitUntil(10_000) { camera.state.value.photo != null }
        compose.onNodeWithText("Your message").performScrollTo().performTextInput("Which button?")
        compose.waitUntil(10_000) { chat.state.value.draft == "Which button?" }
        compose.onNodeWithText("Send to assistant").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.waitForIdle()
        compose.onNodeWithText("Send to assistant").performClick()
        try {
            compose.waitUntil(15_000) { !chat.state.value.sending && (chat.state.value.messages.isNotEmpty() || chat.state.value.storageError) }
        } catch (error: Throwable) {
            val state = chat.state.value
            throw AssertionError("Photo chat did not complete: sending=${state.sending}, loading=${state.loading}, " +
                "storageError=${state.storageError}, messages=${state.messages.size}, photo=${camera.state.value.photo != null}, " +
                "draftPresent=${state.draft.isNotBlank()}", error)
        }
        assertTrue("storageError=${chat.state.value.storageError}; statuses=${chat.state.value.messages.map { it.status }}",
            !chat.state.value.storageError && chat.state.value.messages.lastOrNull()?.status == ChatStatus.READY)
        assertTrue(chat.state.value.messages.map { it.kind } == listOf(ChatKind.PHOTO, ChatKind.TEXT, ChatKind.ASSISTANT))
        val saved = File(URI(chat.state.value.currentPhoto!!.localUri))
        assertTrue(saved.isFile)
        compose.runOnIdle { chat = newChat() }
        compose.waitUntil(10_000) { !chat.state.value.loading && chat.state.value.messages.size == 3 }
        compose.onNodeWithText("Which button?").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("The photo shows a television remote.").performScrollTo().assertIsDisplayed()
        assertTrue(File(URI(chat.state.value.currentPhoto!!.localUri)).isFile)
    }
}
