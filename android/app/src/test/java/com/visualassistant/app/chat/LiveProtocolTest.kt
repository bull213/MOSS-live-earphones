package com.visualassistant.app.chat

import com.visualassistant.app.camera.CapturedPhoto
import java.io.IOException
import java.io.StringReader
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LiveProtocolTest {
    @Test fun multipartContainsQuestionHistoryAndOptionalImage() {
        val body = LiveProtocol.requestBody("boundary", "Question?", "[]", byteArrayOf(1, 2, 3))
        val text = body.toByteArray().toString(Charsets.ISO_8859_1)
        assertTrue(text.contains("name=\"question\"\r\nContent-Type: text/plain; charset=utf-8\r\n\r\nQuestion?"))
        assertTrue(text.contains("name=\"history_json\""))
        assertTrue(text.contains("name=\"image\"; filename=\"photo.jpg\""))
        assertTrue(text.endsWith("\r\n--boundary--\r\n"))
        assertFalse(LiveProtocol.requestBody("boundary", "Question?", "[]", null)
            .toByteArray().toString(Charsets.UTF_8).contains("name=\"image\""))
    }

    @Test fun multipartContainsPhotoIdWhenSupplied() {
        val body = LiveProtocol.requestBody("boundary", "Question?", "[]", byteArrayOf(1, 2, 3), "test-photo-id")
        val text = body.toByteArray().toString(Charsets.ISO_8859_1)
        assertTrue(text.contains("name=\"photo_id\"\r\nContent-Type: text/plain; charset=utf-8\r\n\r\ntest-photo-id"))
    }

    @Test fun historyJsonMapsPhotoIdFromTurn() {
        val photo = CapturedPhoto("photo-123", "file:///photo.jpg", Instant.EPOCH)
        val photoMsg = ChatMessage(sessionId = "s", turnId = "turn-1", kind = ChatKind.PHOTO, source = ChatSource.PHONE, photo = photo)
        val textMsg = ChatMessage(sessionId = "s", turnId = "turn-1", kind = ChatKind.TEXT, source = ChatSource.PHONE, text = "Look at this")
        val json = LiveProtocol.historyJson(listOf(photoMsg, textMsg))
        assertTrue(json.contains("\"photo_id\":\"photo-123\""))
        assertTrue(json.contains("\"text\":\"Look at this\""))
    }

    @Test fun responseReaderRejectsOversizedInput() {
        assertEquals("x".repeat(65536), LiveProtocol.readResponse(StringReader("x".repeat(65536))))
        assertThrows(IOException::class.java) { LiveProtocol.readResponse(StringReader("x".repeat(65537))) }
    }

    @Test fun cancellationDuringPhotoEncodingDoesNotOpenConnection() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        var opened = false
        val assistant = PhoneLiveAssistant(encoder = {
            started.countDown()
            try { release.await() } catch (_: InterruptedException) { }
            byteArrayOf(1)
        }, openConnection = { opened = true; throw IOException("Unexpected connection") })
        val photo = CapturedPhoto("photo", "file:///photo.jpg", Instant.EPOCH)
        val job = launch { assistant.answer("Question?", photo, emptyList()) }
        kotlinx.coroutines.yield()
        assertTrue(started.await(5, TimeUnit.SECONDS))
        job.cancelAndJoin()
        release.countDown()
        assertFalse(opened)
    }

    @Test fun parseResponseParsesNormalResponse() {
        val json = """{"reply": "Hello there", "retrieval_status": "unused"}"""
        val result = LiveProtocol.parseResponse(json)
        assertEquals("Hello there", result.reply)
        assertNull(result.notebookTitle)
        assertFalse(result.readAloud)
        assertEquals("unused", result.retrievalStatus)
        assertTrue(result.sources.isEmpty())
    }

    @Test fun parseResponseFormatsLocalSources() {
        val json = """{"reply": "Check the filter", "retrieval_status": "used", "sources": [{"title": "Washer Manual"}]}"""
        val result = LiveProtocol.parseResponse(json)
        assertEquals("Check the filter\n\nSources: Washer Manual", result.reply)
        assertEquals(listOf("Washer Manual"), result.sources)
        assertFalse(result.readAloud)
    }

    @Test fun parseResponseParsesNotebookLMStructuredResult() {
        val json = """{
            "reply": "Press the Defrost button and enter 500 grams.",
            "retrieval_status": "notebooklm",
            "notebook_title": "Microwave User Manual",
            "read_aloud": true,
            "sources": [{"title": "Microwave User Manual"}]
        }"""
        val result = LiveProtocol.parseResponse(json)
        assertEquals("Press the Defrost button and enter 500 grams.", result.reply)
        assertEquals("Microwave User Manual", result.notebookTitle)
        assertTrue(result.readAloud)
        assertEquals("notebooklm", result.retrievalStatus)
    }
}
