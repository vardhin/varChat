package com.example.varchat

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UDPHolePunching(private val localPort: Int = 0) {
    private var socket: DatagramSocket? = null
    private val TAG = "UDPHolePunching"

    suspend fun start() = withContext(Dispatchers.IO) {
        try {
            socket = DatagramSocket(localPort)
            Log.d(TAG, "Socket created on port: ${socket?.localPort}")
            
            // Start listening for incoming packets
            startListening()
        } catch (e: Exception) {
            Log.e(TAG, "Error creating socket: ${e.message}")
        }
    }

    private fun startListening() {
        Thread {
            try {
                val buffer = ByteArray(1024)
                val packet = DatagramPacket(buffer, buffer.size)
                
                while (true) {
                    socket?.receive(packet)
                    val message = String(packet.data, 0, packet.length)
                    Log.d(TAG, "Received from ${packet.address}:${packet.port}: $message")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error receiving packet: ${e.message}")
            }
        }.start()
    }

    suspend fun sendMessage(message: String, remoteAddress: String, remotePort: Int) = withContext(Dispatchers.IO) {
        try {
            val address = InetAddress.getByName(remoteAddress)
            val data = message.toByteArray()
            val packet = DatagramPacket(data, data.size, address, remotePort)
            socket?.send(packet)
            Log.d(TAG, "Message sent to $remoteAddress:$remotePort")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending packet: ${e.message}")
        }
    }

    fun getLocalPort(): Int {
        return socket?.localPort ?: -1
    }

    fun stop() {
        socket?.close()
        socket = null
    }
} 