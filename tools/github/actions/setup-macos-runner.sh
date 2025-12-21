#!/bin/bash
# setup-macos-runner.sh - Setup GitHub Actions self-hosted runner on macOS
#
# Auto-mode (default): Uses gh CLI to generate token and installs service
# Manual mode: Provide token explicitly
#
# Usage:
#   ./setup-macos-runner.sh [OPTIONS] [TOKEN]
#
# Options:
#   --auto          Explicit auto-mode (default)
#   --no-svc        Do NOT install/start background service in auto-mode
#   --version VER   Runner version (default: 2.329.0)
#   --name NAME     Custom runner name
#   --labels LABELS Extra labels
#   --dir PATH      Custom directory

set -e

# Configuration
DEFAULT_RUNNER_VERSION="2.329.0"
REPO_OWNER="albertocavalcante"
REPO_NAME="groovy-lsp"
REPO_URL="https://github.com/${REPO_OWNER}/${REPO_NAME}"

# Detect Architecture
ARCH_NAME=$(uname -m)
if [ "$ARCH_NAME" = "x86_64" ]; then
    RUNNER_ARCH="x64"
elif [ "$ARCH_NAME" = "arm64" ]; then
    RUNNER_ARCH="arm64"
else
    echo "Error: Unsupported architecture: $ARCH_NAME"
    exit 1
fi

generate_runner_name() {
    local hostname_short
    hostname_short=$(hostname -s | tr '[:upper:]' '[:lower:]' | tr -cd '[:alnum:]-')
    local rand_suffix
    rand_suffix=$(LC_ALL=C tr -dc 'a-z0-9' < /dev/urandom | head -c 4)
    echo "groovy-lsp-macos-${hostname_short}-${rand_suffix}"
}

generate_workspace_dir() {
    local rand_suffix
    rand_suffix=$(LC_ALL=C tr -dc 'a-z0-9' < /dev/urandom | head -c 6)
    echo "$HOME/.gha-runners/groovy-lsp-${rand_suffix}"
}

show_help() {
    cat << EOF
GitHub Actions Self-Hosted Runner Setup for macOS

USAGE:
    $(basename "$0") [OPTIONS] [TOKEN]

MODES:
    (no args)       Auto-mode: Generate token + Install Service (default)
    <TOKEN>         Manual mode: Use provided token

OPTIONS:
    --count N       Create N runners (default: 1)
    --no-svc        Skip service installation (auto-mode only)
    --version VER   Set runner version (default: $DEFAULT_RUNNER_VERSION)
    --name NAME     Custom runner name (suffix added for multiple)
    --labels EXTRA  Additional labels (comma-separated)
    --dir PATH      Custom runner directory (suffix added for multiple)
    --help          Show this help message

EXAMPLES:
    # Create 5 runners at once
    $(basename "$0") --count 5

    # Create 3 runners without service
    $(basename "$0") --count 3 --no-svc
EOF
}

# Parse arguments
AUTO_MODE=true
INSTALL_SVC=true
RUNNER_VERSION="$DEFAULT_RUNNER_VERSION"
RUNNER_COUNT=1
TOKEN=""
RUNNER_NAME=""
EXTRA_LABELS=""
RUNNER_DIR=""

while [[ $# -gt 0 ]]; do
    case $1 in
        --help|-h)
            show_help
            exit 0
            ;;
        --auto)
            AUTO_MODE=true
            shift
            ;;
        --count)
            RUNNER_COUNT="$2"
            if ! [[ "$RUNNER_COUNT" =~ ^[0-9]+$ ]] || [ "$RUNNER_COUNT" -lt 1 ]; then
                echo "Error: --count must be a positive integer"
                exit 1
            fi
            shift 2
            ;;
        --no-svc)
            INSTALL_SVC=false
            shift
            ;;
        --version)
            RUNNER_VERSION="$2"
            shift 2
            ;;
        --name)
            RUNNER_NAME="$2"
            shift 2
            ;;
        --labels)
            EXTRA_LABELS="$2"
            shift 2
            ;;
        --dir)
            RUNNER_DIR="$2"
            shift 2
            ;;
        -*)
            echo "Unknown option: $1"
            exit 1
            ;;
        *)
            TOKEN="$1"
            AUTO_MODE=false
            shift
            ;;
    esac
done

# Save original directory for multi-runner loops
ORIGINAL_DIR="$(pwd)"

# Loop to create multiple runners
for ((i=1; i<=RUNNER_COUNT; i++)); do
    if [ "$RUNNER_COUNT" -gt 1 ]; then
        echo ""
        echo "========================================="
        echo "Setting up runner $i of $RUNNER_COUNT"
        echo "========================================="
        echo ""
    fi

    CURRENT_RUNNER_NAME="${RUNNER_NAME:-$(generate_runner_name)}"
    CURRENT_RUNNER_DIR="${RUNNER_DIR:-$(generate_workspace_dir)}"

    BASE_LABELS="self-hosted,macOS,${RUNNER_ARCH},groovy-lsp,local-macos"
    if [ -n "$EXTRA_LABELS" ]; then
        ALL_LABELS="${BASE_LABELS},${EXTRA_LABELS}"
    else
        ALL_LABELS="$BASE_LABELS"
    fi

    echo "GitHub Actions Runner Setup ($RUNNER_VERSION)"
    echo "Name:      $CURRENT_RUNNER_NAME"
    echo "Directory: $CURRENT_RUNNER_DIR"
    echo "Labels:    $ALL_LABELS"
    echo "Service:   $($INSTALL_SVC && echo "Yes" || echo "No")"
    echo ""

