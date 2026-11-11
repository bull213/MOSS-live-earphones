package com.visualassistant.app.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Sends a UDP broadcast to notify the PC when the app opens,
 * automatically launching Start MOSS Live.cmd on the PC if it is running
 * the phone trigger listener.
 */
internal object AppWakeTrigger {
    private const val TAG = "AppWakeTrigger"
    private const val TRIGGER_PORT = 8008
    private const val WAKE_PAYLOAD = "WAKE_MOSS_LIVE"

    suspend fun sendWakeSignal(): String? = withContext(Dispatchers.IO) {
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 800
                val data = WAKE_PAYLOAD.toByteArray(Charsets.UTF_8)

                // 1. Broadcast to global broadcast 255.255.255.255
                try {
                    val globalPacket = DatagramPacket(
                        data,
                        data.size,
                        InetAddress.getByName("255.255.255.255"),
                        TRIGGER_PORT,
                    )
                    socket.send(globalPacket)
                } catch (e: Exception) {
                    Log.d(TAG, "Global broadcast skipped: ${e.message}")
                }

                // 2. Broadcast to specific local subnet broadcasts (e.g. 192.168.43.255)
                for (bcast in getSubnetBroadcastAddresses()) {
                    try {
                        val subnetPacket = DatagramPacket(data, data.size, bcast, TRIGGER_PORT)
                        socket.send(subnetPacket)
                    } catch (_: Exception) {
                    }
                }

                // 3. Unicast to common hotspot DHCP clients (bypasses AP broadcast filtering)
                for (unicastIp in getCommonClientIps()) {
                    try {
                        val targetAddr = InetAddress.getByName(unicastIp)
                        val unicastPacket = DatagramPacket(data, data.size, targetAddr, TRIGGER_PORT)
                        socket.send(unicastPacket)
                    } catch (_: Exception) {
                    }
                }

                Log.d(TAG, "Sent wake signal to PC on port $TRIGGER_PORT")

                // 4. Listen for immediate ACK from PC
                try {
                    val buffer = ByteArray(256)
                    val ackPacket = DatagramPacket(buffer, buffer.size)
                    socket.receive(ackPacket)
                    val serverIp = ackPacket.address.hostAddress
                    Log.i(TAG, "Received ACK from PC at $serverIp")
                    return@withContext serverIp
                } catch (_: Exception) {
                    // Timeout if PC takes a second to launch or does not reply
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send wake signal: ${e.message}")
        }
        null
    }

    private fun getSubnetBroadcastAddresses(): List<InetAddress> {
        val broadcasts = mutableListOf<InetAddress>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (ifaceAddr in iface.interfaceAddresses) {
                    val bcast = ifaceAddr.broadcast
                    if (bcast != null) {
                        broadcasts.add(bcast)
                    }
                }
            }
        } catch (_: Exception) {
            // Ignore
        }
        return broadcasts
    }

    private fun getCommonClientIps(): List<String> {
        val targets = mutableListOf<String>()
        // Known PC allocations on Samsung hotspot:
        targets.add("10.183.3.200")
        targets.add("192.168.43.100")
        targets.add("192.168.43.2")
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return targets
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses) {
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        val parts = host.split(".")
                        if (parts.size == 4) {
                            val prefix = "${parts[0]}.${parts[1]}.${parts[2]}"
                            // Most common DHCP client allocations on hotspot:
                            listOf(200, 100, 2, 3, 4, 101, 102).forEach { last ->
                                if (last.toString() != parts[3]) {
                                    targets.add("$prefix.$last")
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Ignore
        }
        return targets
    }
}
