package com.visualassistant.app.network

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class NotebookAuthStatus(
    val configured: Boolean,
    val authenticated: Boolean,
    val email: String? = null,
    val reason: String? = null,
    val hasMasterToken: Boolean = false,
    val requiresRebootstrap: Boolean = false,
)

data class NotebookItem(
    val id: String,
    val title: String,
)

data class NotebookTurn(
    val turnId: String,
    val question: String,
    val answer: String,
    val relativeOrder: Int,
)

internal class NotebookAuthApi(private val endpoint: BackendEndpoint? = null) {

    private fun openConnection(path: String, method: String): HttpURLConnection {
        val targetUrl = endpoint?.httpUrl(path) ?: "http://127.0.0.1:8000/$path"
        val connection = URL(targetUrl).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 5_000
        connection.readTimeout = 10_000
        endpoint?.authorizationHeader()?.let {
            connection.setRequestProperty("Authorization", it)
        }
        return connection
    }

    suspend fun getStatus(): NotebookAuthStatus = withContext(Dispatchers.IO) {
        val connection = openConnection("v1/notebooklm/status", "GET")
        try {
            if (connection.responseCode != 200) {
                return@withContext NotebookAuthStatus(configured = false, authenticated = false, reason = "http_${connection.responseCode}")
            }
            val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val json = JSONObject(text)
            NotebookAuthStatus(
                configured = json.optBoolean("configured", false),
                authenticated = json.optBoolean("authenticated", false),
                email = json.optString("email").takeIf { it.isNotBlank() && it != "null" },
                reason = json.optString("reason").takeIf { it.isNotBlank() && it != "null" },
                hasMasterToken = json.optBoolean("has_master_token", false),
                requiresRebootstrap = json.optBoolean("requires_rebootstrap", false),
            )
        } catch (e: Exception) {
            NotebookAuthStatus(configured = false, authenticated = false, reason = e.message ?: "network_error")
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun postEmpty(path: String): Boolean = withContext(Dispatchers.IO) {
        val connection = openConnection(path, "POST")
        try {
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(0)
            connection.outputStream.close()
            connection.responseCode == 200
        } catch (_: Exception) {
            false
        } finally {
            connection.disconnect()
        }
    }

    suspend fun resetAuth(): Boolean = postEmpty("v1/notebooklm/auth/reset")

    suspend fun triggerLogin(): Boolean = postEmpty("v1/notebooklm/auth/trigger-login")

    suspend fun listNotebooks(): List<NotebookItem> = withContext(Dispatchers.IO) {
        val connection = openConnection("v1/notebooklm/notebooks", "GET")
        try {
            if (connection.responseCode != 200) {
                return@withContext emptyList()
            }
            val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val array = org.json.JSONArray(text)
            val result = mutableListOf<NotebookItem>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.optString("id")
                val title = obj.optString("title")
                if (id.isNotBlank()) {
                    result.add(NotebookItem(id = id, title = title.ifBlank { "Untitled Notebook" }))
                }
            }
            result
        } catch (_: Exception) {
            emptyList()
        } finally {
            connection.disconnect()
        }
    }

    suspend fun getHistory(notebookId: String): List<NotebookTurn> = withContext(Dispatchers.IO) {
        val connection = openConnection("v1/notebooklm/notebooks/$notebookId/history", "GET")
        connection.readTimeout = 20_000
        try {
            if (connection.responseCode != 200) {
                return@withContext emptyList()
            }
            val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val json = JSONObject(text)
            val turnsArray = json.optJSONArray("turns") ?: return@withContext emptyList()
            val result = mutableListOf<NotebookTurn>()
            for (i in 0 until turnsArray.length()) {
                val obj = turnsArray.optJSONObject(i) ?: continue
                val turnId = obj.optString("turn_id", "turn-$i")
                val question = obj.optString("question")
                val answer = obj.optString("answer")
                val order = obj.optInt("relative_order", i)
                if (question.isNotBlank() && answer.isNotBlank()) {
                    result.add(NotebookTurn(turnId = turnId, question = question, answer = answer, relativeOrder = order))
                }
            }
            result
        } catch (_: Exception) {
            emptyList()
        } finally {
            connection.disconnect()
        }
    }
}
