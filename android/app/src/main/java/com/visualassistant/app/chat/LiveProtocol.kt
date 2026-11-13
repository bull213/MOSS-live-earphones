package com.visualassistant.app.chat

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.Reader
import org.json.JSONArray
import org.json.JSONObject

data class AssistantTurnResult(
    val reply: String,
    val notebookTitle: String? = null,
    val readAloud: Boolean = false,
    val retrievalStatus: String = "unused",
    val sources: List<String> = emptyList(),
)

/** Request and response formatting for the live endpoint. */
internal object LiveProtocol {
    fun historyJson(history: List<ChatMessage>): String = JSONArray(liveContext(history).map { item ->
        val obj = JSONObject().put("role", item.role).put("text", item.text)
        if (item.photoId != null) {
            obj.put("photo_id", item.photoId)
        }
        obj
    }).toString()

    fun requestBody(
        boundary: String,
        question: String,
        historyJson: String,
        image: ByteArray?,
        photoId: String? = null,
        notebookId: String? = null,
    ): ByteArrayOutputStream {
        val body = ByteArrayOutputStream()
        fun field(name: String, value: String) {
            body.write(("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n\r\n$value\r\n").toByteArray(Charsets.UTF_8))
        }
        field("question", question)
        field("history_json", historyJson)
        if (photoId != null) {
            field("photo_id", photoId)
        }
        if (notebookId != null) {
            field("notebook_id", notebookId)
        }
        if (image != null) {
            body.write(("--$boundary\r\nContent-Disposition: form-data; name=\"image\"; filename=\"photo.jpg\"\r\n" +
                "Content-Type: image/jpeg\r\n\r\n").toByteArray(Charsets.UTF_8))
            body.write(image)
            body.write("\r\n".toByteArray(Charsets.UTF_8))
        }
        body.write("--$boundary--\r\n".toByteArray(Charsets.UTF_8))
        return body
    }

    fun readResponse(reader: Reader): String {
        val buffer = CharArray(65537)
        var size = 0
        while (size < buffer.size) {
            val count = reader.read(buffer, size, buffer.size - size)
            if (count < 0) break
            size += count
        }
        if (size > 65536) throw IOException("Assistant response too large")
        return String(buffer, 0, size)
    }

    fun parseResponse(raw: String): AssistantTurnResult {
        val json = JSONObject(raw)
        val reply = json.getString("reply").trim()
        if (reply.isEmpty() || reply.length > 4000) throw IOException("Invalid assistant response")
        val notebookTitle = json.optString("notebook_title").takeIf { it.isNotBlank() && it != "null" }
        val readAloud = json.optBoolean("read_aloud", false)
        val retrievalStatus = json.optString("retrieval_status", "unused")
        val sources = json.optJSONArray("sources")
        val titles = (0 until (sources?.length() ?: 0)).mapNotNull { i ->
            sources?.optJSONObject(i)?.optString("title")?.takeIf { it.isNotBlank() }
        }.distinct()
        val formattedReply = if (titles.isEmpty() || retrievalStatus == "notebooklm") reply else "$reply\n\nSources: ${titles.joinToString(", ")}"
        return AssistantTurnResult(
            reply = formattedReply,
            notebookTitle = notebookTitle,
            readAloud = readAloud,
            retrievalStatus = retrievalStatus,
            sources = titles,
        )
    }

    fun parseReplyText(raw: String): String = parseResponse(raw).reply
}
