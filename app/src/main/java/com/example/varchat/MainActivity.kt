package com.example.varchat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.example.varchat.ui.theme.VarChatTheme
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URL
import java.net.HttpURLConnection
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest

class MainActivity : ComponentActivity() {
    private val udpHolePunching = UDPHolePunching()
    private val stunClient = STUNClient()
    private val signalingClient = SignalingClient()
    private val TAG = "MainActivity"

    // Combined log messages
    private val _combinedLogMessages = MutableStateFlow<List<String>>(emptyList())
    val combinedLogMessages: StateFlow<List<String>> = _combinedLogMessages

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Collect UDPHolePunching logs
        lifecycleScope.launch {
            launch {
                udpHolePunching.logMessages.collect { messages ->
                    val currentLogs = _combinedLogMessages.value
                    val newMessages = messages.filter { !currentLogs.contains(it) }
                    if (newMessages.isNotEmpty()) {
                        _combinedLogMessages.value = (_combinedLogMessages.value + newMessages).takeLast(200)
                    }
                }
            }
            
            // Collect STUN client logs
            launch {
                stunClient.logMessages.collect { messages ->
                    val currentLogs = _combinedLogMessages.value
                    val newMessages = messages.filter { !currentLogs.contains(it) }
                    if (newMessages.isNotEmpty()) {
                        _combinedLogMessages.value = (_combinedLogMessages.value + newMessages).takeLast(200)
                    }
                }
            }
            
            // Collect SignalingClient logs
            launch {
                signalingClient.logMessages.collect { messages ->
                    val currentLogs = _combinedLogMessages.value
                    val newMessages = messages.filter { !currentLogs.contains(it) }
                    if (newMessages.isNotEmpty()) {
                        _combinedLogMessages.value = (_combinedLogMessages.value + newMessages).takeLast(200)
                    }
                }
            }
            
            // Listen for connection info from signaling server
            launch {
                signalingClient.connectionInfo.collectLatest { connectionInfo ->
                    connectionInfo?.let {
                        Log.d(TAG, "Received connection info: ${it.ip}:${it.port} for peer ${it.peerId}")
                        // Start hole punching with the received connection info
                        udpHolePunching.startHolePunching(it.ip, it.port)
                    }
                }
            }
        }

        setContent {
            VarChatTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SignalingUDPHolePunchingDemo(
                        udpHolePunching = udpHolePunching, 
                        stunClient = stunClient,
                        signalingClient = signalingClient,
                        combinedLogs = combinedLogMessages
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        udpHolePunching.stop()
        signalingClient.disconnect()
    }
}

@Composable
fun SignalingUDPHolePunchingDemo(
    udpHolePunching: UDPHolePunching, 
    stunClient: STUNClient,
    signalingClient: SignalingClient,
    combinedLogs: StateFlow<List<String>>
) {
    var status by remember { mutableStateOf("Not started") }
    var publicIP by remember { mutableStateOf("") }
    var publicPort by remember { mutableStateOf("") }
    var showChat by remember { mutableStateOf(false) }
    var showLogs by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var selectedPeer by remember { mutableStateOf("") }
    var isUsingSignaling by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val messages by udpHolePunching.messages.collectAsState()
    val logMessages by combinedLogs.collectAsState()
    val connectionStatus by udpHolePunching.connectionStatus.collectAsState()
    val availablePeers by signalingClient.availablePeers.collectAsState()
    var signalingConnected by remember { mutableStateOf(signalingClient.isConnected()) }
    val logListState = rememberLazyListState()

    // Track signaling server connection state changes
    LaunchedEffect(Unit) {
        // Check connection status periodically
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            while (true) {
                signalingConnected = signalingClient.isConnected()
                kotlinx.coroutines.delay(1000) // Check every second
            }
        }
    }

    // Auto-scroll to bottom of logs when new entries appear
    LaunchedEffect(logMessages.size) {
        if (logMessages.isNotEmpty()) {
            logListState.animateScrollToItem(logMessages.size - 1)
        }
    }

    LaunchedEffect(connectionStatus) {
        when (connectionStatus) {
            UDPHolePunching.ConnectionStatus.CONNECTED -> {
                status = "Connected successfully!"
                showChat = true
            }
            UDPHolePunching.ConnectionStatus.ATTEMPTING -> {
                status = if (isUsingSignaling) {
                    "Attempting connection to peer..."
                } else {
                    "Attempting direct connection..."
                }
                // Optionally, auto-show logs when attempting connection
                showLogs = true
            }
            UDPHolePunching.ConnectionStatus.FAILED -> {
                status = "Connection failed! Please try again."
            }
            UDPHolePunching.ConnectionStatus.NOT_CONNECTED -> {
                status = "Not connected"
            }
        }
    }

