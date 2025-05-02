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

class STUNClient {
    private val TAG = "STUNClient"
    private val STUN_SERVERS = listOf(
        "stun1.l.google.com:19302",
        "stun2.l.google.com:19302",
        "stun3.l.google.com:19302",
        "stun4.l.google.com:19302"
    )

    // STUN attribute types (defined in RFC 5389)
    private val ATTR_XOR_MAPPED_ADDRESS = 0x0020
    private val ATTR_MAPPED_ADDRESS = 0x0001
    
    // STUN magic cookie (fixed value in network byte order)
    private val MAGIC_COOKIE_INT = 0x2112A442

    data class STUNResponse(
        val publicIP: String,
        val publicPort: Int
    )

    private fun dumpHex(bytes: ByteArray, length: Int): String {
        val sb = StringBuilder()
        for (i in 0 until minOf(length, bytes.size)) {
            sb.append(String.format("%02X ", bytes[i]))
            if ((i + 1) % 16 == 0) sb.append("\n")
        }
        return sb.toString()
    }

    suspend fun getPublicAddress(): STUNResponse? = withContext(Dispatchers.IO) {
        var socket: DatagramSocket? = null
        try {
            Log.d(TAG, "Starting STUN client process")
            
            // Create socket
            try {
                socket = DatagramSocket()
                socket.soTimeout = 5000
                val localPort = socket.localPort
                Log.d(TAG, "STUN client using local port: $localPort")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create socket: ${e.message}")
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
                
                Log.d(TAG, "STUN request prepared with transaction ID: ${dumpHex(transactionId, transactionId.size)}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to prepare STUN request: ${e.message}")
                e.printStackTrace()
                return@withContext null
            }

            for (server in STUN_SERVERS) {
                Log.d(TAG, "Trying STUN server: $server")
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
                        Log.d(TAG, "Resolved STUN server $host to ${address.hostAddress}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to resolve STUN server address $server: ${e.message}")
                        continue
                    }
                    
                    // Step 2: Send STUN request
                    try {
                        Log.d(TAG, "Sending STUN request to $server")
                        val packet = DatagramPacket(request, request.size, address, serverPort)
                        socket.send(packet)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send STUN request to $server: ${e.message}")
                        continue
                    }

                    // Step 3: Receive response
                    val response = ByteArray(1024)
                    val responsePacket = DatagramPacket(response, response.size)
                    try {
                        Log.d(TAG, "Waiting for STUN response from $server")
                        socket.receive(responsePacket)
                        Log.d(TAG, "Received response from STUN server $server (${responsePacket.length} bytes)")
                        Log.d(TAG, "Response hex dump:\n${dumpHex(response, responsePacket.length)}")
                    } catch (e: SocketTimeoutException) {
                        Log.e(TAG, "Timeout waiting for response from STUN server $server")
                        continue
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to receive response from STUN server $server: ${e.message}")
                        continue
                    }

                    // Step 4: Validate response is proper STUN response
                    try {
                        // Check if it's a valid STUN response (first 2 bits should be 00)
                        if ((response[0].toInt() and 0xC0) != 0) {
                            Log.e(TAG, "Not a valid STUN response from $server, invalid first 2 bits")
                            continue
                        }

                        // Check if it's a Binding Response (0x0101)
                        if (response[0].toInt() != 0x01 || response[1].toInt() != 0x01) {
                            Log.e(TAG, "Not a Binding Response from $server: ${response[0]}, ${response[1]}")
                            continue
                        }

                        // Get message length
                        val messageLength = ((response[2].toInt() and 0xFF) shl 8) or (response[3].toInt() and 0xFF)
                        Log.d(TAG, "STUN message length: $messageLength bytes")
                        
                        // Verify the transaction ID
                        var idMatch = true
                        for (i in 0 until 12) {
                            if (response[i + 8] != request[i + 8]) {
                                Log.e(TAG, "Transaction ID mismatch at position $i")
                                idMatch = false
                                break
                            }
                        }
                        if (!idMatch) {
                            Log.e(TAG, "Transaction ID mismatch in response from $server")
                            continue
                        }
                        
                        Log.d(TAG, "Transaction ID in response matches request")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error validating STUN response from $server: ${e.message}")
                        continue
                    }

                    // Step 5: Parse STUN attributes
                    try {
                        var offset = 20 // Skip STUN header
                        var foundAddress = false
                        var ip: String? = null
                        var port: Int = -1
                        val messageLength = ((response[2].toInt() and 0xFF) shl 8) or (response[3].toInt() and 0xFF)

                        while (offset < 20 + messageLength) {
                            // Get attribute type and length
                            val attrType = ((response[offset].toInt() and 0xFF) shl 8) or (response[offset + 1].toInt() and 0xFF)
                            val attrLength = ((response[offset + 2].toInt() and 0xFF) shl 8) or (response[offset + 3].toInt() and 0xFF)
                            
                            Log.d(TAG, "Attribute type: 0x${Integer.toHexString(attrType)}, length: $attrLength at offset $offset")

                            if (attrType == ATTR_XOR_MAPPED_ADDRESS) {
                                Log.d(TAG, "Found XOR-MAPPED-ADDRESS attribute")
                                
                                try {
                                    // Skip the reserved byte
                                    val family = response[offset + 5].toInt() and 0xFF
                                    
                                    if (family == 0x01) { // IPv4
                                        // XOR port with first 2 bytes of magic cookie
                                        val xorPort = ((response[offset + 6].toInt() and 0xFF) shl 8) or 
                                                  (response[offset + 7].toInt() and 0xFF)
                                        port = xorPort xor ((magicCookieBytes[0].toInt() and 0xFF) shl 8 or (magicCookieBytes[1].toInt() and 0xFF))
                                        
                                        // XOR address with magic cookie
                                        val xorAddress = ByteArray(4)
                                        for (i in 0 until 4) {
                                            xorAddress[i] = (response[offset + 8 + i].toInt() xor magicCookieBytes[i].toInt()).toByte()
                                        }
                                        ip = InetAddress.getByAddress(xorAddress).hostAddress
                                        Log.d(TAG, "Decoded XOR-MAPPED-ADDRESS: $ip:$port")
                                        foundAddress = true
                                        serverSuccess = true
                                        break
                                    } else if (family == 0x02) { // IPv6
                                        Log.d(TAG, "IPv6 address found (not supported in this implementation)")
                                    } else {
                                        Log.d(TAG, "Unsupported address family: $family")
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error parsing XOR-MAPPED-ADDRESS: ${e.message}")
                                    e.printStackTrace()
                                }
                            } else if (attrType == ATTR_MAPPED_ADDRESS && !foundAddress) {
                                Log.d(TAG, "Found MAPPED-ADDRESS attribute")
                                
                                try {
                                    // Skip the reserved byte
                                    val family = response[offset + 5].toInt() and 0xFF
                                    
                                    if (family == 0x01) { // IPv4
                                        port = ((response[offset + 6].toInt() and 0xFF) shl 8) or (response[offset + 7].toInt() and 0xFF)
                                        val ipBytes = ByteArray(4)
                                        System.arraycopy(response, offset + 8, ipBytes, 0, 4)
                                        ip = InetAddress.getByAddress(ipBytes).hostAddress
                                        Log.d(TAG, "Decoded MAPPED-ADDRESS: $ip:$port")
                                        foundAddress = true
                                        serverSuccess = true
                                        // Don't break here because we still want to check for XOR-MAPPED-ADDRESS
                                    } else if (family == 0x02) { // IPv6
                                        Log.d(TAG, "IPv6 address found (not supported in this implementation)")
                                    } else {
                                        Log.d(TAG, "Unsupported address family: $family")
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error parsing MAPPED-ADDRESS: ${e.message}")
                                    e.printStackTrace()
                                }
                            } else {
                                Log.d(TAG, "Skipping attribute type: 0x${Integer.toHexString(attrType)}")
                            }
                            
                            // Move to next attribute (attributes are padded to 4 bytes)
                            try {
                                val paddedLength = (attrLength + 3) and (-4); // Round up to multiple of 4
                                offset += 4 + paddedLength;
                            } catch (e: Exception) {
                                Log.e(TAG, "Error calculating next attribute offset: ${e.message}")
                                break
                            }
                        }

                        if (foundAddress && ip != null && port != -1) {
                            Log.d(TAG, "Successfully obtained public address from $server: $ip:$port")
                            return@withContext STUNResponse(ip, port)
                        } else {
                            Log.e(TAG, "Could not find address in STUN response from $server")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing STUN attributes from $server: ${e.message}")
                        e.printStackTrace()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "General error with STUN server $server: ${e.message}")
                    e.printStackTrace()
                }
                
                if (serverSuccess) {
                    break
                }
            }
            Log.e(TAG, "All STUN servers failed to provide a valid public address")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Critical STUN error: ${e.message}")
            e.printStackTrace()
            null
        } finally {
            try {
                socket?.close()
                Log.d(TAG, "STUN socket closed")
            } catch (e: Exception) {
                Log.e(TAG, "Error closing socket: ${e.message}")
            }
        }
    }
} 