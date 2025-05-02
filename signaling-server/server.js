const express = require('express');
const http = require('http');
const WebSocket = require('ws');
const dgram = require('dgram');
const { v4: uuidv4 } = require('uuid');

// Configuration
const WEB_PORT = process.env.WEB_PORT || 3000;
const UDP_PORT = process.env.UDP_PORT || 3001;

// Track connected peers
const peers = new Map();

// Create Express app
const app = express();
const server = http.createServer(app);
const wss = new WebSocket.Server({ server });

// Simple status page
app.get('/', (req, res) => {
  res.send(`
    <html>
      <head><title>UDP Hole Punching Server</title></head>
      <body>
        <h1>UDP Hole Punching Signaling Server</h1>
        <p>Status: Running</p>
        <p>Connected peers: ${peers.size}</p>
      </body>
    </html>
  `);
});

// Setup WebSocket signaling
wss.on('connection', (ws, req) => {
  const peerId = uuidv4();
  console.log(`New peer connected: ${peerId}`);
  
  // Store peer connection
  peers.set(peerId, { ws, candidates: [] });
  
  // Send the peer its ID
  ws.send(JSON.stringify({ type: 'id', peerId }));
  
  // Send the peer a list of all other peers
  const peerList = Array.from(peers.keys()).filter(id => id !== peerId);
  ws.send(JSON.stringify({ type: 'peers', peers: peerList }));
  
  // Notify other peers about this new peer
  for (const [id, peer] of peers.entries()) {
    if (id !== peerId) {
      peer.ws.send(JSON.stringify({ type: 'new-peer', peerId }));
    }
  }
  
  ws.on('message', (message) => {
    try {
      const data = JSON.parse(message);
      
      switch (data.type) {
        case 'signal':
          // Forward signaling data to the target peer
          if (data.target && peers.has(data.target)) {
            peers.get(data.target).ws.send(JSON.stringify({
              type: 'signal',
              signal: data.signal,
              source: peerId
            }));
          }
          break;
          
        case 'register-udp':
          // Store UDP endpoint info
          if (peers.has(peerId)) {
            peers.get(peerId).udpPort = data.port;
            peers.get(peerId).publicIp = req.socket.remoteAddress;
            console.log(`Peer ${peerId} registered UDP endpoint: ${req.socket.remoteAddress}:${data.port}`);
          }
          break;
          
        case 'request-connection':
          // Request connection to another peer
          if (data.target && peers.has(data.target)) {
            const targetPeer = peers.get(data.target);
            const sourcePeer = peers.get(peerId);
            
            if (targetPeer.udpPort && sourcePeer.udpPort) {
              // Send each peer the other's public endpoint
              targetPeer.ws.send(JSON.stringify({
                type: 'connection-info',
                peerId: peerId,
                ip: sourcePeer.publicIp,
                port: sourcePeer.udpPort
              }));
              
              sourcePeer.ws.send(JSON.stringify({
                type: 'connection-info',
                peerId: data.target,
                ip: targetPeer.publicIp,
                port: targetPeer.udpPort
              }));
              
              console.log(`Connection requested between ${peerId} and ${data.target}`);
            } else {
              ws.send(JSON.stringify({
                type: 'error',
                message: 'One or both peers have not registered their UDP endpoints'
              }));
            }
          } else {
            ws.send(JSON.stringify({
              type: 'error',
              message: 'Target peer not found'
            }));
          }
          break;
      }
    } catch (error) {
      console.error('Error processing message:', error);
    }
  });
  
  ws.on('close', () => {
    console.log(`Peer disconnected: ${peerId}`);
    
    // Notify other peers
    for (const [id, peer] of peers.entries()) {
      if (id !== peerId) {
        peer.ws.send(JSON.stringify({
          type: 'peer-disconnected',
          peerId
        }));
      }
    }
    
    // Remove peer from the list
    peers.delete(peerId);
  });
});

// Create UDP server for STUN-like functionality
const udpServer = dgram.createSocket('udp4');

udpServer.on('error', (err) => {
  console.error(`UDP server error:\n${err.stack}`);
  udpServer.close();
});

udpServer.on('message', (msg, rinfo) => {
  try {
    const data = JSON.parse(msg.toString());
    
    if (data.type === 'stun') {
      // Send back the client's public endpoint info
      const response = JSON.stringify({
        type: 'stun-response',
        ip: rinfo.address,
        port: rinfo.port
      });
      
      udpServer.send(response, rinfo.port, rinfo.address);
      console.log(`Sent STUN response to ${rinfo.address}:${rinfo.port}`);
    }
  } catch (error) {
    console.error('Error processing UDP message:', error);
  }
});

udpServer.on('listening', () => {
  const address = udpServer.address();
  console.log(`UDP server listening on ${address.address}:${address.port}`);
});

// Start servers
udpServer.bind(UDP_PORT);

server.listen(WEB_PORT, () => {
  console.log(`WebSocket server running on port ${WEB_PORT}`);
}); 