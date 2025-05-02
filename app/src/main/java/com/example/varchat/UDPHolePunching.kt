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
    private var isListening = false
    private var connectionAttemptThread: Thread? = null

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages
    
    private val _connectionStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.NOT_CONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus

    enum class ConnectionStatus {
        NOT_CONNECTED,
        ATTEMPTING,
        CONNECTED,
        FAILED
    }

    data class ChatMessage(
        val text: String,
        val isLocal: Boolean,
        val timestamp: Long = System.currentTimeMillis()
    )

    suspend fun start() = withContext(Dispatchers.IO) {
        if (socket != null) {
            Log.d(TAG, "Socket already exists, reusing socket on port: ${socket?.localPort}")
            return@withContext
        }
        
        try {
            socket = DatagramSocket(localPort)
            Log.d(TAG, "Socket created on port: ${socket?.localPort}")
            startListening()
        } catch (e: Exception) {
            Log.e(TAG, "Error creating socket: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun startListening() {
        if (isListening) {
            Log.d(TAG, "Already listening, ignoring duplicate call")
            return
        }
        
        isListening = true
        Thread {
            try {
                val buffer = ByteArray(1024)
                val packet = DatagramPacket(buffer, buffer.size)
                
                while (isListening && socket != null) {
                    try {
                        socket?.receive(packet)
                        val message = String(packet.data, 0, packet.length)
                        Log.d(TAG, "Received packet from ${packet.address}:${packet.port}, length: ${packet.length}")
                        
                        if (!isConnected) {
                            // First message received, establish connection
                            remoteAddress = packet.address
                            remotePort = packet.port
                            isConnected = true
                            _connectionStatus.value = ConnectionStatus.CONNECTED
                            Log.d(TAG, "Connection established with ${packet.address}:${packet.port}")
                        }
                        
                        if (message != "HOLE_PUNCH") {
                            _messages.value = _messages.value + ChatMessage(
                                text = message,
                                isLocal = false
                            )
                        } else {
                            Log.d(TAG, "Received hole punching packet, not displaying in chat")
                            // Send a reply to keep the hole open
                            try {
                                val replyPacket = DatagramPacket("HOLE_PUNCH".toByteArray(), "HOLE_PUNCH".length, packet.address, packet.port)
                                socket?.send(replyPacket)
                                Log.d(TAG, "Sent hole punch reply to ${packet.address}:${packet.port}")
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to send hole punch reply: ${e.message}")
                            }
                        }
                    } catch (e: Exception) {
                        if (isListening) {
                            Log.e(TAG, "Error receiving packet: ${e.message}")
                        } else {
                            // Socket likely closed, breaking out of loop
                            break
                        }
                    }
                }
                Log.d(TAG, "Listening thread ended")
            } catch (e: Exception) {
                Log.e(TAG, "Error in listening thread: ${e.message}")
                e.printStackTrace()
            }
        }.start()
    }

    suspend fun startHolePunching(remoteIP: String, remotePort: Int) = withContext(Dispatchers.IO) {
        try {
            // Make sure the socket is created first
            if (socket == null) {
                start()
            }
            
            // Reset state if retrying
            if (connectionStatus.value == ConnectionStatus.FAILED || connectionStatus.value == ConnectionStatus.CONNECTED) {
                isConnected = false
            }
            
            this@UDPHolePunching.remoteAddress = InetAddress.getByName(remoteIP)
            this@UDPHolePunching.remotePort = remotePort
            
            Log.d(TAG, "Starting hole punching to $remoteIP:$remotePort")
            _connectionStatus.value = ConnectionStatus.ATTEMPTING
            
            // Send initial packet to create NAT mapping
            val initialPacket = "HOLE_PUNCH".toByteArray()
            val packet = DatagramPacket(initialPacket, initialPacket.size, this@UDPHolePunching.remoteAddress, remotePort)
            
            try {
                socket?.send(packet)
                Log.d(TAG, "Sent initial hole punch packet")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send initial hole punch packet: ${e.message}")
                _connectionStatus.value = ConnectionStatus.FAILED
                throw e
            }
            
            // Stop any existing connection attempt thread
            connectionAttemptThread?.interrupt()
            
            // Start sending periodic packets to keep the hole open
            connectionAttemptThread = Thread {
                var attempts = 0
                val maxAttempts = 30 // Try for 30 seconds
                
                while (!isConnected && attempts < maxAttempts && !Thread.currentThread().isInterrupted) {
                    try {
                        socket?.send(packet)
                        Log.d(TAG, "Sent hole punch packet (attempt ${attempts+1})")
                        Thread.sleep(1000) // Send every second
                        attempts++
                    } catch (e: Exception) {
                        if (!Thread.currentThread().isInterrupted) {
                            Log.e(TAG, "Error during hole punching: ${e.message}")
                            _connectionStatus.value = ConnectionStatus.FAILED
                        }
                        break
                    }
                }
                
                if (isConnected) {
                    Log.d(TAG, "Hole punching successful after $attempts attempts")
                    _connectionStatus.value = ConnectionStatus.CONNECTED
                    // Send a welcome message to let the peer know we're connected
                    try {
                        val welcomeMessage = "Connected successfully after $attempts attempts!"
                        val welcomeData = welcomeMessage.toByteArray()
                        val welcomePacket = DatagramPacket(welcomeData, welcomeData.size, this@UDPHolePunching.remoteAddress, this@UDPHolePunching.remotePort)
                        socket?.send(welcomePacket)
                        
                        // Add to our own chat
                        _messages.value = _messages.value + ChatMessage(
                            text = welcomeMessage,
                            isLocal = true
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send welcome message: ${e.message}")
                    }
                } else if (attempts >= maxAttempts && !Thread.currentThread().isInterrupted) {
                    Log.e(TAG, "Hole punching failed after $maxAttempts attempts")
                    _connectionStatus.value = ConnectionStatus.FAILED
                }
            }.apply {
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting hole punching: ${e.message}")
            _connectionStatus.value = ConnectionStatus.FAILED
            e.printStackTrace()
            throw e
        }
    }

    suspend fun sendMessage(message: String) = withContext(Dispatchers.IO) {
        try {
            if (!isConnected) {
                throw Exception("Not connected to remote peer")
            }
            
            val data = message.toByteArray()
            val packet = DatagramPacket(data, data.size, remoteAddress, remotePort)
            
            try {
                socket?.send(packet)
                Log.d(TAG, "Sent message to $remoteAddress:$remotePort: $message")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send message: ${e.message}")
                throw e
            }
            
            _messages.value = _messages.value + ChatMessage(
                text = message,
                isLocal = true
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error sending message: ${e.message}")
            e.printStackTrace()
            throw e
        }
    }

    fun getLocalPort(): Int {
        return socket?.localPort ?: -1
    }

    fun stop() {
        isListening = false
        connectionAttemptThread?.interrupt()
        connectionAttemptThread = null
        socket?.close()
        socket = null
        isConnected = false
        _connectionStatus.value = ConnectionStatus.NOT_CONNECTED
        Log.d(TAG, "UDP hole punching stopped")
    }
} 