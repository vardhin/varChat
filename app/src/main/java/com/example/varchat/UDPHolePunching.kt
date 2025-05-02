package com.example.varchat

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class UDPHolePunching(private val localPort: Int = 0) {
    private var socket: DatagramSocket? = null
    private val TAG = "UDPHolePunching"
    private var isConnected = AtomicBoolean(false)
    private var remoteAddress: InetAddress? = null
    private var remotePort: Int = 0
    private var isListening = AtomicBoolean(false)
    private var connectionAttemptThread: Thread? = null
    private var keepAliveThread: Thread? = null

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages
    
    private val _connectionStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.NOT_CONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus
    
    private val _logMessages = MutableStateFlow<List<String>>(emptyList())
    val logMessages: StateFlow<List<String>> = _logMessages

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

    private fun addLogMessage(message: String) {
        val timestamp = System.currentTimeMillis()
        val formattedTime = java.text.SimpleDateFormat("HH:mm:ss.SSS").format(java.util.Date(timestamp))
        val logMessage = "[$formattedTime] $message"
        Log.d(TAG, logMessage)
        
        // Keep only the last 100 messages
        val updatedLogs = _logMessages.value + logMessage
        if (updatedLogs.size > 100) {
            _logMessages.value = updatedLogs.takeLast(100)
        } else {
            _logMessages.value = updatedLogs
        }
    }

    suspend fun start() = withContext(Dispatchers.IO) {
        if (socket != null) {
            addLogMessage("Socket already exists on port: ${socket?.localPort}")
            return@withContext
        }
        
        try {
            socket = DatagramSocket(localPort)
            addLogMessage("Socket created on port: ${socket?.localPort}")
            startListening()
        } catch (e: Exception) {
            addLogMessage("Error creating socket: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun startListening() {
        if (isListening.get()) {
            addLogMessage("Already listening, ignoring duplicate call")
            return
        }
        
        isListening.set(true)
        Thread {
            try {
                val buffer = ByteArray(1024)
                val packet = DatagramPacket(buffer, buffer.size)
                
                addLogMessage("Started listening for incoming packets")
                
                while (isListening.get() && socket != null) {
                    try {
                        socket?.receive(packet)
                        val message = String(packet.data, 0, packet.length)
                        addLogMessage("Received packet from ${packet.address}:${packet.port}, length: ${packet.length}, content: '$message'")
                        
                        if (!isConnected.get()) {
                            // First message received, establish connection
                            remoteAddress = packet.address
                            remotePort = packet.port
                            isConnected.set(true)
                            _connectionStatus.value = ConnectionStatus.CONNECTED
                            addLogMessage("Connection established with ${packet.address}:${packet.port}")
                            
                            // Start keep-alive thread when connection is established
                            startKeepAliveThread()
                        }
                        
                        when (message) {
                            "HOLE_PUNCH" -> {
                                addLogMessage("Received hole punching packet")
                                // Send a reply to keep the hole open
                                try {
                                    val replyPacket = DatagramPacket("HOLE_PUNCH".toByteArray(), "HOLE_PUNCH".length, packet.address, packet.port)
                                    socket?.send(replyPacket)
                                    addLogMessage("Sent hole punch reply")
                                } catch (e: Exception) {
                                    addLogMessage("Failed to send hole punch reply: ${e.message}")
                                }
                            }
                            "KEEP_ALIVE" -> {
                                addLogMessage("Received keep-alive packet")
                                // No need to respond to keep-alive packets
                            }
                            else -> {
                                _messages.value = _messages.value + ChatMessage(
                                    text = message,
                                    isLocal = false
                                )
                                addLogMessage("Added message to chat: $message")
                            }
                        }
                    } catch (e: Exception) {
                        if (isListening.get()) {
                            addLogMessage("Error receiving packet: ${e.message}")
                        } else {
                            // Socket likely closed, breaking out of loop
                            break
                        }
                    }
                }
                addLogMessage("Listening thread ended")
            } catch (e: Exception) {
                addLogMessage("Error in listening thread: ${e.message}")
                e.printStackTrace()
            }
        }.start()
    }
    
    private fun startKeepAliveThread() {
        if (keepAliveThread != null && keepAliveThread?.isAlive == true) {
            addLogMessage("Keep-alive thread already running")
            return
        }
        
        keepAliveThread = Thread {
            addLogMessage("Started keep-alive thread")
            while (isConnected.get() && !Thread.currentThread().isInterrupted && socket != null && remoteAddress != null) {
                try {
                    val keepAliveData = "KEEP_ALIVE".toByteArray()
                    val packet = DatagramPacket(keepAliveData, keepAliveData.size, remoteAddress, remotePort)
                    socket?.send(packet)
                    addLogMessage("Sent keep-alive packet")
                    Thread.sleep(15000) // Send keep-alive every 15 seconds
                } catch (e: InterruptedException) {
                    addLogMessage("Keep-alive thread interrupted")
                    break
                } catch (e: Exception) {
                    addLogMessage("Error sending keep-alive: ${e.message}")
                    if (socket == null || !isConnected.get()) {
                        break
                    }
                    Thread.sleep(5000) // On error, retry after 5 seconds
                }
            }
            addLogMessage("Keep-alive thread ended")
        }.apply {
            isDaemon = true
            start()
        }
    }

    suspend fun startHolePunching(remoteIP: String, remotePort: Int) = withContext(Dispatchers.IO) {
        try {
            // Make sure the socket is created first
            if (socket == null) {
                start()
            }
            
            // Reset state if retrying
            if (connectionStatus.value == ConnectionStatus.FAILED || connectionStatus.value == ConnectionStatus.CONNECTED) {
                isConnected.set(false)
                addLogMessage("Resetting connection state for new hole punching attempt")
            }
            
            this@UDPHolePunching.remoteAddress = InetAddress.getByName(remoteIP)
            this@UDPHolePunching.remotePort = remotePort
            
            addLogMessage("Starting hole punching to $remoteIP:$remotePort")
            _connectionStatus.value = ConnectionStatus.ATTEMPTING
            
            // Send initial packet to create NAT mapping
            val initialPacket = "HOLE_PUNCH".toByteArray()
            val packet = DatagramPacket(initialPacket, initialPacket.size, this@UDPHolePunching.remoteAddress, remotePort)
            
            try {
                socket?.send(packet)
                addLogMessage("Sent initial hole punch packet")
            } catch (e: Exception) {
                addLogMessage("Failed to send initial hole punch packet: ${e.message}")
                _connectionStatus.value = ConnectionStatus.FAILED
                throw e
            }
            
            // Stop any existing connection attempt thread
            connectionAttemptThread?.interrupt()
            
            // Start sending periodic packets to keep the hole open
            connectionAttemptThread = Thread {
                var attempts = 0
                val maxAttempts = 60 // Try for 60 seconds
                val initialBackoff = 200L // Start with 200ms
                var currentBackoff = initialBackoff
                
                addLogMessage("Started connection attempt thread")
                
                while (!isConnected.get() && attempts < maxAttempts && !Thread.currentThread().isInterrupted) {
                    try {
                        socket?.send(packet)
                        attempts++
                        
                        // Log less frequently as attempts increase
                        if (attempts % 5 == 0 || attempts < 5) {
                            addLogMessage("Sent hole punch packet (attempt $attempts/$maxAttempts)")
                        }
                        
                        Thread.sleep(currentBackoff)
                        
                        // Adaptive timing - gradually increase delay between packets
                        if (attempts < 10) {
                            // Rapid punching at start (200-500ms)
                            currentBackoff = initialBackoff + (attempts * 30)
                        } else if (attempts < 20) {
                            // Medium pace (0.5s - 1s)
                            currentBackoff = 500L + ((attempts - 10) * 50)
                        } else {
                            // Slower pace for remaining attempts (1s - 2s)
                            currentBackoff = 1000L + ((attempts - 20) * 50).coerceAtMost(1000)
                        }
                    } catch (e: InterruptedException) {
                        addLogMessage("Connection attempt thread interrupted")
                        break
                    } catch (e: Exception) {
                        if (!Thread.currentThread().isInterrupted) {
                            addLogMessage("Error during hole punching: ${e.message}")
                            _connectionStatus.value = ConnectionStatus.FAILED
                        }
                        break
                    }
                }
                
                if (isConnected.get()) {
                    addLogMessage("Hole punching successful after $attempts attempts")
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
                        addLogMessage("Sent welcome message")
                    } catch (e: Exception) {
                        addLogMessage("Failed to send welcome message: ${e.message}")
                    }
                } else if (attempts >= maxAttempts && !Thread.currentThread().isInterrupted) {
                    addLogMessage("Hole punching failed after $maxAttempts attempts")
                    _connectionStatus.value = ConnectionStatus.FAILED
                }
            }.apply {
                isDaemon = true
                start()
            }
        } catch (e: Exception) {
            addLogMessage("Error starting hole punching: ${e.message}")
            _connectionStatus.value = ConnectionStatus.FAILED
            e.printStackTrace()
            throw e
        }
    }

    suspend fun sendMessage(message: String) = withContext(Dispatchers.IO) {
        try {
            if (!isConnected.get()) {
                addLogMessage("Cannot send message: not connected to remote peer")
                throw Exception("Not connected to remote peer")
            }
            
            val data = message.toByteArray()
            val packet = DatagramPacket(data, data.size, remoteAddress, remotePort)
            
            try {
                socket?.send(packet)
                addLogMessage("Sent message: $message")
            } catch (e: Exception) {
                addLogMessage("Failed to send message: ${e.message}")
                throw e
            }
            
            _messages.value = _messages.value + ChatMessage(
                text = message,
                isLocal = true
            )
        } catch (e: Exception) {
            addLogMessage("Error sending message: ${e.message}")
            e.printStackTrace()
            throw e
        }
    }

    fun getLocalPort(): Int {
        return socket?.localPort ?: -1
    }

    fun stop() {
        isListening.set(false)
        isConnected.set(false)
        connectionAttemptThread?.interrupt()
        connectionAttemptThread = null
        keepAliveThread?.interrupt()
        keepAliveThread = null
        socket?.close()
        socket = null
        _connectionStatus.value = ConnectionStatus.NOT_CONNECTED
        addLogMessage("UDP hole punching stopped")
    }
} 