package com.visualassistant.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.visualassistant.app.camera.CameraViewModel
import com.visualassistant.app.camera.CaptureStore
import com.visualassistant.app.chat.AndroidThumbnailProvider
import com.visualassistant.app.chat.ChatViewModel
import com.visualassistant.app.chat.ConversationDatabaseProvider
import com.visualassistant.app.chat.ConversationRepository
import com.visualassistant.app.chat.PhoneLiveAssistant
import com.visualassistant.app.mosslive.MossLiveSessionController
import com.visualassistant.app.mosslive.MoonshineReplyTranscriber
import com.visualassistant.app.mosslive.OkHttpMossLiveTransport
import com.visualassistant.app.mosslive.PhoneRealtimeAudioSource
import com.visualassistant.app.network.AppWakeTrigger
import com.visualassistant.app.network.BackendEndpoint
import com.visualassistant.app.network.NotebookAuthApi
import com.visualassistant.app.ui.ChatRoute
import com.visualassistant.app.ui.VisualAssistantTheme
import com.visualassistant.app.voice.MoonshineModelInstaller
import com.visualassistant.app.voice.PhoneAudioCaptureRouter
import com.visualassistant.app.voice.PhoneAudioSource
import com.visualassistant.app.voice.SpeechController
import com.visualassistant.app.voice.AndroidTextToSpeechSpeaker
import com.visualassistant.app.voice.ToneEarconPlayer
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    private var audioCaptureRouter: PhoneAudioCaptureRouter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store = CaptureStore(File(cacheDir, "captures"))
        val endpoint = BackendEndpoint(applicationContext)
        val router = PhoneAudioCaptureRouter(applicationContext)
        audioCaptureRouter = router
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
                CameraViewModel::class.java -> CameraViewModel(store)
                ChatViewModel::class.java -> ChatViewModel(
                    PhoneLiveAssistant(applicationContext.contentResolver, endpoint), AndroidThumbnailProvider(applicationContext.contentResolver),
                    store = ConversationRepository(ConversationDatabaseProvider.get(applicationContext),
                        File(filesDir, "conversation_photos"), applicationContext.contentResolver),
                    speech = SpeechController(
                        installModel = { MoonshineModelInstaller(applicationContext).ensureInstalled() },
                        source = router.asAudioSource(),
                    ),
                    mossLive = MossLiveSessionController(
                        OkHttpMossLiveTransport(endpoint),
                        router.asRealtimeAudioSource(),
                        MoonshineReplyTranscriber { MoonshineModelInstaller(applicationContext).ensureInstalled() },
                        applicationContext.getSystemService(android.media.AudioManager::class.java),
                    ),
                    mossPhotoEncoder = AndroidThumbnailProvider(
                        applicationContext.contentResolver, maxLongSide = 512, maxBytes = 512 * 1024,
                    ),
                    speaker = AndroidTextToSpeechSpeaker(applicationContext),
                    notebookAuthApi = NotebookAuthApi(endpoint),
                    earconPlayer = ToneEarconPlayer(),
                    audioCaptureRouter = router,
                )
                else -> throw IllegalArgumentException("Unknown ViewModel")
            } as T
        }
        val viewModel = ViewModelProvider(this, factory)[CameraViewModel::class.java]
        val chatViewModel = ViewModelProvider(this, factory)[ChatViewModel::class.java]
        setContent {
            VisualAssistantTheme {
                ChatRoute(this, viewModel, chatViewModel, store, endpoint = endpoint)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        audioCaptureRouter?.release()
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch {
            val endpoint = BackendEndpoint(applicationContext)
            // When an always-on remote server (e.g. Tailscale) is configured, bypass local wake signal
            if (!endpoint.isRemoteConfigured()) {
                val serverIp = AppWakeTrigger.sendWakeSignal()
                if (serverIp != null) {
                    endpoint.host = "$serverIp:8000"
                }
            }
        }
    }
}
