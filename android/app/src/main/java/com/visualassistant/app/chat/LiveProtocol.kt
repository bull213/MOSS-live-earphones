package com.visualassistant.app.chat

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.Reader
import org.json.JSONArray
import org.json.JSONObject

/** Request and response formatting for the live endpoint. */
internal object LiveProtocol {
    fun historyJson(history: List<ChatMessage>): String = JSONArray(liveContext(history).map { (role, text) ->
        JSONObject().put("role", role).put("text", text)
    }).toString()

    fun requestBody(boundary: String, question: String, historyJson: String, image: ByteArray?): ByteArrayOutputStream {
        val body = ByteArrayOutputStream()
        fun field(name: String, value: String) {
            body.write(("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n\r\n$value\r\n").toByteArray(Charsets.UTF_8))
        }
        field("question", question)
        field("history_json", historyJson)
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

    fun parseResponse(raw: String): String {
        val json = JSONObject(raw)
        val reply = json.getString("reply").trim()
        if (reply.isEmpty() || reply.length > 4000) throw IOException("Invalid assistant response")
        val sources = json.optJSONArray("sources")
        val titles = (0 until (sources?.length() ?: 0)).mapNotNull { i ->
            sources?.optJSONObject(i)?.optString("title")?.takeIf { it.isNotBlank() }
        }.distinct()
        return if (titles.isEmpty()) reply else "$reply\n\nSources: ${titles.joinToString(", ")}"
    }
}
