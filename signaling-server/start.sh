#!/bin/bash

# Check if tmux is installed
if ! command -v tmux &> /dev/null; then
    echo "tmux is not installed. Installing now..."
    
    # Detect operating system
    if [[ "$OSTYPE" == "linux-gnu"* ]]; then
        # Linux
        if command -v apt-get &> /dev/null; then
            # Debian/Ubuntu
            sudo apt-get update
            sudo apt-get install -y tmux
        elif command -v yum &> /dev/null; then
            # CentOS/RHEL
            sudo yum install -y tmux
        else
            echo "Unsupported Linux distribution. Please install tmux manually."
            exit 1
        fi
    elif [[ "$OSTYPE" == "darwin"* ]]; then
        # macOS
        brew install tmux
    else
        echo "Unsupported operating system. Please install tmux manually."
        exit 1
    fi
fi

# Check if cloudflared is running
if ! command -v cloudflared &> /dev/null; then
    echo "cloudflared is not installed. Please run ./setup-cloudflare.sh first."
    exit 1
fi

# Create a new tmux session
tmux new-session -d -s signaling-server

# Split the window horizontally
tmux split-window -h -t signaling-server

# Run the signaling server in the left pane
tmux send-keys -t signaling-server:0.0 "cd $(pwd) && npm start" C-m

# Run cloudflared in the right pane
tmux send-keys -t signaling-server:0.1 "cloudflared tunnel run signaling-server" C-m

# Attach to the tmux session
tmux attach-session -t signaling-server

echo "Server and tunnel started. Press Ctrl+B then D to detach from the session."
echo "To reattach to the session, run: tmux attach-session -t signaling-server" 