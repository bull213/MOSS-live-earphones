package com.visualassistant.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class AndroidTextToSpeechSpeaker(
    private val context: Context,
    private val locale: Locale = Locale.getDefault(),
) : Speaker {
    private var tts: TextToSpeech? = null
    private val isInitialized = AtomicBoolean(false)
    private val currentlySpeaking = AtomicBoolean(false)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private val currentOnDone = AtomicReference<(() -> Unit)?>(null)

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = locale
                isInitialized.set(true)
            }
        }
    }

    override fun speak(text: String, onStart: () -> Unit, onDone: () -> Unit) {
        val engine = tts ?: return
        if (!isInitialized.get()) return
        val sanitized = TtsTextSanitizer.sanitize(text)
        if (sanitized.isBlank()) return

        stop()
        requestAudioFocus()
        currentlySpeaking.set(true)
        currentOnDone.set(onDone)

        val utteranceId = UUID.randomUUID().toString()
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                onStart()
            }

            override fun onDone(utteranceId: String?) {
                finishSpeech()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                finishSpeech()
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                finishSpeech()
            }
        })

        val params = Bundle()
        engine.speak(sanitized, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
    }

    override fun stop() {
        tts?.stop()
        finishSpeech()
    }

    override fun isSpeaking(): Boolean = currentlySpeaking.get()

    override fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        isInitialized.set(false)
    }

    private fun requestAudioFocus() {
        val am = audioManager ?: return
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener { }
            .build()
        am.requestAudioFocus(focus)
        focusRequest = focus
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        focusRequest?.let { am.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    private fun finishSpeech() {
        if (currentlySpeaking.compareAndSet(true, false)) {
            abandonAudioFocus()
            currentOnDone.getAndSet(null)?.invoke()
        }
    }
}
