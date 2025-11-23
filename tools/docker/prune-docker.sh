#!/bin/bash

# ANSI Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

echo "🧹 Pruning Docker Data (Safe Mode)..."
echo "This will remove unused data to free up space."
echo "It will NOT delete running containers or named volumes."

if ! docker ps > /dev/null 2>&1; then
    log_error "Docker is not running! Please start Docker first."
    exit 1
fi

log_info "Running 'docker system prune'..."
# Prune dangling images, stopped containers, and build cache
docker system prune -f

log_info "Pruning build cache (often fixes build errors)..."
docker builder prune -f

log_info "Checking disk space..."
df -h .

echo ""
echo "✅ Cleanup complete."
echo "If you still have issues, try 'docker system prune -a' (deletes all unused images)."
echo "If you have severe corruption, use 'tools/docker/factory-reset-docker-macos.sh'."


