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
                0x00, 0x01, // Message Type: Binding Request
                0x00, 0x00, // Message Length
                0x21, 0x12, 0xA4, 0x42, // Magic Cookie
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 // Transaction ID
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
                        val publicIP = responsePacket.address.hostAddress
                        val publicPort = responsePacket.port
                        Log.d(TAG, "STUN Response - Public IP: $publicIP, Public Port: $publicPort")
                        return@withContext STUNResponse(publicIP, publicPort)
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