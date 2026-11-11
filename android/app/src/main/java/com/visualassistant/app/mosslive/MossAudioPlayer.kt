package com.visualassistant.app.mosslive

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.AudioTrack.WRITE_NON_BLOCKING
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal interface MossAudioOutput {
    fun start(scope: CoroutineScope)
    fun enqueue(context: MossAudioContext, bytes: ByteArray)
    fun audioDone(runId: String, turnId: String)
    fun stopResponse(runId: String, turnId: String)
    suspend fun stop()
    fun isResponseFullyPlayed(responseKey: String): Boolean
    fun isPlaybackActive(): Boolean = false
}

internal interface MossPlaybackTrack {
    val playbackHeadPosition: Long
    val routeType: Int
    val bufferCapacityFrames: Int
    fun setStartThresholdInFrames(frames: Int): Int
    fun play()
    fun write(bytes: ByteArray, offset: Int, size: Int): Int
    fun pause()
    fun flush()
    fun release()
}

internal fun interface MossPlaybackTrackFactory {
    fun create(): MossPlaybackTrack
}

internal class MossAudioPlayer(
    private val audioManager: AudioManager?,
    private val sendControl: (String) -> Boolean,
    private val onFailure: () -> Unit,
    private val playbackTrackFactory: MossPlaybackTrackFactory? = null,
    private val playbackDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val diagnostics: MossLiveDiagnostics? = null,
    private val monotonicMs: () -> Long = { System.nanoTime() / NANOS_PER_MILLISECOND },
    private val onPlaybackDrained: ((responseKey: String) -> Unit)? = null,
    private val logPlayback: (message: String, warning: Boolean) -> Unit = { message, warning ->
        if (warning) android.util.Log.w("MossLive", message) else android.util.Log.i("MossLive", message)
    },
) : MossAudioOutput {
    private data class Piece(val context: MossAudioContext, val bytes: ByteArray) {
        val interrupted = AtomicBoolean(false)
        val chunkAckSent = AtomicBoolean(false)
        @Volatile var startHead: Long = 0
        @Volatile var writtenFrames: Long = 0
        @Volatile var playedCompletely: Boolean = false
    }

    private data class StopProgress(
        val completedChunkToAcknowledge: MossAudioContext?,
        val playedAudioMs: Long,
        val shouldFlushTrack: Boolean,
    )

    private val queue = ArrayBlockingQueue<Piece>(MAX_PENDING_AUDIO_CHUNKS)
    private val pendingBudget = PendingAudioBudget(MAX_PENDING_AUDIO_BYTES)
    private val completedAudio = ConcurrentHashMap.newKeySet<String>()
    private val interruptedResponses = ConcurrentHashMap.newKeySet<String>()
    private val stoppedResponses = ConcurrentHashMap.newKeySet<String>()
    private val lock = Any()
    @Volatile private var track: MossPlaybackTrack? = null
    @Volatile private var current: Piece? = null
    @Volatile private var worker: Job? = null
    @Volatile private var audioFocusRequest: AudioFocusRequest? = null

    override fun start(scope: CoroutineScope) {
        if (track != null) return
        val output = playbackTrackFactory?.create() ?: createPlatformTrack()
        val startThresholdFrames = output.setStartThresholdInFrames(START_THRESHOLD_FRAMES)
        check(startThresholdFrames in 1..output.bufferCapacityFrames) {
            "AudioTrack rejected the streaming start threshold"
        }
        track = output
        logPlayback(
            "Playback event=audio_track_started route=${output.routeType} " +
                "start_threshold_frames=$startThresholdFrames buffer_capacity_frames=${output.bufferCapacityFrames}",
            false,
        )
        worker = scope.launch(playbackDispatcher) { playLoop() }
    }

    override fun enqueue(context: MossAudioContext, bytes: ByteArray) {
        if (bytes.isEmpty() || bytes.size % BYTES_PER_FRAME != 0 || !pendingBudget.reserve(bytes.size)) {
            interruptedResponses.add(context.responseKey)
            logPlayback("Playback event=audio_queue_limit", true)
            onFailure()
            return
        }
        if (!queue.offer(Piece(context, bytes))) {
            pendingBudget.release(bytes.size)
            interruptedResponses.add(context.responseKey)
            logPlayback("Playback event=audio_queue_full", true)
            onFailure()
        }
    }

    override fun audioDone(runId: String, turnId: String) {
        val key = MossResponseId(runId, turnId).key
        val shouldComplete = synchronized(lock) {
            if (key in interruptedResponses) return@synchronized false
            completedAudio.add(key)
            claimCompletedResponseLocked(key)
        }
        if (shouldComplete) {
            sendControl(MossLiveProtocol.completedResponse(runId, turnId), key)
        }
    }

    override fun stopResponse(runId: String, turnId: String) {
        val response = MossResponseId(runId, turnId)
        val progress = synchronized(lock) { prepareResponseStopLocked(response) } ?: return
        progress.completedChunkToAcknowledge?.let {
            sendControl(MossLiveProtocol.completedChunk(it), response.key)
        }
        if (progress.shouldFlushTrack) {
            runCatching { track?.pause() }
            runCatching { track?.flush() }
        }
        sendControl(MossLiveProtocol.stoppedResponse(runId, turnId, progress.playedAudioMs), response.key)
        onPlaybackDrained?.invoke(response.key)
    }

    /** Marks one response stopped and snapshots the actual progress before leaving the lock. */
    private fun prepareResponseStopLocked(response: MossResponseId): StopProgress? {
        if (!stoppedResponses.add(response.key)) return null

        val removed = mutableListOf<Piece>()
        queue.removeIf { piece ->
            (piece.context.responseKey == response.key).also { if (it) removed.add(piece) }
        }
        removed.forEach { pendingBudget.release(it.bytes.size) }

        val active = current?.takeIf { it.context.responseKey == response.key }
        val activeAlreadyComplete = active?.playedCompletely == true
        val completedChunk = active
            ?.takeIf { it.playedCompletely && it.chunkAckSent.compareAndSet(false, true) }
            ?.context
        if (active != null && !activeAlreadyComplete) active.interrupted.set(true)

        interruptedResponses.add(response.key)
        completedAudio.remove(response.key)
        val playedFrames = if (active != null && !activeAlreadyComplete) {
            val head = track?.playbackHeadPosition ?: active.startHead
            framesSince(active.startHead, head).coerceIn(0L, active.writtenFrames)
        } else {
            0L
        }
        return StopProgress(
            completedChunkToAcknowledge = completedChunk,
            playedAudioMs = playedFrames * 1000 / SAMPLE_RATE,
            shouldFlushTrack = active != null && !activeAlreadyComplete,
        )
    }

    override suspend fun stop() {
        worker?.cancel()
        worker?.join()
        worker = null
        queue.clear()
        pendingBudget.reset()
        completedAudio.clear()
        synchronized(lock) {
            current = null
            runCatching { track?.pause() }
            runCatching { track?.flush() }
            runCatching { track?.release() }
            track = null
        }
        audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        audioFocusRequest = null
    }

    override fun isResponseFullyPlayed(responseKey: String): Boolean =
        responseKey !in interruptedResponses && synchronized(lock) {
            current?.context?.responseKey != responseKey && queue.none { it.context.responseKey == responseKey }
        }

    override fun isPlaybackActive(): Boolean = synchronized(lock) {
        current != null || !queue.isEmpty()
    }

    private fun createPlatformTrack(): MossPlaybackTrack {
        val minBytes = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBytes > 0) { "Phone speaker format is unavailable" }
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(minBytes, SAMPLE_RATE * BYTES_PER_FRAME / 5))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (audioTrack.state != AudioTrack.STATE_INITIALIZED) {
            audioTrack.release()
            throw IllegalStateException("AudioTrack failed to initialize")
        }
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { }
            .build()
        if (audioManager != null && audioManager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            audioTrack.release()
            throw IllegalStateException("Audio focus is unavailable")
        }
        audioFocusRequest = focus
        return PlatformMossPlaybackTrack(audioTrack)
    }

    private suspend fun playLoop() {
        while (kotlin.coroutines.coroutineContext.isActive) {
            val piece = takeNextPiece()
            if (piece == null) {
                delay(IDLE_POLL_MS)
                continue
            }
            if (!playQueuedPiece(piece)) break
        }
    }

    private fun takeNextPiece(): Piece? = synchronized(lock) {
        queue.poll().also { current = it }
    }

    /** Sends per-chunk acknowledgement before making the response appear drained. */
    private suspend fun playQueuedPiece(piece: Piece): Boolean {
        val output = track ?: return false
        val didFinish = try {
            playPiece(output, piece)
        } finally {
            pendingBudget.release(piece.bytes.size)
        }

        if (didFinish) acknowledgeChunkIfPlayed(piece)
        if (finishResponseIfDrained(piece, didFinish)) {
            sendControl(
                MossLiveProtocol.completedResponse(piece.context.runId, piece.context.turnId),
                piece.context.responseKey,
            )
            onPlaybackDrained?.invoke(piece.context.responseKey)
        }
        return true
    }

    private fun acknowledgeChunkIfPlayed(piece: Piece) {
        val shouldAcknowledge = synchronized(lock) { piece.chunkAckSent.compareAndSet(false, true) }
        if (shouldAcknowledge) {
            sendControl(MossLiveProtocol.completedChunk(piece.context), piece.context.responseKey)
        }
    }

    private fun finishResponseIfDrained(piece: Piece, didFinish: Boolean): Boolean = synchronized(lock) {
        if (current === piece) current = null
        didFinish && claimCompletedResponseLocked(piece.context.responseKey)
    }

    private suspend fun playPiece(output: MossPlaybackTrack, piece: Piece): Boolean {
        piece.startHead = output.playbackHeadPosition
        output.play()
        if (!writePiece(output, piece)) return false
        return awaitPlayback(output, piece)
    }

    private suspend fun writePiece(output: MossPlaybackTrack, piece: Piece): Boolean {
        var offset = 0
        var lastProgressAt = monotonicMs()
        while (offset < piece.bytes.size && !piece.interrupted.get()) {
            val remaining = piece.bytes.size - offset
            val written = output.write(piece.bytes, offset, remaining)
            if (written < 0 || written > remaining) {
                logPlayback("Playback event=audio_write_error code=$written", true)
                interruptedResponses.add(piece.context.responseKey)
                onFailure()
                return false
            }
            if (written > 0) {
                offset += written
                piece.writtenFrames = (offset / BYTES_PER_FRAME).toLong()
                diagnostics?.recordPlaybackWrite(
                    written,
                    piece.writtenFrames,
                    framesSince(piece.startHead, output.playbackHeadPosition),
                )
                lastProgressAt = monotonicMs()
            } else {
                if (monotonicMs() - lastProgressAt > PLAYBACK_STALL_MS) {
                    logPlayback("Playback event=audio_write_stalled bytes_accepted=$offset", true)
                    interruptedResponses.add(piece.context.responseKey)
                    onFailure()
                    return false
                }
                delay(WRITE_RETRY_MS)
            }
        }
        return offset == piece.bytes.size && !piece.interrupted.get()
    }

    private suspend fun awaitPlayback(output: MossPlaybackTrack, piece: Piece): Boolean {
        var lastPlayedFrames = 0L
        var progressAt = monotonicMs()
        while (!piece.interrupted.get()) {
            val playedFrames = framesSince(piece.startHead, output.playbackHeadPosition)
            if (playedFrames > lastPlayedFrames) {
                lastPlayedFrames = playedFrames
                progressAt = monotonicMs()
            }
            if (playedFrames >= piece.writtenFrames) {
                piece.playedCompletely = true
                diagnostics?.recordPlaybackChunkFinished()
                return true
            }
            if (monotonicMs() - progressAt > PLAYBACK_STALL_MS) {
                logPlayback("Playback event=audio_head_stalled frames=$playedFrames target=${piece.writtenFrames}", true)
                interruptedResponses.add(piece.context.responseKey)
                onFailure()
                return false
            }
            delay(PLAYBACK_POLL_MS)
        }
        return false
    }

    private fun claimCompletedResponseLocked(key: String): Boolean {
        val drained = current?.context?.responseKey != key &&
            queue.none { it.context.responseKey == key }
        return drained && key !in interruptedResponses && completedAudio.remove(key)
    }

    private fun sendControl(control: String, responseKey: String): Boolean {
        val accepted = sendControl.invoke(control)
        if (!accepted) {
            interruptedResponses.add(responseKey)
            logPlayback("Playback event=control_send_rejected", true)
            onFailure()
        }
        return accepted
    }

    private fun framesSince(start: Long, current: Long): Long =
        (current - start) and PLAYBACK_HEAD_MASK

    private class PlatformMossPlaybackTrack(private val track: AudioTrack) : MossPlaybackTrack {
        override val playbackHeadPosition: Long
            get() = track.playbackHeadPosition.toLong() and PLAYBACK_HEAD_MASK
        override val routeType: Int get() = track.routedDevice?.type ?: 0
        override val bufferCapacityFrames: Int get() = track.bufferCapacityInFrames
        override fun setStartThresholdInFrames(frames: Int): Int = track.setStartThresholdInFrames(frames)
        override fun play() = track.play()
        override fun write(bytes: ByteArray, offset: Int, size: Int): Int =
            track.write(bytes, offset, size, WRITE_NON_BLOCKING)
        override fun pause() = track.pause()
        override fun flush() = track.flush()
        override fun release() = track.release()
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val BYTES_PER_FRAME = 2
        const val MAX_PENDING_AUDIO_CHUNKS = 128
        const val MAX_PENDING_AUDIO_BYTES = 6 * 1024 * 1024
        const val PLAYBACK_STALL_MS = 2_000L
        const val PLAYBACK_HEAD_MASK = 0xffffffffL
        const val START_THRESHOLD_FRAMES = 1
        const val WRITE_RETRY_MS = 3L
        const val PLAYBACK_POLL_MS = 5L
        const val IDLE_POLL_MS = 5L
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
