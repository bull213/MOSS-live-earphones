package com.visualassistant.app.chat

import com.visualassistant.app.camera.CapturedPhoto
import com.visualassistant.app.camera.MainDispatcherRule
import com.visualassistant.app.network.NotebookItem
import com.visualassistant.app.mosslive.MossAudioContext
import com.visualassistant.app.mosslive.MossAudioOutput
import com.visualassistant.app.mosslive.MossConnectionState
import com.visualassistant.app.mosslive.MossLiveSessionController
import com.visualassistant.app.mosslive.MossLiveTransport
import com.visualassistant.app.mosslive.MossLiveTransportListener
import com.visualassistant.app.mosslive.RealtimeAudioSource
import com.visualassistant.app.voice.AudioCaptureRouter
import com.visualassistant.app.voice.AudioChunk
import com.visualassistant.app.voice.AudioDestination
import com.visualassistant.app.voice.AudioSource
import com.visualassistant.app.voice.EarconPlayer
import com.visualassistant.app.voice.Speaker
import com.visualassistant.app.voice.SpeechProblem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelSwitchingTest {

    @get:Rule val main = MainDispatcherRule()

    private val notebooks = listOf(
        NotebookItem(id = "nb-neuro", title = "Neuroscience"),
        NotebookItem(id = "nb-car", title = "Car Maintenance"),
    )

    private class FakeAudioCaptureRouter : AudioCaptureRouter {
        private var _destination = AudioDestination.MOSS
        override val activeDestination: AudioDestination get() = _destination
        override fun setDestination(destination: AudioDestination) {
            this._destination = destination
        }

        override fun asRealtimeAudioSource(): RealtimeAudioSource = object : RealtimeAudioSource {
            override suspend fun start(scope: CoroutineScope, onPcm: (ByteArray) -> Unit, onFailure: () -> Unit) = Unit
            override suspend fun stop() = Unit
        }

        override fun asAudioSource(): AudioSource = object : AudioSource {
            override suspend fun start(scope: CoroutineScope, onFailure: (SpeechProblem) -> Unit): ReceiveChannel<AudioChunk> =
                Channel<AudioChunk>(1)
            override suspend fun stop() = Unit
        }

        override fun release() = Unit
    }

    private class FakeSpeaker : Speaker {
        var lastSpokenText: String? = null
        private var _isSpeaking = false
        override fun speak(text: String, onStart: () -> Unit, onDone: () -> Unit) {
            lastSpokenText = text
            _isSpeaking = true
            onStart()
            _isSpeaking = false
            onDone()
        }
        override fun stop() { _isSpeaking = false }
        override fun isSpeaking() = _isSpeaking
        override fun shutdown() = Unit
    }

    private class FakeEarconPlayer : EarconPlayer {
        var isPlaying = false
        override fun playWaiting(scope: CoroutineScope?) { isPlaying = true }
        override fun stopWaiting() { isPlaying = false }
    }

    private class FakeMossAudioOutput : MossAudioOutput {
        var paused = false
        override fun start(scope: CoroutineScope) = Unit
        override fun enqueue(context: MossAudioContext, bytes: ByteArray) = Unit
        override fun audioDone(runId: String, turnId: String) = Unit
        override fun stopResponse(runId: String, turnId: String) = Unit
        override suspend fun stop() = Unit
        override fun isResponseFullyPlayed(responseKey: String) = false
        override fun pause() { paused = true }
    }

    private class SwitchingTestTransport : MossLiveTransport {
        var listener: MossLiveTransportListener? = null
        val controls = mutableListOf<String>()
        override fun connect(listener: MossLiveTransportListener) { this.listener = listener }
        override fun sendText(text: String): Boolean { controls += text; return true }
        override fun sendBinary(bytes: ByteArray) = true
        override fun queuedBytes() = 0L
        override fun isOpen(): Boolean = true
        override fun close() = Unit
        override fun close(code: Int, reason: String) = Unit
    }

    private fun createMossController(transport: SwitchingTestTransport): MossLiveSessionController {
        return MossLiveSessionController(
            transport = transport,
            audioSource = object : RealtimeAudioSource {
                override suspend fun start(scope: CoroutineScope, onPcm: (ByteArray) -> Unit, onFailure: () -> Unit) = Unit
                override suspend fun stop() = Unit
            },
            audioOutputFactory = { _, _, _ -> FakeMossAudioOutput() }
        )
    }

    @Test
    fun testCompoundQueryDispatchesTurnAndKeepsMutedUntilTTSDone() = runTest(main.dispatcher) {
        val transport = SwitchingTestTransport()
        val moss = createMossController(transport)

        val router = FakeAudioCaptureRouter()
        val speaker = FakeSpeaker()
        val earcon = FakeEarconPlayer()

        var answeredQuery: String? = null
        val assistant = object : LiveAssistant {
            override suspend fun answer(
                question: String,
                photo: CapturedPhoto?,
                history: List<ChatMessage>,
                notebookId: String?,
            ): AssistantTurnResult {
                answeredQuery = question
                return AssistantTurnResult("In Neuroscience: Dopamine mediates motivation.", "Neuroscience")
            }
        }

        val store = MemoryConversationStore()
        val model = ChatViewModel(
            assistant = assistant,
            thumbnails = { byteArrayOf() },
            io = main.dispatcher,
            store = store,
            mossLive = moss,
            speaker = speaker,
            earconPlayer = earcon,
            audioCaptureRouter = router,
        )
        runCurrent()

        model.startMossLive()
        runCurrent()

        val listener = requireNotNull(transport.listener)
        listener.onOpen()
        listener.onText("""{"action":"session_attached","data":{"session_id":"s1"}}""")
        listener.onText("""{"action":"session.updated","data":{"error":""}}""")
        runCurrent()

        // 1. Interim update should cancel active turn
        listener.onText("""{"action":"input.transcript.updated","data":{"turn":"t1","text":"use notebook Neuro"}}""")
        runCurrent()
        assertTrue(transport.controls.any { it.contains("response.cancel") })

        // Populate notebooks in state
        val field = ChatViewModel::class.java.getDeclaredField("mutableState")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = field.get(model) as MutableStateFlow<ChatUiState>
        stateFlow.value = stateFlow.value.copy(notebooks = notebooks)

        // 2. Finalized transcript matching compound query
        listener.onText("""{"action":"input.transcript.done","data":{"turn":"t1","text":"use notebook Neuroscience what is the dopamine pathway?"}}""")
        runCurrent()

        assertTrue(moss.isSuspended)
        assertEquals("what is the dopamine pathway?", answeredQuery)
        assertEquals("In Neuroscience: Dopamine mediates motivation.", speaker.lastSpokenText)

        // Advance decay guard (200ms)
        advanceTimeBy(200L)
        runCurrent()

        // After decay guard, router should be set to VOICE for user follow-up questions
        assertEquals(AudioDestination.VOICE, router.activeDestination)
        assertEquals(AssistantMode.NOTEBOOK_ACTIVE, model.assistantMode.value)

        moss.stop()
    }

    @Test
    fun testSingleSwitchWithoutQueryVocalizesConfirmation() = runTest(main.dispatcher) {
        val transport = SwitchingTestTransport()
        val moss = createMossController(transport)

        val router = FakeAudioCaptureRouter()
        val speaker = FakeSpeaker()
        val earcon = FakeEarconPlayer()

        val store = MemoryConversationStore()
        val model = ChatViewModel(
            assistant = LiveAssistant.text { _, _, _ -> "" },
            thumbnails = { byteArrayOf() },
            io = main.dispatcher,
            store = store,
            mossLive = moss,
            speaker = speaker,
            earconPlayer = earcon,
            audioCaptureRouter = router,
        )
        runCurrent()

        val field = ChatViewModel::class.java.getDeclaredField("mutableState")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = field.get(model) as MutableStateFlow<ChatUiState>
        stateFlow.value = stateFlow.value.copy(notebooks = notebooks)

        model.startMossLive()
        runCurrent()

        val listener = requireNotNull(transport.listener)
        listener.onOpen()
        listener.onText("""{"action":"session_attached","data":{"session_id":"s1"}}""")
        listener.onText("""{"action":"session.updated","data":{"error":""}}""")
        runCurrent()

        listener.onText("""{"action":"input.transcript.done","data":{"turn":"t1","text":"Use notebook Car Maintenance"}}""")
        runCurrent()

        assertTrue(moss.isSuspended)
        assertEquals(AudioDestination.MUTED, router.activeDestination)
        assertEquals("Switched to notebook Car Maintenance", speaker.lastSpokenText)

        advanceTimeBy(200L)
        runCurrent()

        assertEquals(AudioDestination.VOICE, router.activeDestination)
        assertEquals(AssistantMode.NOTEBOOK_ACTIVE, model.assistantMode.value)
        moss.stop()
    }

    @Test
    fun testUnknownNotebookSpeaksErrorAndResumesMoss() = runTest(main.dispatcher) {
        val transport = SwitchingTestTransport()
        val moss = createMossController(transport)

        val router = FakeAudioCaptureRouter()
        val speaker = FakeSpeaker()

        val store = MemoryConversationStore()
        val model = ChatViewModel(
            assistant = LiveAssistant.text { _, _, _ -> "" },
            thumbnails = { byteArrayOf() },
            io = main.dispatcher,
            store = store,
            mossLive = moss,
            speaker = speaker,
            audioCaptureRouter = router,
        )
        runCurrent()

        model.startMossLive()
        runCurrent()

        val listener = requireNotNull(transport.listener)
        listener.onOpen()
        listener.onText("""{"action":"session_attached","data":{"session_id":"s1"}}""")
        listener.onText("""{"action":"session.updated","data":{"error":""}}""")
        runCurrent()

        listener.onText("""{"action":"input.transcript.done","data":{"turn":"t1","text":"Use notebook Quantum Physics"}}""")
        runCurrent()

        assertEquals("Notebook Quantum Physics not found. Staying in MOSS", speaker.lastSpokenText)

        advanceTimeBy(200L)
        runCurrent()

        assertFalse(moss.isSuspended)
        assertTrue(moss.state.value.microphoneTransmitting)
        moss.stop()
    }

    @Test
    fun testBackToMossResumesSession() = runTest(main.dispatcher) {
        val transport = SwitchingTestTransport()
        val moss = createMossController(transport)

        val router = FakeAudioCaptureRouter()
        val speaker = FakeSpeaker()

        val store = MemoryConversationStore()
        val model = ChatViewModel(
            assistant = LiveAssistant.text { _, _, _ -> "" },
            thumbnails = { byteArrayOf() },
            io = main.dispatcher,
            store = store,
            mossLive = moss,
            speaker = speaker,
            audioCaptureRouter = router,
        )
        runCurrent()

        val field = ChatViewModel::class.java.getDeclaredField("mutableState")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = field.get(model) as MutableStateFlow<ChatUiState>
        stateFlow.value = stateFlow.value.copy(notebooks = notebooks)

        model.startMossLive()
        runCurrent()

        val listener = requireNotNull(transport.listener)
        listener.onOpen()
        listener.onText("""{"action":"session_attached","data":{"session_id":"s1"}}""")
        listener.onText("""{"action":"session.updated","data":{"error":""}}""")
        runCurrent()

        // Switch to notebook
        listener.onText("""{"action":"input.transcript.done","data":{"turn":"t1","text":"Use notebook Neuroscience"}}""")
        runCurrent()
        advanceTimeBy(200L)
        runCurrent()
        assertTrue(moss.isSuspended)

        // User speaks "Back to MOSS" from notebook
        val method = ChatViewModel::class.java.getDeclaredMethod(
            "handleSpokenCommand",
            com.visualassistant.app.voice.SpokenCommand::class.java,
            Boolean::class.javaPrimitiveType,
            com.visualassistant.app.voice.SpeechText::class.java,
        )
        method.isAccessible = true
        method.invoke(model, com.visualassistant.app.voice.SpokenCommand.BackToMoss, false, null)
        runCurrent()

        assertEquals("Returning to MOSS", speaker.lastSpokenText)
        advanceTimeBy(200L)
        runCurrent()

        assertFalse(moss.isSuspended)
        assertEquals(AudioDestination.MOSS, router.activeDestination)
        assertEquals(AssistantMode.MOSS_ACTIVE, model.assistantMode.value)
        moss.stop()
    }
}
