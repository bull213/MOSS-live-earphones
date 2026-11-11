package com.visualassistant.app.voice

import ai.moonshine.voice.JNI
import ai.moonshine.voice.Transcriber
import ai.moonshine.voice.TranscriberOption
import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** A short accelerated regression check; it does not replace a real-device soak. */
@RunWith(AndroidJUnit4::class)
class MoonshineNativeMemoryTest {
    @Test fun repeatedAudioSubmissionDoesNotRetainEveryInputArray() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = MoonshineModelInstaller(context).ensureInstalled()
        val engine = Transcriber(listOf(TranscriberOption("return_audio_data", "false")))
        try {
            engine.loadFromFiles(directory.absolutePath, JNI.MOONSHINE_MODEL_ARCH_SMALL_STREAMING)
            engine.setUpdateInterval(5.0)
            engine.start()
            repeat(300) { engine.addAudio(FloatArray(1600), 16000) }
            System.gc()
            val baseline = Debug.getNativeHeapAllocatedSize()
            repeat(3000) { engine.addAudio(FloatArray(1600), 16000) }
            System.gc()
            val growth = Debug.getNativeHeapAllocatedSize() - baseline
            assertTrue("Native allocation grew by $growth bytes for five minutes of silent input", growth < 8L * 1024 * 1024)
            engine.stop()
        } finally {
            engine.close()
        }
    }
}
