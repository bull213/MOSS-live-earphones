package com.visualassistant.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.visualassistant.app.R
import com.visualassistant.app.chat.ChatUiState
import com.visualassistant.app.network.BackendEndpoint
import com.visualassistant.app.voice.PendingVoiceText
import com.visualassistant.app.voice.SpeechState
import com.visualassistant.app.mosslive.MossLiveUiState
import kotlinx.coroutines.launch

@Composable
internal fun ConversationWorkspace(
    state: ChatUiState,
    hasPhoto: Boolean,
    openingCamera: Boolean,
    storageFailed: Boolean,
    onDraftChanged: (String) -> Unit,
    onPhoto: () -> Unit,
    onSend: () -> Unit,
    onNew: () -> Unit,
    onRecent: () -> Unit,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onSave: () -> Unit,
    onRetryStorage: () -> Unit,
    onLoadEarlier: () -> Unit,
    speech: SpeechState = SpeechState(),
    pendingVoice: List<PendingVoiceText> = emptyList(),
    voiceAvailable: Boolean = false,
    microphonePermission: CameraPermissionStatus = CameraPermissionStatus.Requestable,
    microphoneRequested: Boolean = false,
    onVoice: () -> Unit = {},
    onVoiceSettings: () -> Unit = {},
    onSaveVoice: (String) -> Unit = {},
    onDiscardVoice: (String) -> Unit = {},
    onBeforeSettings: (() -> Unit) -> Unit = { it() },
    actionBusy: Boolean = false,
    onLiveMode: (Boolean) -> Unit = {},
    onAttachPhoto: (Boolean) -> Unit = {},
    onUseTranscript: (String) -> Unit = {},
    onImportBook: () -> Unit = {},
    moss: MossLiveUiState = MossLiveUiState(),
    onStartMoss: () -> Unit = {},
    onSharePhotoWithMoss: () -> Unit = {},
    onTakeLivePhoto: () -> Unit = {},
    endpoint: BackendEndpoint? = null,
    speakingMessageId: String? = null,
    onReplaySpeech: (String, String) -> Unit = { _, _ -> },
    onSelectNotebook: (com.visualassistant.app.network.NotebookItem) -> Unit = {},
    onExitNotebook: () -> Unit = {},
    onLoadNotebooks: () -> Unit = {},
    onClearRecentMemory: () -> Unit = {},
) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var settings by rememberSaveable { mutableStateOf(false) }
    var confirmNew by rememberSaveable { mutableStateOf(false) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    val busy = state.sending || state.loading || openingCamera || actionBusy
    fun closeDrawer() { scope.launch { drawer.close() } }

    BackHandler(enabled = settings && drawer.currentValue == DrawerValue.Closed) { settings = false }
    WorkspaceDialogs(
        confirmNew = confirmNew,
        deleteId = deleteId,
        onCancelNew = { confirmNew = false },
        onConfirmNew = { confirmNew = false; settings = false; onNew(); closeDrawer() },
        onCancelDelete = { deleteId = null },
        onConfirmDelete = { id -> onDelete(id); deleteId = null },
    )

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        val content: @Composable () -> Unit = {
            if (settings) SettingsInfo(onBack = { settings = false }, endpoint = endpoint, onClearRecentMemory = onClearRecentMemory)
            else ChatScreen(state, hasPhoto, openingCamera, storageFailed, onDraftChanged, onPhoto, onSend,
                onNewSession = onNew, onHistory = { scope.launch { drawer.open() } },
                onRetryStorage = onRetryStorage, onSave = onSave, onLoadEarlier = onLoadEarlier, showMenu = !wide,
                speech = speech, pendingVoice = pendingVoice, voiceAvailable = voiceAvailable,
                microphonePermission = microphonePermission, microphoneRequested = microphoneRequested,
                onVoice = onVoice, onVoiceSettings = onVoiceSettings, onSaveVoice = onSaveVoice, onDiscardVoice = onDiscardVoice,
                actionBusy = actionBusy, onLiveMode = onLiveMode, onAttachPhoto = onAttachPhoto,
                onUseTranscript = onUseTranscript, moss = moss, onStartMoss = onStartMoss,
                onSharePhotoWithMoss = onSharePhotoWithMoss, onTakeLivePhoto = onTakeLivePhoto,
                speakingMessageId = speakingMessageId, onReplaySpeech = onReplaySpeech,
                onExitNotebook = onExitNotebook)
        }
        val drawerContent: @Composable () -> Unit = {
            AssistantSidebar(state = state, busy = busy, showingSettings = settings,
                onRecent = { settings = false; onExitNotebook(); onRecent(); closeDrawer() },
                onNew = { if (state.messages.isEmpty()) { settings = false; onExitNotebook(); onNew(); closeDrawer() } else confirmNew = true },
                onOpen = { id -> settings = false; onExitNotebook(); onOpen(id); closeDrawer() },
                onDelete = { deleteId = it },
                onSettings = { onBeforeSettings { settings = true; closeDrawer() } },
                onImportBook = { onImportBook(); closeDrawer() },
                onSelectNotebook = { nb -> settings = false; onSelectNotebook(nb); closeDrawer() },
                onLoadNotebooks = onLoadNotebooks)
        }
        if (wide) {
            PermanentNavigationDrawer(drawerContent = {
                PermanentDrawerSheet(Modifier.widthIn(max = 320.dp)) { drawerContent() }
            }, content = content)
        } else {
            ModalNavigationDrawer(drawerState = drawer, gesturesEnabled = !busy,
                drawerContent = { ModalDrawerSheet(Modifier.widthIn(max = 320.dp)) { drawerContent() } },
                content = content)
        }
    }
}

@Composable
private fun WorkspaceDialogs(
    confirmNew: Boolean,
    deleteId: String?,
    onCancelNew: () -> Unit,
    onConfirmNew: () -> Unit,
    onCancelDelete: () -> Unit,
    onConfirmDelete: (String) -> Unit,
) {
    if (confirmNew) AlertDialog(onDismissRequest = onCancelNew,
        title = { Text(stringResource(R.string.chat_new_session)) },
        text = { Text(stringResource(R.string.chat_clear_question)) },
        confirmButton = { TextButton(onClick = onConfirmNew) { Text(stringResource(R.string.chat_start_over)) } },
        dismissButton = { TextButton(onClick = onCancelNew) { Text(stringResource(R.string.chat_cancel)) } })
    if (deleteId != null) AlertDialog(onDismissRequest = onCancelDelete,
        title = { Text(stringResource(R.string.history_delete_title)) },
        text = { Text(stringResource(R.string.history_delete_description)) },
        confirmButton = { TextButton(onClick = { onConfirmDelete(deleteId) }) { Text(stringResource(R.string.history_confirm_delete)) } },
        dismissButton = { TextButton(onClick = onCancelDelete) { Text(stringResource(R.string.chat_cancel)) } })
}
