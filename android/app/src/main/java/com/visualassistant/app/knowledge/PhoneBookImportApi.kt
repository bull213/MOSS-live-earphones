package com.visualassistant.app.knowledge

import com.visualassistant.app.network.BackendEndpoint
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject

internal data class BookInspection(
    val jobId: String,
    val pageCount: Int,
    val pagesRequiringOcr: List<Int>,
)

internal data class BookImportResult(
    val title: String,
    val status: String,
    val passages: Int,
    val warnings: List<String>,
)

/** Owns the two local HTTP requests used to stage and index a phone-selected book. */
internal class PhoneBookImportApi(
    private val endpoint: BackendEndpoint? = null,
    private val openConnection: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection },
) {
    fun inspect(pdf: File, name: String): BookInspection {
        val targetUrl = endpoint?.httpUrl("v1/knowledge/inspect") ?: "$BASE_URL/inspect"
        val connection = openConnection(targetUrl)
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = INSPECT_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/pdf")
            endpoint?.authorizationHeader()?.let {
                connection.setRequestProperty("Authorization", it)
            }
            connection.setRequestProperty("X-Document-Name", URLEncoder.encode(name, StandardCharsets.UTF_8.name()))
            connection.setFixedLengthStreamingMode(pdf.length())
            pdf.inputStream().buffered().use { source ->
                connection.outputStream.buffered().use { destination -> source.copyTo(destination) }
            }
            val response = JSONObject(readResponse(connection))
            val pages = response.getJSONArray("pages_requiring_ocr")
            val pageNumbers = (0 until pages.length()).map { pages.getInt(it) }
            val pageCount = response.getInt("page_count")
            if (pageCount !in 1..MAX_PAGES || pageNumbers.any { it !in 1..pageCount } || pageNumbers.distinct().size != pageNumbers.size) {
                throw IOException("The backend returned invalid PDF page information.")
            }
            return BookInspection(response.getString("job_id"), pageCount, pageNumbers)
        } finally {
            connection.disconnect()
        }
    }

    fun commit(jobId: String, pageTexts: Map<String, String>): BookImportResult {
        val pages = JSONObject()
        pageTexts.forEach { (page, text) -> pages.put(page, text) }
        val payload = JSONObject().put("job_id", jobId).put("page_texts", pages)
            .toString().toByteArray(StandardCharsets.UTF_8)
        if (payload.size > MAX_OCR_JSON_BYTES) throw IOException("Recognized text exceeds the 16 MiB import limit.")

        val targetUrl = endpoint?.httpUrl("v1/knowledge/import") ?: "$BASE_URL/import"
        val connection = openConnection(targetUrl)
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = IMPORT_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            endpoint?.authorizationHeader()?.let {
                connection.setRequestProperty("Authorization", it)
            }
            connection.setFixedLengthStreamingMode(payload.size)
            connection.outputStream.use { it.write(payload) }
            val response = JSONObject(readResponse(connection))
            val warnings = response.optJSONArray("warnings") ?: JSONArray()
            return BookImportResult(
                title = response.optString("title", "Book"),
                status = response.optString("status", "indexed"),
                passages = response.optInt("passages", 0),
                warnings = (0 until warnings.length()).map { warnings.optString(it) },
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun readResponse(connection: HttpURLConnection): String {
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            val message = try { JSONObject(body).getJSONObject("error").getString("message") }
            catch (_: Exception) { "The local book import failed. Check the USB connection and backend." }
            throw IOException(message)
        }
        return body
    }

    private companion object {
        const val BASE_URL = "http://127.0.0.1:8000/v1/knowledge"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val INSPECT_TIMEOUT_MS = 180_000
        const val IMPORT_TIMEOUT_MS = 600_000
        const val MAX_OCR_JSON_BYTES = 16 * 1024 * 1024
        const val MAX_PAGES = 3000
    }
}
