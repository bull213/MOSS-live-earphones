package com.visualassistant.app.chat

import com.visualassistant.app.camera.CapturedPhoto
import com.visualassistant.app.camera.MainDispatcherRule
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val photo = CapturedPhoto("photo-1", "file:///test.jpg", Instant.EPOCH)

    @Test fun photoQuestionAndActualReplyShareTurnInOrder() = runTest(main.dispatcher) {
        val model = ChatViewModel(LiveAssistant.text { _, _, _ -> "Actual assistant response" }, { byteArrayOf(1, 2) }, main.dispatcher)
        model.submit(photo, "  Which button?  ")
        runCurrent()
        val events = model.state.value.messages
        assertEquals(listOf(ChatKind.PHOTO, ChatKind.TEXT, ChatKind.ASSISTANT), events.map { it.kind })
        assertEquals(1, events.map { it.turnId }.distinct().size)
        assertEquals("Which button?", events[1].text)
        assertEquals("Actual assistant response", events[2].text)
        assertArrayEquals(byteArrayOf(1, 2), events[0].thumbnail)
        assertTrue(events.all { it.status == ChatStatus.READY })
        assertEquals("", model.state.value.draft)
        model.submit(photo, "Another question")
        runCurrent()
        assertEquals(1, model.state.value.messages.count { it.kind == ChatKind.PHOTO })
        assertEquals(2, model.state.value.messages.count { it.kind == ChatKind.ASSISTANT })
    }

    @Test fun failureKeepsDraftAndShowsFailedMessage() = runTest(main.dispatcher) {
        val model = ChatViewModel(LiveAssistant.text { _, _, _ -> throw IOException("private details") }, { byteArrayOf(1) }, main.dispatcher)
        model.submit(photo, "Help")
        runCurrent()
        assertEquals("Help", model.state.value.draft)
        assertEquals(ChatStatus.FAILED, model.state.value.messages.last().status)
        assertFalse(model.state.value.sending)
        assertFalse(model.state.value.messages.toString().contains("private details"))
    }

    @Test fun newSessionRejectsLateResponseAndClearsImages() = runTest(main.dispatcher) {
        val gate = CompletableDeferred<String>()
        val model = ChatViewModel(LiveAssistant.text { _, _, _ -> withContext(NonCancellable) { gate.await() } }, { byteArrayOf(1) }, main.dispatcher)
        model.submit(photo, "Help")
        runCurrent()
        val previousSession = model.state.value.sessionId
        model.newSession()
        gate.complete("old response")
        runCurrent()
        assertTrue(model.state.value.messages.isEmpty())
        assertNotEquals(previousSession, model.state.value.sessionId)
        assertFalse(model.state.value.sending)
    }

    @Test fun emptyAndDuplicateRequestsDoNotSend() = runTest(main.dispatcher) {
        var calls = 0
        val gate = CompletableDeferred<String>()
        val model = ChatViewModel(LiveAssistant.text { _, _, _ -> calls++; gate.await() }, { byteArrayOf(1) }, main.dispatcher)
        model.submit(photo, "  ")
        model.submit(photo, "Help")
        model.submit(photo, "Help again")
        runCurrent()
        assertEquals(1, calls)
        assertTrue(model.state.value.sending)
        gate.complete("Actual")
        runCurrent()
    }

    @Test fun longSessionIsBoundedAndReportsTruncation() = runTest(main.dispatcher) {
        val model = ChatViewModel(LiveAssistant.text { _, _, _ -> "Actual" }, { byteArrayOf(1) }, main.dispatcher)
        repeat(35) { index ->
            model.submit(photo, "Question $index")
            runCurrent()
        }
        assertEquals(60, model.state.value.messages.size)
        assertTrue(model.state.value.historyTruncated)
        assertEquals("Question 34", model.state.value.messages[58].text)
        assertEquals("Actual", model.state.value.messages.last().text)
    }

    @Test fun readAloudTriggersSpeakerAndPreservesNotebookTitle() = runTest(main.dispatcher) {
        val speaker = object : com.visualassistant.app.voice.Speaker {
            var spokenText: String? = null
            var speakCalls = 0
            var stopCalls = 0
            override fun speak(text: String, onStart: () -> Unit, onDone: () -> Unit) {
                speakCalls++
                spokenText = text
                onStart()
            }
            override fun stop() {
                stopCalls++
            }
            override fun isSpeaking(): Boolean = spokenText != null
            override fun shutdown() {}
        }
        val assistant = LiveAssistant { _, _, _ ->
            AssistantTurnResult(
                reply = "Press defrost button",
                notebookTitle = "Microwave Guide",
                readAloud = true,
                retrievalStatus = "notebooklm",
            )
        }
        val model = ChatViewModel(
            assistant = assistant,
            thumbnails = { byteArrayOf(1) },
            io = main.dispatcher,
            speaker = speaker,
        )
        model.submit(photo, "How do I defrost?")
        runCurrent()

        val lastMessage = model.state.value.messages.last()
        assertEquals("Press defrost button", lastMessage.text)
        assertEquals("Microwave Guide", lastMessage.notebookTitle)
        assertTrue(lastMessage.readAloud)
        assertEquals(1, speaker.speakCalls)
        assertEquals("Press defrost button", speaker.spokenText)
        assertEquals(lastMessage.id, model.speakingMessageId.value)

        model.replaySpeech(lastMessage.id, lastMessage.text)
        assertEquals(1, speaker.stopCalls)
        assertNull(model.speakingMessageId.value)
    }

    @Test fun textTurnAfterPhotoPassesContextWithoutAttachingPhoto() = runTest(main.dispatcher) {
        var receivedPhoto: CapturedPhoto? = null
        val assistant = LiveAssistant { _, p, _ ->
            receivedPhoto = p
            AssistantTurnResult("Turn answered")
        }
        val model = ChatViewModel(assistant, { byteArrayOf(1) }, main.dispatcher)
        model.submit(photo, "First question")
        runCurrent()
        assertNotNull(receivedPhoto)

        receivedPhoto = null
        model.updateDraft("Follow-up question")
        model.sendTextWithoutPhoto()
        runCurrent()
        assertNull(receivedPhoto)
    }

    @Test fun notebookSelectionAndTurnExecutionWithEarconAndTts() = runTest(main.dispatcher) {
        var requestedNotebookId: String? = null
        val testAssistant = object : LiveAssistant {
            override suspend fun answer(
                question: String,
                photo: CapturedPhoto?,
                history: List<ChatMessage>,
                notebookId: String?,
            ): AssistantTurnResult {
                requestedNotebookId = notebookId
                return AssistantTurnResult(
                    reply = "To clean the filter, turn it counter-clockwise.",
                    notebookTitle = "Washer Manual",
                    readAloud = true,
                )
            }
        }
        val speaker = object : com.visualassistant.app.voice.Speaker {
            var spokenText: String? = null
            var speakCalls = 0
            var stopCalls = 0
            override fun speak(text: String, onStart: () -> Unit, onDone: () -> Unit) {
                speakCalls++
                spokenText = text
                onStart()
            }
            override fun stop() { stopCalls++ }
            override fun isSpeaking(): Boolean = speakCalls > stopCalls
            override fun shutdown() {}
        }
        var earconPlayCount = 0
        var earconStopCount = 0
        val earcon = object : com.visualassistant.app.voice.EarconPlayer {
            override fun playWaiting(scope: kotlinx.coroutines.CoroutineScope?) { earconPlayCount++ }
            override fun stopWaiting() { earconStopCount++ }
        }
        val model = ChatViewModel(
            assistant = testAssistant,
            thumbnails = { byteArrayOf(1) },
            io = main.dispatcher,
            speaker = speaker,
            earconPlayer = earcon,
        )

        val notebook = com.visualassistant.app.network.NotebookItem("nb-washer", "Washer Manual")
        model.selectNotebook(notebook)
        assertEquals(notebook, model.state.value.activeNotebook)
        assertTrue(model.state.value.messages.isEmpty())
        earconStopCount = 0

        model.updateDraft("How do I clean the filter?")
        model.sendTextWithoutPhoto()
        assertEquals(0, earconPlayCount)
        runCurrent()

        assertEquals("nb-washer", requestedNotebookId)
        val msgs = model.state.value.messages
        assertEquals(2, msgs.size)
        assertEquals("How do I clean the filter?", msgs[0].text)
        assertEquals("To clean the filter, turn it counter-clockwise.", msgs[1].text)
        assertEquals("Washer Manual", msgs[1].notebookTitle)
        assertFalse(msgs[1].readAloud)
        assertEquals(0, speaker.speakCalls)

        // Manual readout on bubble works
        model.replaySpeech(msgs[1].id, msgs[1].text)
        assertEquals(1, speaker.speakCalls)
        assertEquals("To clean the filter, turn it counter-clockwise.", speaker.spokenText)

        model.selectNotebook(null)
        assertNull(model.state.value.activeNotebook)
    }
}
