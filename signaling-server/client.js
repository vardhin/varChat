const WebSocket = require('ws');
const dgram = require('dgram');
const readline = require('readline');

// Configuration - update these values
const SIGNALING_SERVER = 'ws://test.vardhin.tech:3000'; // Change this to your server
const LOCAL_UDP_PORT = 49152 + Math.floor(Math.random() * 16383); // Random port between 49152 and 65535

// Create UDP socket
const udpSocket = dgram.createSocket('udp4');
let myPeerId = null;
let peers = [];
let activePeer = null;

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
      console.log(`Message from ${rinfo.address}:${rinfo.port}: ${data.message}`);
    }
  } catch (error) {
    console.log(`Raw UDP message from ${rinfo.address}:${rinfo.port}: ${msg.toString()}`);
  }
});

udpSocket.on('listening', () => {
  const address = udpSocket.address();
  console.log(`UDP client listening on ${address.address}:${address.port}`);
  
  // Register our UDP port with the signaling server
  if (ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify({
      type: 'register-udp',
      port: address.port
    }));
  }
  
  // Request STUN information
  requestStun();
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
        peers.push(data.peerId);
        console.log(`New peer connected: ${data.peerId}`);
        break;
        
      case 'peer-disconnected':
        peers = peers.filter(id => id !== data.peerId);
        console.log(`Peer disconnected: ${data.peerId}`);
        if (activePeer === data.peerId) {
          activePeer = null;
          console.log('Active peer disconnected');
        }
        break;
        
      case 'connection-info':
        activePeer = data.peerId;
        console.log(`Received connection info for peer ${data.peerId}`);
        console.log(`Attempting to establish direct UDP connection to ${data.ip}:${data.port}`);
        
        // Send initial UDP packets to punch a hole
        sendUdpPunchthrough(data.ip, data.port);
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

// Attempt to establish direct UDP connection
function sendUdpPunchthrough(ip, port) {
  // Send a series of UDP packets to punch a hole
  const punchMsg = JSON.stringify({
    type: 'p2p-message',
    message: 'Hole punching packet',
    peerId: myPeerId
  });
  
  console.log(`Sending UDP punch packets to ${ip}:${port}`);
  
  // Send multiple packets to increase chances of success
  for (let i = 0; i < 5; i++) {
    udpSocket.send(punchMsg, port, ip, (err) => {
      if (err) {
        console.error(`Error sending UDP punch packet ${i}:`, err);
      }
    });
  }
  
  console.log('Now try sending a message with "send <message>"');
}

// Send a message to the active peer
function sendMessage(message) {
  if (!activePeer) {
    console.log('No active peer. Please connect to a peer first.');
    return;
  }
  
  // Get the peer's connection info
  ws.send(JSON.stringify({
    type: 'request-connection',
    target: activePeer
  }));
  
  // Wait briefly to let the connection info arrive
  setTimeout(() => {
    // This would be more proper with state management, but for demo purposes:
    console.log(`Please manually type 'send ${message}' again in a moment`);
  }, 1000);
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
      if (parts.length < 2) {
        console.log('Usage: send <message>');
        break;
      }
      
      const message = parts.slice(1).join(' ');
      
      if (!activePeer) {
        console.log('No active peer. Please connect to a peer first.');
        break;
      }
      
      // Get the connection info by requesting it again
      ws.send(JSON.stringify({
        type: 'request-connection',
        target: activePeer
      }));
      
      // Wait a moment for the connection info to arrive
      setTimeout(() => {
        // In a proper implementation, we would track the connection info
        // For demo purposes we just ask them to try again in a moment
        console.log('Please try sending your message again in a moment');
      }, 1000);
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
      console.log('  connect <peerID> - Connect to a peer');
      console.log('  send <message> - Send a message to the active peer');
      console.log('  stun - Request your public endpoint info');
      console.log('  exit - Exit the application');
      console.log('  help - Show this help');
      break;
      
    default:
      console.log('Unknown command. Type "help" for available commands.');
  }
}

// Start command loop
console.log('UDP Hole Punching Client');
console.log('Type "help" for available commands');

rl.on('line', (input) => {
  handleCommand(input);
  rl.prompt();
});

rl.prompt(); 