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

internal data class LiveHistoryItem(
    val role: String,
    val text: String,
    val photoId: String? = null,
)

internal interface LiveAssistant {
    suspend fun answer(
        question: String,
        photo: CapturedPhoto?,
        history: List<ChatMessage>,
        notebookId: String? = null,
    ): AssistantTurnResult

    companion object {
        operator fun invoke(block: suspend (question: String, photo: CapturedPhoto?, history: List<ChatMessage>) -> AssistantTurnResult): LiveAssistant =
            object : LiveAssistant {
                override suspend fun answer(
                    question: String,
                    photo: CapturedPhoto?,
                    history: List<ChatMessage>,
                    notebookId: String?,
                ): AssistantTurnResult = block(question, photo, history)
            }

        fun text(block: suspend (question: String, photo: CapturedPhoto?, history: List<ChatMessage>) -> String): LiveAssistant =
            invoke { q, p, h -> AssistantTurnResult(block(q, p, h)) }
    }
}

internal fun liveContext(history: List<ChatMessage>): List<LiveHistoryItem> {
    var remaining = 4000
    val result = mutableListOf<LiveHistoryItem>()
    val photoByTurn = history.filter { it.kind == ChatKind.PHOTO && it.photo != null }
        .associate { it.turnId to it.photo!!.id }
    val selected = history.asReversed().asSequence()
        .filter { it.status == ChatStatus.READY && it.text.isNotBlank() &&
            it.kind in setOf(ChatKind.TEXT, ChatKind.ASSISTANT) }
        .take(8)
    for (message in selected) {
        val text = message.text.takeLast(minOf(remaining, 1000))
        if (text.isNotBlank()) {
            val role = if (message.kind == ChatKind.ASSISTANT) "assistant" else "user"
            val photoId = if (message.kind == ChatKind.TEXT) photoByTurn[message.turnId] else null
            result.add(LiveHistoryItem(role = role, text = text, photoId = photoId))
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
        val conn = URL(targetUrl).openConnection() as HttpURLConnection
        endpoint?.authorizationHeader()?.let {
            conn.setRequestProperty("Authorization", it)
        }
        conn
    },
) : LiveAssistant {
    constructor(resolver: ContentResolver, endpoint: BackendEndpoint? = null) : this(
        AndroidPhotoEncoder(resolver),
        endpoint,
    )

    override suspend fun answer(
        question: String,
        photo: CapturedPhoto?,
        history: List<ChatMessage>,
        notebookId: String?,
    ): AssistantTurnResult {
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
                    val body = LiveProtocol.requestBody(
                        boundary,
                        question,
                        LiveProtocol.historyJson(history),
                        image,
                        photo?.id,
                        notebookId,
                    )
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

    private fun send(connection: HttpURLConnection, boundary: String, body: ByteArrayOutputStream): AssistantTurnResult {
        connection.requestMethod = "POST"
        connection.connectTimeout = 5_000
        connection.readTimeout = 55_000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        endpoint?.authorizationHeader()?.let {
            connection.setRequestProperty("Authorization", it)
        }
        connection.setFixedLengthStreamingMode(body.size())
        connection.outputStream.use { body.writeTo(it) }
        if (connection.responseCode != 200) {
            val errMessage = try {
                val errStream = connection.errorStream
                if (errStream != null) {
                    val errJson = org.json.JSONObject(errStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
                    errJson.optJSONObject("error")?.optString("message") ?: "HTTP ${connection.responseCode}"
                } else "HTTP ${connection.responseCode}"
            } catch (_: Exception) { "HTTP ${connection.responseCode}" }
            if (connection.responseCode == 401) throw IOException("Google NotebookLM authentication expired. Please reconnect in Settings.")
            throw IOException("Assistant request failed: $errMessage")
        }
        val raw = connection.inputStream.bufferedReader(Charsets.UTF_8).use(LiveProtocol::readResponse)
        return LiveProtocol.parseResponse(raw)
    }
}