# Get token (auto-mode)
if [ "$AUTO_MODE" = true ]; then
    echo "Generating runner token..."

    if ! command -v gh &> /dev/null; then
        echo "Error: 'gh' CLI not found. Install with 'brew install gh' and login."
        exit 1
    fi

    if ! gh auth status &> /dev/null; then
        echo "Error: gh CLI not authenticated. Run 'gh auth login'."
        exit 1
    fi

    TOKEN=$(gh api \
        --method POST \
        -H "Accept: application/vnd.github+json" \
        "/repos/${REPO_OWNER}/${REPO_NAME}/actions/runners/registration-token" \
        --jq '.token')

    if [ -z "$TOKEN" ]; then
        echo "Error: Failed to generate token. Ensure admin access."
        exit 1
    fi
fi

if [ -z "$TOKEN" ]; then
    echo "Error: No token provided."
    exit 1
fi

# Prepare
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
CACHE_DIR="$PROJECT_ROOT/.github/.cache/runners"
mkdir -p "$CACHE_DIR"

TAR_FILE_NAME="actions-runner-osx-${RUNNER_ARCH}-${RUNNER_VERSION}.tar.gz"
CACHED_FILE="$CACHE_DIR/$TAR_FILE_NAME"
DOWNLOAD_URL="https://github.com/actions/runner/releases/download/v${RUNNER_VERSION}/${TAR_FILE_NAME}"

# Fetch expected checksum from GitHub
fetch_checksum() {
    local version="$1"
    local filename="$2"

    if ! command -v curl &> /dev/null || ! command -v jq &> /dev/null; then
        return 1
    fi

    # Fetch release and extract digest from assets array
    local checksum
    checksum=$(curl -sL "https://api.github.com/repos/actions/runner/releases/tags/v${version}" | \
        jq -r ".assets[] | select(.name == \"$filename\") | .digest" 2>/dev/null | \
        sed 's/^sha256://')

    if [ -n "$checksum" ] && [ ${#checksum} -eq 64 ]; then
        echo "$checksum"
        return 0
    fi

    return 1
}

echo "Fetching checksum for v${RUNNER_VERSION}..."
EXPECTED_CHECKSUM=$(fetch_checksum "$RUNNER_VERSION" "$TAR_FILE_NAME")

if [ -z "$EXPECTED_CHECKSUM" ]; then
    echo "Warning: Could not fetch checksum from GitHub"
    echo "  Checksum validation will be skipped (NOT RECOMMENDED)"
    read -p "  Continue without verification? (y/N) " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        exit 1
    fi
else
    echo "Expected SHA256: $EXPECTED_CHECKSUM"
fi

# Download or use cache
DOWNLOAD_NEEDED=true

if [ -f "$CACHED_FILE" ]; then
    if [ -n "$EXPECTED_CHECKSUM" ]; then
        echo "Verifying cached file..."
        if echo "${EXPECTED_CHECKSUM}  $CACHED_FILE" | shasum -a 256 -c > /dev/null 2>&1; then
            echo "Cached file verified."
            DOWNLOAD_NEEDED=false
        else
            echo "Cached file corrupted or version mismatch. Re-downloading..."
            rm "$CACHED_FILE"
        fi
    else
        echo "Warning: Using cached file without verification"
        DOWNLOAD_NEEDED=false
    fi
fi

# Download
if [ "$DOWNLOAD_NEEDED" = true ]; then
    echo "Downloading runner v${RUNNER_VERSION}..."
    curl -sL -o "$CACHED_FILE" "$DOWNLOAD_URL"

    if [ -n "$EXPECTED_CHECKSUM" ]; then
        echo "Verifying download..."
        if echo "${EXPECTED_CHECKSUM}  $CACHED_FILE" | shasum -a 256 -c; then
            echo "Download verified."
        else
            echo "ERROR: Checksum verification failed!"
            echo "  The downloaded file may be corrupted or tampered with."
            rm "$CACHED_FILE"
            exit 1
        fi
    else
        echo "Warning: Downloaded file without verification"
    fi
fi

# Install
if [ -d "$CURRENT_RUNNER_DIR" ]; then
    echo "Directory exists: $CURRENT_RUNNER_DIR"
    read -p "Remove and replace? (y/N) " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        rm -rf "$CURRENT_RUNNER_DIR"
    else
        echo "Using existing directory..."
    fi
fi
mkdir -p "$CURRENT_RUNNER_DIR"

cd "$CURRENT_RUNNER_DIR"
echo "Extracting..."
tar xzf "$CACHED_FILE"

echo "Configuring..."
./config.sh \
    --url "$REPO_URL" \
    --token "$TOKEN" \
    --name "$CURRENT_RUNNER_NAME" \
    --labels "$ALL_LABELS" \
    --work "_work" \
    --unattended \
    --replace

# Service Installation (Auto-mode default)
if [ "$AUTO_MODE" = true ] && [ "$INSTALL_SVC" = true ]; then
    echo "Installing service..."
    ./svc.sh install
    echo "Starting service..."
    ./svc.sh start
    echo "Service started successfully."
else
    echo "Setup complete."
    echo "To start manually:"
    echo "  cd $CURRENT_RUNNER_DIR && ./run.sh"
    echo "To install service:"
    echo "  cd $CURRENT_RUNNER_DIR"
    echo "  ./svc.sh install && ./svc.sh start"
fi

# Return to original directory for next iteration
cd "$ORIGINAL_DIR"
done  # End of runner creation loop
