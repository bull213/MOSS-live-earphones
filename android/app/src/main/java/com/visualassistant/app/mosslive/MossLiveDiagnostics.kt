package com.visualassistant.app.mosslive

import java.util.LinkedHashSet
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Content-free counters retained for one session and rendered once when it ends. */
internal class MossLiveDiagnostics {
    private val events = ConcurrentHashMap<String, AtomicLong>()
    private val controlsCreated = ConcurrentHashMap<String, AtomicLong>()
    private val controlsAccepted = ConcurrentHashMap<String, AtomicLong>()
    private val controlsRejected = ConcurrentHashMap<String, AtomicLong>()
    private val uplinkPcmFrames = AtomicLong()
    private val echoSuppressedFrames = AtomicLong()
    private val downlinkAudioChunks = AtomicLong()
    private val downlinkAudioBytes = AtomicLong()
    private val playbackBytesAccepted = AtomicLong()
    private val playbackChunksFinished = AtomicLong()
    private val playbackFrameTargetsMax = AtomicLong()
    private val playbackHeadFramesMax = AtomicLong()
    private val interruptionReasons = ConcurrentHashMap<String, AtomicLong>()
    private val providerErrorCodes = ConcurrentHashMap<String, AtomicLong>()
    private val responseIds = LinkedHashSet<String>()
    @Volatile private var sessionCorrelationId = "not_started"
    @Volatile private var startedAtMs = 0L
    @Volatile private var shutdownInitiator = "unknown"

    @Synchronized
    fun reset() {
        events.clear()
        controlsCreated.clear()
        controlsAccepted.clear()
        controlsRejected.clear()
        uplinkPcmFrames.set(0)
        echoSuppressedFrames.set(0)
        downlinkAudioChunks.set(0)
        downlinkAudioBytes.set(0)
        playbackBytesAccepted.set(0)
        playbackChunksFinished.set(0)
        playbackFrameTargetsMax.set(0)
        playbackHeadFramesMax.set(0)
        interruptionReasons.clear()
        providerErrorCodes.clear()
        responseIds.clear()
        sessionCorrelationId = UUID.randomUUID().toString().take(12)
        startedAtMs = monotonicMs()
        shutdownInitiator = "unknown"
    }

    fun recordEvent(action: String): String {
        val safeAction = action.takeIf(SAFE_EVENT_ACTIONS::contains) ?: "other"
        events.computeIfAbsent(safeAction) { AtomicLong() }.incrementAndGet()
        return safeAction
    }

    fun recordControl(raw: String, accepted: Boolean) {
        val action = runCatching { org.json.JSONObject(raw).optString("action") }.getOrDefault("other")
        val safe = action.takeIf(SAFE_CONTROL_ACTIONS::contains) ?: "other"
        controlsCreated.computeIfAbsent(safe) { AtomicLong() }.incrementAndGet()
        val target = if (accepted) controlsAccepted else controlsRejected
        target.computeIfAbsent(safe) { AtomicLong() }.incrementAndGet()
    }

    @Synchronized
    fun recordAudioContext(context: MossAudioContext) {
        if (responseIds.size < MAX_RECORDED_RESPONSE_IDS) {
            responseIds.add("run=${safeId(context.runId)},turn=${safeId(context.turnId)},chunk=${safeId(context.chunkId)}")
        }
    }

