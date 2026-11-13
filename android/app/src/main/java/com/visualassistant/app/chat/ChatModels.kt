package com.visualassistant.app.chat

import com.visualassistant.app.camera.CapturedPhoto
import com.visualassistant.app.network.NotebookItem
import java.time.Instant
import java.util.UUID

enum class ChatKind { TEXT, VOICE_TRANSCRIPT, PHOTO, ASSISTANT, SYSTEM }
enum class ChatSource { PHONE, EARPHONES, ASSISTANT, SYSTEM }
enum class ChatStatus { READY, SENDING, FAILED, PARTIAL }
enum class ChatAction { TAKE_PHOTO }

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val turnId: String,
    val timestamp: Instant = Instant.now(),
    val kind: ChatKind,
    val source: ChatSource,
    val text: String = "",
    val thumbnail: ByteArray? = null,
    val photo: CapturedPhoto? = null,
    val status: ChatStatus = ChatStatus.READY,
    val actions: List<ChatAction> = emptyList(),
    val notebookTitle: String? = null,
    val readAloud: Boolean = false,
)

data class ConversationSummary(val id: String, val title: String, val preview: String, val lastActivity: Instant, val saved: Boolean = false)
data class ConversationSnapshot(val id: String, val messages: List<ChatMessage>, val currentPhoto: CapturedPhoto?, val saved: Boolean = false, val truncated: Boolean = false)

data class ChatUiState(
    val sessionId: String = UUID.randomUUID().toString(),
    val messages: List<ChatMessage> = emptyList(),
    val draft: String = "",
    val sending: Boolean = false,
    val lastPhotoId: String? = null,
    val historyTruncated: Boolean = false,
    val currentPhoto: CapturedPhoto? = null,
    val history: List<ConversationSummary> = emptyList(),
    val loading: Boolean = false,
    val storageError: Boolean = false,
    val saved: Boolean = false,
    val recentMessages: List<ChatMessage> = emptyList(),
    val viewingSaved: Boolean = false,
    val recentDisplayLimited: Boolean = false,
    val recentCanLoadMore: Boolean = false,
    val liveMode: Boolean = true,
    val attachPhoto: Boolean = false,
    val activeNotebook: NotebookItem? = null,
    val notebooks: List<NotebookItem> = emptyList(),
    val loadingNotebooks: Boolean = false,
)

internal data class RecentTimeline(val messages: List<ChatMessage>, val displayLimited: Boolean = false, val canLoadMore: Boolean = false)

internal fun interface ThumbnailProvider {
    fun create(photo: CapturedPhoto): ByteArray
}

internal interface ConversationStore {
    suspend fun initialize(): ConversationSnapshot
    suspend fun newConversation(): ConversationSnapshot
    suspend fun open(id: String): ConversationSnapshot
    suspend fun history(): List<ConversationSummary>
    suspend fun save(conversationId: String): ConversationSnapshot
    suspend fun recentContext(): List<ChatMessage>
    suspend fun recentTimeline(maxMessages: Int = 200): RecentTimeline = RecentTimeline(recentContext())
    suspend fun resumeRecent(): ConversationSnapshot = initialize()
    suspend fun beginTurn(conversationId: String, photo: CapturedPhoto, question: String, includePhoto: Boolean): ConversationSnapshot
    suspend fun addPhotoMessage(conversationId: String, photo: CapturedPhoto): ConversationSnapshot =
        throw UnsupportedOperationException("Adding photos is unavailable")
    suspend fun beginTextTurn(conversationId: String, question: String): ConversationSnapshot =
        throw UnsupportedOperationException("Text-only assistant turns are unavailable")
    suspend fun upsertMossTranscript(
        conversationId: String, turnId: String, assistant: Boolean, text: String,
    ): ConversationSnapshot
    suspend fun addVoiceTranscript(conversationId: String, messageId: String, text: String): ConversationSnapshot
    suspend fun discardVoiceTranscript(conversationId: String, messageId: String): ConversationSnapshot
    suspend fun finishTurn(conversationId: String, messageId: String, answer: String?, failed: Boolean, notebookTitle: String? = null): ConversationSnapshot
    suspend fun delete(id: String, currentId: String): ConversationSnapshot
    suspend fun clearRecentMemory(currentId: String? = null, viewingSaved: Boolean = false): ConversationSnapshot =
        initialize()
}
