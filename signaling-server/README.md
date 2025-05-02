# UDP Hole Punching Signaling Server

This is a simple signaling server that facilitates UDP hole punching between two peers, allowing direct peer-to-peer UDP communication even when peers are behind NAT.

## Overview

The system consists of:
1. A signaling server with WebSocket and UDP interfaces
2. Client applications that can establish direct UDP connections through NAT

## Requirements

- Node.js 14.x or higher
- npm

## Installation

```bash
npm install
```

## Server Setup

1. Update the domain name in the server configuration
2. Ensure ports 3000 (WebSocket) and 3001 (UDP) are accessible
3. Run the server:

```bash
node server.js
```

## Connecting to Cloudflare

To expose your local server through the `test.vardhin.tech` domain via Cloudflare:

1. Install Cloudflare Tunnel (cloudflared):
   ```bash
   # On Ubuntu/Debian:
   curl -L --output cloudflared.deb https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64.deb
   sudo dpkg -i cloudflared.deb
   ```

2. Authenticate with Cloudflare:
   ```bash
   cloudflared tunnel login
   ```

3. Create a tunnel:
   ```bash
   cloudflared tunnel create signaling-server
   ```

4. Configure your tunnel (create a config.yml file):
   ```yaml
   tunnel: <YOUR_TUNNEL_ID>
   credentials-file: /path/to/credentials.json
   
   ingress:
     - hostname: test.vardhin.tech
       service: http://localhost:3000
     - service: http_status:404
   ```

5. Create a DNS record:
   ```bash
   cloudflared tunnel route dns signaling-server test.vardhin.tech
   ```

6. Run the tunnel:
   ```bash
   cloudflared tunnel run signaling-server
   ```

> Important: UDP traffic requires additional Cloudflare configuration or might not be supported by Cloudflare tunnels. You might need to use a direct connection or another tunneling solution like ngrok for the UDP port.

## Client Usage

Run a client:

```bash
node client-improved.js
```

Available commands:
- `list` - Show available peers
- `connect <peerId>` - Request connection to a peer
- `send <peerId> <message>` - Send a direct UDP message to a peer
- `connections` - List established UDP connections
- `stun` - Request your public UDP endpoint info
- `help` - Show all commands
- `exit` - Exit the application

## How It Works

1. Peers connect to the signaling server via WebSocket
2. Each peer registers its UDP socket with the server
3. When a peer wants to connect to another peer, it requests the connection through the signaling server
4. The server sends each peer the other's public UDP endpoint info
5. Both peers send UDP packets to each other to punch holes in their NATs
6. Once holes are punched, peers can communicate directly via UDP without going through the server

## Limitations

- Not all NAT types support hole punching
- Some symmetrical NATs or restrictive firewalls may block this technique
- The signaling server must be accessible by both peers

## License

ISC 