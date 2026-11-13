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
        var storedToken: String? = "secret-token"
        val endpoint = BackendEndpoint(
            getPreference = { stored },
            setPreference = { stored = it },
            getTokenPreference = { storedToken },
            setTokenPreference = { storedToken = it },
        )

        assertEquals("192.168.1.100:8000", endpoint.host)
        assertEquals("secret-token", endpoint.bearerToken)
        assertEquals("Bearer secret-token", endpoint.authorizationHeader())

        endpoint.resetToDefault()
        assertEquals(null, stored)
        assertEquals(null, storedToken)
        assertEquals("127.0.0.1:8000", endpoint.host)
        assertEquals(null, endpoint.bearerToken)
        assertEquals(null, endpoint.authorizationHeader())
    }

    @Test
    fun detectsRemoteAndTailscaleEndpoints() {
        var stored: String? = null
        val endpoint = BackendEndpoint(
            getPreference = { stored },
            setPreference = { stored = it },
        )

        // Loopback default
        assertEquals(false, endpoint.isRemoteConfigured())
        assertEquals(false, endpoint.isTailscale())

        // Tailscale IP
        endpoint.host = "100.85.12.34:8000"
        assertEquals(true, endpoint.isRemoteConfigured())
        assertEquals(true, endpoint.isTailscale())

        // Tailscale MagicDNS
        endpoint.host = "https://my-desktop.tail12345.ts.net"
        assertEquals(true, endpoint.isRemoteConfigured())
        assertEquals(true, endpoint.isTailscale())

        // Arbitrary domain
        endpoint.host = "assistant.example.com"
        assertEquals(true, endpoint.isRemoteConfigured())
        assertEquals(false, endpoint.isTailscale())
    }
}
