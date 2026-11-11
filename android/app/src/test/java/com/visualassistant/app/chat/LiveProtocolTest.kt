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
}
