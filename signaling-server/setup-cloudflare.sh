#!/bin/bash

# Check if cloudflared is installed
if ! command -v cloudflared &> /dev/null; then
    echo "cloudflared is not installed. Installing now..."
    
    # Detect operating system
    if [[ "$OSTYPE" == "linux-gnu"* ]]; then
        # Linux
        if command -v apt-get &> /dev/null; then
            # Debian/Ubuntu
            curl -L --output cloudflared.deb https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64.deb
            sudo dpkg -i cloudflared.deb
            rm cloudflared.deb
        elif command -v yum &> /dev/null; then
            # CentOS/RHEL
            curl -L --output cloudflared.rpm https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-x86_64.rpm
            sudo rpm -i cloudflared.rpm
            rm cloudflared.rpm
        else
            echo "Unsupported Linux distribution. Please install cloudflared manually."
            exit 1
        fi
    elif [[ "$OSTYPE" == "darwin"* ]]; then
        # macOS
        brew install cloudflare/cloudflare/cloudflared
    else
        echo "Unsupported operating system. Please install cloudflared manually."
        exit 1
    fi
fi

echo "cloudflared installed successfully."

# Check if user is already logged in
echo "Checking Cloudflare authentication..."
if ! cloudflared tunnel list &> /dev/null; then
    echo "You need to authenticate with Cloudflare."
    cloudflared tunnel login
else
    echo "Already authenticated with Cloudflare."
fi

# Set up tunnel
echo "Setting up Cloudflare tunnel..."

# Create tunnel
echo "Creating tunnel 'signaling-server'..."
TUNNEL_ID=$(cloudflared tunnel create signaling-server | grep -oP 'Created tunnel signaling-server with id \K[0-9a-f-]+')

if [ -z "$TUNNEL_ID" ]; then
    echo "Failed to create tunnel or extract tunnel ID. Check if the tunnel already exists."
    # Try to get the ID of an existing tunnel
    TUNNEL_ID=$(cloudflared tunnel list | grep signaling-server | awk '{print $1}')
    
    if [ -z "$TUNNEL_ID" ]; then
        echo "Could not find an existing tunnel. Exiting."
        exit 1
    else
        echo "Found existing tunnel with ID: $TUNNEL_ID"
    fi
else
    echo "Created tunnel with ID: $TUNNEL_ID"
fi

# Create config file
echo "Creating tunnel configuration..."
mkdir -p ~/.cloudflared

cat > ~/.cloudflared/config.yml << EOF
tunnel: $TUNNEL_ID
credentials-file: ~/.cloudflared/${TUNNEL_ID}.json

ingress:
  - hostname: test.vardhin.tech
    service: http://localhost:3000
  - service: http_status:404
EOF

echo "Creating DNS record..."
cloudflared tunnel route dns signaling-server test.vardhin.tech

echo "Configuration complete!"
echo ""
echo "To start the tunnel, run:"
echo "cloudflared tunnel run signaling-server"
echo ""
echo "To start the signaling server, run:"
echo "npm start"
echo ""
echo "NOTE: UDP traffic might not be tunneled by Cloudflare. Consider using port forwarding"
echo "or a dedicated UDP tunneling solution for the UDP port (3001)."

# Make the script executable
chmod +x setup-cloudflare.sh 