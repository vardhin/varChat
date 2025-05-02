package com.example.varchat

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.net.SocketTimeoutException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class STUNClient {
    private val TAG = "STUNClient"
    private val STUN_SERVERS = listOf(
        "stun.l.google.com:19302",  // Primary Google STUN
        "stun1.l.google.com:19302",
        "stun2.l.google.com:19302",
        "stun3.l.google.com:19302",
        "stun4.l.google.com:19302"
    )

    // STUN attribute types (defined in RFC 5389)
    private val ATTR_XOR_MAPPED_ADDRESS = 0x0020
    private val ATTR_MAPPED_ADDRESS = 0x0001
    // Adding other common attribute types for better debugging
    private val ATTR_SOFTWARE = 0x8022
    private val ATTR_FINGERPRINT = 0x8028
    private val ATTR_ERROR_CODE = 0x0009
    private val ATTR_UNKNOWN_ATTRIBUTES = 0x000A
    private val ATTR_ALTERNATE_SERVER = 0x8023
    
    // STUN magic cookie (fixed value in network byte order)
    private val MAGIC_COOKIE_INT = 0x2112A442

    private val _logMessages = MutableStateFlow<List<String>>(emptyList())
    val logMessages: StateFlow<List<String>> = _logMessages

    data class STUNResponse(
        val publicIP: String,
        val publicPort: Int
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

    private fun dumpHex(bytes: ByteArray, length: Int): String {
        val sb = StringBuilder()
        val lineSize = 16
        
        for (i in 0 until minOf(length, bytes.size)) {
            // Add offset at the beginning of each line
            if (i % lineSize == 0) {
                if (i > 0) sb.append("\n")
                sb.append(String.format("%04X: ", i))
            }
            
            // Hex representation of byte
            sb.append(String.format("%02X ", bytes[i]))
            
            // Add extra space between 8th and 9th byte for readability
            if (i % lineSize == 7) {
                sb.append(" ")
            }
            
            // At the end of each line (or end of data), print ASCII representation
            if ((i % lineSize == lineSize - 1) || (i == minOf(length, bytes.size) - 1)) {
                // Padding for incomplete lines
                val padding = lineSize - 1 - (i % lineSize)
                for (j in 0 until padding) {
                    sb.append("   ")
                }
                if (padding > 7) sb.append(" ") // Extra space for padding beyond 8th byte
                
                sb.append(" | ")
                
                // ASCII representation
                val lineStart = i - (i % lineSize)
                for (j in lineStart..i) {
                    val c = bytes[j].toInt() and 0xFF
                    if (c in 32..126) { // Printable ASCII
                        sb.append(c.toChar())
                    } else {
                        sb.append('.')
                    }
                }
            }
        }
        
        return sb.toString()
    }

    private fun getAttributeName(type: Int): String {
        return when (type) {
            ATTR_MAPPED_ADDRESS -> "MAPPED-ADDRESS"
            ATTR_XOR_MAPPED_ADDRESS -> "XOR-MAPPED-ADDRESS"
            ATTR_SOFTWARE -> "SOFTWARE"
            ATTR_FINGERPRINT -> "FINGERPRINT"
            ATTR_ERROR_CODE -> "ERROR-CODE"
            ATTR_UNKNOWN_ATTRIBUTES -> "UNKNOWN-ATTRIBUTES"
            ATTR_ALTERNATE_SERVER -> "ALTERNATE-SERVER"
            else -> "UNKNOWN-TYPE-0x${Integer.toHexString(type)}"
        }
    }

    suspend fun getPublicAddress(): STUNResponse? = withContext(Dispatchers.IO) {
        var socket: DatagramSocket? = null
        try {
            addLogMessage("Starting STUN client process")
            
            // Create socket
            try {
                socket = DatagramSocket()
                socket.soTimeout = 5000
                val localPort = socket.localPort
                addLogMessage("STUN client using local port: $localPort")
            } catch (e: Exception) {
                addLogMessage("Failed to create socket: ${e.message}")
                e.printStackTrace()
                return@withContext null
            }
            
            // Generate transaction ID and prepare request
            val transactionId = ByteArray(12)
            val request: ByteArray
            val magicCookieBytes: ByteArray
            
            try {
                // Generate random transaction ID
                for (i in transactionId.indices) {
                    transactionId[i] = (Math.random() * 256).toInt().toByte()
                }

                // Convert magic cookie int to byte array
                magicCookieBytes = ByteBuffer.allocate(4)
                    .order(ByteOrder.BIG_ENDIAN)
                    .putInt(MAGIC_COOKIE_INT)
                    .array()

                // STUN binding request
                request = ByteArray(20) // STUN header is 20 bytes
                // Message Type: Binding Request (0x0001)
                request[0] = 0x00.toByte()
                request[1] = 0x01.toByte()
                // Message Length (0 bytes)
                request[2] = 0x00.toByte()
                request[3] = 0x00.toByte()
                // Magic Cookie
                System.arraycopy(magicCookieBytes, 0, request, 4, 4)
                // Transaction ID
                System.arraycopy(transactionId, 0, request, 8, 12)
                
                addLogMessage("STUN request prepared:")
                addLogMessage("Transaction ID: ${dumpHex(transactionId, transactionId.size)}")
                addLogMessage("Magic cookie: ${dumpHex(magicCookieBytes, magicCookieBytes.size)}")
                addLogMessage("Complete request packet (${request.size} bytes):\n${dumpHex(request, request.size)}")
            } catch (e: Exception) {
                addLogMessage("Failed to prepare STUN request: ${e.message}")
                e.printStackTrace()
                return@withContext null
            }

            for (server in STUN_SERVERS) {
                addLogMessage("Trying STUN server: $server")
                var serverSuccess = false
                
                try {
                    // Step 1: Parse server address and prepare packet
                    var address: InetAddress
                    var serverPort: Int
                    
                    try {
                        val parts = server.split(":")
                        val host = parts[0]
                        serverPort = parts[1].toInt()
                        address = InetAddress.getByName(host)
                        addLogMessage("Resolved STUN server $host to ${address.hostAddress}")
                    } catch (e: Exception) {
                        addLogMessage("Failed to resolve STUN server address $server: ${e.message}")
                        continue
                    }
                    
                    // Step 2: Send STUN request
                    try {
                        addLogMessage("Sending STUN request to $server (${address.hostAddress}:$serverPort)")
                        val packet = DatagramPacket(request, request.size, address, serverPort)
                        socket.send(packet)
                        addLogMessage("STUN request sent successfully")
                    } catch (e: Exception) {
                        addLogMessage("Failed to send STUN request to $server: ${e.message}")
                        continue
                    }

                    // Step 3: Receive response
                    val response = ByteArray(1024)
                    val responsePacket = DatagramPacket(response, response.size)
                    try {
                        addLogMessage("Waiting for STUN response from $server (timeout: ${socket.soTimeout}ms)")
                        socket.receive(responsePacket)
                        addLogMessage("Received response from ${responsePacket.address}:${responsePacket.port} (${responsePacket.length} bytes)")
                        addLogMessage("Response hex dump:\n${dumpHex(response, responsePacket.length)}")
                    } catch (e: SocketTimeoutException) {
                        addLogMessage("Timeout waiting for response from STUN server $server")
                        continue
                    } catch (e: Exception) {
                        addLogMessage("Failed to receive response from STUN server $server: ${e.message}")
                        continue
                    }

                    // Step 4: Validate response is proper STUN response
                    try {
                        // Check if it's a valid STUN response (first 2 bits should be 00)
                        if ((response[0].toInt() and 0xC0) != 0) {
                            addLogMessage("Not a valid STUN response, invalid first 2 bits: ${response[0].toInt() and 0xC0}")
                            continue
                        }

                        // Check if it's a Binding Response (0x0101)
                        val messageType = ((response[0].toInt() and 0xFF) shl 8) or (response[1].toInt() and 0xFF)
                        addLogMessage("Message type: 0x${Integer.toHexString(messageType)}")
                        
                        if (messageType != 0x0101) {
                            addLogMessage("Not a Binding Response: 0x${Integer.toHexString(messageType)}")
                            continue
                        }

                        // Get message length
                        val messageLength = ((response[2].toInt() and 0xFF) shl 8) or (response[3].toInt() and 0xFF)
                        addLogMessage("STUN message length: $messageLength bytes")
                        
                        // Verify the magic cookie
                        val responseCookie = ByteArray(4)
                        System.arraycopy(response, 4, responseCookie, 0, 4)
                        addLogMessage("Response cookie: ${dumpHex(responseCookie, 4)}")
                        
                        var cookieMatch = true
                        for (i in 0 until 4) {
                            if (responseCookie[i] != magicCookieBytes[i]) {
                                addLogMessage("Magic cookie mismatch at position $i")
                                cookieMatch = false
                                break
                            }
                        }
                        
                        if (!cookieMatch) {
                            addLogMessage("Magic cookie mismatch in response")
                            continue
                        }
                        
                        // Verify the transaction ID
                        val responseTransactionId = ByteArray(12)
                        System.arraycopy(response, 8, responseTransactionId, 0, 12)
                        addLogMessage("Response transaction ID: ${dumpHex(responseTransactionId, 12)}")
                        
                        var idMatch = true
                        for (i in 0 until 12) {
                            if (responseTransactionId[i] != transactionId[i]) {
                                addLogMessage("Transaction ID mismatch at position $i")
                                idMatch = false
                                break
                            }
                        }
                        
                        if (!idMatch) {
                            addLogMessage("Transaction ID mismatch in response")
                            continue
                        }
                        
                        addLogMessage("Transaction ID and magic cookie in response match request")
                    } catch (e: Exception) {
                        addLogMessage("Error validating STUN response: ${e.message}")
                        e.printStackTrace()
                        continue
                    }

                    // Step 5: Parse STUN attributes
                    try {
                        var offset = 20 // Skip STUN header
                        var foundAddress = false
                        var ip: String? = null
                        var port: Int = -1
                        val messageLength = ((response[2].toInt() and 0xFF) shl 8) or (response[3].toInt() and 0xFF)
                        val endOffset = 20 + messageLength
                        
                        addLogMessage("Starting attribute parsing from offset 20 to $endOffset")

                        while (offset < endOffset) {
                            if (offset + 4 > response.size) {
                                addLogMessage("Response buffer overrun when reading attribute header at offset $offset")
                                break
                            }
                            
                            // Get attribute type and length
                            val attrType = ((response[offset].toInt() and 0xFF) shl 8) or (response[offset + 1].toInt() and 0xFF)
                            val attrLength = ((response[offset + 2].toInt() and 0xFF) shl 8) or (response[offset + 3].toInt() and 0xFF)
                            
                            val attrName = getAttributeName(attrType)
                            addLogMessage("Found attribute: $attrName (0x${Integer.toHexString(attrType)}), length: $attrLength at offset $offset")
                            
                            if (offset + 4 + attrLength > response.size) {
                                addLogMessage("Response buffer overrun when reading attribute value at offset $offset")
                                break
                            }

                            if (attrType == ATTR_XOR_MAPPED_ADDRESS) {
                                addLogMessage("Processing XOR-MAPPED-ADDRESS attribute")
                                
                                try {
                                    // Dump the entire attribute for debugging
                                    val attrBytes = ByteArray(attrLength)
                                    System.arraycopy(response, offset + 4, attrBytes, 0, attrLength)
                                    addLogMessage("XOR-MAPPED-ADDRESS bytes: ${dumpHex(attrBytes, attrLength)}")
                                    
                                    if (attrLength < 4) {
                                        addLogMessage("XOR-MAPPED-ADDRESS attribute too short: $attrLength bytes")
                                        break
                                    }
                                    
                                    // Skip the reserved byte
                                    val family = response[offset + 5].toInt() and 0xFF
                                    addLogMessage("Address family: $family (1=IPv4, 2=IPv6)")
                                    
                                    if (family == 0x01) { // IPv4
                                        if (attrLength < 8) {
                                            addLogMessage("IPv4 XOR-MAPPED-ADDRESS attribute too short: $attrLength bytes")
                                            break
                                        }
                                        
                                        // XOR port with first 2 bytes of magic cookie
                                        val xorPort = ((response[offset + 6].toInt() and 0xFF) shl 8) or 
                                                  (response[offset + 7].toInt() and 0xFF)
                                        addLogMessage("XOR'd port value: $xorPort")
                                        
                                        port = xorPort xor ((magicCookieBytes[0].toInt() and 0xFF) shl 8 or (magicCookieBytes[1].toInt() and 0xFF))
                                        addLogMessage("Decoded port: $port")
                                        
                                        // XOR address with magic cookie
                                        val xorAddress = ByteArray(4)
                                        for (i in 0 until 4) {
                                            xorAddress[i] = (response[offset + 8 + i].toInt() xor magicCookieBytes[i].toInt()).toByte()
                                        }
                                        
                                        // Dump the XOR'd address for debugging
                                        addLogMessage("XOR'd address bytes: ${dumpHex(ByteArray(4).apply {
                                            System.arraycopy(response, offset + 8, this, 0, 4)
                                        }, 4)}")
                                        addLogMessage("Decoded address bytes: ${dumpHex(xorAddress, 4)}")
                                        
                                        ip = InetAddress.getByAddress(xorAddress).hostAddress
                                        addLogMessage("Decoded XOR-MAPPED-ADDRESS: $ip:$port")
                                        foundAddress = true
                                        serverSuccess = true
                                        
                                        // Don't break here, continue parsing to see all attributes
                                    } else if (family == 0x02) { // IPv6
                                        addLogMessage("IPv6 address found (not supported in this implementation)")
                                    } else {
                                        addLogMessage("Unsupported address family: $family")
                                    }
                                } catch (e: Exception) {
                                    addLogMessage("Error parsing XOR-MAPPED-ADDRESS: ${e.message}")
                                    e.printStackTrace()
                                }
                            } else if (attrType == ATTR_MAPPED_ADDRESS && !foundAddress) {
                                addLogMessage("Processing MAPPED-ADDRESS attribute")
                                
                                try {
                                    // Dump the entire attribute for debugging
                                    val attrBytes = ByteArray(attrLength)
                                    System.arraycopy(response, offset + 4, attrBytes, 0, attrLength)
                                    addLogMessage("MAPPED-ADDRESS bytes: ${dumpHex(attrBytes, attrLength)}")
                                    
                                    if (attrLength < 4) {
                                        addLogMessage("MAPPED-ADDRESS attribute too short: $attrLength bytes")
                                        break
                                    }
                                    
                                    // Skip the reserved byte
                                    val family = response[offset + 5].toInt() and 0xFF
                                    addLogMessage("Address family: $family (1=IPv4, 2=IPv6)")
                                    
                                    if (family == 0x01) { // IPv4
                                        if (attrLength < 8) {
                                            addLogMessage("IPv4 MAPPED-ADDRESS attribute too short: $attrLength bytes")
                                            break
                                        }
                                        
                                        port = ((response[offset + 6].toInt() and 0xFF) shl 8) or (response[offset + 7].toInt() and 0xFF)
                                        addLogMessage("Decoded port: $port")
                                        
                                        val ipBytes = ByteArray(4)
                                        System.arraycopy(response, offset + 8, ipBytes, 0, 4)
                                        
                                        // Dump the address bytes for debugging
                                        addLogMessage("Address bytes: ${dumpHex(ipBytes, 4)}")
                                        
                                        ip = InetAddress.getByAddress(ipBytes).hostAddress
                                        addLogMessage("Decoded MAPPED-ADDRESS: $ip:$port")
                                        foundAddress = true
                                        serverSuccess = true
                                        // Don't break here because we still want to check for XOR-MAPPED-ADDRESS
                                    } else if (family == 0x02) { // IPv6
                                        addLogMessage("IPv6 address found (not supported in this implementation)")
                                    } else {
                                        addLogMessage("Unsupported address family: $family")
                                    }
                                } catch (e: Exception) {
                                    addLogMessage("Error parsing MAPPED-ADDRESS: ${e.message}")
                                    e.printStackTrace()
                                }
                            } else if (attrType == ATTR_SOFTWARE) {
                                try {
                                    val softwareBytes = ByteArray(attrLength)
                                    System.arraycopy(response, offset + 4, softwareBytes, 0, attrLength)
                                    val software = String(softwareBytes)
                                    addLogMessage("SERVER SOFTWARE: $software")
                                } catch (e: Exception) {
                                    addLogMessage("Error parsing SOFTWARE attribute: ${e.message}")
                                }
                            } else if (attrType == ATTR_ERROR_CODE) {
                                try {
                                    if (attrLength >= 4) {
                                        val errorClass = response[offset + 6].toInt() and 0x07
                                        val errorNumber = response[offset + 7].toInt() and 0xFF
                                        val errorCode = errorClass * 100 + errorNumber
                                        
                                        val reasonBytes = ByteArray(attrLength - 4)
                                        System.arraycopy(response, offset + 8, reasonBytes, 0, attrLength - 4)
                                        val reason = String(reasonBytes)
                                        
                                        addLogMessage("STUN ERROR: $errorCode - $reason")
                                    }
                                } catch (e: Exception) {
                                    addLogMessage("Error parsing ERROR-CODE attribute: ${e.message}")
                                }
                            } else {
                                addLogMessage("Skipping attribute type: ${getAttributeName(attrType)}")
                            }
                            
                            // Move to next attribute (attributes are padded to 4 bytes)
                            try {
                                val paddedLength = (attrLength + 3) and (-4) // Round up to multiple of 4
                                offset += 4 + paddedLength
                                addLogMessage("Moving to next attribute at offset $offset")
                            } catch (e: Exception) {
                                addLogMessage("Error calculating next attribute offset: ${e.message}")
                                break
                            }
                        }

                        if (foundAddress && ip != null && port != -1) {
                            addLogMessage("Successfully obtained public address: $ip:$port")
                            return@withContext STUNResponse(ip, port)
                        } else {
                            addLogMessage("Could not find address in STUN response (found address: $foundAddress, ip: $ip, port: $port)")
                        }
                    } catch (e: Exception) {
                        addLogMessage("Error parsing STUN attributes: ${e.message}")
                        e.printStackTrace()
                    }
                } catch (e: Exception) {
                    addLogMessage("General error with STUN server $server: ${e.message}")
                    e.printStackTrace()
                }
                
                if (serverSuccess) {
                    break
                }
            }
            addLogMessage("All STUN servers failed to provide a valid public address")
            null
        } catch (e: Exception) {
            addLogMessage("Critical STUN error: ${e.message}")
            e.printStackTrace()
            null
        } finally {
            try {
                socket?.close()
                addLogMessage("STUN socket closed")
            } catch (e: Exception) {
                addLogMessage("Error closing socket: ${e.message}")
            }
        }
    }
} 