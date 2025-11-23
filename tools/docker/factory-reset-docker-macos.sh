#!/bin/bash

# ANSI Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

echo -e "${RED}⚠️  WARNING: THIS SCRIPT IS FOR MACOS ONLY ⚠️${NC}"
echo -e "${RED}⚠️  IT WILL FACTORY RESET DOCKER DESKTOP AND DELETE ALL DATA ⚠️${NC}"
echo "This is a destructive operation to fix severe disk corruption."
echo "Try running 'tools/docker/prune-docker.sh' first if you just want to clear space."
echo ""
echo "This will delete:"
echo "  - All Docker images"
echo "  - All containers"
echo "  - All local volumes"
echo "  - Docker Desktop settings"
echo ""
read -p "Are you sure you want to proceed with FACTORY RESET? (type 'RESET' to confirm): " CONFIRM

if [ "$CONFIRM" != "RESET" ]; then
    echo "Operation cancelled. No changes made."
    exit 1
fi

log_info "Stopping Docker Desktop..."
pkill -f Docker 2>/dev/null || true
killall Docker 2>/dev/null || true
killall "Docker Desktop" 2>/dev/null || true
killall com.docker.backend 2>/dev/null || true

log_info "Cleaning Docker configuration and data..."

# Known paths for Docker on macOS
DIRS_TO_CLEAN=(
    "$HOME/Library/Containers/com.docker.docker"
    "$HOME/Library/Application Support/Docker Desktop"
    "$HOME/Library/Group Containers/group.com.docker"
    "$HOME/.docker"
)

for DIR in "${DIRS_TO_CLEAN[@]}"; do
    if [ -d "$DIR" ]; then
        log_info "Removing $DIR..."
        rm -rf "$DIR"
    fi
done

log_success "Docker data purged."

log_info "Restarting Docker Desktop (it will re-initialize)..."
open /Applications/Docker.app

log_info "⏳ Waiting for Docker to initialize (this may take a minute)..."
MAX_RETRIES=120
COUNT=0

while ! docker ps > /dev/null 2>&1; do
    sleep 2
    COUNT=$((COUNT+1))
    echo -n "."
    
    if [ $COUNT -ge $MAX_RETRIES ]; then
        echo ""
        log_error "Timed out waiting for Docker to start."
        exit 1
    fi
done

echo ""
log_success "Docker is reset and running! You can now rebuild your dev container."

