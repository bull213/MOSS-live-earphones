package com.visualassistant.app.voice

import ai.moonshine.voice.JNI
import ai.moonshine.voice.Transcriber
import ai.moonshine.voice.TranscriberOption
import ai.moonshine.voice.TranscriptEvent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses Moonshine's public two-cities fixture, not microphone or network access. */
@RunWith(AndroidJUnit4::class)
class MoonshineStreamingTest {
    @Test fun bundledSmallStreamingProducesPartialAndFinalTextOffline() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val modelDirectory = MoonshineModelInstaller(context).ensureInstalled()
        val fixture = InstrumentationRegistry.getInstrumentation().context.assets
            .open("moonshine-two-cities-16k.wav").use { it.readBytes() }
        val pcm = readMono16kFixture(fixture)
        val partials = mutableListOf<String>()
        val finals = mutableListOf<String>()
        val engine = Transcriber(listOf(TranscriberOption("return_audio_data", "false")))
        try {
            engine.addListener { event ->
                when (event) {
                    is TranscriptEvent.LineTextChanged -> event.line.text?.let(partials::add)
                    is TranscriptEvent.LineCompleted -> event.line.text?.let(finals::add)
                    else -> Unit
                }
            }
            engine.loadFromFiles(modelDirectory.absolutePath, JNI.MOONSHINE_MODEL_ARCH_SMALL_STREAMING)
            engine.start()
            val chunkSize = 1600
            val firstSeconds = pcm.take(16000 * 12).toFloatArray()
            for (offset in firstSeconds.indices step chunkSize) {
                engine.addAudio(firstSeconds.copyOfRange(offset, minOf(offset + chunkSize, firstSeconds.size)), 16000)
                SystemClock.sleep(100)
            }
            engine.stop()
            assertTrue("Expected a non-empty provisional update", partials.any { it.isNotBlank() })
            assertTrue("Expected a non-empty finalized transcript", finals.any { it.isNotBlank() })
            assertTrue("Expected the opening words of the public audio fixture",
                finals.joinToString(" ").lowercase().contains("best of times"))
        } finally {
            engine.close()
        }
    }

}
