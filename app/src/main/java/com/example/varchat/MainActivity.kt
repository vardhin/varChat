package com.example.varchat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.example.varchat.ui.theme.VarChatTheme

class MainActivity : ComponentActivity() {
    private val udpHolePunching = UDPHolePunching()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VarChatTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    UDPHolePunchingDemo(udpHolePunching)
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
fun UDPHolePunchingDemo(udpHolePunching: UDPHolePunching) {
    var localPort by remember { mutableStateOf("") }
    var remoteAddress by remember { mutableStateOf("") }
    var remotePort by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Not started") }
    val scope = rememberCoroutineScope()

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
                    udpHolePunching.start()
                    localPort = udpHolePunching.getLocalPort().toString()
                    status = "Listening on port: $localPort"
                }
            }
        ) {
            Text("Start UDP Server")
        }

        OutlinedTextField(
            value = remoteAddress,
            onValueChange = { remoteAddress = it },
            label = { Text("Remote IP Address") },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = remotePort,
            onValueChange = { remotePort = it },
            label = { Text("Remote Port") },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = message,
            onValueChange = { message = it },
            label = { Text("Message to send") },
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = {
                scope.launch {
                    try {
                        udpHolePunching.sendMessage(
                            message,
                            remoteAddress,
                            remotePort.toInt()
                        )
                        status = "Message sent to $remoteAddress:$remotePort"
                    } catch (e: Exception) {
                        status = "Error: ${e.message}"
                    }
                }
            }
        ) {
            Text("Send Message")
        }

        Text(status, style = MaterialTheme.typography.bodyMedium)
    }
}