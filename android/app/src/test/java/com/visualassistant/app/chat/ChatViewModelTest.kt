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
        val model = ChatViewModel({ _, _, _ -> "Actual assistant response" }, { byteArrayOf(1, 2) }, main.dispatcher)
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
        val model = ChatViewModel({ _, _, _ -> throw IOException("private details") }, { byteArrayOf(1) }, main.dispatcher)
        model.submit(photo, "Help")
        runCurrent()
        assertEquals("Help", model.state.value.draft)
        assertEquals(ChatStatus.FAILED, model.state.value.messages.last().status)
        assertFalse(model.state.value.sending)
        assertFalse(model.state.value.messages.toString().contains("private details"))
    }

    @Test fun newSessionRejectsLateResponseAndClearsImages() = runTest(main.dispatcher) {
        val gate = CompletableDeferred<String>()
        val model = ChatViewModel({ _, _, _ -> withContext(NonCancellable) { gate.await() } }, { byteArrayOf(1) }, main.dispatcher)
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
        val model = ChatViewModel({ _, _, _ -> calls++; gate.await() }, { byteArrayOf(1) }, main.dispatcher)
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
        val model = ChatViewModel({ _, _, _ -> "Actual" }, { byteArrayOf(1) }, main.dispatcher)
        repeat(35) { index ->
            model.submit(photo, "Question $index")
            runCurrent()
        }
        assertEquals(60, model.state.value.messages.size)
        assertTrue(model.state.value.historyTruncated)
        assertEquals("Question 34", model.state.value.messages[58].text)
        assertEquals("Actual", model.state.value.messages.last().text)
    }
}
