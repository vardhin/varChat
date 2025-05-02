package com.example.varchat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

class MainActivity : ComponentActivity() {
    private val udpHolePunching = UDPHolePunching()
    private val stunClient = STUNClient()
    private val TAG = "MainActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VarChatTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    UDPHolePunchingDemo(udpHolePunching, stunClient)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        udpHolePunching.stop()
    }
}

@Composable
fun UDPHolePunchingDemo(udpHolePunching: UDPHolePunching, stunClient: STUNClient) {
    var remoteAddress by remember { mutableStateOf("") }
    var remotePort by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Not started") }
    var publicIP by remember { mutableStateOf("") }
    var publicPort by remember { mutableStateOf("") }
    var showChat by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val messages by udpHolePunching.messages.collectAsState()

    if (showChat) {
        ChatScreen(
            messages = messages,
            onSendMessage = { message ->
                scope.launch {
                    udpHolePunching.sendMessage(message)
                }
            },
            onBack = { showChat = false }
        )
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("UDP Hole Punching Demo", style = MaterialTheme.typography.headlineMedium)
            
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
                                    status = "Public IP: $publicIP, Public Port: $publicPort"
                                    udpHolePunching.start()
                                } else {
                                    // STUN failed, try IP API fallback
                                    val ipInfo = getPublicIPFallback()
                                    if (ipInfo != null) {
                                        publicIP = ipInfo.first
                                        val localPort = udpHolePunching.getLocalPort()
                                        publicPort = if (localPort > 0) localPort.toString() else ""
                                        
                                        if (publicIP.isNotEmpty()) {
                                            status = "Got public IP (API): $publicIP, Using local port: $publicPort"
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

            Text("Your Public Address: $publicIP:$publicPort")

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
                            status = "Starting hole punching..."
                            showChat = true
                        } catch (e: Exception) {
                            status = "Error: ${e.message}"
                        }
                    }
                },
                enabled = publicIP.isNotEmpty() && publicPort.isNotEmpty() && remoteAddress.isNotEmpty() && remotePort.isNotEmpty()
            ) {
                Text("Start Hole Punching")
            }

            Text(status, style = MaterialTheme.typography.bodyMedium)
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
    onSendMessage: (String) -> Unit,
    onBack: () -> Unit
) {
    var message by remember { mutableStateOf("") }
    
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
            Button(onClick = onBack) {
                Text("Back")
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages) { message ->
                MessageBubble(
                    message = message.text,
                    isLocal = message.isLocal
                )
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
                placeholder = { Text("Type a message...") }
            )
            Button(
                onClick = {
                    if (message.isNotBlank()) {
                        onSendMessage(message)
                        message = ""
                    }
                },
                modifier = Modifier.padding(start = 8.dp)
            ) {
                Text("Send")
            }
        }
    }
}

@Composable
fun MessageBubble(message: String, isLocal: Boolean) {
    val alignment = if (isLocal) Alignment.CenterEnd else Alignment.CenterStart
    val color = if (isLocal) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
    
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = alignment
    ) {
        Surface(
            color = color,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.padding(horizontal = 8.dp)
        ) {
            Text(
                text = message,
                modifier = Modifier.padding(8.dp),
                color = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}