    if (showChat) {
        ChatScreen(
            messages = messages,
            connectionStatus = connectionStatus,
            onSendMessage = { message ->
                scope.launch {
                    udpHolePunching.sendMessage(message)
                }
            },
            onBack = { 
                showChat = false 
            },
            onShowLogs = {
                showLogs = !showLogs
            },
            showLogs = showLogs,
            logMessages = logMessages,
            logListState = logListState
        )
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("UDP Hole Punching Demo with Signaling", style = MaterialTheme.typography.headlineMedium)
            
            // Connection method selector
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(
                    onClick = { isUsingSignaling = false },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (!isUsingSignaling) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text("Direct Connection")
                }
                
                Button(
                    onClick = { isUsingSignaling = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isUsingSignaling) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text("Use Signaling Server")
                }
            }
            
            if (isUsingSignaling) {
                // Signaling server connection UI
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                signalingClient.connect()
                                // Start UDP socket to get local port
                                udpHolePunching.start()
                                // Get the local port and register it with the signaling server
                                val localPort = udpHolePunching.getLocalPort()
                                if (localPort > 0) {
                                    signalingClient.registerUdpPort(localPort)
                                    
                                    // Get public address using the signaling server's STUN service
                                    try {
                                        val stunInfo = signalingClient.requestStunInfo(udpHolePunching.getSocket())
                                        if (stunInfo != null) {
                                            publicIP = stunInfo.first
                                            publicPort = stunInfo.second.toString()
                                            status = "Got public address from signaling STUN: $publicIP:$publicPort"
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error getting STUN info from signaling server", e)
                                    }
                                }
                            }
                        },
                        enabled = !signalingConnected
                    ) {
                        Text("Connect to Signaling Server")
                    }
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    Text(
                        text = if (signalingConnected) {
                            "Connected (ID: ${signalingClient.getMyPeerId() ?: "Unknown"})"
                        } else {
                            "Not connected"
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                
                // Available peers list
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text("Available Peers", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        if (availablePeers.isEmpty()) {
                            Text("No peers available", style = MaterialTheme.typography.bodyMedium)
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(120.dp)
                            ) {
                                items(availablePeers) { peer ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(4.dp)
                                            .background(
                                                if (selectedPeer == peer) 
                                                    MaterialTheme.colorScheme.primaryContainer 
                                                else 
                                                    Color.Transparent
                                            )
                                            .clickable { selectedPeer = peer }
                                            .padding(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = peer,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                
                Button(
                    onClick = {
                        if (selectedPeer.isNotEmpty()) {
                            scope.launch {
                                signalingClient.requestConnection(selectedPeer)
                                status = "Requested connection to peer $selectedPeer"
                            }
                        }
                    },
                    enabled = selectedPeer.isNotEmpty() && signalingConnected
                ) {
                    Text("Connect to Selected Peer")
                }
            } else {
                // Direct connection UI (original layout)
                if (isLoading) {
                    CircularProgressIndicator()
                    Text("Attempting to get public address...", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Button(
                        onClick = {
                            scope.launch {
                                isLoading = true
                                try {
                                    // Attempt STUN first
                                    val stunResponse = stunClient.getPublicAddress()
                                    if (stunResponse != null) {
                                        publicIP = stunResponse.publicIP
                                        publicPort = stunResponse.publicPort.toString()
                                        status = "Got public address: $publicIP:$publicPort"
                                        udpHolePunching.start()
                                    } else {
                                        // STUN failed, try IP API fallback
                                        val ipInfo = getPublicIPFallback()
                                        if (ipInfo != null) {
                                            publicIP = ipInfo.first
                                            val localPort = udpHolePunching.getLocalPort()
                                            publicPort = if (localPort > 0) localPort.toString() else ""
                                            
                                            if (publicIP.isNotEmpty()) {
                                                status = "Got public IP: $publicIP, Port: $publicPort"
                                                udpHolePunching.start()
                                            } else {
                                                status = "Failed to get public address"
                                            }
                                        } else {
                                            status = "Failed to get public address"
                                        }
                                    }
                                } catch (e: Exception) {
                                    status = "Error: ${e.message}"
                                } finally {
                                    isLoading = false
                                }
                            }
                        }
                    ) {
                        Text("Get Public Address")
                    }
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Your Public Address", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (publicIP.isNotEmpty() && publicPort.isNotEmpty()) {
                                "$publicIP:$publicPort"
                            } else {
                                "Not available yet"
                            },
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }

                var remoteAddress by remember { mutableStateOf("") }
                var remotePort by remember { mutableStateOf("") }

                OutlinedTextField(
                    value = remoteAddress,
                    onValueChange = { remoteAddress = it },
                    label = { Text("Friend's Public IP") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = remotePort,
                    onValueChange = { remotePort = it },
                    label = { Text("Friend's Public Port") },
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    onClick = {
                        scope.launch {
                            try {
                                udpHolePunching.startHolePunching(remoteAddress, remotePort.toInt())
                            } catch (e: Exception) {
                                status = "Error: ${e.message}"
                            }
                        }
                    },
                    enabled = publicIP.isNotEmpty() && publicPort.isNotEmpty() && remoteAddress.isNotEmpty() && remotePort.isNotEmpty()
                ) {
                    Text("Start Hole Punching")
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = when (connectionStatus) {
                        UDPHolePunching.ConnectionStatus.CONNECTED -> Color(0xFF4CAF50)
                        UDPHolePunching.ConnectionStatus.ATTEMPTING -> Color(0xFFFFA000)
                        UDPHolePunching.ConnectionStatus.FAILED -> Color(0xFFF44336)
                        UDPHolePunching.ConnectionStatus.NOT_CONNECTED -> MaterialTheme.colorScheme.surfaceVariant
                    }
                )
            ) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (connectionStatus != UDPHolePunching.ConnectionStatus.NOT_CONNECTED) {
                        Color.White
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(16.dp)
                )
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = { showLogs = !showLogs }
                ) {
                    Text(if (showLogs) "Hide Logs" else "Show Logs")
                }
            }
            
            if (showLogs) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp)
                    ) {
                        Text("Connection Logs", style = MaterialTheme.typography.titleMedium)
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        LazyColumn(
                            state = logListState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))
                                .padding(8.dp)
                        ) {
                            items(logMessages) { logMessage ->
                                val isStunLog = logMessage.contains("STUN")
                                val isSignalLog = logMessage.contains("SIGNAL")
                                val isHolePunchLog = logMessage.contains("hole punch", ignoreCase = true)
                                val textColor = when {
                                    isStunLog -> Color(0xFF2196F3) // Blue for STUN
                                    isSignalLog -> Color(0xFF00BCD4) // Cyan for signaling
                                    isHolePunchLog -> Color(0xFF9C27B0) // Purple for hole punching
                                    logMessage.contains("ERROR", ignoreCase = true) -> Color(0xFFF44336) // Red for errors
                                    logMessage.contains("Connected successfully") -> Color(0xFF4CAF50) // Green for success
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                                
                                Text(
                                    text = logMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = textColor,
                                    modifier = Modifier.padding(vertical = 2.dp),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Fallback method to get public IP address using a public API
 */
suspend fun getPublicIPFallback(): Pair<String, String>? = withContext(Dispatchers.IO) {
    val urls = listOf(
        "https://api.ipify.org",
        "https://ifconfig.me/ip",
        "https://icanhazip.com",
        "https://checkip.amazonaws.com"
    )
    
    for (urlStr in urls) {
        try {
            val url = URL(urlStr)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            
            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val ip = reader.readLine().trim()
                reader.close()
                
                if (ip.isNotEmpty()) {
                    Log.d("PublicIPFallback", "Got public IP from $urlStr: $ip")
                    return@withContext Pair(ip, "")
                }
            } else {
                Log.e("PublicIPFallback", "Failed to get IP from $urlStr, response code: $responseCode")
            }
        } catch (e: Exception) {
            Log.e("PublicIPFallback", "Error with $urlStr: ${e.message}")
        }
    }
    
    Log.e("PublicIPFallback", "All API fallbacks failed")
    null
}

@Composable
fun ChatScreen(
    messages: List<UDPHolePunching.ChatMessage>,
    connectionStatus: UDPHolePunching.ConnectionStatus,
    onSendMessage: (String) -> Unit,
    onBack: () -> Unit,
    onShowLogs: () -> Unit,
    showLogs: Boolean,
    logMessages: List<String>,
    logListState: LazyListState
) {
    var message by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    
    // Scroll to bottom when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Chat", style = MaterialTheme.typography.headlineMedium)
            
            // Connection status indicator
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .padding(end = 4.dp)
                        .align(Alignment.CenterVertically),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        modifier = Modifier.size(12.dp),
                        shape = MaterialTheme.shapes.small,
                        color = when (connectionStatus) {
                            UDPHolePunching.ConnectionStatus.CONNECTED -> Color(0xFF4CAF50)
                            UDPHolePunching.ConnectionStatus.ATTEMPTING -> Color(0xFFFFA000)
                            UDPHolePunching.ConnectionStatus.FAILED -> Color(0xFFF44336)
                            UDPHolePunching.ConnectionStatus.NOT_CONNECTED -> Color.Gray
                        }
                    ) {}
                }
                
                Text(
                    text = when (connectionStatus) {
                        UDPHolePunching.ConnectionStatus.CONNECTED -> "Connected"
                        UDPHolePunching.ConnectionStatus.ATTEMPTING -> "Connecting..."
                        UDPHolePunching.ConnectionStatus.FAILED -> "Failed"
                        UDPHolePunching.ConnectionStatus.NOT_CONNECTED -> "Disconnected"
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }
            
            Row {
                TextButton(onClick = onShowLogs) {
                    Text(if (showLogs) "Hide Logs" else "Show Logs")
                }
                
                Button(onClick = onBack) {
                    Text("Back")
                }
            }
        }
        
        if (showLogs) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .padding(vertical = 8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                ) {
                    Text("Connection & STUN Logs", style = MaterialTheme.typography.titleSmall)
                    
                    LazyColumn(
                        state = logListState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .padding(4.dp)
                    ) {
                        items(logMessages) { logMessage ->
                            val isStunLog = logMessage.contains("STUN")
                            val isSignalLog = logMessage.contains("SIGNAL")
                            val isHolePunchLog = logMessage.contains("hole punch", ignoreCase = true)
                            val textColor = when {
                                isStunLog -> Color(0xFF2196F3) // Blue for STUN
                                isSignalLog -> Color(0xFF00BCD4) // Cyan for signaling
                                isHolePunchLog -> Color(0xFF9C27B0) // Purple for hole punching
                                logMessage.contains("ERROR", ignoreCase = true) -> Color(0xFFF44336) // Red for errors
                                logMessage.contains("Connected successfully") -> Color(0xFF4CAF50) // Green for success
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            
                            Text(
                                text = logMessage,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = textColor,
                                modifier = Modifier.padding(vertical = 1.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        if (messages.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = when (connectionStatus) {
                            UDPHolePunching.ConnectionStatus.CONNECTED -> "Connected! Start chatting."
                            UDPHolePunching.ConnectionStatus.ATTEMPTING -> "Establishing connection..."
                            UDPHolePunching.ConnectionStatus.FAILED -> "Connection failed. Go back and try again."
                            UDPHolePunching.ConnectionStatus.NOT_CONNECTED -> "Not connected."
                        },
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages) { message ->
                    MessageBubble(message = message)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = message,
                onValueChange = { message = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type a message...") },
                enabled = connectionStatus == UDPHolePunching.ConnectionStatus.CONNECTED,
                singleLine = true
            )
            Button(
                onClick = {
                    if (message.isNotBlank()) {
                        onSendMessage(message)
                        message = ""
                    }
                },
                modifier = Modifier.padding(start = 8.dp),
                enabled = message.isNotBlank() && connectionStatus == UDPHolePunching.ConnectionStatus.CONNECTED
            ) {
                Text("Send")
            }
        }
    }
}

@Composable
fun MessageBubble(message: UDPHolePunching.ChatMessage) {
    val isLocal = message.isLocal
    val alignment = if (isLocal) Alignment.End else Alignment.Start
    val backgroundColor = if (isLocal) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
    val textColor = MaterialTheme.colorScheme.onPrimary
    
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Surface(
            color = backgroundColor,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.padding(vertical = 2.dp, horizontal = 8.dp)
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    text = message.text,
                    color = textColor
                )
            }
        }
        
        Text(
            text = formatTimestamp(message.timestamp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
        )
    }
}

fun formatTimestamp(timestamp: Long): String {
    val calendar = java.util.Calendar.getInstance()
    calendar.timeInMillis = timestamp
    
    val hours = calendar.get(java.util.Calendar.HOUR_OF_DAY)
    val minutes = calendar.get(java.util.Calendar.MINUTE)
    return String.format("%02d:%02d", hours, minutes)
}