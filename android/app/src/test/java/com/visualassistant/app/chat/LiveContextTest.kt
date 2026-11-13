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
        assertEquals(
            listOf(
                LiveHistoryItem("user", "First"),
                LiveHistoryItem("assistant", "Answer"),
                LiveHistoryItem("user", "Next")
            ),
            result
        )
    }

    @Test fun associatesPhotoIdByTurnId() {
        val photo = com.visualassistant.app.camera.CapturedPhoto(
            id = "photo-123",
            localUri = "file:///photo.jpg",
            capturedAt = java.time.Instant.EPOCH
        )
        val photoMsg = ChatMessage(sessionId = "session", turnId = "turn-1", kind = ChatKind.PHOTO,
            source = ChatSource.PHONE, text = "", status = ChatStatus.READY, photo = photo)
        val textMsg = ChatMessage(sessionId = "session", turnId = "turn-1", kind = ChatKind.TEXT,
            source = ChatSource.PHONE, text = "Look at this", status = ChatStatus.READY)
        val result = liveContext(listOf(photoMsg, textMsg))
        assertEquals(listOf(LiveHistoryItem("user", "Look at this", photoId = "photo-123")), result)
    }

    @Test fun boundsMessagesAndTotalCharacters() {
        val result = liveContext((1..10).map { message(ChatKind.TEXT, "x".repeat(1500)) })
        assertEquals(4, result.size)
        assertEquals(4000, result.sumOf { it.text.length })
        assertTrue(result.all { it.text.length <= 1000 })
    }
}
