package com.visualassistant.app.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Captures only the phone microphone. Audio stays in bounded, in-memory buffers. */
internal class PhoneAudioSource(context: Context) : AudioSource {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val lifecycle = Mutex()
    private var active: Capture? = null

    private class Capture(
        val recorder: AudioRecord,
        val channel: Channel<AudioChunk>,
        val stopping: AtomicBoolean,
        val callback: AudioManager.AudioRecordingCallback,
        val executor: java.util.concurrent.ExecutorService,
        val job: Job,
    )

    @SuppressLint("MissingPermission") // Checked immediately before opening the recorder.
    override suspend fun start(scope: CoroutineScope, onFailure: (SpeechProblem) -> Unit): ReceiveChannel<AudioChunk> =
        withContext(Dispatchers.IO) {
            lifecycle.withLock {
                check(active == null)
                if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    throw SpeechException(SpeechProblem.PERMISSION)
                }
                val device = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
                    ?: throw SpeechException(SpeechProblem.ROUTE)
                val recorder = createRecorder()
                val channel = Channel<AudioChunk>(20)
                val stopping = AtomicBoolean(false)
                val failed = AtomicBoolean(false)
                fun fail(problem: SpeechProblem) {
                    if (!stopping.get() && failed.compareAndSet(false, true)) onFailure(problem)
                }
                val executor = Executors.newSingleThreadExecutor()
                val callback = object : AudioManager.AudioRecordingCallback() {
                    override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                        if (configs.any { it.clientAudioSessionId == recorder.audioSessionId && it.isClientSilenced }) {
                            fail(SpeechProblem.INTERRUPTED)
                        }
                    }
                }
                try {
                    if (!recorder.setPreferredDevice(device)) throw SpeechException(SpeechProblem.ROUTE)
                    recorder.registerAudioRecordingCallback(executor, callback)
                    recorder.startRecording()
                    if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw SpeechException(SpeechProblem.AUDIO)
                    val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                        val sampleRate = recorder.sampleRate
                        val samples = ShortArray(sampleRate / 10)
                        val startedAt = SystemClock.elapsedRealtime()
                        try {
                            while (!stopping.get() && currentCoroutineContext().isActive) {
                                val count = recorder.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                                if (count <= 0) {
                                    fail(SpeechProblem.AUDIO)
                                    break
                                }
                                val routed = recorder.routedDevice
                                if ((routed != null && routed.type != AudioDeviceInfo.TYPE_BUILTIN_MIC) ||
                                    (routed == null && SystemClock.elapsedRealtime() - startedAt > 2000)) {
                                    fail(SpeechProblem.ROUTE)
                                    break
                                }
                                val chunk = AudioChunk(FloatArray(count) { samples[it] / 32768f }, sampleRate)
                                if (channel.trySend(chunk).isFailure) {
                                    fail(SpeechProblem.OVERLOADED)
                                    break
                                }
                            }
                        } catch (_: SecurityException) {
                            fail(SpeechProblem.PERMISSION)
                        } catch (_: Exception) {
                            fail(SpeechProblem.AUDIO)
                        } finally {
                            channel.close()
                        }
                    }
                    active = Capture(recorder, channel, stopping, callback, executor, job)
                    job.start()
                    channel
                } catch (error: Exception) {
                    runCatching { recorder.unregisterAudioRecordingCallback(callback) }
                    runCatching { recorder.stop() }
                    recorder.release()
                    executor.shutdown()
                    channel.close()
                    when (error) {
                        is SpeechException -> throw error
                        is SecurityException -> throw SpeechException(SpeechProblem.PERMISSION, error)
                        else -> throw SpeechException(SpeechProblem.AUDIO, error)
                    }
                }
            }
        }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        lifecycle.withLock {
            val capture = active ?: return@withLock
            capture.stopping.set(true)
            runCatching { capture.recorder.stop() }
            capture.job.join()
            runCatching { capture.recorder.unregisterAudioRecordingCallback(capture.callback) }
            capture.recorder.release()
            capture.executor.shutdown()
            capture.channel.close()
            active = null
        }
    }

    @SuppressLint("MissingPermission")
    private fun createRecorder(): AudioRecord {
        for (rate in intArrayOf(16000, 48000, 44100)) {
            val minimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minimum <= 0) continue
            val recorder = try {
                AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(maxOf(minimum * 2, rate * 2 / 5))
                    .build()
            } catch (error: SecurityException) {
                throw SpeechException(SpeechProblem.PERMISSION, error)
            } catch (_: IllegalArgumentException) {
                continue
            } catch (_: UnsupportedOperationException) {
                continue
            }
            if (recorder.state == AudioRecord.STATE_INITIALIZED) return recorder
            recorder.release()
        }
        throw SpeechException(SpeechProblem.AUDIO)
    }
}
