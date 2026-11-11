package com.visualassistant.app.chat

import org.junit.Assert.*
import org.junit.Test

class LiveContextTest {
    private fun message(kind: ChatKind, text: String, status: ChatStatus = ChatStatus.READY) =
        ChatMessage(sessionId = "session", turnId = "turn", kind = kind,
            source = if (kind == ChatKind.ASSISTANT) ChatSource.ASSISTANT else ChatSource.PHONE,
            text = text, status = status)

    @Test fun excludesVoiceAndFailedMessagesAndPreservesOrder() {
        val result = liveContext(listOf(message(ChatKind.TEXT, "First"), message(ChatKind.VOICE_TRANSCRIPT, "Private voice"),
            message(ChatKind.ASSISTANT, "Answer"), message(ChatKind.ASSISTANT, "Failed", ChatStatus.FAILED),
            message(ChatKind.TEXT, "Next")))
        assertEquals(listOf("user" to "First", "assistant" to "Answer", "user" to "Next"), result)
    }

    @Test fun boundsMessagesAndTotalCharacters() {
        val result = liveContext((1..10).map { message(ChatKind.TEXT, "x".repeat(1500)) })
        assertEquals(4, result.size)
        assertEquals(4000, result.sumOf { it.second.length })
        assertTrue(result.all { it.second.length <= 1000 })
    }
}
