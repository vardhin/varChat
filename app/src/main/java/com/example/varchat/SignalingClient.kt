package com.example.varchat

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.lang.Exception
import java.net.URI
import java.net.URISyntaxException
import java.util.concurrent.atomic.AtomicBoolean

class SignalingClient(
    private val serverUrl: String = Config.SIGNALING_SERVER_URL
) {
    private val TAG = "SignalingClient"
    private var socket: WebSocketClient? = null
    private val gson = Gson()
    private var myPeerId: String? = null
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isConnected = AtomicBoolean(false)
    
    // Message flow for updating the UI
    private val _logMessages = MutableStateFlow<List<String>>(emptyList())
    val logMessages: StateFlow<List<String>> = _logMessages
    
    // Available peers list
    private val _availablePeers = MutableStateFlow<List<String>>(emptyList())
    val availablePeers: StateFlow<List<String>> = _availablePeers
    
    // Peer connection info received
    private val _connectionInfo = MutableStateFlow<PeerConnectionInfo?>(null)
    val connectionInfo: StateFlow<PeerConnectionInfo?> = _connectionInfo
    
    data class PeerConnectionInfo(
        val peerId: String,
        val ip: String,
        val port: Int
    )
    
    // WebSocket message types
    sealed class SignalingMessage {
        data class Id(val peerId: String) : SignalingMessage()
        data class Peers(val peers: List<String>) : SignalingMessage()
        data class NewPeer(val peerId: String) : SignalingMessage()
        data class PeerDisconnected(val peerId: String) : SignalingMessage()
        data class ConnectionInfo(val peerId: String, val ip: String, val port: Int) : SignalingMessage()
        data class Error(val message: String) : SignalingMessage()
    }
    
    private fun addLogMessage(message: String) {
        val timestamp = System.currentTimeMillis()
        val formattedTime = java.text.SimpleDateFormat("HH:mm:ss.SSS").format(java.util.Date(timestamp))
        val logMessage = "[$formattedTime] SIGNAL: $message"
        Log.d(TAG, logMessage)
        
        // Keep only the last 100 messages
        val updatedLogs = _logMessages.value + logMessage
        if (updatedLogs.size > 100) {
            _logMessages.value = updatedLogs.takeLast(100)
        } else {
            _logMessages.value = updatedLogs
        }
    }
    
    fun connect() {
        if (isConnected.get()) {
            addLogMessage("Already connected to signaling server")
            return
        }
        
        try {
            val uri = URI(serverUrl)
            
            socket = object : WebSocketClient(uri) {
                override fun onOpen(handshakedata: ServerHandshake?) {
                    addLogMessage("Connected to signaling server")
                    isConnected.set(true)
                }
                
                override fun onMessage(message: String?) {
                    message?.let {
                        try {
                            addLogMessage("Received message: $it")
                            val map = gson.fromJson(it, Map::class.java)
                            handleSignalingMessage(map)
                        } catch (e: Exception) {
                            addLogMessage("Error parsing message: ${e.message}")
                        }
                    }
                }
                
                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    addLogMessage("Disconnected from signaling server: $reason")
                    isConnected.set(false)
                    myPeerId = null
                    _availablePeers.value = emptyList()
                }
                
                override fun onError(ex: Exception?) {
                    addLogMessage("WebSocket error: ${ex?.message}")
                }
            }
            
            socket?.connect()
        } catch (e: URISyntaxException) {
            addLogMessage("Invalid server URL: ${e.message}")
        } catch (e: Exception) {
            addLogMessage("Error connecting to signaling server: ${e.message}")
        }
    }
    
    fun disconnect() {
        socket?.close()
        isConnected.set(false)
        myPeerId = null
        _availablePeers.value = emptyList()
        _connectionInfo.value = null
        addLogMessage("Disconnected from signaling server")
    }
    
    private fun handleSignalingMessage(messageMap: Map<*, *>) {
        try {
            val type = messageMap["type"] as? String ?: return
            
            when (type) {
                "id" -> {
                    val peerId = messageMap["peerId"] as? String ?: return
                    myPeerId = peerId
                    addLogMessage("Assigned peer ID: $peerId")
                }
                
                "peers" -> {
                    val peers = messageMap["peers"] as? List<*> ?: return
                    val peerIds = peers.filterIsInstance<String>()
                    _availablePeers.value = peerIds
                    addLogMessage("Available peers: ${peerIds.joinToString(", ")}")
                }
                
                "new-peer" -> {
                    val peerId = messageMap["peerId"] as? String ?: return
                    val currentPeers = _availablePeers.value.toMutableList()
                    if (!currentPeers.contains(peerId)) {
                        currentPeers.add(peerId)
                        _availablePeers.value = currentPeers
                        addLogMessage("New peer connected: $peerId")
                    }
                }
                
                "peer-disconnected" -> {
                    val peerId = messageMap["peerId"] as? String ?: return
                    val currentPeers = _availablePeers.value.toMutableList()
                    if (currentPeers.contains(peerId)) {
                        currentPeers.remove(peerId)
                        _availablePeers.value = currentPeers
                        addLogMessage("Peer disconnected: $peerId")
                    }
                }
                
                "connection-info" -> {
                    val peerId = messageMap["peerId"] as? String ?: return
                    val ip = messageMap["ip"] as? String ?: return
                    val port = (messageMap["port"] as? Double)?.toInt() ?: return
                    
                    _connectionInfo.value = PeerConnectionInfo(peerId, ip, port)
                    addLogMessage("Received connection info for peer $peerId at $ip:$port")
                }
                
                "error" -> {
                    val message = messageMap["message"] as? String ?: return
                    addLogMessage("Error from server: $message")
                }
            }
        } catch (e: Exception) {
            addLogMessage("Error handling signaling message: ${e.message}")
        }
    }
    
    fun registerUdpPort(port: Int) {
        if (!isConnected.get()) {
            addLogMessage("Cannot register UDP port: not connected to signaling server")
            return
        }
        
        val message = mapOf(
            "type" to "register-udp",
            "port" to port
        )
        
        sendMessage(message)
        addLogMessage("Registered UDP port $port with signaling server")
    }
    
    /**
     * Request STUN information from our signaling server's UDP service
     * This is an alternative to using the standard STUN servers
     */
    suspend fun requestStunInfo(udpSocket: java.net.DatagramSocket): Pair<String, Int>? = kotlinx.coroutines.withContext(Dispatchers.IO) {
        try {
            addLogMessage("Requesting STUN info from signaling server UDP service")
            
            // Parse server host from the WebSocket URL
            val uri = URI(serverUrl)
            val serverHost = uri.host
            val serverPort = Config.SIGNALING_SERVER_UDP_PORT
            
            addLogMessage("Using STUN server at $serverHost:$serverPort")
            
            // Create STUN request packet
            val stunRequest = mapOf(
                "type" to "stun"
            )
            val jsonData = gson.toJson(stunRequest).toByteArray()
            
            val serverAddr = java.net.InetAddress.getByName(serverHost)
            val packet = java.net.DatagramPacket(jsonData, jsonData.size, serverAddr, serverPort)
            
            // Set timeout for response
            udpSocket.soTimeout = 5000
            
            // Send the request
            udpSocket.send(packet)
            addLogMessage("Sent STUN request to $serverHost:$serverPort")
            
            // Receive response
            val buffer = ByteArray(1024)
            val responsePacket = java.net.DatagramPacket(buffer, buffer.size)
            
            try {
                udpSocket.receive(responsePacket)
                val responseJson = String(buffer, 0, responsePacket.length)
                addLogMessage("Received STUN response: $responseJson")
                
                try {
                    val response = gson.fromJson(responseJson, Map::class.java)
                    val type = response["type"] as? String
                    
                    if (type == "stun-response") {
                        val ip = response["ip"] as? String
                        val port = (response["port"] as? Double)?.toInt()
                        
                        if (ip != null && port != null) {
                            addLogMessage("STUN response shows public endpoint: $ip:$port")
                            return@withContext Pair(ip, port)
                        }
    fun requestConnection(targetPeerId: String) {
        if (!isConnected.get()) {
            addLogMessage("Cannot request connection: not connected to signaling server")
            return
        }
        
        val message = mapOf(
            "type" to "request-connection",
            "target" to targetPeerId
        )
        
        sendMessage(message)
        addLogMessage("Requested connection to peer $targetPeerId")
    }
    
    private fun sendMessage(message: Any) {
        try {
            val json = gson.toJson(message)
            coroutineScope.launch {
                socket?.send(json)
            }
        } catch (e: Exception) {
            addLogMessage("Error sending message: ${e.message}")
        }
    }
    
    fun getMyPeerId(): String? = myPeerId
    
    fun isConnected(): Boolean = isConnected.get()
} 