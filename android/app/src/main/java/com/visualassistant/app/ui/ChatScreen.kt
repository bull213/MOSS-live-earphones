package com.visualassistant.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.visualassistant.app.R
import com.visualassistant.app.chat.ChatUiState
import com.visualassistant.app.chat.ChatMessage
import com.visualassistant.app.voice.PendingVoiceText
import com.visualassistant.app.voice.SpeechPhase
import com.visualassistant.app.voice.SpeechProblem
import com.visualassistant.app.voice.SpeechState
import com.visualassistant.app.voice.VoiceSaveStatus
import com.visualassistant.app.mosslive.MossConnectionState
import com.visualassistant.app.mosslive.MossLiveUiState

@Composable
internal fun ChatScreen(
    state: ChatUiState,
    hasPhoto: Boolean,
    openingCamera: Boolean,
    storageFailed: Boolean,
    onDraftChanged: (String) -> Unit,
    onPhoto: () -> Unit,
    onSend: () -> Unit,
    onNewSession: () -> Unit,
    onHistory: () -> Unit = {},
    onRetryStorage: () -> Unit = {},
    onSave: () -> Unit = {},
    onLoadEarlier: () -> Unit = {},
    showMenu: Boolean = true,
    speech: SpeechState = SpeechState(),
    pendingVoice: List<PendingVoiceText> = emptyList(),
    voiceAvailable: Boolean = false,
    microphonePermission: CameraPermissionStatus = CameraPermissionStatus.Requestable,
    microphoneRequested: Boolean = false,
    onVoice: () -> Unit = {},
    onVoiceSettings: () -> Unit = {},
    onSaveVoice: (String) -> Unit = {},
    onDiscardVoice: (String) -> Unit = {},
    actionBusy: Boolean = false,
    onLiveMode: (Boolean) -> Unit = {},
    onAttachPhoto: (Boolean) -> Unit = {},
    onUseTranscript: (String) -> Unit = {},
    moss: MossLiveUiState = MossLiveUiState(),
    onStartMoss: () -> Unit = {},
    onSharePhotoWithMoss: () -> Unit = {},
    onTakeLivePhoto: () -> Unit = {},
) {
    val list = rememberLazyListState()
    val messages = if (state.viewingSaved) state.messages else state.recentMessages.ifEmpty { state.messages }
    val title = if (state.viewingSaved) {
        state.history.firstOrNull { it.id == state.sessionId }?.title?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.history_saved)
    } else stringResource(R.string.recent_memory_title)
    val currentTitle = state.history.firstOrNull { it.id == state.sessionId }?.title?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.history_untitled)

    LaunchedEffect(messages.lastOrNull()?.id, messages.lastOrNull()?.status, state.viewingSaved,
        pendingVoice.lastOrNull()?.text?.id) {
        val layout = list.layoutInfo
        val nearBottom = layout.totalItemsCount == 0 ||
            (layout.visibleItemsInfo.lastOrNull()?.index ?: 0) >= layout.totalItemsCount - 3
        if (nearBottom) {
            withFrameNanos { }
            val last = list.layoutInfo.totalItemsCount - 1
            if (last >= 0) list.animateScrollToItem(last)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { ChatHeader(state, title, showMenu, onHistory, onSave, actionBusy) },
        bottomBar = { ChatComposer(state, hasPhoto, openingCamera, currentTitle, onDraftChanged, onPhoto, onSend,
            speech, voiceAvailable, microphonePermission, microphoneRequested, onVoice, onVoiceSettings, actionBusy,
            pendingVoice.any { it.status != VoiceSaveStatus.SAVING }, onLiveMode, onAttachPhoto, moss, onStartMoss,
            onSharePhotoWithMoss, onTakeLivePhoto) },
    ) { insets ->
        ChatTimeline(state, messages, list, insets, openingCamera, storageFailed, onPhoto, onRetryStorage, onLoadEarlier,
            pendingVoice, onSaveVoice, onDiscardVoice, actionBusy, onUseTranscript)
    }
}