    fun recordUplinkPcmFrame() { uplinkPcmFrames.incrementAndGet() }
    fun recordEchoSuppressedFrame() { echoSuppressedFrames.incrementAndGet() }
    fun recordDownlinkAudioChunk(byteCount: Int) {
        downlinkAudioChunks.incrementAndGet()
        downlinkAudioBytes.addAndGet(byteCount.toLong())
    }
    fun recordPlaybackWrite(acceptedBytes: Int, frameTarget: Long, playbackHeadFrames: Long) {
        playbackBytesAccepted.addAndGet(acceptedBytes.toLong())
        playbackFrameTargetsMax.accumulateAndGet(frameTarget) { old, new -> maxOf(old, new) }
        playbackHeadFramesMax.accumulateAndGet(playbackHeadFrames) { old, new -> maxOf(old, new) }
    }
    fun recordPlaybackChunkFinished() { playbackChunksFinished.incrementAndGet() }
    fun recordInterruptionReason(reason: String) {
        val safe = when {
            reason.contains("timeout", ignoreCase = true) -> "timeout"
            reason.contains("playback", ignoreCase = true) -> "playback"
            reason.contains("user", ignoreCase = true) -> "user"
            reason.isBlank() -> "unspecified"
            else -> "other"
        }
        interruptionReasons.computeIfAbsent(safe) { AtomicLong() }.incrementAndGet()
    }
    fun recordProviderErrorCode(code: String) {
        val safe = code.takeIf(SAFE_PROVIDER_ERROR_CODES::contains) ?: "other"
        providerErrorCodes.computeIfAbsent(safe) { AtomicLong() }.incrementAndGet()
    }
    fun recordShutdown(initiator: String) {
        shutdownInitiator = initiator.take(MAX_ID_LENGTH).filter { it.isLetterOrDigit() || it in "_-" }
            .ifBlank { "unknown" }
    }

    fun summary(): String {
        val eventSummary = sortedCounts(events)
        val createdSummary = sortedCounts(controlsCreated)
        val acceptedSummary = sortedCounts(controlsAccepted)
        val rejectedSummary = sortedCounts(controlsRejected)
        val interruptionSummary = sortedCounts(interruptionReasons)
        val errorSummary = sortedCounts(providerErrorCodes)
        val ids = synchronized(this) { responseIds.joinToString("|") }
        val elapsed = (monotonicMs() - startedAtMs).coerceAtLeast(0)
        return "session=$sessionCorrelationId elapsed_ms=$elapsed shutdown=$shutdownInitiator; " +
            "events=$eventSummary; controls_created=$createdSummary; controls_accepted=$acceptedSummary; " +
            "controls_rejected=$rejectedSummary; uplink_pcm_frames=${uplinkPcmFrames.get()}; " +
            "echo_suppressed_frames=${echoSuppressedFrames.get()}; " +
            "downlink_audio_chunks=${downlinkAudioChunks.get()}; downlink_audio_bytes=${downlinkAudioBytes.get()}; " +
            "playback_bytes_accepted=${playbackBytesAccepted.get()}; playback_chunks_finished=${playbackChunksFinished.get()}; " +
            "playback_frame_target_max=${playbackFrameTargetsMax.get()}; playback_head_frames_max=${playbackHeadFramesMax.get()}; " +
            "interruption_reasons=$interruptionSummary; provider_errors=$errorSummary; audio_ids=$ids"
    }

    private fun sortedCounts(counts: Map<String, AtomicLong>): String =
        counts.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value.get()}" }

    private fun safeId(value: String): String = value.take(MAX_ID_LENGTH)
        .filter { it.isLetterOrDigit() || it in "_-" }.ifBlank { "invalid" }

    private fun monotonicMs(): Long = System.nanoTime() / NANOS_PER_MILLISECOND

    private companion object {
        const val MAX_RECORDED_RESPONSE_IDS = 16
        const val MAX_ID_LENGTH = 64
        const val NANOS_PER_MILLISECOND = 1_000_000L
        val SAFE_EVENT_ACTIONS = setOf(
            "session_attached", "session.updated", "session.closing",
            "input.speech.started", "input.speech.stopped",
            "input.transcript.updated", "input.transcript.done", "input.transcript.revised",
            "output.transcript.updated", "output.transcript.done",
            "response.audio.started", "response.audio.context", "response.audio.done", "response.audio.stop",
            "response.ended", "response.interrupted", "error",
        )
        val SAFE_CONTROL_ACTIONS = setOf("mic_state", "media.mode", "playback.chunk.completed", "playback.completed", "playback.stopped")
        val SAFE_PROVIDER_ERROR_CODES = setOf(
            "moss_not_configured", "moss_session_failed", "moss_configuration_failed", "moss_timeout",
            "moss_upstream_rejected", "moss_unavailable", "moss_connection_lost", "provider_protocol_error",
        )
    }
}
