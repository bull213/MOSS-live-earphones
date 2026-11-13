package com.visualassistant.app.mosslive

import com.visualassistant.app.camera.CapturedPhoto
import com.visualassistant.app.chat.ChatKind
import com.visualassistant.app.chat.ChatMessage
import com.visualassistant.app.chat.ChatSource
import com.visualassistant.app.chat.ChatStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class MossSessionContextTest {
    @Test
    fun buildsBoundedRoleLabeledContextAndNewestThreePhotosInTimelineOrder() {
        val messages = (1..5).map { index ->
            ChatMessage(id = "p$index", sessionId = "s", turnId = "t$index", kind = ChatKind.PHOTO,
                source = ChatSource.PHONE, photo = CapturedPhoto("$index", "file://$index", Instant.EPOCH))
        } + listOf(
            ChatMessage(sessionId = "s", turnId = "t", kind = ChatKind.TEXT, source = ChatSource.PHONE, text = "What is this?"),
            ChatMessage(sessionId = "s", turnId = "t", kind = ChatKind.ASSISTANT, source = ChatSource.ASSISTANT, text = "A cup."),
            ChatMessage(sessionId = "s", turnId = "pending", kind = ChatKind.ASSISTANT, source = ChatSource.ASSISTANT,
                text = "partial", status = ChatStatus.PARTIAL),
        )
        val context = MossSessionContext.from(messages) { it.photo?.id?.toByteArray() }
        assertTrue(context.instructions.contains("User: [Shared a photo]"))
        assertTrue(context.instructions.contains("User: What is this?"))
        assertTrue(context.instructions.contains("Assistant: A cup."))
        assertTrue(!context.instructions.contains("partial"))
        assertTrue(context.instructions.contains("The latest photo from the user's recent chat history is active in your live camera video stream"))
        assertEquals(listOf("5", "4", "3"), context.photos.map { it.decodeToString() })
    }

    @Test
    fun prioritizesStagedPhotoAndDeduplicatesAgainstHistory() {
        val messages = (1..3).map { index ->
            ChatMessage(id = "p$index", sessionId = "s", turnId = "t$index", kind = ChatKind.PHOTO,
                source = ChatSource.PHONE, photo = CapturedPhoto("$index", "file://$index", Instant.EPOCH))
        }
        val staged = CapturedPhoto("staged-current", "file://staged", Instant.now())
        val context = MossSessionContext.from(
            messages = messages,
            stagedPhoto = staged,
            encodeCapturedPhoto = { it.id.toByteArray() },
        )

        // Staged photo is first, followed by up to 2 historical photos (newest first)
        assertEquals(3, context.photos.size)
        assertEquals("staged-current", context.photos[0].decodeToString())
        assertEquals("3", context.photos[1].decodeToString())
        assertEquals("2", context.photos[2].decodeToString())
        assertTrue(context.instructions.contains("A current user photo is attached as primary visual context"))
    }

    @Test
    fun deduplicatesIfStagedPhotoMatchesExistingMessagePhoto() {
        val messages = (1..3).map { index ->
            ChatMessage(id = "p$index", sessionId = "s", turnId = "t$index", kind = ChatKind.PHOTO,
                source = ChatSource.PHONE, photo = CapturedPhoto("$index", "file://$index", Instant.EPOCH))
        }
        // Staged photo is photo "3" (already in messages)
        val staged = CapturedPhoto("3", "file://3", Instant.now())
        val context = MossSessionContext.from(
            messages = messages,
            stagedPhoto = staged,
            encodeCapturedPhoto = { it.id.toByteArray() },
        )

        // Photo 3 should only appear once (at position 0), plus photo 2 and 1
        assertEquals(3, context.photos.size)
        assertEquals("3", context.photos[0].decodeToString())
        assertEquals("2", context.photos[1].decodeToString())
        assertEquals("1", context.photos[2].decodeToString())
    }

    @Test
    fun fromAudioOnlyExcludesAllPhotosAndVideoPrompts() {
        val messages = (1..3).map { index ->
            ChatMessage(id = "p$index", sessionId = "s", turnId = "t$index", kind = ChatKind.PHOTO,
                source = ChatSource.PHONE, photo = CapturedPhoto("$index", "file://$index", Instant.EPOCH))
        } + listOf(
            ChatMessage(sessionId = "s", turnId = "t", kind = ChatKind.TEXT, source = ChatSource.PHONE, text = "Hello audio"),
        )
        val context = MossSessionContext.fromAudioOnly(messages)

        // Must have zero photos attached
        assertTrue(context.photos.isEmpty())
        // Instructions must not prompt MOSS about camera video stream
        assertTrue(!context.instructions.contains("live camera video stream"))
        assertTrue(!context.instructions.contains("camera feed"))
        // Must still preserve dialogue text history
        assertTrue(context.instructions.contains("User: Hello audio"))
    }
}
