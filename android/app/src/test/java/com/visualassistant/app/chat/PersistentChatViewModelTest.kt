package com.visualassistant.app.chat

import com.visualassistant.app.camera.CapturedPhoto
import com.visualassistant.app.camera.MainDispatcherRule
import com.visualassistant.app.voice.AudioChunk
import com.visualassistant.app.voice.AudioSource
import com.visualassistant.app.voice.RecognitionEvent
import com.visualassistant.app.voice.SpeechController
import com.visualassistant.app.voice.SpeechDetector
import com.visualassistant.app.voice.SpeechProblem
import com.visualassistant.app.voice.StreamingSpeechRecognizer
import com.visualassistant.app.mosslive.MossAudioOutput
import com.visualassistant.app.mosslive.MossLiveSessionController
import com.visualassistant.app.mosslive.MossLiveTransport
import com.visualassistant.app.mosslive.MossLiveTransportListener
import com.visualassistant.app.mosslive.RealtimeAudioSource
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

internal class MemoryConversationStore : ConversationStore {
    val conversations = linkedMapOf<String, ConversationSnapshot>()
    var active: String? = null
    var failVoiceAfterCommitOnce = false
    override suspend fun initialize() = active?.let { conversations[it] } ?: newConversation()
    override suspend fun resumeRecent(): ConversationSnapshot =
        conversations.values.lastOrNull { !it.saved }?.also { active = it.id } ?: newConversation()
    override suspend fun newConversation(): ConversationSnapshot {
        val id = UUID.randomUUID().toString()
        active = id
        return ConversationSnapshot(id, emptyList(), null).also { conversations[id] = it }
    }
    override suspend fun open(id: String): ConversationSnapshot {
        active = id
        return conversations.getValue(id)
    }
    override suspend fun history() = conversations.values.filter { it.messages.isNotEmpty() }.map {
        ConversationSummary(it.id, it.messages.firstOrNull { message -> message.kind == ChatKind.TEXT }?.text.orEmpty(), "", Instant.EPOCH, it.saved)
    }
    override suspend fun save(conversationId: String): ConversationSnapshot =
        conversations.getValue(conversationId).copy(saved = true).also { conversations[conversationId] = it }
    override suspend fun recentContext(): List<ChatMessage> = conversations.values.flatMap { it.messages }
    override suspend fun beginTextTurn(conversationId: String, question: String): ConversationSnapshot {
        val current = conversations.getValue(conversationId)
        val turn = UUID.randomUUID().toString()
        val user = ChatMessage(sessionId = conversationId, turnId = turn, kind = ChatKind.TEXT, source = ChatSource.PHONE, text = question)
        val pending = ChatMessage(sessionId = conversationId, turnId = turn, kind = ChatKind.ASSISTANT,
            source = ChatSource.ASSISTANT, status = ChatStatus.SENDING)
        return current.copy(messages = current.messages + listOf(user, pending)).also { conversations[conversationId] = it }
    }
    override suspend fun upsertMossTranscript(
        conversationId: String, turnId: String, assistant: Boolean, text: String,
    ): ConversationSnapshot {
        val current = conversations.getValue(conversationId)
        val kind = if (assistant) ChatKind.ASSISTANT else ChatKind.TEXT
        val source = if (assistant) ChatSource.ASSISTANT else ChatSource.PHONE
        val existing = current.messages.indexOfFirst { it.turnId == turnId && it.kind == kind && it.source == source }
        val status = ChatStatus.READY
        val message = if (existing < 0) ChatMessage(
            sessionId = conversationId, turnId = turnId, kind = kind, source = source, text = text, status = status,
        ) else current.messages[existing].copy(text = text, status = status)
        val messages = if (existing < 0) current.messages + message else current.messages.toMutableList().also { it[existing] = message }
        return current.copy(messages = messages).also { conversations[conversationId] = it }
    }
    override suspend fun addVoiceTranscript(conversationId: String, messageId: String, text: String): ConversationSnapshot {
        val current = conversations.getValue(conversationId)
        if (current.messages.any { it.id == messageId }) return current
        val voice = ChatMessage(id = messageId, sessionId = conversationId, turnId = messageId,
            kind = ChatKind.VOICE_TRANSCRIPT, source = ChatSource.PHONE, text = text)
        val updated = current.copy(messages = current.messages + voice).also { conversations[conversationId] = it }
        if (failVoiceAfterCommitOnce) {
            failVoiceAfterCommitOnce = false
            throw java.io.IOException("simulated failure after commit")
        }
        return updated
    }
    override suspend fun discardVoiceTranscript(conversationId: String, messageId: String): ConversationSnapshot {
        val current = conversations.getValue(conversationId)
        return current.copy(messages = current.messages.filterNot { it.id == messageId && it.kind == ChatKind.VOICE_TRANSCRIPT })
            .also { conversations[conversationId] = it }
    }
    override suspend fun beginTurn(conversationId: String, photo: CapturedPhoto, question: String, includePhoto: Boolean): ConversationSnapshot {
        val turn = UUID.randomUUID().toString()
        val current = conversations.getValue(conversationId)
        val image = if (includePhoto) ChatMessage(sessionId = conversationId, turnId = turn,
            kind = ChatKind.PHOTO, source = ChatSource.PHONE, photo = photo) else null
        val text = ChatMessage(sessionId = conversationId, turnId = turn, kind = ChatKind.TEXT, source = ChatSource.PHONE, text = question)
        val pending = ChatMessage(sessionId = conversationId, turnId = turn, kind = ChatKind.ASSISTANT,
            source = ChatSource.ASSISTANT, status = ChatStatus.SENDING)
        return current.copy(messages = current.messages + listOfNotNull(image, text, pending), currentPhoto = photo)
            .also { conversations[conversationId] = it }
    }
    override suspend fun finishTurn(conversationId: String, messageId: String, answer: String?, failed: Boolean, notebookTitle: String?): ConversationSnapshot {
        val current = conversations.getValue(conversationId)
        return current.copy(messages = current.messages.map {
            if (it.id == messageId) it.copy(text = answer.orEmpty(), status = if (failed) ChatStatus.FAILED else ChatStatus.READY, notebookTitle = notebookTitle) else it
        }).also { conversations[conversationId] = it }
    }
    override suspend fun delete(id: String, currentId: String): ConversationSnapshot {
        conversations.remove(id)
        val remaining = conversations[currentId] ?: conversations.values.lastOrNull() ?: return newConversation()
        active = remaining.id
        return remaining
    }
    override suspend fun clearRecentMemory(currentId: String?, viewingSaved: Boolean): ConversationSnapshot {
        val unsavedIds = conversations.filterValues { !it.saved }.keys.toList()
        for (id in unsavedIds) {
            conversations.remove(id)
        }
        if (viewingSaved && currentId != null && conversations.containsKey(currentId)) {
            active = currentId
            return conversations.getValue(currentId)
        }
        return newConversation()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PersistentChatViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val photo = CapturedPhoto("photo", "file:///private-photo.jpg", Instant.EPOCH)

    @Test fun mossAssistantReplyUsesSameConversation() = runTest(main.dispatcher) {
        val store = MemoryConversationStore()
        val conversation = store.initialize()
        store.upsertMossTranscript(conversation.id, "turn-1", assistant = false, text = "question")
        store.upsertMossTranscript(conversation.id, "turn-1", assistant = true, text = "reply")
        val rows = store.conversations.getValue(conversation.id).messages
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.sessionId == conversation.id && it.turnId == "turn-1" })
        assertEquals(ChatKind.ASSISTANT, rows.last().kind)
        assertEquals(ChatStatus.READY, rows.last().status)
    }

    @Test fun mossFinalTranscriptPersistsBeforeAnyPlaybackAcknowledgement() = runTest(main.dispatcher) {
        val store = MemoryConversationStore()
        val conversation = store.initialize()
        var activeListener: MossLiveTransportListener? = null
        val transport = object : MossLiveTransport {
            override fun connect(listener: MossLiveTransportListener) { activeListener = listener }
            override fun sendText(text: String) = true
            override fun sendBinary(bytes: ByteArray) = true
            override fun queuedBytes() = 0L
            override fun close() = Unit
        }
        val source = object : RealtimeAudioSource {
            override suspend fun start(scope: CoroutineScope, onPcm: (ByteArray) -> Unit, onFailure: () -> Unit) = Unit
            override suspend fun stop() = Unit
        }
        val output = object : MossAudioOutput {
            override fun start(scope: CoroutineScope) = Unit
            override fun enqueue(context: com.visualassistant.app.mosslive.MossAudioContext, bytes: ByteArray) = Unit
            override fun audioDone(runId: String, turnId: String) = Unit
            override fun stopResponse(runId: String, turnId: String) = Unit
            override suspend fun stop() = Unit
            override fun isResponseFullyPlayed(responseKey: String) = false
        }
        val moss = MossLiveSessionController(transport, source, audioOutputFactory = { _, _, _ -> output })
        val model = ChatViewModel(LiveAssistant.text { _, _, _ -> "" }, { byteArrayOf() }, main.dispatcher, store, mossLive = moss)
        runCurrent()

        moss.start(backgroundScope, conversation.id)
        val socket = requireNotNull(activeListener)
        socket.onOpen()
        socket.onText("""{"action":"session_attached","data":{"session_id":"s1"}}""")
        socket.onText("""{"action":"session.updated","data":{"error":""}}""")
        runCurrent()
        socket.onText("""{"action":"response.audio.started","data":{"run_id":"r-partial","turn_id":"t-partial"}}""")
        socket.onText("""{"action":"response.interrupted","data":{"run_id":"r-partial","turn_id":"t-partial","text":"Incomplete fragment","reason":"playback_timeout"}}""")
        runCurrent()
        assertTrue("interrupted transcript must remain unsaved", store.conversations.getValue(conversation.id).messages.isEmpty())

        socket.onText("""{"action":"output.transcript.done","data":{"run_id":"r1","turn_id":"t1","text":"The answer is two."}}""")
        runCurrent()

        val saved = store.conversations.getValue(conversation.id).messages.single()
        assertEquals(ChatKind.ASSISTANT, saved.kind)
        assertEquals("The answer is two.", saved.text)
        assertEquals(ChatStatus.READY, saved.status)
        assertEquals("The answer is two.", model.state.value.messages.single().text)
        // The injected output never acknowledges audio; final transcript persistence is independent.
        moss.stop()
        runCurrent()
    }

    @Test fun liveTextRequiresExplicitSendAndPersistsResponse() = runTest(main.dispatcher) {
        val store = MemoryConversationStore()
        var calls = 0
        var sentPhoto: CapturedPhoto? = photo
        val model = ChatViewModel(LiveAssistant.text { question, image, _ ->
                calls++
                sentPhoto = image
                assertEquals("Which button?", question)
                "Use the input button."
            }, { byteArrayOf(1) }, main.dispatcher, store)
        runCurrent()
        assertTrue(model.state.value.liveMode)
        model.useTranscript("Which button?")
        runCurrent()
        assertEquals(0, calls)
        assertTrue(model.state.value.messages.isEmpty())
        model.sendLive(null)
        runCurrent()
        assertEquals(1, calls)
        assertNull(sentPhoto)
        assertEquals(listOf(ChatKind.TEXT, ChatKind.ASSISTANT), model.state.value.messages.map { it.kind })
        assertEquals("Use the input button.", store.conversations.getValue(model.state.value.sessionId).messages.last().text)
        assertEquals("", model.state.value.draft)
        assertTrue(model.state.value.liveMode)
    }

    @Test fun liveFailureRetainsDraftAndSelectedPhotoIsOptional() = runTest(main.dispatcher) {
        val store = MemoryConversationStore()
        var received: CapturedPhoto? = null
        val model = ChatViewModel(LiveAssistant.text { _, image, _ -> received = image; throw java.io.IOException("private provider detail") },
            { byteArrayOf(1) }, main.dispatcher, store)
        runCurrent()
        model.setLiveMode(true)
        model.updateDraft("Help")
        model.sendLive(photo)
        runCurrent()
        assertEquals(photo, received)
        assertEquals("Help", model.state.value.draft)
        assertEquals(ChatStatus.FAILED, model.state.value.messages.last().status)
        assertFalse(model.state.value.messages.toString().contains("private provider detail"))
    }

    @Test fun liveModeSendsRecentConversationAsContext() = runTest(main.dispatcher) {
        val store = MemoryConversationStore()
        val previous = store.newConversation()
        val pending = store.beginTextTurn(previous.id, "Help me set up the television")
        store.finishTurn(previous.id, pending.messages.last().id, "Check the remote input button.", failed = false)
        var receivedContext: List<ChatMessage> = emptyList()
        val model = ChatViewModel(LiveAssistant.text { _, _, context -> receivedContext = context; "Try the input button." },
            { byteArrayOf(1) }, main.dispatcher, store)
        runCurrent()
        model.setLiveMode(true)
        model.updateDraft("Which button next?")
        model.sendLive(null)
        runCurrent()
        assertEquals(
            listOf("Help me set up the television", "Check the remote input button."),
            receivedContext.map { it.text },
        )
    }

    private fun detector() = object : SpeechDetector {
        override fun hasSpeech(chunk: AudioChunk) = true
        override fun close() = Unit
    }

    @Test fun finalVoiceMessageIsSavedOnceWithoutClearingTypedDraftOrCallingAssistant() = runTest(main.dispatcher) {
        val store = MemoryConversationStore()
        val source = object : AudioSource {
            var channel: Channel<AudioChunk>? = null
            override suspend fun start(scope: CoroutineScope, onFailure: (SpeechProblem) -> Unit): ReceiveChannel<AudioChunk> =
                Channel<AudioChunk>(20).also { channel = it }
            override suspend fun stop() { channel?.close() }
        }
        val recognizer = object : StreamingSpeechRecognizer {
            lateinit var listener: (RecognitionEvent) -> Unit
            override fun load(directory: File, listener: (RecognitionEvent) -> Unit) { this.listener = listener }
            override fun start() = Unit
            override fun accept(chunk: AudioChunk) = Unit
            override fun stop() {
                listener(RecognitionEvent.Completed(7, "Which button?", 0f, 1f))
                listener(RecognitionEvent.Completed(7, "Which button?", 0f, 1f))
            }
            override fun close() = Unit
        }
        var assistantCalls = 0
        val speech = SpeechController({ File(".") }, source, { recognizer }, main.dispatcher, main.dispatcher, ::detector)
        val model = ChatViewModel(LiveAssistant.text { _, _, _ -> assistantCalls++; "Response" }, { byteArrayOf(1) }, main.dispatcher, store, speech)
        runCurrent()
        model.updateDraft("Keep this draft")
        model.startVoice()
        runCurrent()
        source.channel!!.trySend(AudioChunk(FloatArray(1600), 16000))
        runCurrent()
        assertTrue(model.state.value.messages.isEmpty())
        model.stopVoice()
        runCurrent()
        assertEquals("Keep this draft", model.state.value.draft)
        assertEquals(listOf(ChatKind.VOICE_TRANSCRIPT), model.state.value.messages.map { it.kind })
        assertEquals("Which button?", model.state.value.messages.single().text)
        assertTrue(model.pendingVoice.value.isEmpty())
        assertEquals(0, assistantCalls)
    }

    @Test fun retryAfterCommittedVoiceWriteDoesNotDuplicateMessage() = runTest(main.dispatcher) {
        val store = MemoryConversationStore().also { it.failVoiceAfterCommitOnce = true }
        val source = object : AudioSource {
            var channel: Channel<AudioChunk>? = null
            override suspend fun start(scope: CoroutineScope, onFailure: (SpeechProblem) -> Unit): ReceiveChannel<AudioChunk> =
                Channel<AudioChunk>(20).also { channel = it }
            override suspend fun stop() { channel?.close() }
        }
        val recognizer = object : StreamingSpeechRecognizer {
            lateinit var listener: (RecognitionEvent) -> Unit
            override fun load(directory: File, listener: (RecognitionEvent) -> Unit) { this.listener = listener }
            override fun start() = Unit
            override fun accept(chunk: AudioChunk) = Unit
            override fun stop() { listener(RecognitionEvent.Completed(1, "Save this", 0f, 1f)) }
            override fun close() = Unit
        }
        val speech = SpeechController({ File(".") }, source, { recognizer }, main.dispatcher, main.dispatcher, ::detector)
        val model = ChatViewModel(LiveAssistant.text { _, _, _ -> "Response" }, { byteArrayOf(1) }, main.dispatcher, store, speech)
        runCurrent()
        model.startVoice()
        runCurrent()
        source.channel!!.trySend(AudioChunk(FloatArray(1600), 16000))
        runCurrent()
        model.stopVoice()
        runCurrent()
        val pending = model.pendingVoice.value.single()
        assertEquals(com.visualassistant.app.voice.VoiceSaveStatus.FAILED, pending.status)
        assertEquals(1, store.conversations.getValue(model.state.value.sessionId).messages.size)
        model.savePendingVoice(pending.text.id)
        runCurrent()
        assertTrue(model.pendingVoice.value.isEmpty())
        assertEquals(listOf("Save this"), model.state.value.messages.map { it.text })
    }

    @Test fun savingConversationKeepsDraftAndMarksItSaved() = runTest(main.dispatcher) {
        val store = MemoryConversationStore()
        val model = ChatViewModel(LiveAssistant.text { _, _, _ -> "Response" }, { byteArrayOf(1) }, main.dispatcher, store)
        runCurrent()
        model.updateDraft("First message")
        model.sendTextWithoutPhoto()
        runCurrent()
        model.updateDraft("Unsent follow-up")
        model.saveConversation()
        runCurrent()
        assertTrue(model.state.value.saved)
        assertTrue(model.state.value.viewingSaved)
        assertEquals("Unsent follow-up", model.state.value.draft)
        assertTrue(model.state.value.history.single().saved)
        model.showRecent()
        runCurrent()
        assertFalse(model.state.value.viewingSaved)
    }

    @Test fun restoresConversationsSwitchesAndDeletes() = runTest(main.dispatcher) {
        val store = MemoryConversationStore()
        fun model() = ChatViewModel(LiveAssistant.text { _, _, _ -> "Real response" }, { byteArrayOf(1) }, main.dispatcher, store)
        val first = model()
        runCurrent()
        val firstId = first.state.value.sessionId
        first.submit(photo, "First question")
        runCurrent()
        first.newSession()
        runCurrent()
        val secondId = first.state.value.sessionId
        first.updateDraft("Text only")
        first.sendTextWithoutPhoto()
        runCurrent()
        val restarted = model()
        runCurrent()
        assertEquals(secondId, restarted.state.value.sessionId)
        assertEquals("Text only", restarted.state.value.messages.first().text)
        restarted.openConversation(firstId)
        runCurrent()
        assertEquals(listOf(ChatKind.PHOTO, ChatKind.TEXT, ChatKind.ASSISTANT), restarted.state.value.messages.map { it.kind })
        assertEquals("Real response", restarted.state.value.messages.last().text)
        assertEquals(photo, restarted.state.value.currentPhoto)
        restarted.deleteConversation(firstId)
        runCurrent()
        assertEquals(secondId, restarted.state.value.sessionId)
        assertFalse(restarted.state.value.history.any { it.id == firstId })
        assertFalse(store.conversations.containsKey(firstId))
    }

    @Test fun persistentTurnWithNotebookLmTriggersSpeakerAndPreservesTitle() = runTest(main.dispatcher) {
        val store = MemoryConversationStore()
        val speaker = object : com.visualassistant.app.voice.Speaker {
            var spokenText: String? = null
            override fun speak(text: String, onStart: () -> Unit, onDone: () -> Unit) {
                spokenText = text
                onStart()
            }
            override fun stop() {}
            override fun isSpeaking(): Boolean = spokenText != null
            override fun shutdown() {}
        }
        val assistant = LiveAssistant { _, _, _ ->
            AssistantTurnResult(
                reply = "Clean the lint trap before each cycle.",
                notebookTitle = "Dryer User Manual",
                readAloud = true,
                retrievalStatus = "notebooklm",
            )
        }
        val model = ChatViewModel(
            assistant = assistant,
            thumbnails = { byteArrayOf(1) },
            io = main.dispatcher,
            store = store,
            speaker = speaker,
        )
        runCurrent()
        model.updateDraft("How do I clean the dryer?")
        model.sendTextWithoutPhoto()
        runCurrent()

        val messages = model.state.value.messages
        val response = messages.last()
        assertEquals("Clean the lint trap before each cycle.", response.text)
        assertEquals("Dryer User Manual", response.notebookTitle)
        assertEquals("Clean the lint trap before each cycle.", speaker.spokenText)
    }

    @Test fun clearRecentRollingMemoryDeletesUnsavedChatsAndKeepsSavedChats() = runTest(main.dispatcher) {
        val store = MemoryConversationStore()
        val conv1 = store.initialize()
        store.upsertMossTranscript(conv1.id, "turn-1", assistant = false, text = "Unsaved message 1")
        store.save(conv1.id)

        val conv2 = store.newConversation()
        store.upsertMossTranscript(conv2.id, "turn-2", assistant = false, text = "Unsaved message 2")

        val model = ChatViewModel(
            assistant = LiveAssistant { _, _, _ -> AssistantTurnResult("reply") },
            thumbnails = { byteArrayOf(1) },
            io = main.dispatcher,
            store = store,
        )
        runCurrent()

        assertEquals(2, store.conversations.size)
        assertTrue(store.conversations.containsKey(conv1.id))
        assertTrue(store.conversations.containsKey(conv2.id))

        model.clearRecentRollingMemory()
        runCurrent()

        // Unsaved conversation is removed, saved conversation is retained
        assertEquals(2, store.conversations.size) // saved conv1 + freshly created empty recent conv
        assertTrue(store.conversations.containsKey(conv1.id))
        assertFalse(store.conversations.containsKey(conv2.id))
        assertTrue(store.conversations.getValue(conv1.id).saved)
        assertEquals(0, model.state.value.messages.size)
        assertFalse(model.state.value.viewingSaved)
    }

    @Test fun clearRecentRollingMemoryWhileViewingSavedKeepsSavedChatActive() = runTest(main.dispatcher) {
        val store = MemoryConversationStore()
        val savedConv = store.initialize()
        store.upsertMossTranscript(savedConv.id, "turn-saved", assistant = false, text = "Saved message")
        store.save(savedConv.id)

        val unsavedConv = store.newConversation()
        store.upsertMossTranscript(unsavedConv.id, "turn-unsaved", assistant = false, text = "Rolling message")

        val model = ChatViewModel(
            assistant = LiveAssistant { _, _, _ -> AssistantTurnResult("reply") },
            thumbnails = { byteArrayOf(1) },
            io = main.dispatcher,
            store = store,
        )
        runCurrent()

        model.openConversation(savedConv.id)
        runCurrent()

        assertTrue(model.state.value.viewingSaved)
        assertEquals(savedConv.id, model.state.value.sessionId)
        assertEquals(1, model.state.value.messages.size)

        model.clearRecentRollingMemory()
        runCurrent()

        assertTrue(model.state.value.viewingSaved)
        assertEquals(savedConv.id, model.state.value.sessionId)
        assertEquals(1, model.state.value.messages.size)
        assertEquals("Saved message", model.state.value.messages.first().text)
        assertFalse(store.conversations.containsKey(unsavedConv.id))
    }
}
