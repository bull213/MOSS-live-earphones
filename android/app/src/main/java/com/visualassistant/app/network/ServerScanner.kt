package com.visualassistant.app.network

import android.os.SystemClock
import android.util.Log
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Discovers the computer's backend server on USB loopback, local Wi-Fi, or Phone Mobile Hotspot.
 * Centralizes host resolution, health verification, and parallel subnet discovery.
 */
internal object ServerScanner {
    private const val TAG = "ServerScanner"
    private const val CACHE_TTL_MS = 60_000L

    @Volatile private var cachedHost: String? = null
    @Volatile private var cacheTimestamp: Long = 0L

    /**
     * Resolves the best backend host using a 4-tier strategy:
     * 1. Probe USB loopback (127.0.0.1:port for USB cable with ADB reverse) for instant zero-latency connection.
     * 2. Probe current/configured host (if provided and distinct from loopback).
     * 3. Probe cached host (if recent and distinct from loopback).
     * 4. Perform parallel subnet discovery on Wi-Fi / Hotspot interfaces.
     */
    suspend fun findOrResolveBackend(currentHost: String? = null, port: Int = 8000): String? = withContext(Dispatchers.IO) {
        // Tier 1: Check USB loopback (ADB reverse) - instant <2ms check, preferred if phone is connected via cable
        if (verifyHost("127.0.0.1", port)) {
            updateCache("127.0.0.1")
            return@withContext "127.0.0.1"
        }

        // Tier 2: Check configured host
        if (!currentHost.isNullOrBlank()) {
            if (verifyHost(currentHost, port)) {
                updateCache(currentHost)
                return@withContext currentHost
            }
            // If configured host is an explicit remote address (Tailscale or domain), do NOT waste time scanning local subnets
            if (isRemoteHost(currentHost)) {
                return@withContext null
            }
        }

        // Tier 3: Check cached host
        val cached = cachedHost
        if (cached != null && cached != "127.0.0.1" && (SystemClock.elapsedRealtime() - cacheTimestamp < CACHE_TTL_MS)) {
            if (verifyHost(cached, port)) {
                return@withContext cached
            }
        }

        // Tier 4: Subnet discovery on local network / phone hotspot
        val discovered = findBackendServer(port)
        if (discovered != null) {
            updateCache(discovered)
            return@withContext discovered
        }

        null
    }

    /**
     * Scans local network interfaces and probes subnet candidates in parallel.
     */
    suspend fun findBackendServer(port: Int = 8000): String? = withContext(Dispatchers.IO) {
        val candidates = getCandidateIps()
        if (candidates.isEmpty()) return@withContext null

        coroutineScope {
            // First check high-priority candidates in parallel (hotspot / router defaults)
            val priorityList = candidates.take(15)
            val priorityResult = priorityList.map { ip ->
                async {
                    if (probeServer(ip, port)) ip else null
                }
            }.awaitAll().firstOrNull { it != null }

            if (priorityResult != null) return@coroutineScope priorityResult

            // Scan the remainder in small batches
            val remaining = candidates.drop(15)
            for (chunk in remaining.chunked(30)) {
                val found = chunk.map { ip ->
                    async {
                        if (probeServer(ip, port)) ip else null
                    }
                }.awaitAll().firstOrNull { it != null }
                if (found != null) return@coroutineScope found
            }
            null
        }
    }

    /**
     * Verifies that a server is listening and responds with HTTP 200 on /health.
     * Supports both plain IP:port and full https:// URLs for remote Tailscale/MagicDNS setups.
     */
    fun verifyHost(ipOrHost: String, port: Int = 8000): Boolean {
        val isSecure = ipOrHost.startsWith("https://", ignoreCase = true) || ipOrHost.startsWith("wss://", ignoreCase = true)
        val scheme = if (isSecure) "https" else "http"
        val bare = ipOrHost.removePrefix("http://").removePrefix("https://").removePrefix("ws://").removePrefix("wss://").trimEnd('/')
        val hostPart = if (bare.contains(":")) bare.substringBefore(":") else bare
        val effectivePort = if (bare.contains(":")) {
            bare.substringAfter(":").toIntOrNull() ?: port
        } else if (isSecure) {
            443
        } else {
            port
        }
        val isRemote = isRemoteHost(hostPart) || isSecure
        val timeoutMs = if (hostPart == "127.0.0.1" || hostPart == "localhost") {
            250
        } else if (isRemote) {
            5000
        } else {
            1200
        }

        return try {
            if (!isRemote) {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(hostPart, effectivePort), timeoutMs)
                }
            }
            val targetUrl = if (isSecure || isRemote) {
                val portSuffix = if (effectivePort != 443 && effectivePort != 80) ":$effectivePort" else ""
                "$scheme://$hostPart$portSuffix/health"
            } else {
                "$scheme://$hostPart:$effectivePort/health"
            }
            isHealthOkUrl(targetUrl, timeoutMs)
        } catch (_: Exception) {
            false
        }
    }

    private fun probeServer(ip: String, port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), 200)
            }
            isHealthOkUrl("http://$ip:$port/health", 600)
        } catch (_: Exception) {
            false
        }
    }

    private fun isHealthOkUrl(fullUrl: String, timeoutMs: Int = 1200): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(fullUrl)
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.requestMethod = "GET"
            conn.responseCode == 200
        } catch (_: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
    }

    fun isRemoteHost(host: String): Boolean {
        return BackendEndpoint.isRemoteHost(host)
    }

    private fun isPrivateLocalSubnet(host: String): Boolean {
        return host.startsWith("192.168.") ||
                host.startsWith("10.") ||
                (host.startsWith("172.") && host.split(".").let { it.size >= 2 && (it[1].toIntOrNull() ?: 0) in 16..31 })
    }

    fun getCandidateIps(): List<String> {
        val ips = mutableListOf<String>()
        val localSubnets = mutableSetOf(
            "10.183.3",     // Samsung Galaxy S24+ hotspot subnet
            "192.168.43",   // Android Mobile Hotspot default
            "192.168.50",   // Samsung dual-band alternate
            "192.168.137",  // Windows Mobile Hotspot
            "172.20.10",    // iOS hotspot
        )

        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: emptyEnumeration()
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        val parts = host.split(".")
                        if (parts.size == 4) {
                            localSubnets.add("${parts[0]}.${parts[1]}.${parts[2]}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to inspect local network interfaces: ${e.message}")
        }

        for (subnet in localSubnets) {
            // Most common DHCP addresses assigned by Android Mobile Hotspot / routers:
            // 200 is priority #1 because Samsung Hotspot frequently assigns .200 to Windows PCs.
            val priority = listOf(200, 100, 2, 3, 4, 5, 101, 102, 201, 10, 20, 50, 150)
            for (last in priority) {
                ips.add("$subnet.$last")
            }
            for (last in 2..254) {
                if (last !in priority) {
                    ips.add("$subnet.$last")
                }
            }
        }
        return ips
    }

    private fun <T> emptyEnumeration(): java.util.Enumeration<T> = java.util.Collections.emptyEnumeration()

    private fun stripPort(host: String): String {
        val bare = host.removePrefix("http://").removePrefix("https://").removePrefix("ws://").removePrefix("wss://")
        return if (bare.contains(":")) bare.substringBefore(":") else bare
    }

    private fun updateCache(host: String) {
        cachedHost = host
        cacheTimestamp = SystemClock.elapsedRealtime()
    }
}
