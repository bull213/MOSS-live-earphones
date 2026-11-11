package com.visualassistant.app.chat

import android.content.ContentResolver
import androidx.room.withTransaction
import com.visualassistant.app.camera.CapturedPhoto
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Owns only generated files in its dedicated app-private directory. */
internal class ConversationRepository(
    private val db: ConversationDatabase,
    directory: File,
    resolver: ContentResolver,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val recentLimitBytes: Long = 100L * 1024 * 1024,
) : ConversationStore {
    private val dao = db.conversations()
    private val photos = ConversationPhotoStore(directory, resolver)
    private val recentPolicy = RecentHistoryPolicy(recentLimitBytes) { photos.fileFor(it).length() }
    private val timelineReader = RecentTimelineReader(dao, recentLimitBytes) { photos.fileFor(it).length() }
    private val mutex = Mutex()

    override suspend fun initialize(): ConversationSnapshot = withContext(io) {
        mutex.withLock {
            dao.failInterruptedMessages()
            cleanupOrphanPhotos()
            trimRecent()
            snapshot(dao.latest() ?: create())
        }
    }

    override suspend fun newConversation(): ConversationSnapshot = withContext(io) {
        mutex.withLock { snapshot(create()) }
    }

    override suspend fun resumeRecent(): ConversationSnapshot = withContext(io) {
        mutex.withLock { snapshot(dao.latestRecent() ?: create()) }
    }

    override suspend fun open(id: String): ConversationSnapshot = withContext(io) {
        mutex.withLock {
            val conversation = dao.conversation(id) ?: throw IOException("Conversation unavailable")
            val opened = conversation.copy(lastActivity = nextActivity())
            dao.updateConversation(opened.id, opened.lastActivity, opened.title)
            snapshot(opened)
        }
    }

    override suspend fun history(): List<ConversationSummary> = withContext(io) {
        dao.history().map { ConversationSummary(it.id, it.title, it.preview.orEmpty(), Instant.ofEpochMilli(it.lastActivity), it.saved) }
    }

    override suspend fun save(conversationId: String): ConversationSnapshot = withContext(io) {
        mutex.withLock {
            val conversation = dao.conversation(conversationId) ?: throw IOException("Conversation unavailable")
            dao.saveConversation(conversationId)
            snapshot(conversation.copy(saved = true))
        }
    }

    override suspend fun recentContext(): List<ChatMessage> = withContext(io) {
        mutex.withLock { recentPolicy.boundedContext(dao.allMessages()).map { it.toChatMessage() } }
    }

    override suspend fun recentTimeline(maxMessages: Int): RecentTimeline = withContext(io) {
        mutex.withLock {
            val rows = timelineReader.read(maxMessages)
            RecentTimeline(rows.messages.map { it.toChatMessage() }, rows.displayLimited, rows.canLoadMore)
        }
    }

    override suspend fun beginTextTurn(conversationId: String, question: String): ConversationSnapshot = withContext(io) {
        mutex.withLock {
            val conversation = requireConversation(conversationId)
            val now = nextActivity()
            val turn = UUID.randomUUID().toString()
            val user = turnMessage(conversationId, turn, now, ChatKind.TEXT, ChatSource.PHONE, question)
            val response = turnMessage(conversationId, turn, now, ChatKind.ASSISTANT, ChatSource.ASSISTANT,
                status = ChatStatus.SENDING)
            insertTurn(conversationId, now, titleFor(conversation, question), listOf(user, response))
            snapshot(requireConversation(conversationId))
        }
    }

    override suspend fun upsertMossTranscript(
        conversationId: String, turnId: String, assistant: Boolean, text: String,
    ): ConversationSnapshot = withContext(io) {
        mutex.withLock {
            val conversation = dao.conversation(conversationId) ?: throw IOException("Conversation unavailable")
            val now = nextActivity()
            val rows = dao.turnMessages(conversationId, turnId)
            val kind = if (assistant) ChatKind.ASSISTANT else ChatKind.TEXT
            val source = if (assistant) ChatSource.ASSISTANT else ChatSource.PHONE
            val status = ChatStatus.READY
            val message = rows.firstOrNull { it.kind == kind.name && it.source == source.name }
            val title = if (assistant) conversation.title else titleFor(conversation, text)
            db.withTransaction {
                if (message == null) {
                    dao.insertMessages(listOf(turnMessage(conversationId, turnId, now, kind, source, text, status)))
                } else {
                    dao.updateMessageText(message.id, status.name, text, now)
                }
                dao.updateConversation(conversationId, now, title)
            }
            trimRecent()
            snapshot(requireConversation(conversationId))
        }
    }

    override suspend fun addVoiceTranscript(conversationId: String, messageId: String, text: String): ConversationSnapshot = withContext(io) {
        mutex.withLock {
            val conversation = dao.conversation(conversationId) ?: throw IOException("Conversation unavailable")
            val normalized = text.trim()
            require(normalized.isNotEmpty())
            val previous = dao.message(messageId)
            if (previous != null) {
                if (previous.conversationId != conversationId || previous.kind != ChatKind.VOICE_TRANSCRIPT.name || previous.text != normalized) {
                    throw IOException("Voice message ID already belongs to another message")
                }
                return@withLock snapshot(conversation)
            }
            val now = nextActivity()
            val voice = turnMessage(conversationId, messageId, now, ChatKind.VOICE_TRANSCRIPT, ChatSource.PHONE, normalized)
                .copy(id = messageId)
            insertTurn(conversationId, now, titleFor(conversation, normalized), listOf(voice))
            trimRecent()
            snapshot(requireConversation(conversationId))
        }
    }

    override suspend fun discardVoiceTranscript(conversationId: String, messageId: String): ConversationSnapshot = withContext(io) {
        mutex.withLock {
            val conversation = dao.conversation(conversationId) ?: throw IOException("Conversation unavailable")
            val existing = dao.message(messageId) ?: return@withLock snapshot(conversation)
            if (existing.conversationId != conversationId || existing.kind != ChatKind.VOICE_TRANSCRIPT.name) {
                throw IOException("Voice message ID belongs to another message")
            }
            db.withTransaction {
                dao.deleteVoiceMessage(conversationId, messageId)
                val title = dao.messages(conversationId).firstOrNull {
                    it.kind == ChatKind.TEXT.name || it.kind == ChatKind.VOICE_TRANSCRIPT.name
                }?.text?.take(80).orEmpty()
                dao.updateTitle(conversationId, title)
            }
            snapshot(requireConversation(conversationId))
        }
    }

    override suspend fun beginTurn(
        conversationId: String, photo: CapturedPhoto, question: String, includePhoto: Boolean,
    ): ConversationSnapshot = withContext(io) {
        mutex.withLock {
            val conversation = dao.conversation(conversationId) ?: throw IOException("Conversation unavailable")
            val turn = UUID.randomUUID().toString()
            var savedFile: File? = null
            var committed = false
            try {
                val photoKey = if (includePhoto) {
                    photos.save(photo).also { savedFile = it }.name
                } else {
                    dao.messages(conversationId).lastOrNull { it.imageKey != null }?.imageKey
                        ?: throw IOException("Saved photo unavailable")
                }
                val photoMessage = turnMessage(conversationId, turn, photo.capturedAt.toEpochMilli(),
                    ChatKind.PHOTO, ChatSource.PHONE, imageKey = photoKey, captureId = photo.id)
                val now = nextActivity()
                val user = turnMessage(conversationId, turn, now, ChatKind.TEXT, ChatSource.PHONE, question)
                val response = turnMessage(conversationId, turn, now, ChatKind.ASSISTANT, ChatSource.ASSISTANT,
                    status = ChatStatus.SENDING)
                val title = titleFor(conversation, question)
                val updated = conversation.copy(title = title, lastActivity = now)
                insertTurn(conversationId, now, title, listOf(photoMessage, user, response))
                committed = true
                snapshot(updated)
            } catch (error: Exception) {
                val uncommittedPhoto = savedFile
                if (!committed && uncommittedPhoto != null) removeUncommittedPhoto(uncommittedPhoto)
                throw error
            }
        }
    }

    override suspend fun addPhotoMessage(
        conversationId: String, photo: CapturedPhoto,
    ): ConversationSnapshot = withContext(io) {
        mutex.withLock {
            val conversation = dao.conversation(conversationId) ?: throw IOException("Conversation unavailable")
            val turn = UUID.randomUUID().toString()
            var savedFile: File? = null
            var committed = false
            try {
                val photoKey = photos.save(photo).also { savedFile = it }.name
                val photoMessage = turnMessage(conversationId, turn, photo.capturedAt.toEpochMilli(),
                    ChatKind.PHOTO, ChatSource.PHONE, imageKey = photoKey, captureId = photo.id)
                val now = nextActivity()
                val title = if (conversation.title.isBlank()) "Photo" else conversation.title
                val updated = conversation.copy(title = title, lastActivity = now)
                insertTurn(conversationId, now, title, listOf(photoMessage))
                committed = true
                snapshot(updated)
            } catch (error: Exception) {
                val uncommittedPhoto = savedFile
                if (!committed && uncommittedPhoto != null) removeUncommittedPhoto(uncommittedPhoto)
                throw error
            }
        }
    }

    override suspend fun finishTurn(conversationId: String, messageId: String, answer: String?, failed: Boolean): ConversationSnapshot = withContext(io) {
        mutex.withLock {
            val conversation = dao.conversation(conversationId) ?: throw IOException("Conversation unavailable")
            val now = nextActivity()
            db.withTransaction {
                dao.updateMessage(messageId, if (failed) ChatStatus.FAILED.name else ChatStatus.READY.name, answer.orEmpty(), now)
                dao.updateConversation(conversationId, now, conversation.title)
            }
            trimRecent()
            snapshot(requireConversation(conversationId))
        }
    }

    override suspend fun delete(id: String, currentId: String): ConversationSnapshot = withContext(io) {
        mutex.withLock {
            val images = dao.messages(id).mapNotNull { it.imageKey }.distinct()
            db.withTransaction { dao.deleteConversation(id) }
            deleteUnreferencedPhotos(images)
            snapshot(dao.conversation(currentId) ?: dao.latest() ?: create())
        }
    }

    private suspend fun create(): ConversationEntity {
        val now = nextActivity()
        return ConversationEntity(UUID.randomUUID().toString(), now, now, "").also { dao.insertConversation(it) }
    }

    private suspend fun nextActivity(): Long = maxOf(System.currentTimeMillis(), (dao.maxActivity() ?: 0L) + 1L)

    private suspend fun requireConversation(id: String): ConversationEntity =
        dao.conversation(id) ?: throw IOException("Conversation unavailable")

    private fun titleFor(conversation: ConversationEntity, question: String): String =
        if (conversation.title.isEmpty()) question.take(80) else conversation.title

    private fun turnMessage(
        conversationId: String, turnId: String, timestamp: Long, kind: ChatKind, source: ChatSource,
        text: String = "", status: ChatStatus = ChatStatus.READY, imageKey: String? = null, captureId: String? = null,
    ) = MessageEntity(
        id = UUID.randomUUID().toString(), conversationId = conversationId, turnId = turnId,
        timestamp = timestamp, kind = kind.name, source = source.name, text = text, status = status.name,
        imageKey = imageKey, captureId = captureId,
    )

    private suspend fun insertTurn(id: String, time: Long, title: String, messages: List<MessageEntity>) {
        db.withTransaction {
            dao.insertMessages(messages)
            dao.updateConversation(id, time, title)
        }
    }

    private suspend fun cleanupOrphanPhotos() = photos.cleanupOrphans(dao.referencedImages().toSet())

    private suspend fun removeUncommittedPhoto(file: File) {
        // A transaction can commit just before cancellation. Delete only after
        // confirming that the database does not reference the newly saved file.
        withContext(NonCancellable) {
            val unreferenced = runCatching { file.name !in dao.referencedImages() }.getOrDefault(false)
            if (unreferenced) file.delete()
        }
    }

    private suspend fun deleteUnreferencedPhotos(images: List<String>) {
        val remaining = dao.referencedImages().toSet()
        var cleanupFailed = false
        for (key in images) {
            if (key !in remaining && !photos.delete(key)) cleanupFailed = true
        }
        if (cleanupFailed) throw IOException("Could not remove conversation photographs")
    }

    private suspend fun trimRecent() {
        val turns = recentPolicy.turnsToRemove(dao.recentMessages())
        if (turns.isEmpty()) return
        db.withTransaction {
            for ((conversationId, turnId) in turns) {
                dao.deleteTurn(conversationId, turnId)
                dao.markTruncated(conversationId)
            }
            for (id in turns.map { it.first }.distinct()) {
                val title = dao.messages(id).firstOrNull { it.kind == ChatKind.TEXT.name || it.kind == ChatKind.VOICE_TRANSCRIPT.name }?.text?.take(80).orEmpty()
                dao.updateTitle(id, title)
            }
        }
        // A crash between the transaction and cleanup is repaired on the next launch.
        cleanupOrphanPhotos()
    }

    private suspend fun snapshot(conversation: ConversationEntity): ConversationSnapshot {
        val rows = dao.messages(conversation.id)
        val messages = rows.map { it.toChatMessage() }
        val latestPhoto = rows.lastOrNull { it.imageKey != null }?.savedPhoto(requireFile = true)
        return ConversationSnapshot(conversation.id, messages, latestPhoto, conversation.saved, conversation.truncated)
    }

    private fun MessageEntity.toChatMessage(): ChatMessage {
        val messageKind = ChatKind.valueOf(kind)
        val messageStatus = ChatStatus.valueOf(status)
        return ChatMessage(
            id = id, sessionId = conversationId, turnId = turnId,
            timestamp = Instant.ofEpochMilli(timestamp), kind = messageKind,
            source = ChatSource.valueOf(source), text = text, status = messageStatus,
            photo = savedPhoto(),
            actions = if (messageKind == ChatKind.ASSISTANT && messageStatus == ChatStatus.READY) {
                listOf(ChatAction.TAKE_PHOTO)
            } else emptyList(),
        )
    }

    private fun MessageEntity.savedPhoto(requireFile: Boolean = false): CapturedPhoto? {
        val key = imageKey ?: return null
        val file = photos.fileFor(key)
        if (requireFile && !file.isFile) return null
        return CapturedPhoto(captureId ?: key, file.toURI().toString(), Instant.ofEpochMilli(timestamp))
    }

}
