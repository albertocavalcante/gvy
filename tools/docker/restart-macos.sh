#!/bin/bash

# ANSI Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Logging Helper Functions
log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

FORCE_MODE=false
if [[ "$1" == "--force" || "$1" == "-f" ]]; then
    FORCE_MODE=true
fi

log_info "🐳 Restarting Docker Desktop..."
if [ "$FORCE_MODE" = false ]; then
    log_info "(Running in Graceful Mode. Use --force or -f to kill immediately)"
fi

# 1. Quit Logic
log_info "Stopping Docker processes..."

if [ "$FORCE_MODE" = true ]; then
    log_warn "🔥 Force killing immediately..."
    pkill -9 -f Docker 2>/dev/null || true
    killall -9 Docker 2>/dev/null || true
    killall -9 "Docker Desktop" 2>/dev/null || true
else
    # Graceful attempt first
    osascript -e 'quit app "Docker"' 2>/dev/null || true
    sleep 10

    # Standard cleanup after graceful quit attempt
    pkill -f Docker 2>/dev/null || true
fi

# 2. Cleanup leftovers (Always run this ensuring clean slate)
if pgrep -f Docker > /dev/null; then
    log_info "Cleaning up remaining processes..."
    killall Docker 2>/dev/null || true
    killall "Docker Desktop" 2>/dev/null || true
    killall com.docker.backend 2>/dev/null || true
    killall com.docker.hyperkit 2>/dev/null || true
fi

# 3. Wait a moment
log_info "Waiting for cleanup..."
sleep 3

# 4. Start
log_info "Starting Docker Desktop..."

if [ ! -d "/Applications/Docker.app" ]; then
    log_error "Docker.app not found in /Applications. Is Docker Desktop installed?"
    exit 1
fi

# We use a loop to try 'open' because sometimes it times out (-1712) but succeeds on retry.
MAX_LAUNCH_RETRIES=3
LAUNCH_COUNT=0

while [ $LAUNCH_COUNT -lt $MAX_LAUNCH_RETRIES ]; do
    open /Applications/Docker.app 2>/dev/null || true
    sleep 5

    if pgrep -f "Docker" > /dev/null; then
        log_success "Docker Desktop process started."
        break
    fi

    log_warn "Docker process not found. Retrying launch..."
    LAUNCH_COUNT=$((LAUNCH_COUNT+1))
done

if [ $LAUNCH_COUNT -ge $MAX_LAUNCH_RETRIES ]; then
    log_error "Failed to launch Docker after $MAX_LAUNCH_RETRIES attempts."
    exit 1
fi

log_info "⏳ Waiting for Docker daemon to become available..."
MAX_RETRIES=60
COUNT=0
DOCKER_LOG="$HOME/Library/Containers/com.docker.docker/Data/log/vm/dockerd.log"

while ! docker ps > /dev/null 2>&1; do
    sleep 2
    COUNT=$((COUNT+1))

    # Every 5th retry (10 seconds), show status from logs
    if [ $((COUNT % 5)) -eq 0 ]; then
        if [ -f "$DOCKER_LOG" ]; then
            echo ""
            echo -e "${BLUE}--- Recent Docker Logs (${DOCKER_LOG}) ---${NC}"
            tail -n 3 "$DOCKER_LOG"
            echo -e "${BLUE}--------------------------${NC}"
        else
            echo -n "."
        fi
    else
        echo -n "."
    fi

    if [ $COUNT -ge $MAX_RETRIES ]; then
        echo ""
        log_error "Timed out waiting for Docker to start."
        exit 1
    fi
done

echo ""
log_success "Docker is up and running!"
