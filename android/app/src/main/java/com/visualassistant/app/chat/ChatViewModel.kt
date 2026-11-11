package com.visualassistant.app.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.visualassistant.app.camera.CapturedPhoto
import com.visualassistant.app.voice.PendingVoiceText
import com.visualassistant.app.voice.SpeechController
import com.visualassistant.app.voice.SpeechOutput
import com.visualassistant.app.voice.SpeechPhase
import com.visualassistant.app.voice.SpeechState
import com.visualassistant.app.voice.SpeechText
import com.visualassistant.app.voice.VoiceSaveStatus
import com.visualassistant.app.mosslive.MossLiveSessionController
import com.visualassistant.app.mosslive.MossLiveUiState
import com.visualassistant.app.mosslive.MossSessionContext
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Coordinates conversation state, voice persistence, and assistant turns. */
class ChatViewModel internal constructor(
    private val assistant: LiveAssistant,
    private val thumbnails: ThumbnailProvider,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val store: ConversationStore? = null,
    private val speech: SpeechController? = null,
    private val mossLive: MossLiveSessionController? = null,
    private val mossPhotoEncoder: ThumbnailProvider = thumbnails,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ChatUiState(loading = store != null))
    val state = mutableState.asStateFlow()
    internal val speechState = speech?.state ?: MutableStateFlow(SpeechState()).asStateFlow()
    internal val voiceAvailable: Boolean get() = speech != null
    internal val mossState = mossLive?.state ?: MutableStateFlow(MossLiveUiState()).asStateFlow()
    private val mutablePendingVoice = MutableStateFlow<List<PendingVoiceText>>(emptyList())
    internal val pendingVoice = mutablePendingVoice.asStateFlow()
    private val voiceWrites = Mutex()
    private var request: Job? = null
    private var recentMessageLimit = RECENT_PAGE_SIZE

    init {
        if (store != null) retryStorage()
        if (mossLive != null) viewModelScope.launch {
            mossLive.transcriptUpdates.collect { update ->
                if (update.final) {
                    persistMossTranscript(update.sessionId, update.turnId, update.assistant, update.text)
                }
            }
        }
        if (speech != null) viewModelScope.launch {
            speech.outputs.collect { output ->
                when (output) {
                    is SpeechOutput.Completed -> {
                        mutablePendingVoice.update { it + PendingVoiceText(output.text, VoiceSaveStatus.SAVING) }
                        persistVoice(output.text)
                    }
                    is SpeechOutput.Barrier -> output.done.complete(Unit)
                }
            }
        }
    }

    internal fun startVoice() {
        val current = state.value
        if (store == null || current.loading || current.sending || speechState.value.phase != SpeechPhase.IDLE ||
            pendingVoice.value.any { it.status != VoiceSaveStatus.SAVING }) return
        speech?.start(viewModelScope, current.sessionId)
    }

    internal fun stopVoice() { speech?.requestStop() }

    internal fun startMossLive(stagedPhoto: CapturedPhoto? = null) {
        val current = state.value
        val repository = store ?: return
        val controller = mossLive ?: return
        if (current.loading || current.sending) return
        mutableState.value = current.copy(loading = true, storageError = false)
        viewModelScope.launch {
            try {
                val conversation = if (current.viewingSaved || current.saved) repository.resumeRecent() else null
                if (conversation != null) applySnapshot(conversation, history = repository.history(), viewingSaved = false)
                val sessionId = conversation?.id ?: current.sessionId
                val timeline = repository.recentTimeline(recentMessageLimit)
                val latestRollingPhoto = timeline.messages.asReversed().firstOrNull { it.kind == ChatKind.PHOTO && it.photo != null }?.photo
                val targetPhoto = stagedPhoto ?: current.currentPhoto ?: latestRollingPhoto
                val context = withContext(io) {
                    MossSessionContext.from(
                        messages = timeline.messages,
                        stagedPhoto = targetPhoto,
                        encodeCapturedPhoto = { photo -> runCatching { mossPhotoEncoder.create(photo) }.getOrNull() },
                    )
                }
                controller.start(viewModelScope, sessionId, context)
                mutableState.update { it.copy(loading = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = state.value.copy(loading = false, storageError = true)
            }
        }
    }

    internal fun sharePhotoWithMossLive(photo: CapturedPhoto) {
        val controller = mossLive ?: return
        viewModelScope.launch {
            val imageBytes = withContext(io) {
                runCatching { mossPhotoEncoder.create(photo) }.getOrNull()
            }
            if (imageBytes != null) {
                controller.appendExternalContext(
                    instructions = "User shared an updated photo for visual context. Refer to this image when answering.",
                    photo = imageBytes,
                )
            }
        }
    }

    internal fun addPhotoToConversation(photo: CapturedPhoto) {
        val repository = store ?: return
        val current = state.value
        viewModelScope.launch {
            try {
                val snapshot = withContext(io) { repository.addPhotoMessage(current.sessionId, photo) }
                applySnapshot(snapshot, history = repository.history())
                sharePhotoWithMossLive(photo)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                sharePhotoWithMossLive(photo)
            }
        }
    }

    internal fun stopMossLive() { mossLive?.stop() }

    private suspend fun persistMossTranscript(sessionId: String, turnId: String, assistant: Boolean, text: String) {
        val repository = store ?: return
        try {
            voiceWrites.withLock {
                val updated = repository.upsertMossTranscript(sessionId, turnId, assistant, text)
                refreshConversationViews(updated, repository)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (state.value.sessionId == sessionId) {
                mutableState.update { it.copy(storageError = true) }
            }
        }
    }

    /** Returns false if a transcript still needs the user's save/discard decision. */
    internal suspend fun stopVoiceAndWait(): Boolean {
        speech?.stopAndWait()
        voiceWrites.withLock { }
        return pendingVoice.value.isEmpty()
    }

    internal fun savePendingVoice(id: String) {
        val pending = pendingVoice.value.firstOrNull { it.text.id == id && it.status != VoiceSaveStatus.SAVING } ?: return
        mutablePendingVoice.update { list -> list.map { if (it.text.id == id) it.copy(status = VoiceSaveStatus.SAVING) else it } }
        viewModelScope.launch { persistVoice(pending.text) }
    }

    internal fun discardPendingVoice(id: String) {
        val pending = pendingVoice.value.firstOrNull { it.text.id == id && it.status != VoiceSaveStatus.SAVING } ?: return
        val repository = store ?: return
        mutablePendingVoice.update { list -> list.map { if (it.text.id == id) it.copy(status = VoiceSaveStatus.SAVING) else it } }
        viewModelScope.launch {
            voiceWrites.withLock {
                try {
                    val snapshot = repository.discardVoiceTranscript(pending.text.conversationId, id)
                    refreshConversationViews(snapshot, repository)
                    mutablePendingVoice.update { list -> list.filterNot { it.text.id == id } }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    failPendingVoice(id)
                }
            }
        }
    }

    private suspend fun persistVoice(text: SpeechText) {
        val repository = store ?: return
        voiceWrites.withLock {
            try {
                val snapshot = repository.addVoiceTranscript(text.conversationId, text.id, text.text)
                refreshConversationViews(snapshot, repository)
                mutablePendingVoice.update { list -> list.filterNot { it.text.id == text.id } }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failPendingVoice(text.id)
            }
        }
    }

    private fun failPendingVoice(id: String) {
        mutablePendingVoice.update { list -> list.map { if (it.text.id == id) it.copy(status = VoiceSaveStatus.FAILED) else it } }
    }

    private suspend fun refreshConversationViews(snapshot: ConversationSnapshot, repository: ConversationStore) {
        val history = repository.history()
        val recent = repository.recentTimeline(recentMessageLimit)
        mutableState.update { current ->
            val isCurrentConversation = current.sessionId == snapshot.id
            current.copy(
                messages = if (isCurrentConversation) snapshot.messages else current.messages,
                currentPhoto = if (isCurrentConversation) snapshot.currentPhoto else current.currentPhoto,
                lastPhotoId = if (isCurrentConversation) snapshot.currentPhoto?.id else current.lastPhotoId,
                saved = if (isCurrentConversation) snapshot.saved else current.saved,
                historyTruncated = if (isCurrentConversation) snapshot.truncated else current.historyTruncated,
                history = history,
                recentMessages = recent.messages,
                recentDisplayLimited = recent.displayLimited,
                recentCanLoadMore = recent.canLoadMore,
            )
        }
    }

    fun retryStorage() {
        val repository = store ?: return
        if (state.value.sending) return
        mutableState.value = state.value.copy(loading = true, storageError = false)
        viewModelScope.launch {
            try {
                val restored = repository.initialize()
                val snapshot = if (restored.saved) repository.resumeRecent() else restored
                val history = repository.history()
                applySnapshot(snapshot, history = history)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = state.value.copy(loading = false, storageError = true)
            }
        }
    }

    fun updateDraft(value: String) {
        if (!state.value.sending && !state.value.loading && value.length <= 1000) mutableState.value = state.value.copy(draft = value)
    }

    fun setLiveMode(enabled: Boolean) {
        if (!state.value.sending && !state.value.loading) {
            mutableState.update { it.copy(liveMode = enabled, attachPhoto = false) }
        }
    }

    fun setAttachPhoto(enabled: Boolean) {
        if (!state.value.sending && !state.value.loading) mutableState.update { it.copy(attachPhoto = enabled) }
    }

    fun useTranscript(text: String) { updateDraft(text.take(1000)) }

    fun sendLive(photo: CapturedPhoto?) {
        val current = state.value
        val repository = store ?: return
        val text = current.draft.trim()
        if (!current.liveMode || !current.canSubmit(text)) return
        submitPersistent(repository, current, photo, text)
    }

    fun submit(photo: CapturedPhoto, question: String) {
        val current = state.value
        val text = question.trim()
        if (!current.canSubmit(text)) return
        if (store != null) {
            submitPersistent(store, current, photo, text)
            return
        }
        val session = current.sessionId
        val turn = UUID.randomUUID().toString()
        val photoEvent = if (current.lastPhotoId != photo.id) ChatMessage(
            sessionId = session, turnId = turn, timestamp = photo.capturedAt,
            kind = ChatKind.PHOTO, source = ChatSource.PHONE, status = ChatStatus.SENDING,
        ) else null
        val user = ChatMessage(sessionId = session, turnId = turn, kind = ChatKind.TEXT, source = ChatSource.PHONE, text = text)
        val response = ChatMessage(sessionId = session, turnId = turn, kind = ChatKind.ASSISTANT, source = ChatSource.ASSISTANT, status = ChatStatus.SENDING)
        val all = current.messages + listOfNotNull(photoEvent) + user + response
        mutableState.value = current.copy(messages = all.takeLast(MAX_MESSAGES),
            historyTruncated = current.historyTruncated || all.size > MAX_MESSAGES,
            draft = text, sending = true)
        request = viewModelScope.launch {
            try {
                if (photoEvent != null) {
                    val thumbnail = withContext(io) { thumbnails.create(photo) }
                    currentCoroutineContext().ensureActive()
                    updateMessage(photoEvent.id) { it.copy(thumbnail = thumbnail, status = ChatStatus.READY) }
                    mutableState.value = state.value.copy(lastPhotoId = photo.id)
                }
                val context = if (current.liveMode) current.recentMessages else emptyList()
                val answer = assistant.answer(text, photo, context)
                currentCoroutineContext().ensureActive()
                updateMessage(response.id) { it.copy(text = answer, timestamp = Instant.now(), status = ChatStatus.READY, actions = listOf(ChatAction.TAKE_PHOTO)) }
                mutableState.value = state.value.copy(sending = false, draft = "")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                if (photoEvent != null) updateMessage(photoEvent.id) {
                    if (it.status == ChatStatus.SENDING) it.copy(status = ChatStatus.FAILED) else it
                }
                updateMessage(response.id) { it.copy(timestamp = Instant.now(), status = ChatStatus.FAILED) }
                mutableState.value = state.value.copy(sending = false)
            }
        }
    }

    private fun updateMessage(id: String, update: (ChatMessage) -> ChatMessage) {
        mutableState.value = state.value.copy(messages = state.value.messages.map { if (it.id == id) update(it) else it })
    }

    fun newSession() {
        if (store != null) {
            if (state.value.sending || state.value.loading) return
            switchConversation(viewingSaved = false) { store.newConversation() }
            return
        }
        request?.cancel()
        request = null
        mutableState.value = ChatUiState()
    }

    fun sendTextWithoutPhoto() {
        val repository = store ?: return
        val current = state.value
        val text = current.draft.trim()
        if (!current.canSubmit(text)) return
        submitPersistent(repository, current, null, text)
    }

    fun openConversation(id: String) {
        if (state.value.sending || state.value.loading || store == null) return
        switchConversation(viewingSaved = true) { store.open(id) }
    }

    fun showRecent() {
        val repository = store ?: return
        if (state.value.sending || state.value.loading) return
        if (!state.value.viewingSaved && !state.value.saved) return
        switchConversation(viewingSaved = false) { repository.resumeRecent() }
    }

    fun loadEarlierRecent() {
        val repository = store ?: return
        if (state.value.loading || state.value.sending || state.value.viewingSaved || !state.value.recentCanLoadMore) return
        val nextLimit = (recentMessageLimit.toLong() + RECENT_PAGE_SIZE).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        mutableState.value = state.value.copy(loading = true, storageError = false)
        viewModelScope.launch {
            try {
                val recent = repository.recentTimeline(nextLimit)
                recentMessageLimit = nextLimit
                mutableState.value = state.value.copy(loading = false, recentMessages = recent.messages,
                    recentDisplayLimited = recent.displayLimited, recentCanLoadMore = recent.canLoadMore)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = state.value.copy(loading = false, storageError = true)
            }
        }
    }

    fun saveConversation() {
        val repository = store ?: return
        val current = state.value
        if (current.sending || current.loading || current.saved || current.messages.isEmpty()) return
        switchConversation(draft = current.draft, viewingSaved = true) { repository.save(current.sessionId) }
    }

    fun deleteConversation(id: String) {
        if (state.value.sending || state.value.loading || store == null) return
        val currentId = state.value.sessionId
        if (id == currentId && state.value.viewingSaved) {
            switchConversation(viewingSaved = false) { store.delete(id, currentId); store.resumeRecent() }
        } else {
            switchConversation { store.delete(id, currentId) }
        }
    }

    private fun switchConversation(draft: String = "", viewingSaved: Boolean = state.value.viewingSaved, operation: suspend () -> ConversationSnapshot) {
        val repository = store ?: return
        mutableState.value = state.value.copy(loading = true, storageError = false)
        viewModelScope.launch {
            try {
                val snapshot = operation()
                applySnapshot(snapshot, draft = draft, history = repository.history(), viewingSaved = viewingSaved)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A deletion may have committed even if file cleanup failed. Reload
                // a valid conversation and surface the problem instead of using stale state.
                val recovered = runCatching { repository.initialize() }.getOrNull()
                if (recovered != null) {
                    applySnapshot(recovered, history = runCatching { repository.history() }.getOrDefault(emptyList()))
                }
                mutableState.value = state.value.copy(loading = false, storageError = true)
            }
        }
    }

    private fun submitPersistent(repository: ConversationStore, current: ChatUiState, photo: CapturedPhoto?, text: String) {
        mutableState.value = current.copy(sending = true, draft = text, storageError = false)
        request = viewModelScope.launch {
            val snapshot = try {
                beginPersistentTurn(repository, current, photo, text)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                failStorage(text)
                return@launch
            }
            val responseId = snapshot.messages.lastOrNull { it.kind == ChatKind.ASSISTANT && it.status == ChatStatus.SENDING }?.id
            if (responseId == null) {
                failStorage(text)
                return@launch
            }
            applySnapshot(snapshot, sending = true, draft = text, history = current.history)
            val answer = try {
                answerPersistentTurn(current, snapshot, photo, text)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            try {
                currentCoroutineContext().ensureActive()
                completePersistentTurn(repository, current.sessionId, responseId, answer, text, photo)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                failStorage(text)
            }
        }
    }

    private suspend fun beginPersistentTurn(
        repository: ConversationStore, current: ChatUiState, photo: CapturedPhoto?, text: String,
    ): ConversationSnapshot = if (photo == null) repository.beginTextTurn(current.sessionId, text)
        else repository.beginTurn(current.sessionId, photo, text, current.currentPhoto?.id != photo.id)

    private suspend fun answerPersistentTurn(
        current: ChatUiState, snapshot: ConversationSnapshot, photo: CapturedPhoto?, text: String,
    ): String {
        val savedPhoto = if (photo == null) null else snapshot.currentPhoto ?: throw IllegalStateException("Saved photo unavailable")
        val context = if (current.liveMode) current.recentMessages else emptyList()
        return assistant.answer(text, savedPhoto, context)
    }

    private suspend fun completePersistentTurn(
        repository: ConversationStore, sessionId: String, responseId: String, answer: String?, text: String,
        photo: CapturedPhoto?,
    ) {
        val completed = repository.finishTurn(sessionId, responseId, answer, failed = answer == null)
        applySnapshot(completed, draft = if (answer == null) text else "", history = repository.history())
        if (answer != null) {
            val image = if (photo == null) null else completed.currentPhoto?.let { captured ->
                withContext(io) { runCatching { mossPhotoEncoder.create(captured) }.getOrNull() }
            }
            mossLive?.appendExternalContext("User: $text\nAssistant: $answer", image)
        }
    }

    private suspend fun applySnapshot(snapshot: ConversationSnapshot, sending: Boolean = false, draft: String = "", history: List<ConversationSummary>, viewingSaved: Boolean = state.value.viewingSaved) {
        val recent = store?.recentTimeline(recentMessageLimit) ?: RecentTimeline(snapshot.messages)
        mutableState.value = ChatUiState(sessionId = snapshot.id, messages = snapshot.messages,
            currentPhoto = snapshot.currentPhoto, lastPhotoId = snapshot.currentPhoto?.id,
            sending = sending, draft = draft, history = history, saved = snapshot.saved,
            historyTruncated = snapshot.truncated, recentMessages = recent.messages,
            recentDisplayLimited = recent.displayLimited, recentCanLoadMore = recent.canLoadMore, viewingSaved = viewingSaved,
            liveMode = state.value.liveMode, attachPhoto = state.value.attachPhoto)
    }

    private fun ChatUiState.canSubmit(text: String): Boolean =
        !sending && !loading && text.isNotEmpty() && text.length <= 1000

    private fun failStorage(draft: String) {
        mutableState.value = state.value.copy(sending = false, storageError = true, draft = draft)
    }

    override fun onCleared() {
        speech?.requestStop()
        mossLive?.stop()
        super.onCleared()
    }

    private companion object {
        const val MAX_MESSAGES = 60
        const val RECENT_PAGE_SIZE = 200
    }
}
