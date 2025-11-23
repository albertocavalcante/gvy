#!/bin/bash
set -e

# Resolve paths
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$(dirname "$SCRIPT_DIR")")"
CACHE_SCRIPT="$SCRIPT_DIR/download-vsix.sh"

# Locate devcontainer CLI
DEVCONTAINER_CLI="devcontainer"
if ! command -v $DEVCONTAINER_CLI &> /dev/null; then
    # Fallback for Homebrew install on macOS
    if [ -f "/opt/homebrew/bin/devcontainer" ]; then
        DEVCONTAINER_CLI="/opt/homebrew/bin/devcontainer"
    elif [ -f "/usr/local/bin/devcontainer" ]; then
        DEVCONTAINER_CLI="/usr/local/bin/devcontainer"
    else
        echo "Error: 'devcontainer' CLI not found."
        echo "Please install it via: brew install devcontainer (or npm install -g @devcontainers/cli)"
        exit 1
    fi
fi

echo "Using CLI: $DEVCONTAINER_CLI"

# 1. Pre-cache artifacts to speed up build
echo "Checking/Updating local cache..."
if [ -f "$CACHE_SCRIPT" ]; then
    "$CACHE_SCRIPT"
else
    echo "Warning: Cache script not found at $CACHE_SCRIPT. Skipping pre-cache."
fi

# 2. Build the container
echo "Building Dev Container..."
# Use debug log level to see more details
"$DEVCONTAINER_CLI" build --workspace-folder "$ROOT_DIR" --log-level debug

echo "✅ Build complete!"

