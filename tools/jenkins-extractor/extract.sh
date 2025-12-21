#!/bin/bash
#
# Jenkins GDSL Extraction Script
#
# Starts a Jenkins container, waits for it to be ready, then extracts
# GDSL metadata from the /pipeline-syntax/gdsl endpoint.
#
# Usage:
#   ./extract.sh [output_dir]
#
# Output:
#   output_dir/gdsl-output.groovy  - Raw GDSL from Jenkins
#   output_dir/globals-output.html - Global variables reference
#   output_dir/metadata.json       - Parsed JSON metadata

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUTPUT_DIR="${1:-$SCRIPT_DIR/output}"
CONTAINER_NAME="jenkins-gdsl-extractor"
JENKINS_PORT=8888
MAX_WAIT_SECONDS=300

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
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

cleanup() {
    log_info "Cleaning up..."
    docker stop "$CONTAINER_NAME" 2>/dev/null || true
    docker rm "$CONTAINER_NAME" 2>/dev/null || true
}

# Set trap for cleanup
trap cleanup EXIT

# Create output directory
mkdir -p "$OUTPUT_DIR"

# Build the image if needed
log_info "Building Jenkins extractor image..."
docker build -t jenkins-extractor "$SCRIPT_DIR"

# Remove any existing container
docker rm -f "$CONTAINER_NAME" 2>/dev/null || true

# Start Jenkins
log_info "Starting Jenkins container..."
docker run -d \
    --name "$CONTAINER_NAME" \
    -p "$JENKINS_PORT:8080" \
    jenkins-extractor

# Wait for Jenkins to be ready
log_info "Waiting for Jenkins to start (max ${MAX_WAIT_SECONDS}s)..."
SECONDS_WAITED=0
while [ $SECONDS_WAITED -lt $MAX_WAIT_SECONDS ]; do
    if curl -s "http://localhost:$JENKINS_PORT/api/json" > /dev/null 2>&1; then
        log_info "Jenkins is ready after ${SECONDS_WAITED}s"
        break
    fi
    sleep 5
    SECONDS_WAITED=$((SECONDS_WAITED + 5))
    echo -n "."
done
echo ""

if [ $SECONDS_WAITED -ge $MAX_WAIT_SECONDS ]; then
    log_error "Jenkins failed to start within ${MAX_WAIT_SECONDS}s"
    docker logs "$CONTAINER_NAME"
    exit 1
fi

# Extract GDSL
log_info "Extracting GDSL..."
if curl -s "http://localhost:$JENKINS_PORT/pipeline-syntax/gdsl" > "$OUTPUT_DIR/gdsl-output.groovy"; then
    log_info "GDSL saved to $OUTPUT_DIR/gdsl-output.groovy"
else
    log_error "Failed to extract GDSL"
    exit 1
fi

# Extract globals reference
log_info "Extracting globals reference..."
if curl -s "http://localhost:$JENKINS_PORT/pipeline-syntax/globals" > "$OUTPUT_DIR/globals-output.html"; then
    log_info "Globals saved to $OUTPUT_DIR/globals-output.html"
else
    log_warn "Failed to extract globals (non-critical)"
fi

# Get Jenkins version
JENKINS_VERSION=$(curl -s -I "http://localhost:$JENKINS_PORT/" | grep -i "X-Jenkins:" | awk '{print $2}' | tr -d '\r')
log_info "Jenkins version: $JENKINS_VERSION"

# Create metadata header
cat > "$OUTPUT_DIR/extraction-info.json" << EOF
{
    "jenkinsVersion": "$JENKINS_VERSION",
    "extractedAt": "$(date -u +"%Y-%m-%dT%H:%M:%SZ")",
    "source": "docker-extraction"
}
EOF

log_info "Extraction complete!"
log_info "Output files:"
ls -la "$OUTPUT_DIR"

# Note: The GDSL to JSON conversion should be done by a separate Kotlin tool
# that uses the GdslParser class we implemented.
log_info ""
log_info "To convert GDSL to JSON, run:"
log_info "  ./gradlew :tools:jenkins-extractor:run --args=\"$OUTPUT_DIR/gdsl-output.groovy\""

