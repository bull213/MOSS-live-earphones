package com.visualassistant.app.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.visualassistant.app.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneAudioSourceTest {
    @Test fun opensAndReleasesPhoneMicrophoneTwice() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("Microphone permission must be granted by the device user before this test",
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        val activity = ActivityScenario.launch(MainActivity::class.java)
        val source = PhoneAudioSource(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            repeat(2) {
                val input = source.start(scope) { }
                val chunk = withTimeout(5000) { input.receive() }
                assertTrue(chunk.samples.isNotEmpty())
                assertTrue(chunk.sampleRate in listOf(16000, 44100, 48000))
                source.stop()
            }
        } finally {
            source.stop()
            scope.cancel()
            activity.close()
        }
    }
}
