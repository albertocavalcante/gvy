#!/bin/bash
set -e

# Resolve paths
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$(dirname "$SCRIPT_DIR")")"
DEVCONTAINER_DIR="$ROOT_DIR/.devcontainer"
CACHE_DIR="$DEVCONTAINER_DIR/cache"
JSON_FILE="$DEVCONTAINER_DIR/devcontainer.json"

# -----------------------------------------------------------------------------
# Error Handling Documentation
# -----------------------------------------------------------------------------
# Common errors you might encounter:
#
# 1. curl: (56) Failure writing output to destination
#    - This typically means the disk is full (ENOSPC: no space left on device).
#    - The Kotlin VSIX is large (~600MB). Ensure you have at least 1GB free.
#    - Check space with: df -h
#    - Clean Docker builder cache: docker builder prune
#
# 2. ENOSPC: no space left on device
#    - Host filesystem is full.
#    - Docker builds may fail with "write error" or "no space left on device".
# -----------------------------------------------------------------------------

# Check dependencies
if ! command -v jq &> /dev/null; then
    echo "Error: 'jq' is not installed. Please install it to run this script."
    exit 1
fi

# Read configuration from devcontainer.json
# Using -c to produce compact output, but we need to strip comments (//)
# because standard JSON doesn't support them, but devcontainer.json does.
# We use sed to strip lines starting with whitespace and //
VERSION=$(grep -v '^[[:space:]]*//' "$JSON_FILE" | jq -r '.build.args.KOTLIN_LSP_VERSION')
CHECKSUM=$(grep -v '^[[:space:]]*//' "$JSON_FILE" | jq -r '.build.args.KOTLIN_LSP_SHA256')

if [ "$VERSION" == "null" ] || [ "$CHECKSUM" == "null" ]; then
    echo "Error: Could not extract KOTLIN_LSP_VERSION or KOTLIN_LSP_SHA256 from $JSON_FILE"
    exit 1
fi

FILENAME="kotlin-${VERSION}.vsix"
FILE_PATH="$CACHE_DIR/$FILENAME"
URL="https://download-cdn.jetbrains.com/kotlin-lsp/${VERSION}/${FILENAME}"

echo "Configured Version: $VERSION"
echo "Target File: $FILE_PATH"

# Check if file exists and matches checksum
if [ -f "$FILE_PATH" ]; then
    echo "File exists. Verifying checksum..."
    CURRENT_SUM=$(sha256sum "$FILE_PATH" | awk '{print $1}')
    if [ "$CURRENT_SUM" == "$CHECKSUM" ]; then
        echo "✅ Cache is valid. No download needed."
        exit 0
    else
        echo "⚠️  Checksum mismatch! Cached file is invalid."
        echo "Expected: $CHECKSUM"
        echo "Found:    $CURRENT_SUM"
        rm -f "$FILE_PATH"
    fi
fi

# Download
echo "⬇️  Downloading from $URL..."
mkdir -p "$CACHE_DIR"

# Capture curl exit code to warn about disk space
set +e
curl -fSL -o "$FILE_PATH" "$URL"
CURL_EXIT=$?
set -e

if [ $CURL_EXIT -ne 0 ]; then
    echo "❌ Download failed with exit code $CURL_EXIT."
    if [ $CURL_EXIT -eq 56 ] || [ $CURL_EXIT -eq 23 ]; then
        echo "⚠️  Possible Cause: Disk Full (ENOSPC)."
        echo "   - Please check your available disk space."
        echo "   - Try running: docker system prune -a"
    fi
    rm -f "$FILE_PATH"
    exit $CURL_EXIT
fi

# Verify again
echo "Verifying checksum..."
CURRENT_SUM=$(sha256sum "$FILE_PATH" | awk '{print $1}')
if [ "$CURRENT_SUM" == "$CHECKSUM" ]; then
    echo "✅ Download successful and verified."
else
    echo "❌ Download corrupted."
    rm -f "$FILE_PATH"
    exit 1
fi
