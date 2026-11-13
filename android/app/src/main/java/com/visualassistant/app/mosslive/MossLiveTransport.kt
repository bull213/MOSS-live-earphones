package com.visualassistant.app.mosslive

import android.util.Log
import com.visualassistant.app.network.BackendEndpoint
import com.visualassistant.app.network.ServerScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

internal interface MossLiveTransportListener {
    fun onOpen()
    fun onText(text: String)
    fun onBinary(bytes: ByteArray)
    fun onClosed()
    fun onFailure()
}

internal interface MossLiveTransport {
    fun connect(listener: MossLiveTransportListener)
    fun sendText(text: String): Boolean
    fun sendBinary(bytes: ByteArray): Boolean
    fun sendCameraFrame(bytes: ByteArray): Boolean = sendBinary(bytes)
    fun queuedBytes(): Long
    fun close()
    fun close(code: Int, reason: String) { close() }
    fun isOpen(): Boolean = true
}

internal class OkHttpMossLiveTransport(
    private val endpoint: BackendEndpoint? = null,
) : MossLiveTransport {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var connectJob: Job? = null

    override fun connect(listener: MossLiveTransportListener) {
        connectJob?.cancel()
        connectJob = CoroutineScope(Dispatchers.IO).launch {
            val configuredHost = endpoint?.host
            val resolvedHost = ServerScanner.findOrResolveBackend(configuredHost)
            val finalHost = if (resolvedHost != null) {
                val fullHost = if (resolvedHost.contains(":")) resolvedHost else "$resolvedHost:8000"
                endpoint?.host = fullHost
                fullHost
            } else {
                configuredHost ?: DEFAULT_HOST
            }

            val targetUrl = endpoint?.wsUrl("v1/realtime/moss-live") ?: "ws://$finalHost/v1/realtime/moss-live"
            Log.i("MossLive", "Connecting realtime transport to $targetUrl (resolvedHost=$resolvedHost)...")
            connectInternal(targetUrl, listener) {
                listener.onFailure()
            }
        }
    }

    private fun connectInternal(
        url: String,
        listener: MossLiveTransportListener,
        onConnectFailed: () -> Unit,
    ) {
        var opened = false
        var retryAttempt = 0
        val requestBuilder = Request.Builder().url(url)
        endpoint?.authorizationHeader()?.let {
            requestBuilder.addHeader("Authorization", it)
        }
        val request = requestBuilder.build()

        fun attemptConnect() {
            socket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    opened = true
                    listener.onOpen()
                }

                override fun onMessage(webSocket: WebSocket, text: String) = listener.onText(text)

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) = listener.onBinary(bytes.toByteArray())

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = listener.onClosed()

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.w(
                        "MossLive",
                        "Realtime transport to $url failed (type=${t.javaClass.simpleName}, http=${response?.code})",
                    )
                    if (!opened && retryAttempt < 1 && endpoint?.isRemoteConfigured() == true) {
                        retryAttempt++
                        Log.i("MossLive", "Retrying remote WebSocket connection (attempt $retryAttempt)...")
                        CoroutineScope(Dispatchers.IO).launch {
                            kotlinx.coroutines.delay(600L)
                            attemptConnect()
                        }
                    } else if (!opened) {
                        onConnectFailed()
                    } else {
                        listener.onFailure()
                    }
                }
            })
        }
        attemptConnect()
    }

    override fun sendText(text: String): Boolean = socket?.send(text) == true

    override fun sendBinary(bytes: ByteArray): Boolean {
        val connection = socket ?: return false
        if (connection.queueSize() > MAX_QUEUED_BYTES) return false
        return connection.send(ByteString.of(*bytes))
    }

    override fun sendCameraFrame(bytes: ByteArray): Boolean {
        val connection = socket ?: return false
        if (bytes.size > MAX_CAMERA_FRAME_BYTES || connection.queueSize() + bytes.size > MAX_QUEUED_CAMERA_BYTES) return false
        return connection.send(ByteString.of(*bytes))
    }

    override fun queuedBytes(): Long = socket?.queueSize() ?: 0L

    override fun close() {
        close(1000, "Session ended")
    }

    override fun close(code: Int, reason: String) {
        connectJob?.cancel()
        connectJob = null
        socket?.close(code, reason)
        socket = null
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
    }

    override fun isOpen(): Boolean = socket != null

    private companion object {
        const val DEFAULT_HOST = "127.0.0.1:8000"
        const val MAX_QUEUED_BYTES = 32 * 1024L
        const val MAX_CAMERA_FRAME_BYTES = 512 * 1024 + 1
        const val MAX_QUEUED_CAMERA_BYTES = 2 * 1024 * 1024L
    }
}
