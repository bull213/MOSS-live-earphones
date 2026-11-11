package com.visualassistant.app.network

import org.junit.Assert.assertEquals
import org.junit.Test

class BackendEndpointTest {
    @Test
    fun defaultsToLoopbackPort8000WhenNoPreferenceSet() {
        var stored: String? = null
        val endpoint = BackendEndpoint(
            getPreference = { stored },
            setPreference = { stored = it },
        )

        assertEquals("127.0.0.1:8000", endpoint.host)
        assertEquals("http://127.0.0.1:8000/v1/assistant/turns", endpoint.httpUrl("v1/assistant/turns"))
        assertEquals("http://127.0.0.1:8000/v1/knowledge", endpoint.httpUrl("/v1/knowledge"))
        assertEquals("http://127.0.0.1:8000/v1/knowledge/inspect", endpoint.httpUrl("v1/knowledge/inspect"))
        assertEquals("http://127.0.0.1:8000/v1/knowledge/import", endpoint.httpUrl("v1/knowledge/import"))
        assertEquals("ws://127.0.0.1:8000/v1/realtime/moss-live", endpoint.wsUrl("v1/realtime/moss-live"))
    }

    @Test
    fun sanitizesAndPersistsCustomLocalNetworkIp() {
        var stored: String? = null
        val endpoint = BackendEndpoint(
            getPreference = { stored },
            setPreference = { stored = it },
        )

        // Raw input with http prefix and trailing slash
        endpoint.host = "http://172.30.43.18:8000/"
        assertEquals("172.30.43.18:8000", stored)
        assertEquals("172.30.43.18:8000", endpoint.host)
        assertEquals("http://172.30.43.18:8000/v1/assistant/turns", endpoint.httpUrl("v1/assistant/turns"))
        assertEquals("http://172.30.43.18:8000/v1/knowledge/inspect", endpoint.httpUrl("v1/knowledge/inspect"))
        assertEquals("http://172.30.43.18:8000/v1/knowledge/import", endpoint.httpUrl("v1/knowledge/import"))
        assertEquals("ws://172.30.43.18:8000/v1/realtime/moss-live", endpoint.wsUrl("v1/realtime/moss-live"))
    }

    @Test
    fun supportsSecureHttpsAndWssUrlsForCloudHosting() {
        var stored: String? = null
        val endpoint = BackendEndpoint(
            getPreference = { stored },
            setPreference = { stored = it },
        )

        endpoint.host = "https://assistant.example.com"
        assertEquals("https://assistant.example.com/v1/assistant/turns", endpoint.httpUrl("v1/assistant/turns"))
        assertEquals("wss://assistant.example.com/v1/realtime/moss-live", endpoint.wsUrl("v1/realtime/moss-live"))
    }

    @Test
    fun resetToDefaultRestoresLoopback() {
        var stored: String? = "192.168.1.100:8000"
        val endpoint = BackendEndpoint(
            getPreference = { stored },
            setPreference = { stored = it },
        )

        assertEquals("192.168.1.100:8000", endpoint.host)
        endpoint.resetToDefault()
        assertEquals(null, stored)
        assertEquals("127.0.0.1:8000", endpoint.host)
    }
}
