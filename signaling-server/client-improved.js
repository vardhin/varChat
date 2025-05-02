const WebSocket = require('ws');
const dgram = require('dgram');
const readline = require('readline');

// Configuration - update these values
const SIGNALING_SERVER = 'ws://test.vardhin.tech:3000'; // Change this to your server
const LOCAL_UDP_PORT = 49152 + Math.floor(Math.random() * 16383); // Random port between 49152 and 65535

// State
const udpSocket = dgram.createSocket('udp4');
let myPeerId = null;
let peers = [];
let peerConnections = new Map(); // Map of peerId to their endpoint info

// Connect to signaling server
const ws = new WebSocket(SIGNALING_SERVER);

// Create readline interface for user input
const rl = readline.createInterface({
  input: process.stdin,
  output: process.stdout
});

// Setup UDP socket
udpSocket.on('error', (err) => {
  console.error(`UDP socket error:\n${err.stack}`);
  udpSocket.close();
});

udpSocket.on('message', (msg, rinfo) => {
  try {
    const data = JSON.parse(msg.toString());
    
    if (data.type === 'stun-response') {
      console.log(`Your public UDP endpoint is ${data.ip}:${data.port}`);
    } else if (data.type === 'p2p-message') {
      console.log(`[${data.peerId || 'Unknown'}] ${data.message}`);
      
      // Store the endpoint if we don't already have it
      if (data.peerId && !peerConnections.has(data.peerId)) {
        peerConnections.set(data.peerId, {
          ip: rinfo.address,
          port: rinfo.port
        });
        console.log(`Stored endpoint for peer ${data.peerId}: ${rinfo.address}:${rinfo.port}`);
      }
      
      // Send acknowledgment
      const ackMsg = JSON.stringify({
        type: 'p2p-ack',
        message: 'Message received',
        peerId: myPeerId
      });
      
      udpSocket.send(ackMsg, rinfo.port, rinfo.address);
    } else if (data.type === 'p2p-ack') {
      console.log(`Acknowledgment from ${data.peerId || 'Unknown'}`);
      
      // If this is from a peer we're trying to connect to, store their endpoint
      if (data.peerId && !peerConnections.has(data.peerId)) {
        peerConnections.set(data.peerId, {
          ip: rinfo.address,
          port: rinfo.port
        });
        console.log(`Connection with ${data.peerId} established via UDP hole punching`);
      }
    }
  } catch (error) {
    // Can't parse as JSON, just log raw message
    console.log(`Raw UDP message from ${rinfo.address}:${rinfo.port}: ${msg.toString()}`);
  }
});

udpSocket.on('listening', () => {
  const address = udpSocket.address();
  console.log(`UDP client listening on ${address.address}:${address.port}`);
  
  // Register our UDP port with the signaling server if already connected
  if (ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify({
      type: 'register-udp',
      port: address.port
    }));
  }
});

// Bind the UDP socket
udpSocket.bind(LOCAL_UDP_PORT);

// Setup WebSocket client
ws.on('open', () => {
  console.log('Connected to signaling server');
  
  // Register our UDP port with the signaling server
  if (udpSocket.address()) {
    ws.send(JSON.stringify({
      type: 'register-udp',
      port: udpSocket.address().port
    }));
  }
});

ws.on('message', (message) => {
  try {
    const data = JSON.parse(message);
    
    switch (data.type) {
      case 'id':
        myPeerId = data.peerId;
        console.log(`Assigned peer ID: ${myPeerId}`);
        break;
        
      case 'peers':
        peers = data.peers;
        console.log('Available peers:', peers.length ? peers.join(', ') : 'none');
        break;
        
      case 'new-peer':
        if (!peers.includes(data.peerId)) {
          peers.push(data.peerId);
          console.log(`New peer connected: ${data.peerId}`);
        }
        break;
        
      case 'peer-disconnected':
        peers = peers.filter(id => id !== data.peerId);
        console.log(`Peer disconnected: ${data.peerId}`);
        
        // Remove from connections map
        if (peerConnections.has(data.peerId)) {
          peerConnections.delete(data.peerId);
          console.log(`Removed connection to ${data.peerId}`);
        }
        break;
        
      case 'connection-info':
        console.log(`Received connection info for peer ${data.peerId}`);
        console.log(`Attempting to establish direct UDP connection to ${data.ip}:${data.port}`);
        
        // Store peer endpoint
        peerConnections.set(data.peerId, {
          ip: data.ip,
          port: data.port
        });
        
        // Send initial UDP packets to punch a hole
        startHolePunching(data.peerId, data.ip, data.port);
        break;
        
      case 'error':
        console.error(`Error from server: ${data.message}`);
        break;
    }
  } catch (error) {
    console.error('Error processing WebSocket message:', error);
  }
});

ws.on('close', () => {
  console.log('Disconnected from signaling server');
  process.exit(0);
});

