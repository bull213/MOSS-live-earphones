package com.visualassistant.app.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.visualassistant.app.camera.CameraViewModel
import com.visualassistant.app.camera.CaptureProblem
import com.visualassistant.app.camera.CaptureStore
import com.visualassistant.app.chat.ChatKind
import com.visualassistant.app.chat.ChatViewModel
import com.visualassistant.app.R
import com.visualassistant.app.knowledge.PhoneBookImporter
import com.visualassistant.app.network.BackendEndpoint
import com.visualassistant.app.voice.SpeechPhase
import com.visualassistant.app.mosslive.MossConnectionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
internal fun ChatRoute(
    activity: ComponentActivity,
    camera: CameraViewModel,
    chat: ChatViewModel,
    store: CaptureStore,
    cameraBinding: CameraBinding = PhoneCameraBinding,
    endpoint: BackendEndpoint? = null,
) {
    val cameraState by camera.state.collectAsStateWithLifecycle()
    val chatState by chat.state.collectAsStateWithLifecycle()
    val speechState by chat.speechState.collectAsStateWithLifecycle()
    val pendingVoice by chat.pendingVoice.collectAsStateWithLifecycle()
    val mossState by chat.mossState.collectAsStateWithLifecycle()
    val speakingMessageId by chat.speakingMessageId.collectAsStateWithLifecycle()
    val mossActive = mossState.connection !in setOf(MossConnectionState.IDLE, MossConnectionState.ERROR)
    val scope = rememberCoroutineScope()
    var showCamera by rememberSaveable { mutableStateOf(false) }
    var openingCamera by rememberSaveable { mutableStateOf(false) }
    var storageFailed by rememberSaveable { mutableStateOf(false) }
    var sessionPhotoId by rememberSaveable { mutableStateOf<String?>(null) }
    var actionBusy by remember { mutableStateOf(false) }
    var importingBook by remember { mutableStateOf(false) }
    var importStatus by remember { mutableStateOf<String?>(null) }
    var importError by remember { mutableStateOf(false) }
    var importWarnings by remember { mutableStateOf<List<String>>(emptyList()) }
    var importJob by remember { mutableStateOf<Job?>(null) }
    var showImportDisclosure by remember { mutableStateOf(false) }
    var mossPermissionPending by remember { mutableStateOf(false) }
    val latestRecentPhoto = chatState.recentMessages.asReversed().firstOrNull { it.kind == ChatKind.PHOTO && it.photo != null }?.photo
    val selectedPhoto = cameraState.photo?.takeIf { it.id == sessionPhotoId }
        ?: (chatState.currentPhoto ?: latestRecentPhoto).takeIf { chatState.attachPhoto }
    val bookImporter = remember(activity, endpoint) { PhoneBookImporter(activity.contentResolver, activity.cacheDir, endpoint) }
    val bookPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !importingBook) {
            importingBook = true
            importError = false
            importWarnings = emptyList()
            importStatus = "Preparing selected PDF…"
            importJob = scope.launch {
                try {
                    val result = bookImporter.import(uri) { progress ->
                        scope.launch { importStatus = progress }
                    }
                    importWarnings = result.warnings
                    importStatus = if (result.status == "unchanged")
                        activity.getString(R.string.book_import_unchanged, result.title)
                    else activity.getString(R.string.book_import_success, result.title, result.passages)
                } catch (cancelled: CancellationException) {
                    importStatus = "Book import cancelled. The temporary backend copy will expire automatically."
                } catch (error: Exception) {
                    importError = true
                    importStatus = error.message ?: "The local book import failed. Check the USB connection and backend."
                } finally {
                    importingBook = false
                    importJob = null
                }
            }
        }
    }
    val microphone = rememberMicrophonePermissionState(activity) {
        if (mossPermissionPending) {
            mossPermissionPending = false
            scope.launch {
                chat.stopVoiceAndWait()
                chat.startMossLive(enableVideo = false)
            }
        } else if (!showCamera && !actionBusy) chat.startVoice()
    }

    DisposableEffect(activity, microphone, chat) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> microphone.refresh()
                Lifecycle.Event.ON_STOP -> { chat.stopVoice(); chat.stopMossLive(); chat.stopSpeech() }
                else -> Unit
            }
        }
        microphone.refresh()
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer); chat.stopVoice(); chat.stopMossLive(); chat.stopSpeech() }
    }

    fun afterVoiceStops(action: () -> Unit) {
        if (actionBusy) return
        actionBusy = true
        scope.launch {
            try {
                val wasSpeaking = chat.isSpeaking()
                chat.stopSpeech()
                if (chat.stopVoiceAndWait()) {
                    if (chat.state.value.activeNotebook != null) {
                        chat.stopMossLive()
                    }
                    if (wasSpeaking) kotlinx.coroutines.delay(150)
                    action()
                }
            }
            finally { actionBusy = false }
        }
    }

    if (showImportDisclosure) AlertDialog(
        onDismissRequest = { showImportDisclosure = false },
        title = { Text(stringResource(R.string.book_import_privacy_title)) },
        text = { Text(stringResource(R.string.book_import_privacy_body)) },
        confirmButton = {
            TextButton(onClick = {
                showImportDisclosure = false
                bookPicker.launch(arrayOf("application/pdf"))
            }) { Text(stringResource(R.string.book_import_continue)) }
        },
        dismissButton = {
            TextButton(onClick = { showImportDisclosure = false }) { Text(stringResource(R.string.chat_cancel)) }
        },
    )

    importStatus?.let { message ->
        AlertDialog(
            onDismissRequest = { if (!importingBook) importStatus = null },
            title = { Text(stringResource(if (importError) R.string.book_import_failed_title else R.string.book_import_progress_title)) },
            text = {
                androidx.compose.foundation.layout.Column {
                    Text(message)
                    if (importWarnings.isNotEmpty()) {
                        Text("\nReview these extraction warnings:", style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
                        importWarnings.take(6).forEach { Text("• $it") }
                        if (importWarnings.size > 6) Text("And ${importWarnings.size - 6} more page warnings.")
                    }
                }
            },
            confirmButton = {
                if (importingBook) TextButton(onClick = { importJob?.cancel() }) {
                    Text(stringResource(R.string.chat_cancel))
                } else TextButton(onClick = { importStatus = null }) {
                    Text(stringResource(R.string.book_import_done))
                }
            },
        )
    }

    LaunchedEffect(openingCamera, cameraState.photo, cameraState.isDeleting, cameraState.problem) {
        if (openingCamera && !cameraState.isDeleting) {
            if (cameraState.photo == null) {
                openingCamera = false
                showCamera = true
            } else if (cameraState.problem == CaptureProblem.STORAGE) {
                openingCamera = false
                storageFailed = true
            }
        }
    }
    BackHandler(enabled = showCamera) { showCamera = false }
    if (showCamera) {
        CameraRoute(activity, camera, store, question = chatState.draft,
            onQuestionChanged = chat::updateDraft, cameraBinding = cameraBinding,
            mossActive = mossActive,
            onChatSubmit = { photo, question ->
                sessionPhotoId = photo.id
                if (mossActive || question.isBlank()) {
                    chat.addPhotoToConversation(photo)
                    showCamera = false
                } else {
                    chat.submit(photo, question)
                    showCamera = false
                }
            })
    } else {
        ConversationWorkspace(
            state = chatState,
            hasPhoto = selectedPhoto != null && !cameraState.isDeleting,
            openingCamera = openingCamera,
            storageFailed = storageFailed,
            onRetryStorage = { afterVoiceStops { chat.retryStorage() } },
            onDraftChanged = chat::updateDraft,
            onPhoto = {
                afterVoiceStops {
                    storageFailed = false
                    sessionPhotoId = null
                    if (cameraState.photo == null) showCamera = true
                    else {
                        openingCamera = true
                        camera.retake()
                    }
                }
            },
            onSend = { afterVoiceStops {
                val photoToSubmit = selectedPhoto
                sessionPhotoId = null
                val activeNb = chat.state.value.activeNotebook
                if (activeNb != null) {
                    chat.submitNotebookText(activeNb, chat.state.value.draft)
                } else if (photoToSubmit != null) {
                    chat.submit(photoToSubmit, chat.state.value.draft)
                } else {
                    chat.sendTextWithoutPhoto()
                }
            } },
            onLiveMode = { enabled ->
                chat.setLiveMode(enabled)
                if (enabled && (chat.state.value.viewingSaved || chat.state.value.saved)) chat.showRecent()
            },
            onAttachPhoto = chat::setAttachPhoto,
            onUseTranscript = chat::useTranscript,
            onRecent = {
                afterVoiceStops {
                    if (chat.state.value.viewingSaved || chat.state.value.saved) {
                        sessionPhotoId = null
                        if (cameraState.photo != null) camera.retake()
                        chat.showRecent()
                    }
                }
            },
            onOpen = { id ->
                afterVoiceStops {
                    sessionPhotoId = null
                    if (cameraState.photo != null) camera.retake()
                    chat.openConversation(id)
                }
            },
            onNew = {
                afterVoiceStops {
                    sessionPhotoId = null
                    storageFailed = false
                    if (cameraState.photo != null) camera.retake()
                    chat.newSession()
                }
            },
            onDelete = { id ->
                afterVoiceStops {
                    if (id == chat.state.value.sessionId) {
                        sessionPhotoId = null
                        if (cameraState.photo != null) camera.retake()
                    }
                    chat.deleteConversation(id)
                }
            },
            onSave = { afterVoiceStops { chat.saveConversation() } },
            onImportBook = { afterVoiceStops { showImportDisclosure = true } },
            onLoadEarlier = chat::loadEarlierRecent,
            speech = speechState,
            pendingVoice = pendingVoice,
            voiceAvailable = chat.voiceAvailable,
            microphonePermission = microphone.status,
            microphoneRequested = microphone.wasRequested,
            onVoice = {
                mossPermissionPending = false
                if (speechState.phase != SpeechPhase.IDLE) chat.stopVoice()
                else when (microphone.status) {
                    CameraPermissionStatus.Granted -> chat.startVoice()
                    CameraPermissionStatus.Requestable -> microphone.request()
                    CameraPermissionStatus.SettingsRequired -> microphone.openSettings()
                }
            },
            onVoiceSettings = microphone.openSettings,
            onSaveVoice = chat::savePendingVoice,
            onDiscardVoice = chat::discardPendingVoice,
            onBeforeSettings = { action -> afterVoiceStops(action) },
            actionBusy = actionBusy || importingBook || mossActive,
            moss = mossState,
            onStartMoss = {
                if (mossState.connection == MossConnectionState.IDLE || mossState.connection == MossConnectionState.ERROR) {
                    when (microphone.status) {
                        CameraPermissionStatus.Granted -> afterVoiceStops { chat.startMossLive(enableVideo = false) }
                        CameraPermissionStatus.Requestable -> { mossPermissionPending = true; microphone.request() }
                        CameraPermissionStatus.SettingsRequired -> microphone.openSettings()
                    }
                } else chat.stopMossLive()
            },
            onSharePhotoWithMoss = {
                selectedPhoto?.let(chat::sharePhotoWithMossLive)
            },
            onTakeLivePhoto = {
                sessionPhotoId = null
                camera.retake()
                showCamera = true
            },
            endpoint = endpoint,
            speakingMessageId = speakingMessageId,
            onReplaySpeech = chat::replaySpeech,
            onSelectNotebook = { nb ->
                afterVoiceStops {
                    sessionPhotoId = null
                    if (cameraState.photo != null) camera.retake()
                    chat.selectNotebook(nb)
                }
            },
            onExitNotebook = {
                afterVoiceStops {
                    chat.selectNotebook(null)
                }
            },
            onLoadNotebooks = chat::loadNotebooks,
            onClearRecentMemory = {
                afterVoiceStops {
                    sessionPhotoId = null
                    if (cameraState.photo != null) camera.retake()
                    chat.clearRecentRollingMemory()
                }
            },
        )
    }
}
