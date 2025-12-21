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

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUTPUT_DIR="${1:-$SCRIPT_DIR/output}"
CONTAINER_NAME="jenkins-gdsl-extractor"
JENKINS_PORT=8888
MAX_WAIT_SECONDS=300
# Additional wait time for Pipeline Syntax plugin after Jenkins core is ready
MAX_PLUGIN_WAIT_SECONDS=180

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

sha256_file() {
    local file="$1"
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$file" | awk '{print $1}'
        return 0
    fi
    if command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$file" | awk '{print $1}'
        return 0
    fi
    log_error "No SHA-256 tool found (need sha256sum or shasum)"
    return 1
}

# Check if content is valid GDSL (starts with // comment, not HTML)
is_valid_gdsl() {
    local content="$1"
    # Trim leading whitespace (GDSL may start with newlines)
    content="${content#"${content%%[![:space:]]*}"}"
    # Valid GDSL starts with "//" (Groovy comment) not "<" (HTML)
    if [[ "$content" == //* ]]; then
        return 0
    fi
    return 1
}

# Wait for the GDSL endpoint to return actual GDSL content
wait_for_gdsl_endpoint() {
    local max_seconds=$1
    local seconds_waited=0

    log_info "Waiting for Pipeline Syntax plugin to initialize (max ${max_seconds}s)..."

    while [ $seconds_waited -lt $max_seconds ]; do
        # Fetch the GDSL endpoint
        local response
        response=$(curl -s "http://localhost:$JENKINS_PORT/pipeline-syntax/gdsl" 2>/dev/null || echo "")

        # Check if we got valid GDSL content
        if is_valid_gdsl "$response"; then
            log_info "Pipeline Syntax plugin ready after ${seconds_waited}s"
            return 0
        fi

        sleep 5
        seconds_waited=$((seconds_waited + 5))
        echo -n "."
    done
    echo ""
    return 1
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
docker build \
    --build-arg "JENKINS_TAG=${JENKINS_TAG:-lts-jdk21}" \
    -t jenkins-extractor \
    "$SCRIPT_DIR"

# Remove any existing container
docker rm -f "$CONTAINER_NAME" 2>/dev/null || true

# Start Jenkins
log_info "Starting Jenkins container..."
docker run -d \
    --name "$CONTAINER_NAME" \
    -p "$JENKINS_PORT:8080" \
    jenkins-extractor

# Phase 1: Wait for Jenkins core to be ready
log_info "Waiting for Jenkins core to start (max ${MAX_WAIT_SECONDS}s)..."
SECONDS_WAITED=0
while [ $SECONDS_WAITED -lt $MAX_WAIT_SECONDS ]; do
    if curl -s "http://localhost:$JENKINS_PORT/api/json" > /dev/null 2>&1; then
        log_info "Jenkins core ready after ${SECONDS_WAITED}s"
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

# Phase 2: Wait for Pipeline Syntax plugin to be ready
# The /pipeline-syntax/gdsl endpoint is provided by the workflow-cps plugin
# and may take additional time to initialize after Jenkins core is ready
if ! wait_for_gdsl_endpoint $MAX_PLUGIN_WAIT_SECONDS; then
    log_error "Pipeline Syntax plugin failed to initialize within ${MAX_PLUGIN_WAIT_SECONDS}s"
    log_error "This may indicate the workflow-cps plugin is not installed or failed to load"
    docker logs "$CONTAINER_NAME" | tail -100
    exit 1
fi

# Extract GDSL with validation
log_info "Extracting GDSL..."
GDSL_CONTENT=$(curl -s "http://localhost:$JENKINS_PORT/pipeline-syntax/gdsl")

# Validate the content is actual GDSL, not HTML error page
if ! is_valid_gdsl "$GDSL_CONTENT"; then
    log_error "Extracted content is not valid GDSL (possibly HTML error page)"
    log_error "First 200 characters: ${GDSL_CONTENT:0:200}"
    exit 1
fi

# Save the validated GDSL
echo "$GDSL_CONTENT" > "$OUTPUT_DIR/gdsl-output.groovy"
log_info "GDSL saved to $OUTPUT_DIR/gdsl-output.groovy"

# Count extracted methods for validation
METHOD_COUNT=$(grep -c "method(name:" "$OUTPUT_DIR/gdsl-output.groovy" || echo "0")
log_info "Extracted $METHOD_COUNT method definitions"

if [ "$METHOD_COUNT" -lt 5 ]; then
    log_warn "Unusually low method count ($METHOD_COUNT). GDSL may be incomplete."
fi

# Extract globals reference with validation
log_info "Extracting globals reference..."
GLOBALS_CONTENT=$(curl -s "http://localhost:$JENKINS_PORT/pipeline-syntax/globals")

# Check if globals is actual HTML content (should contain <html or <!DOCTYPE)
if [[ "$GLOBALS_CONTENT" == *"<html"* ]] || [[ "$GLOBALS_CONTENT" == *"<!DOCTYPE"* ]]; then
    # Check it's not the "Jenkins is starting" page
    if [[ "$GLOBALS_CONTENT" == *"Jenkins is getting ready"* ]]; then
        log_warn "Globals endpoint returned startup page, skipping"
    else
        echo "$GLOBALS_CONTENT" > "$OUTPUT_DIR/globals-output.html"
        log_info "Globals saved to $OUTPUT_DIR/globals-output.html"
    fi
else
    log_warn "Globals endpoint did not return HTML content, skipping"
fi

# Get Jenkins version
JENKINS_VERSION=$(curl -s -I "http://localhost:$JENKINS_PORT/" | grep -i "X-Jenkins:" | awk '{print $2}' | tr -d '\r')
log_info "Jenkins version: $JENKINS_VERSION"

# Count properties for metadata
PROPERTY_COUNT=$(grep -c "property(name:" "$OUTPUT_DIR/gdsl-output.groovy" || echo "0")

# Create deterministic extraction metadata (used by the converter + audit trail)
PLUGINS_MANIFEST_SHA256=$(sha256_file "$SCRIPT_DIR/plugins.txt")
GDSL_SHA256=$(sha256_file "$OUTPUT_DIR/gdsl-output.groovy")
EXTRACTED_AT="${EXTRACTED_AT:-1970-01-01T00:00:00Z}"

export PLUGINS_MANIFEST_SHA256
export GDSL_SHA256
export EXTRACTED_AT

cat > "$OUTPUT_DIR/extraction-info.json" << EOF
{
    "jenkinsVersion": "$JENKINS_VERSION",
    "extractedAt": "$EXTRACTED_AT",
    "pluginsManifestSha256": "$PLUGINS_MANIFEST_SHA256",
    "gdslSha256": "$GDSL_SHA256",
    "extractorVersion": "1.0.0",
    "statistics": {
        "methodCount": $METHOD_COUNT,
        "propertyCount": $PROPERTY_COUNT
    }
}
EOF

log_info "Extraction complete!"
log_info "Output files:"
ls -la "$OUTPUT_DIR"

# Validate extraction was successful
if [ "$METHOD_COUNT" -gt 0 ]; then
    log_info "SUCCESS: Extracted $METHOD_COUNT methods and $PROPERTY_COUNT properties"
else
    log_error "FAILURE: No methods extracted. Check Jenkins logs for errors."
    docker logs "$CONTAINER_NAME" | tail -50
    exit 1
fi

log_info ""
log_info "Converting GDSL to JSON..."

REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
JSON_OUTPUT_DIR="$OUTPUT_DIR/json"
mkdir -p "$JSON_OUTPUT_DIR"

"$REPO_ROOT/gradlew" :tools:jenkins-extractor:convertGdsl \
    -PgdslFile="$OUTPUT_DIR/gdsl-output.groovy" \
    -PoutputDir="$JSON_OUTPUT_DIR" \
    -PjenkinsVersion="$JENKINS_VERSION" \
    -PpluginId="jenkins" \
    -PpluginVersion="$JENKINS_VERSION" \
    -PpluginDisplayName="Jenkins (combined)" \
    --console=plain

log_info "JSON written to $JSON_OUTPUT_DIR/jenkins.json"