// Send STUN request to get our public endpoint
function requestStun() {
  const stunRequest = JSON.stringify({
    type: 'stun'
  });
  
  // Send to the UDP server
  const serverHost = SIGNALING_SERVER.replace('ws://', '').replace('wss://', '').split(':')[0];
  const serverPort = 3001; // This should match UDP_PORT on server
  
  udpSocket.send(stunRequest, serverPort, serverHost, (err) => {
    if (err) {
      console.error('Error sending STUN request:', err);
    } else {
      console.log(`Sent STUN request to ${serverHost}:${serverPort}`);
    }
  });
}

// Start hole punching process
function startHolePunching(peerId, ip, port) {
  console.log(`Starting hole punching with ${peerId} at ${ip}:${port}`);
  
  // Send multiple packets with increasing delay to maximize chance of success
  const punchMsg = JSON.stringify({
    type: 'p2p-message',
    message: 'Hole punching packet',
    peerId: myPeerId
  });
  
  // Initial burst
  for (let i = 0; i < 3; i++) {
    udpSocket.send(punchMsg, port, ip);
  }
  
  // Follow up with a few more attempts with delay
  let attempts = 0;
  const maxAttempts = 5;
  
  const interval = setInterval(() => {
    if (attempts >= maxAttempts) {
      clearInterval(interval);
      console.log(`Finished hole punching attempts with ${peerId}`);
      return;
    }
    
    console.log(`Sending UDP punch packet to ${peerId} (attempt ${attempts + 1}/${maxAttempts})`);
    udpSocket.send(punchMsg, port, ip, (err) => {
      if (err) {
        console.error(`Error sending UDP punch packet:`, err);
      }
    });
    
    attempts++;
  }, 500); // 500ms between packets
}

// Send a message to a peer
function sendMessageToPeer(peerId, message) {
  if (!peerConnections.has(peerId)) {
    console.log(`No connection to peer ${peerId}. Requesting connection info...`);
    
    // Request connection info from signaling server
    ws.send(JSON.stringify({
      type: 'request-connection',
      target: peerId
    }));
    
    // Let the user know to try again
    console.log('Please try sending your message again in a moment');
    return;
  }
  
  const peerInfo = peerConnections.get(peerId);
  
  const msgPacket = JSON.stringify({
    type: 'p2p-message',
    message: message,
    peerId: myPeerId
  });
  
  udpSocket.send(msgPacket, peerInfo.port, peerInfo.ip, (err) => {
    if (err) {
      console.error(`Error sending message to ${peerId}:`, err);
    } else {
      console.log(`Message sent to ${peerId}`);
    }
  });
}

// List active connections
function listConnections() {
  console.log('Active UDP connections:');
  
  if (peerConnections.size === 0) {
    console.log('  None');
    return;
  }
  
  for (const [peerId, info] of peerConnections.entries()) {
    console.log(`  ${peerId}: ${info.ip}:${info.port}`);
  }
}

// Handle user commands
function handleCommand(input) {
  const parts = input.trim().split(' ');
  const command = parts[0].toLowerCase();
  
  switch (command) {
    case 'list':
      console.log('Available peers:', peers.length ? peers.join(', ') : 'none');
      break;
      
    case 'connect':
      const targetPeerId = parts[1];
      if (!targetPeerId || !peers.includes(targetPeerId)) {
        console.log('Invalid peer ID. Use "list" to see available peers.');
        break;
      }
      
      console.log(`Requesting connection to peer ${targetPeerId}`);
      ws.send(JSON.stringify({
        type: 'request-connection',
        target: targetPeerId
      }));
      break;
      
    case 'send':
      if (parts.length < 3) {
        console.log('Usage: send <peerId> <message>');
        break;
      }
      
      const peerId = parts[1];
      const message = parts.slice(2).join(' ');
      
      if (!peers.includes(peerId)) {
        console.log(`Peer ${peerId} not found. Use "list" to see available peers.`);
        break;
      }
      
      sendMessageToPeer(peerId, message);
      break;
      
    case 'connections':
      listConnections();
      break;
      
    case 'stun':
      requestStun();
      break;
      
    case 'exit':
      console.log('Exiting...');
      ws.close();
      udpSocket.close();
      rl.close();
      process.exit(0);
      break;
      
    case 'help':
      console.log('Available commands:');
      console.log('  list - List available peers');
      console.log('  connect <peerId> - Connect to a peer');
      console.log('  send <peerId> <message> - Send a message to a peer');
      console.log('  connections - List active UDP connections');
      console.log('  stun - Request your public endpoint info');
      console.log('  exit - Exit the application');
      console.log('  help - Show this help');
      break;
      
    default:
      console.log('Unknown command. Type "help" for available commands.');
  }
}

// Start command loop
console.log('UDP Hole Punching Client (Improved)');
console.log('Type "help" for available commands');

rl.on('line', (input) => {
  handleCommand(input);
  rl.prompt();
});

rl.prompt(); 