package com.visualassistant.app.mosslive

import android.util.Log
import android.media.AudioManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

internal enum class MossConnectionState { IDLE, CONNECTING, CONFIGURING, LISTENING, SPEAKING, STOPPING, ERROR }

internal data class MossTranscriptLine(
    val turnId: String,
    val text: String,
    val assistant: Boolean,
    val final: Boolean,
)

internal data class MossLiveUiState(
    val connection: MossConnectionState = MossConnectionState.IDLE,
    val microphoneTransmitting: Boolean = false,
    val cameraSharing: Boolean = false,
    val transcripts: List<MossTranscriptLine> = emptyList(),
    val error: String? = null,
)

internal data class MossTranscriptUpdate(
    val sessionId: String,
    val turnId: String,
    val text: String,
    val assistant: Boolean,
    val final: Boolean,
)

/** Owns one foreground-only phone-to-backend-to-MOSS session. */
internal class MossLiveSessionController(
    private val transport: MossLiveTransport,
    private val audioSource: RealtimeAudioSource,
    private val replyTranscriber: ReplyAudioTranscriber = ReplyAudioTranscriber { "" },
    private val audioManager: AudioManager? = null,
    private val audioOutputFactory: ((sendControl: (String) -> Boolean, onFailure: () -> Unit, onPlaybackDrained: (String) -> Unit) -> MossAudioOutput)? = null,
    isEarphonesActiveProvider: (() -> Boolean)? = null,
    echoSuppressor: MossEchoSuppressor? = null,
) {
    private val diagnostics = MossLiveDiagnostics()
    private val echoSuppressor = echoSuppressor ?: MossEchoSuppressor(
        audioManager = audioManager,
        diagnostics = diagnostics,
        isEarphonesActiveProvider = isEarphonesActiveProvider,
    )
    private val mutableState = MutableStateFlow(MossLiveUiState())
    val state = mutableState.asStateFlow()
    private val mutableTranscriptUpdates = MutableSharedFlow<MossTranscriptUpdate>(extraBufferCapacity = 32)
    val transcriptUpdates = mutableTranscriptUpdates.asSharedFlow()
    private val inputTranscriptTracker = MossInputTranscriptTracker()
    private var activeScope: CoroutineScope? = null
    private var activeSessionId: String? = null
    private var pendingContext = MossSessionContext()
    private var audioContext: MossAudioContext? = null
    private var activeAssistantResponse: MossResponseId? = null
    private var audioPlayer: MossAudioOutput? = null
    private val replyAudio = MossReplyAudioBuffer(MAX_REPLY_AUDIO_BYTES)
    private var activeCameraFrame: ByteArray? = null
    private var cameraStreamJob: kotlinx.coroutines.Job? = null
    private val configured = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)
    private val diagnosticsLogged = AtomicBoolean(false)

    @Synchronized
    fun start(scope: CoroutineScope, sessionId: String, sessionContext: MossSessionContext = MossSessionContext()) {
        if (mutableState.value.connection !in setOf(MossConnectionState.IDLE, MossConnectionState.ERROR)) return
        activeScope = scope
        activeSessionId = sessionId
        pendingContext = sessionContext
        cameraStreamJob?.cancel()
        cameraStreamJob = null
        activeCameraFrame = null
        configured.set(false)
        stopping.set(false)
        diagnosticsLogged.set(false)
        diagnostics.reset()
        echoSuppressor.reset()
        inputTranscriptTracker.reset()
        replyAudio.clear()
        activeAssistantResponse = null
        audioContext = null
        mutableState.value = MossLiveUiState(connection = MossConnectionState.CONNECTING)
        transport.connect(object : MossLiveTransportListener {
            override fun onOpen() {
                if (!stopping.get()) mutableState.update { it.copy(connection = MossConnectionState.CONFIGURING) }
            }

            override fun onText(text: String) = handleText(text)

            override fun onBinary(bytes: ByteArray) {
                val context = synchronized(this@MossLiveSessionController) {
                    audioContext.also { audioContext = null }
                }
                if (context == null) {
                    fail()
                    return
                }
                diagnostics.recordDownlinkAudioChunk(bytes.size)
                replyAudio.append(context.responseKey, bytes)
                audioPlayer?.enqueue(context, bytes)
            }

            override fun onClosed() {
                if (!stopping.get()) fail(shutdownInitiator = "backend_socket_closed")
            }

            override fun onFailure() {
                if (!stopping.get()) fail(shutdownInitiator = "backend_socket_failure")
            }
        })
    }

    fun stop() {
        if (!stopping.compareAndSet(false, true)) return
        cameraStreamJob?.cancel()
        cameraStreamJob = null
        activeCameraFrame = null
        echoSuppressor.onAssistantInterruptedOrStopped()
        diagnostics.recordShutdown("user_stop")
        activeAssistantResponse?.key?.let(::finishFallbackAudio)
        mutableState.update { it.copy(connection = MossConnectionState.STOPPING, microphoneTransmitting = false, cameraSharing = false) }
        val scope = activeScope
        if (scope == null) {
            logDiagnosticsOnce()
            transport.close()
            mutableState.value = mutableState.value.copy(connection = MossConnectionState.IDLE)
            return
        }
        scope.launch {
            try {
                if (configured.get()) {
                    transport.sendText(MossLiveProtocol.videoState(false))
                    transport.sendText(MossLiveProtocol.micState(false))
                }
                audioSource.stop()
                audioPlayer?.stop()
                logDiagnosticsOnce()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Session shutdown still closes the transport and reports an idle state.
            } finally {
                audioPlayer = null
                transport.close()
                activeScope = null
                activeSessionId = null
                pendingContext = MossSessionContext()
                audioContext = null
                mutableState.update { it.copy(connection = MossConnectionState.IDLE, microphoneTransmitting = false, cameraSharing = false) }
            }
        }
    }

    /** Adds a normal chat turn as reference context without replaying MOSS's own live turns. */
    fun appendExternalContext(instructions: String, photo: ByteArray? = null) {
        if (!configured.get() || stopping.get()) return
        instructions.chunked(MAX_INSTRUCTION_CHARS).forEach { chunk ->
            if (!sendControl(MossLiveProtocol.appendInstructions(chunk))) return
        }
        if (photo != null) {
            val scope = activeScope
            if (scope != null) setCameraPhoto(photo, scope)
        }
    }

    private fun handleText(raw: String) {
        val event = try {
            MossLiveProtocol.parseEvent(raw)
        } catch (_: Exception) {
            fail()
            return
        }
        diagnostics.recordEvent(event.action)
        handleEvent(event.action, event.data)
    }

    private fun handleEvent(action: String, data: JSONObject) {
        when (action) {
            "session_attached" -> mutableState.update { it.copy(connection = MossConnectionState.CONFIGURING) }
            "session.updated" -> configureAudioSession(data)
            "input.transcript.updated" -> updateInputTranscript(data, finalized = false, revised = false)
            "input.transcript.done" -> updateInputTranscript(data, finalized = true, revised = false)
            "input.transcript.revised" -> updateInputTranscript(data, finalized = true, revised = true)
            "output.transcript.updated" -> updateAssistantTranscript(data, finalized = false)
            "output.transcript.done" -> updateAssistantTranscript(data, finalized = true)
            "input.speech.started" -> mutableState.update { it.copy(connection = MossConnectionState.LISTENING) }
            "response.audio.started" -> {
                echoSuppressor.onAssistantSpeechStarted()
                if (rememberAssistantResponse(data) == null) fail()
                else mutableState.update { it.copy(connection = MossConnectionState.SPEAKING) }
            }
            "response.audio.context" -> captureAudioContext(data)
            "response.audio.done" -> handleAudioDone(data)
            "response.audio.stop" -> handleAudioStop(data)
            "response.ended" -> finishResponse(data)
            "response.interrupted" -> handleInterrupted(data)
            "error" -> handleProviderError(data)
            else -> Unit
        }
    }

    private fun configureAudioSession(data: JSONObject) {
        val configError = data.opt("error")
        if (configError != null && configError != JSONObject.NULL && configError.toString().isNotEmpty()) {
            fail()
            return
        }
        if (!configured.compareAndSet(false, true)) return
        val scope = activeScope ?: return
        scope.launch {
            try {
                val sendControl: (String) -> Boolean = ::sendControl
                val playbackFailure = { fail(shutdownInitiator = "playback_failure") }
                val onDrained = ::onAssistantPlaybackDrained
                val player = audioOutputFactory?.invoke(sendControl, playbackFailure, onDrained)
                    ?: MossAudioPlayer(
                        audioManager = audioManager,
                        sendControl = sendControl,
                        onFailure = playbackFailure,
                        diagnostics = diagnostics,
                        onPlaybackDrained = onDrained,
                    )
                player.start(scope)
                audioPlayer = player
                check(sendControl(MossLiveProtocol.mediaMode()))
                if (pendingContext.instructions.isNotBlank()) {
                    pendingContext.instructions.chunked(MAX_INSTRUCTION_CHARS).forEach { chunk ->
                        check(sendControl(MossLiveProtocol.appendInstructions(chunk)))
                    }
                }
                if (pendingContext.photos.isNotEmpty()) {
                    setCameraPhoto(pendingContext.photos.firstOrNull(), scope)
                }
                check(sendControl(MossLiveProtocol.micState(true)))
                audioSource.start(scope, { pcm ->
                    val frameToSend = echoSuppressor.processUplinkPcm(pcm)
                    val taggedPcm = MossLiveProtocol.taggedAudio(frameToSend)
                    if (transport.queuedBytes() > MAX_AUDIO_BACKLOG_BYTES || !transport.sendBinary(taggedPcm)) {
                        fail()
                    } else {
                        diagnostics.recordUplinkPcmFrame()
                    }
                }, ::fail)
                mutableState.update { it.copy(connection = MossConnectionState.LISTENING, microphoneTransmitting = true) }
            } catch (_: Exception) {
                fail()
            }
        }
    }

    private fun updateAssistantTranscript(data: JSONObject, finalized: Boolean) {
        val responseId = rememberAssistantResponse(data)
        if (responseId == null) {
            if (finalized) fail()
            return
        }
        updateTranscript(data, assistant = true, finalized = finalized, explicitTurnId = responseId.key)
        if (!finalized) mutableState.update { it.copy(connection = MossConnectionState.SPEAKING) }
    }

    private fun updateInputTranscript(data: JSONObject, finalized: Boolean, revised: Boolean) {
        val providerTurn = data.optString("turn").takeIf(String::isNotBlank)
        val text = data.optString("text")
        val change = when {
            revised -> inputTranscriptTracker.revised(text, providerTurn, data.optString("previous"))
            finalized -> inputTranscriptTracker.done(text, providerTurn)
            else -> inputTranscriptTracker.updated(text, providerTurn)
        } ?: return
        change.removeTurnId?.let { removeTranscriptState(it, assistant = false) }
        if (change.finalized) {
            updateTranscript(data, assistant = false, finalized = true, explicitTurnId = change.turnId)
        } else {
            updateTranscriptState(change.turnId, change.text, assistant = false, finalized = false)
        }
    }

    private fun removeTranscriptState(turnId: String, assistant: Boolean) {
        synchronized(this) {
            mutableState.update { current ->
                current.copy(transcripts = current.transcripts.filterNot { it.turnId == turnId && it.assistant == assistant })
            }
        }
    }

    private fun rememberAssistantResponse(data: JSONObject): MossResponseId? {
        val responseId = MossLiveProtocol.responseId(data) ?: return null
        activeAssistantResponse = responseId
        return responseId
    }

    private fun handleInterrupted(data: JSONObject) {
        echoSuppressor.onAssistantInterruptedOrStopped()
        val text = data.optString("text")
        diagnostics.recordInterruptionReason(data.optString("reason"))
        val responseId = resolveInterruptedResponse(data)
        responseId?.let {
            audioPlayer?.stopResponse(it.runId, it.turnId)
            replyAudio.discard(it.key)
            if (text.isNotBlank()) {
                updateTranscript(data, assistant = true, finalized = false, explicitTurnId = it.key)
            }
            clearAssistantResponse(it.key)
        }
    }

    private fun resolveInterruptedResponse(data: JSONObject): MossResponseId? {
        val runId = data.optString("run_id").takeIf(String::isNotBlank)
        val turnId = data.optString("turn_id").takeIf(String::isNotBlank)
        val active = activeAssistantResponse ?: return null
        if (runId == null && turnId == null) return active
        if (runId != null && runId != active.runId) return null
        if (turnId != null && turnId != active.turnId) return null
        return MossResponseId(runId ?: active.runId, turnId ?: active.turnId)
    }

    private fun captureAudioContext(data: JSONObject) {
        val context = MossLiveProtocol.audioContext(data)
        if (context == null) {
            fail()
        } else {
            val accepted = synchronized(this) {
                if (audioContext != null) false else {
                    audioContext = context
                    true
                }
            }
            if (accepted) diagnostics.recordAudioContext(context) else fail()
        }
    }

    private fun handleAudioDone(data: JSONObject) {
        val responseId = MossLiveProtocol.responseId(data)
        if (responseId != null) audioPlayer?.audioDone(responseId.runId, responseId.turnId) else fail()
    }

    private fun handleAudioStop(data: JSONObject) {
        echoSuppressor.onAssistantInterruptedOrStopped()
        val responseId = MossLiveProtocol.responseId(data)
        if (responseId != null) {
            audioPlayer?.stopResponse(responseId.runId, responseId.turnId)
            replyAudio.discard(responseId.key)
        } else fail()
    }

    private fun finishResponse(data: JSONObject = JSONObject()) {
        val responseId = MossLiveProtocol.responseId(data)
        if (responseId == null) {
            fail()
            return
        }
        if (audioPlayer?.isPlaybackActive() != true) {
            echoSuppressor.onAssistantInterruptedOrStopped()
        }
        if (data.optString("status") == "completed") transcribeReplyAudio(responseId.key)
        else replyAudio.discard(responseId.key)
        clearAssistantResponse(responseId.key)
    }

    private fun clearAssistantResponse(responseKey: String) {
        if (activeAssistantResponse?.key == responseKey) activeAssistantResponse = null
        mutableState.update { if (it.microphoneTransmitting) it.copy(connection = MossConnectionState.LISTENING) else it }
    }

    private fun handleProviderError(data: JSONObject) {
        diagnostics.recordProviderErrorCode(data.optString("code"))
        val message = when (data.optString("code")) {
            "moss_not_configured" ->
                "MOSS Live is not configured on this computer. Ask the administrator to configure the backend."
            "moss_session_failed" ->
                "MOSS could not start a session. Check the backend log for a sanitized failure reason."
            "moss_configuration_failed" ->
                "MOSS rejected the session configuration. Check the model settings and backend log."
            "moss_timeout" ->
                "MOSS did not respond in time. Check the backend connection and try again."
            "moss_upstream_rejected" ->
                data.optString("message").takeIf(String::isNotBlank)
                    ?: "MOSS rejected the backend connection. Check the model and API key configuration."
            "moss_unavailable" ->
                "The backend could not connect to MOSS. Check the backend log for a sanitized failure reason."
            "moss_connection_lost" ->
                "The MOSS connection ended. Check the backend log and try again."
            else -> null
        }
        fail(message ?: "MOSS Live stopped. Check the USB connection and backend, then try again.")
    }

    private fun updateTranscript(
        data: JSONObject, assistant: Boolean, finalized: Boolean, explicitTurnId: String? = null,
        explicitSessionId: String? = null,
    ) {
        val turnId = explicitTurnId ?: data.optString("turn_id").takeIf(String::isNotBlank) ?: return
        val text = data.optString("text")
        val sessionId = explicitSessionId ?: activeSessionId ?: return
        updateTranscriptState(turnId, text, assistant, finalized)
        mutableTranscriptUpdates.tryEmit(MossTranscriptUpdate(sessionId, turnId, text, assistant, finalized))
    }

    private fun updateTranscriptState(turnId: String, text: String, assistant: Boolean, finalized: Boolean) {
        synchronized(this) {
            val lines = mutableState.value.transcripts.toMutableList()
            val index = lines.indexOfFirst { it.turnId == turnId && it.assistant == assistant }
            val line = MossTranscriptLine(turnId, text, assistant, finalized)
            if (index >= 0) lines[index] = line else lines.add(line)
            mutableState.update { it.copy(transcripts = lines.takeLast(MAX_TRANSCRIPT_TURNS * 2)) }
        }
    }

    private fun setCameraPhoto(photo: ByteArray?, scope: CoroutineScope) {
        cameraStreamJob?.cancel()
        cameraStreamJob = null
        activeCameraFrame = photo
        if (photo != null) {
            sendControl(MossLiveProtocol.videoState(true))
            transport.sendCameraFrame(MossLiveProtocol.taggedCameraFrame(photo))
            mutableState.update { it.copy(cameraSharing = true) }
            cameraStreamJob = scope.launch {
                while (configured.get() && !stopping.get()) {
                    kotlinx.coroutines.delay(1500)
                    val current = activeCameraFrame ?: break
                    if (configured.get() && !stopping.get()) {
                        transport.sendCameraFrame(MossLiveProtocol.taggedCameraFrame(current))
                    }
                }
            }
        } else {
            sendControl(MossLiveProtocol.videoState(false))
            mutableState.update { it.copy(cameraSharing = false) }
        }
    }

    private fun fail(
        message: String = "MOSS Live stopped. Check the USB connection and backend, then try again.",
        shutdownInitiator: String = "session_failure",
    ) {
        if (stopping.getAndSet(true)) return
        cameraStreamJob?.cancel()
        cameraStreamJob = null
        activeCameraFrame = null
        echoSuppressor.onAssistantInterruptedOrStopped()
        diagnostics.recordShutdown(shutdownInitiator)
        mutableState.update {
            it.copy(connection = MossConnectionState.STOPPING, microphoneTransmitting = false,
                cameraSharing = false, error = message)
        }
        activeAssistantResponse?.key?.let(::finishFallbackAudio)
        activeScope?.launch {
            runCatching { audioSource.stop() }
            audioPlayer?.stop()
            audioPlayer = null
            logDiagnosticsOnce()
            transport.close()
            activeScope = null
            activeSessionId = null
            audioContext = null
            inputTranscriptTracker.reset()
            activeAssistantResponse = null
            mutableState.update { it.copy(connection = MossConnectionState.ERROR, microphoneTransmitting = false) }
        }
    }

    private fun logDiagnosticsOnce() {
        if (diagnosticsLogged.compareAndSet(false, true)) {
            Log.i("MossLive", "Session diagnostics: ${diagnostics.summary()}")
        }
    }

    private fun sendControl(control: String): Boolean {
        val accepted = transport.sendText(control)
        diagnostics.recordControl(control, accepted)
        return accepted
    }

    private fun transcribeReplyAudio(responseKey: String) {
        val pcm = replyAudio.takeComplete(responseKey) ?: return
        if (mutableState.value.transcripts.any { it.assistant && it.turnId == responseKey && it.text.isNotBlank() }) return
        val scope = activeScope ?: return
        val sessionId = activeSessionId ?: return
        if (pcm.isEmpty()) return
        scope.launch {
            val text = try { replyTranscriber.transcribe(pcm).trim() } catch (_: Exception) { "" }
            if (text.isNotEmpty()) updateTranscript(
                JSONObject().put("text", text), assistant = true, finalized = true,
                explicitTurnId = responseKey, explicitSessionId = sessionId,
            )
        }
    }

    private fun finishFallbackAudio(responseKey: String) {
        if (audioPlayer?.isResponseFullyPlayed(responseKey) == true) transcribeReplyAudio(responseKey)
        else replyAudio.discard(responseKey)
    }

    private fun onAssistantPlaybackDrained(responseKey: String) {
        val scope = activeScope ?: return
        echoSuppressor.onAssistantPlaybackDrained(scope)
    }

    private companion object {
        const val MAX_AUDIO_BACKLOG_BYTES = 32 * 1024L
        const val MAX_INSTRUCTION_CHARS = 1_900
        const val MAX_TRANSCRIPT_TURNS = 20
        const val MAX_REPLY_AUDIO_BYTES = 6 * 1024 * 1024
    }
}
