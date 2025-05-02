package com.example.varchat

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

class UDPHolePunching(private val localPort: Int = 0) {
    private var socket: DatagramSocket? = null
    private val TAG = "UDPHolePunching"
    private var isConnected = false
    private var remoteAddress: InetAddress? = null
    private var remotePort: Int = 0

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    data class ChatMessage(
        val text: String,
        val isLocal: Boolean,
        val timestamp: Long = System.currentTimeMillis()
    )

    suspend fun start() = withContext(Dispatchers.IO) {
        try {
            socket = DatagramSocket(localPort)
            Log.d(TAG, "Socket created on port: ${socket?.localPort}")
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
                    
                    if (!isConnected) {
                        // First message received, establish connection
                        remoteAddress = packet.address
                        remotePort = packet.port
                        isConnected = true
                        Log.d(TAG, "Connection established with ${packet.address}:${packet.port}")
                    }
                    
                    _messages.value = _messages.value + ChatMessage(
                        text = message,
                        isLocal = false
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error receiving packet: ${e.message}")
            }
        }.start()
    }

    suspend fun startHolePunching(remoteIP: String, remotePort: Int) = withContext(Dispatchers.IO) {
        try {
            remoteAddress = InetAddress.getByName(remoteIP)
            this@UDPHolePunching.remotePort = remotePort
            
            // Send initial packet to create NAT mapping
            val initialPacket = "HOLE_PUNCH".toByteArray()
            val packet = DatagramPacket(initialPacket, initialPacket.size, remoteAddress, remotePort)
            socket?.send(packet)
            
            // Start sending periodic packets to keep the hole open
            Thread {
                while (!isConnected) {
                    try {
                        socket?.send(packet)
                        Thread.sleep(1000) // Send every second
                    } catch (e: Exception) {
                        Log.e(TAG, "Error during hole punching: ${e.message}")
                    }
                }
            }.start()
        } catch (e: Exception) {
            Log.e(TAG, "Error starting hole punching: ${e.message}")
        }
    }

    suspend fun sendMessage(message: String) = withContext(Dispatchers.IO) {
        try {
            if (!isConnected) {
                throw Exception("Not connected to remote peer")
            }
            
            val data = message.toByteArray()
            val packet = DatagramPacket(data, data.size, remoteAddress, remotePort)
            socket?.send(packet)
            
            _messages.value = _messages.value + ChatMessage(
                text = message,
                isLocal = true
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error sending message: ${e.message}")
        }
    }

    fun getLocalPort(): Int {
        return socket?.localPort ?: -1
    }

    fun stop() {
        socket?.close()
        socket = null
        isConnected = false
    }
} 