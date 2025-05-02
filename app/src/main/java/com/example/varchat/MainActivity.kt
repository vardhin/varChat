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

class MainActivity : ComponentActivity() {
    private val udpHolePunching = UDPHolePunching()
    private val stunClient = STUNClient()

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
            
            Button(
                onClick = {
                    scope.launch {
                        val stunResponse = stunClient.getPublicAddress()
                        if (stunResponse != null) {
                            publicIP = stunResponse.publicIP
                            publicPort = stunResponse.publicPort.toString()
                            status = "Public IP: $publicIP, Public Port: $publicPort"
                            udpHolePunching.start()
                        } else {
                            status = "Failed to get public address"
                        }
                    }
                }
            ) {
                Text("Get Public Address")
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
                }
            ) {
                Text("Start Hole Punching")
            }

            Text(status, style = MaterialTheme.typography.bodyMedium)
        }
    }
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
    val alignment = if (isLocal) Alignment.End else Alignment.Start
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