@Composable
private fun ChatHeader(state: ChatUiState, title: String, showMenu: Boolean, onHistory: () -> Unit, onSave: () -> Unit, actionBusy: Boolean) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (showMenu) TextButton(onClick = onHistory, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.menu_open))
            }
            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
                Text(stringResource(if (state.liveMode) R.string.chat_live_short else R.string.chat_normal_short), color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge)
            }
            if (!state.saved) {
                TextButton(onClick = onSave, enabled = !state.sending && !state.loading && !actionBusy && state.messages.isNotEmpty(),
                    modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.chat_save)) }
            } else Text(stringResource(R.string.saved_short), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ChatComposer(
    state: ChatUiState, hasPhoto: Boolean, openingCamera: Boolean, currentTitle: String,
    onDraftChanged: (String) -> Unit, onPhoto: () -> Unit, onSend: () -> Unit,
    speech: SpeechState, voiceAvailable: Boolean, microphonePermission: CameraPermissionStatus,
    microphoneRequested: Boolean, onVoice: () -> Unit, onVoiceSettings: () -> Unit,
    actionBusy: Boolean,
    unresolvedVoice: Boolean,
    onLiveMode: (Boolean) -> Unit, onAttachPhoto: (Boolean) -> Unit,
    moss: MossLiveUiState,
    onStartMoss: () -> Unit,
    onSharePhotoWithMoss: () -> Unit = {},
    onTakeLivePhoto: () -> Unit = {},
) {
    val focus = LocalFocusManager.current
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.imePadding().navigationBarsPadding()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onStartMoss,
                    enabled = moss.connection !in setOf(MossConnectionState.STOPPING, MossConnectionState.CONNECTING, MossConnectionState.CONFIGURING),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(if (moss.connection in setOf(MossConnectionState.IDLE, MossConnectionState.ERROR))
                        R.string.moss_start else R.string.moss_stop))
                }
                if (moss.connection in setOf(MossConnectionState.LISTENING, MossConnectionState.SPEAKING)) {
                    Button(
                        onClick = onTakeLivePhoto,
                        modifier = Modifier.padding(start = 8.dp).heightIn(min = 48.dp),
                    ) {
                        Text("Take Picture")
                    }
                    if (hasPhoto && !moss.cameraSharing) {
                        Button(
                            onClick = onSharePhotoWithMoss,
                            modifier = Modifier.padding(start = 6.dp).heightIn(min = 48.dp),
                        ) {
                            Text("Show Photo")
                        }
                    }
                }
                Text(
                    stringResource(when (moss.connection) {
                        MossConnectionState.IDLE -> R.string.moss_idle
                        MossConnectionState.CONNECTING -> R.string.moss_connecting
                        MossConnectionState.CONFIGURING -> R.string.moss_configuring
                        MossConnectionState.LISTENING -> R.string.moss_listening
                        MossConnectionState.SPEAKING -> R.string.moss_speaking
                        MossConnectionState.STOPPING -> R.string.moss_stopping
                        MossConnectionState.ERROR -> R.string.moss_error_short
                    }),
                    modifier = Modifier.padding(start = 12.dp),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            if (moss.cameraSharing) {
                StatusText("Photo streaming to MOSS (camera active)", isError = false)
            }
            if (moss.microphoneTransmitting) StatusText(stringResource(R.string.moss_microphone_transmitting))
            if (moss.connection != MossConnectionState.IDLE) {
                Text(stringResource(R.string.moss_privacy_disclosure), style = MaterialTheme.typography.labelMedium)
            }
            moss.error?.let { StatusText(it, isError = true) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onLiveMode(!state.liveMode) }, enabled = !state.sending && !state.loading && !actionBusy) {
                    Text(stringResource(if (state.liveMode) R.string.chat_switch_chat else R.string.chat_switch_live))
                }
                if (state.liveMode && hasPhoto) TextButton(onClick = { onAttachPhoto(!state.attachPhoto) },
                    enabled = !state.sending && !state.loading && !actionBusy) {
                    Text(stringResource(if (state.attachPhoto) R.string.chat_photo_attached else R.string.chat_attach_photo))
                }
            }
            if (state.liveMode) Text(stringResource(R.string.chat_live_disclosure), style = MaterialTheme.typography.labelMedium)
            Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(24.dp),
                tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    OutlinedTextField(value = state.draft, onValueChange = onDraftChanged,
                        label = { Text(stringResource(R.string.chat_composer_label)) },
                        placeholder = { Text(stringResource(R.string.question_example)) },
                        enabled = !state.sending && !state.loading, minLines = 1, maxLines = 3,
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp))
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        TextButton(onClick = { focus.clearFocus(); onPhoto() },
                            enabled = !state.sending && !state.loading && !openingCamera && !actionBusy,
                            modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.chat_photo)) }
                        TextButton(onClick = { focus.clearFocus(); onVoice() },
                            enabled = voiceAvailable && !state.sending && !state.loading && !openingCamera && !actionBusy &&
                                speech.phase != SpeechPhase.STOPPING && (speech.phase != SpeechPhase.IDLE || !unresolvedVoice),
                            modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(if (speech.phase == SpeechPhase.IDLE) R.string.voice_start else R.string.voice_stop))
                        }
                        Button(onClick = { focus.clearFocus(); onSend() },
                            enabled = state.draft.isNotBlank() && !state.sending && !state.loading && !openingCamera && !actionBusy,
                            modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.chat_send_short)) }
                    }
                }
            }
            Text(stringResource(if (hasPhoto) R.string.photo_ready else R.string.chat_photo_optional),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when (speech.phase) {
                SpeechPhase.PREPARING -> StatusText(stringResource(R.string.voice_preparing))
                SpeechPhase.LISTENING -> StatusText(stringResource(R.string.voice_listening))
                SpeechPhase.STOPPING -> StatusText(stringResource(R.string.voice_finishing))
                SpeechPhase.IDLE -> Unit
            }
            speech.problem?.let { problem -> StatusText(stringResource(voiceProblemString(problem)), isError = true) }
            if (microphoneRequested && microphonePermission != CameraPermissionStatus.Granted) {
                val blocked = microphonePermission == CameraPermissionStatus.SettingsRequired
                StatusText(stringResource(if (blocked) R.string.voice_permission_blocked else R.string.voice_permission_request), isError = true)
                if (blocked) TextButton(onClick = onVoiceSettings, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.open_settings))
                }
            }
            if (!state.viewingSaved) Text(stringResource(R.string.active_discussion, currentTitle),
                modifier = Modifier.padding(horizontal = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ChatTimeline(
    state: ChatUiState, messages: List<ChatMessage>, list: LazyListState, insets: PaddingValues,
    openingCamera: Boolean, storageFailed: Boolean, onPhoto: () -> Unit,
    onRetryStorage: () -> Unit, onLoadEarlier: () -> Unit,
    pendingVoice: List<PendingVoiceText>, onSaveVoice: (String) -> Unit, onDiscardVoice: (String) -> Unit,
    actionBusy: Boolean,
    onUseTranscript: (String) -> Unit,
) {
    val existingIds = messages.map { it.id }.toSet()
    val pending = pendingVoice.filter { it.text.id !in existingIds &&
        (!state.viewingSaved || it.text.conversationId == state.sessionId) }
    LazyColumn(state = list, modifier = Modifier.fillMaxSize().padding(insets),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item(key = "disclosure") {
            Text(stringResource(R.string.chat_disclosure_compact), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!state.viewingSaved) item(key = "memory") {
            Text(stringResource(R.string.recent_notice_compact), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.recentDisplayLimited && !state.viewingSaved) item(key = "limited") {
            if (state.recentCanLoadMore) {
                TextButton(onClick = onLoadEarlier, enabled = !state.loading && !state.sending,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.load_earlier)) }
            } else StatusText(stringResource(R.string.recent_display_limited))
        }
        if (state.loading) item(key = "loading") { StatusText(stringResource(R.string.history_loading)) }
        if (state.storageError) item(key = "database_error") {
            Column {
                StatusText(stringResource(R.string.history_storage_failed), isError = true)
                TextButton(onClick = onRetryStorage, enabled = !state.loading) { Text(stringResource(R.string.retry_storage)) }
            }
        }
        if (state.viewingSaved && state.historyTruncated) item(key = "truncated") { StatusText(stringResource(R.string.chat_truncated)) }
        if (messages.isEmpty() && !state.loading) item(key = "welcome") {
            Column(Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.chat_welcome), style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.chat_welcome_detail), style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        itemsIndexed(messages, key = { _, message -> message.id }) { index, message ->
            Column {
                if (!state.viewingSaved && (index == 0 || messages[index - 1].sessionId != message.sessionId)) {
                    val discussion = state.history.firstOrNull { it.id == message.sessionId }?.title
                        ?.takeIf { it.isNotBlank() } ?: stringResource(R.string.history_untitled)
                    Text(stringResource(R.string.discussion_label, discussion), modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 8.dp)
                        .semantics { heading() }, style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ChatBubble(message, onPhoto, !state.sending && !state.loading && !openingCamera && !actionBusy, onUseTranscript)
            }
        }
        items(pending, key = { it.text.id }) { pendingText ->
            VoiceTranscriptPendingBubble(pendingText, onSaveVoice, onDiscardVoice)
        }
        if (openingCamera) item(key = "opening") { StatusText(stringResource(R.string.preparing)) }
        if (storageFailed) item(key = "storage") { StatusText(stringResource(R.string.storage_failed), isError = true) }
    }
}

private fun voiceProblemString(problem: SpeechProblem): Int = when (problem) {
    SpeechProblem.PERMISSION -> R.string.voice_error_permission
    SpeechProblem.MODEL -> R.string.voice_error_model
    SpeechProblem.AUDIO -> R.string.voice_error_audio
    SpeechProblem.ROUTE -> R.string.voice_error_route
    SpeechProblem.INTERRUPTED -> R.string.voice_error_interrupted
    SpeechProblem.OVERLOADED -> R.string.voice_error_overloaded
    SpeechProblem.TOO_LONG -> R.string.voice_error_too_long
    SpeechProblem.RECOGNITION -> R.string.voice_error_recognition
}
