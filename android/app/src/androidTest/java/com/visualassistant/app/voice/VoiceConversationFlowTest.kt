package com.visualassistant.app.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.visualassistant.app.camera.CameraViewModel
import com.visualassistant.app.camera.CaptureStore
import com.visualassistant.app.chat.AndroidThumbnailProvider
import com.visualassistant.app.chat.ChatKind
import com.visualassistant.app.chat.ChatViewModel
import com.visualassistant.app.chat.ConversationDatabase
import com.visualassistant.app.chat.ConversationRepository
import com.visualassistant.app.ui.ChatRoute
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class VoiceConversationFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val id = UUID.randomUUID().toString()
    private val databaseName = "voice-flow-$id.db"
    private var database: ConversationDatabase? = null
    private var root: File? = null

    private class FixtureSource : AudioSource {
        @Volatile var channel: Channel<AudioChunk>? = null
        override suspend fun start(scope: CoroutineScope, onFailure: (SpeechProblem) -> Unit): ReceiveChannel<AudioChunk> =
            Channel<AudioChunk>(20).also { channel = it }
        override suspend fun stop() { channel?.close() }
    }

    @After fun cleanup() {
        database?.close()
        compose.activity.deleteDatabase(databaseName)
        root?.listFiles()?.forEach { child ->
            if (child.isDirectory) { child.listFiles()?.forEach { it.delete() }; child.delete() } else child.delete()
        }
        root?.delete()
    }

    @Test fun streamedSpeechAppearsAndSurvivesRoomReopenWithoutCallingDemo() {
        val context = compose.activity.applicationContext
        assumeTrue("Microphone permission must be granted by the device user before this test",
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        val testRoot = File(context.cacheDir, "voice-flow-$id").also { root = it }
        val captures = CaptureStore(File(testRoot, "captures"))
        database = Room.databaseBuilder(context, ConversationDatabase::class.java, databaseName).build()
        val repository = ConversationRepository(database!!, File(testRoot, "photos"), context.contentResolver)
        val source = FixtureSource()
        val speech = SpeechController({ MoonshineModelInstaller(context).ensureInstalled() }, source)
        val assistantCalls = AtomicInteger()
        val chat = ChatViewModel({ _, _, _ -> assistantCalls.incrementAndGet(); "Response" },
            AndroidThumbnailProvider(context.contentResolver), store = repository, speech = speech)
        val camera = CameraViewModel(captures)
        compose.setContent { MaterialTheme { ChatRoute(compose.activity, camera, chat, captures) } }
        compose.waitUntil(10_000) { !chat.state.value.loading }
        compose.onNodeWithText("Your message").performTextInput("Keep this draft")
        compose.onNodeWithText("Voice").performClick()
        compose.waitUntil(30_000) { speech.state.value.phase == SpeechPhase.LISTENING }

        val bytes = InstrumentationRegistry.getInstrumentation().context.assets
            .open("moonshine-two-cities-16k.wav").use { it.readBytes() }
        val samples = readMono16kFixture(bytes)
        for (offset in 0 until 16000 * 6 step 1600) {
            runBlocking { source.channel!!.send(AudioChunk(samples.copyOfRange(offset, offset + 1600), 16000)) }
            Thread.sleep(100)
        }
        compose.onNodeWithText("Stop voice").performClick()
        compose.waitUntil(30_000) {
            speech.state.value.phase == SpeechPhase.IDLE && chat.pendingVoice.value.isEmpty() &&
                chat.state.value.messages.any { it.kind == ChatKind.VOICE_TRANSCRIPT }
        }
        assertEquals("Keep this draft", chat.state.value.draft)
        assertEquals(0, assistantCalls.get())
        val fixtureTranscripts = chat.state.value.messages.filter { it.kind == ChatKind.VOICE_TRANSCRIPT }.map { it.text }
        assertTrue("Unexpected fixture transcripts: $fixtureTranscripts",
            fixtureTranscripts.any { it.lowercase().contains("best of times") })
        val savedIds = chat.state.value.messages.filter { it.kind == ChatKind.VOICE_TRANSCRIPT }.map { it.id }

        val conversationId = chat.state.value.sessionId
        database!!.close()
        database = Room.databaseBuilder(context, ConversationDatabase::class.java, databaseName).build()
        val reopened = ConversationRepository(database!!, File(testRoot, "photos"), context.contentResolver)
        val restored = runBlocking { reopened.open(conversationId) }
        assertEquals(savedIds, restored.messages.filter { it.kind == ChatKind.VOICE_TRANSCRIPT }.map { it.id })
    }

    @Test fun backgroundingStopsCaptureAndSavesTheFinishedUtterance() {
        val context = compose.activity.applicationContext
        assumeTrue("Microphone permission must be granted by the device user before this test",
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        val testRoot = File(context.cacheDir, "voice-background-$id").also { root = it }
        val captures = CaptureStore(File(testRoot, "captures"))
        database = Room.databaseBuilder(context, ConversationDatabase::class.java, databaseName).build()
        val repository = ConversationRepository(database!!, File(testRoot, "photos"), context.contentResolver)
        val source = FixtureSource()
        val recognizer = object : StreamingSpeechRecognizer {
            lateinit var listener: (RecognitionEvent) -> Unit
            override fun load(directory: File, listener: (RecognitionEvent) -> Unit) { this.listener = listener }
            override fun start() = Unit
            override fun accept(chunk: AudioChunk) = Unit
            override fun stop() { listener(RecognitionEvent.Completed(42, "finished phrase", 0f, 1f)) }
            override fun close() = Unit
        }
        val detected = AtomicInteger()
        val speech = SpeechController({ testRoot }, source, { recognizer }, newDetector = {
            object : SpeechDetector {
                override fun hasSpeech(chunk: AudioChunk): Boolean { detected.incrementAndGet(); return true }
                override fun close() = Unit
            }
        })
        val chat = ChatViewModel({ _, _, _ -> "Response" }, AndroidThumbnailProvider(context.contentResolver),
            store = repository, speech = speech)
        val camera = CameraViewModel(captures)
        compose.setContent { MaterialTheme { ChatRoute(compose.activity, camera, chat, captures) } }
        compose.waitUntil(10_000) { !chat.state.value.loading }
        compose.onNodeWithText("Voice").performClick()
        compose.waitUntil(10_000) { speech.state.value.phase == SpeechPhase.LISTENING }
        source.channel!!.trySend(AudioChunk(FloatArray(1600), 16000))
        compose.waitUntil(10_000) { detected.get() > 0 }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        try {
            runBlocking {
                withTimeout(10_000) {
                    while (speech.state.value.phase != SpeechPhase.IDLE ||
                        chat.state.value.messages.none { it.text == "finished phrase" }) delay(50)
                }
            }
            assertEquals(1, chat.state.value.messages.count { it.kind == ChatKind.VOICE_TRANSCRIPT })
        } finally {
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        }
    }
}
