package com.example.varchat

/**
 * Configuration constants for the application
 */
object Config {
    // Signaling server configuration
    const val SIGNALING_SERVER_URL = "ws://test.vardhin.tech:3000"
    const val SIGNALING_SERVER_UDP_PORT = 3001
    
    // STUN server configuration (if we want to override default STUN servers)
    val STUN_SERVERS = listOf(
        "stun.l.google.com:19302",
        "stun1.l.google.com:19302",
        "stun2.l.google.com:19302"
    )
} 