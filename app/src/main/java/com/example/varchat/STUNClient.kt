package com.example.varchat

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class STUNClient {
    private val TAG = "STUNClient"
    private val STUN_SERVERS = listOf(
        "stun1.l.google.com:19302",
        "stun2.l.google.com:19302",
        "stun3.l.google.com:19302",
        "stun4.l.google.com:19302"
    )

    data class STUNResponse(
        val publicIP: String,
        val publicPort: Int
    )

    suspend fun getPublicAddress(): STUNResponse? = withContext(Dispatchers.IO) {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            socket.soTimeout = 5000

            // STUN binding request
            val request = byteArrayOf(
                0x00.toByte(), 0x01.toByte(), // Message Type: Binding Request
                0x00.toByte(), 0x00.toByte(), // Message Length
                0x21.toByte(), 0x12.toByte(), 0xA4.toByte(), 0x42.toByte(), // Magic Cookie
                0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte() // Transaction ID
            )

            for (server in STUN_SERVERS) {
                try {
                    val (host, port) = server.split(":")
                    val address = InetAddress.getByName(host)
                    val packet = DatagramPacket(request, request.size, address, port.toInt())
                    socket.send(packet)

                    val response = ByteArray(1024)
                    val responsePacket = DatagramPacket(response, response.size)
                    socket.receive(responsePacket)

                    // Parse STUN response
                    if (response[0] == 0x01.toByte() && response[1] == 0x01.toByte()) { // Binding Response
                        // Get the port from the XOR-MAPPED-ADDRESS attribute
                        val portOffset = 20 // Skip STUN header
                        val port = ((response[portOffset + 2].toInt() and 0xFF) shl 8) or 
                                 (response[portOffset + 3].toInt() and 0xFF)
                        
                        // Get the IP from the XOR-MAPPED-ADDRESS attribute
                        val ipBytes = ByteArray(4)
                        System.arraycopy(response, portOffset + 4, ipBytes, 0, 4)
                        val ip = InetAddress.getByAddress(ipBytes).hostAddress

                        Log.d(TAG, "STUN Response - Public IP: $ip, Public Port: $port")
                        return@withContext STUNResponse(ip, port)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error with STUN server $server: ${e.message}")
                    continue
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "STUN error: ${e.message}")
            null
        } finally {
            socket?.close()
        }
    }
} 