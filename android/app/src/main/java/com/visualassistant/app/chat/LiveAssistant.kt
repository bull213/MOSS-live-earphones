package com.visualassistant.app.chat

import android.content.ContentResolver
import com.visualassistant.app.camera.CapturedPhoto
import com.visualassistant.app.network.BackendEndpoint
import com.visualassistant.app.network.ServerScanner
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal fun interface LiveAssistant {
    suspend fun answer(question: String, photo: CapturedPhoto?, history: List<ChatMessage>): String
}

internal fun liveContext(history: List<ChatMessage>): List<Pair<String, String>> {
    var remaining = 4000
    val result = mutableListOf<Pair<String, String>>()
    val selected = history.asReversed().asSequence()
        .filter { it.status == ChatStatus.READY && it.text.isNotBlank() &&
            it.kind in setOf(ChatKind.TEXT, ChatKind.ASSISTANT) }
        .take(8)
    for (message in selected) {
        val text = message.text.takeLast(minOf(remaining, 1000))
        if (text.isNotBlank()) {
            result.add((if (message.kind == ChatKind.ASSISTANT) "assistant" else "user") to text)
            remaining -= text.length
        }
        if (remaining == 0) break
    }
    return result.asReversed()
}

/** The development endpoint is reachable over adb reverse or local Wi-Fi; keys stay on the backend. */
internal class PhoneLiveAssistant(
    private val encoder: PhotoEncoder,
    private val endpoint: BackendEndpoint? = null,
    private val openConnection: () -> HttpURLConnection = {
        val targetUrl = endpoint?.httpUrl("v1/assistant/turns") ?: "http://127.0.0.1:8000/v1/assistant/turns"
        URL(targetUrl).openConnection() as HttpURLConnection
    },
) : LiveAssistant {
    constructor(resolver: ContentResolver, endpoint: BackendEndpoint? = null) : this(
        AndroidPhotoEncoder(resolver),
        endpoint,
    )

    override suspend fun answer(question: String, photo: CapturedPhoto?, history: List<ChatMessage>): String {
        endpoint?.let { ep ->
            val resolved = ServerScanner.findOrResolveBackend(ep.host)
            if (resolved != null) {
                ep.host = if (resolved.contains(":")) resolved else "$resolved:8000"
            }
        }
        return suspendCancellableCoroutine { continuation ->
            val connectionRef = AtomicReference<HttpURLConnection?>()
            val worker = Thread({
                try {
                    val image = photo?.let { encoder.encode(it) }
                    if (!continuation.isActive) return@Thread
                    val boundary = "live-" + UUID.randomUUID()
                    val body = LiveProtocol.requestBody(boundary, question, LiveProtocol.historyJson(history), image)
                    val connection = openConnection()
                    connectionRef.set(connection)
                    if (!continuation.isActive) { connection.disconnect(); return@Thread }
                    try {
                        val answer = send(connection, boundary, body)
                        if (continuation.isActive) continuation.resume(answer)
                    } finally { connection.disconnect(); connectionRef.set(null) }
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }, "live-assistant")
            continuation.invokeOnCancellation { connectionRef.getAndSet(null)?.disconnect(); worker.interrupt() }
            worker.start()
        }
    }

    private fun send(connection: HttpURLConnection, boundary: String, body: ByteArrayOutputStream): String {
        connection.requestMethod = "POST"
        connection.connectTimeout = 5_000
        connection.readTimeout = 35_000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        connection.setFixedLengthStreamingMode(body.size())
        connection.outputStream.use { body.writeTo(it) }
        if (connection.responseCode != 200) throw IOException("Assistant request failed")
        val raw = connection.inputStream.bufferedReader(Charsets.UTF_8).use(LiveProtocol::readResponse)
        return LiveProtocol.parseResponse(raw)
    }
}
