package com.visualassistant.app.network

import android.content.Context

/**
 * Manages the backend server address for HTTP and WebSocket communication.
 * Defaults to loopback 127.0.0.1:8000 (for USB cable with ADB reverse), but can
 * be configured to a local Wi-Fi address (e.g. 172.30.43.18:8000) or remote URL
 * for cable-free operation.
 */
internal class BackendEndpoint(
    private val getPreference: () -> String?,
    private val setPreference: (String?) -> Unit,
    private val getTokenPreference: () -> String? = { null },
    private val setTokenPreference: (String?) -> Unit = {},
) {
    constructor(context: Context) : this(
        getPreference = {
            context.applicationContext
                .getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
                .getString(KEY_HOST, null)
        },
        setPreference = { value ->
            val editor = context.applicationContext
                .getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
                .edit()
            if (value == null) editor.remove(KEY_HOST) else editor.putString(KEY_HOST, value)
            editor.apply()
        },
        getTokenPreference = {
            context.applicationContext
                .getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
                .getString(KEY_TOKEN, null)
        },
        setTokenPreference = { value ->
            val editor = context.applicationContext
                .getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
                .edit()
            if (value == null) editor.remove(KEY_TOKEN) else editor.putString(KEY_TOKEN, value)
            editor.apply()
        },
    )

    var host: String
        get() = getPreference()?.trim()?.ifBlank { DEFAULT_HOST } ?: DEFAULT_HOST
        set(value) {
            val sanitized = sanitizeHost(value)
            setPreference(sanitized)
        }

    var bearerToken: String?
        get() = getTokenPreference()?.trim()?.ifBlank { null }
        set(value) {
            setTokenPreference(value?.trim()?.ifBlank { null })
        }

    fun authorizationHeader(): String? {
        val token = bearerToken
        return if (!token.isNullOrBlank()) "Bearer $token" else null
    }

    fun isRemoteConfigured(): Boolean {
        val current = host
        return current != DEFAULT_HOST && !current.startsWith("127.") && !current.contains("localhost")
    }

    fun isTailscale(): Boolean {
        val current = host.lowercase()
        return current.contains(".ts.net") || current.startsWith("100.") || current.contains("://100.")
    }

    fun httpUrl(path: String): String {
        val cleanPath = path.removePrefix("/")
        val currentHost = host
        val scheme = if (currentHost.startsWith("https://") || currentHost.startsWith("http://")) "" else "http://"
        return "$scheme$currentHost/$cleanPath"
    }

    fun wsUrl(path: String): String {
        val cleanPath = path.removePrefix("/")
        val currentHost = host
        val scheme = if (currentHost.startsWith("wss://") || currentHost.startsWith("https://")) "wss://" else "ws://"
        val bareHost = currentHost
            .removePrefix("http://")
            .removePrefix("https://")
            .removePrefix("ws://")
            .removePrefix("wss://")
        return "$scheme$bareHost/$cleanPath"
    }

    fun resetToDefault() {
        setPreference(null)
        setTokenPreference(null)
    }

    companion object {
        const val DEFAULT_HOST = "127.0.0.1:8000"
        private const val PREF_FILE = "backend_config"
        private const val KEY_HOST = "pref_backend_host"
        private const val KEY_TOKEN = "pref_backend_token"

        fun sanitizeHost(raw: String): String {
            val trimmed = raw.trim()
            val isSecure = trimmed.startsWith("https://", ignoreCase = true) || trimmed.startsWith("wss://", ignoreCase = true)
            var bare = trimmed
            for (prefix in listOf("https://", "http://", "wss://", "ws://")) {
                if (bare.startsWith(prefix, ignoreCase = true)) {
                    bare = bare.substring(prefix.length)
                    break
                }
            }
            bare = bare.trimEnd('/').ifBlank { DEFAULT_HOST }
            return if (isSecure) "https://$bare" else bare
        }
    }
}
