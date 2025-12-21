#!/bin/bash
# cleanup-macos-runner.sh - Clean up GitHub Actions runners for groovy-lsp
#
# This script will:
# 1. Find all groovy-lsp runner directories
# 2. Stop and uninstall services
# 3. Unregister runners from GitHub
# 4. Remove directories and optionally cache
#
# Usage:
#   ./cleanup-macos-runner.sh [--all] [--skip-unregister]

set -e

REPO_OWNER="albertocavalcante"
REPO_NAME="groovy-lsp"

# Parse arguments
CLEAN_CACHE=false
SKIP_UNREGISTER=false
CUSTOM_PATH=""

while [[ $# -gt 0 ]]; do
    case $1 in
        --all)
            CLEAN_CACHE=true
            shift
            ;;
        --skip-unregister)
            SKIP_UNREGISTER=true
            shift
            ;;
        --path)
            CUSTOM_PATH="$2"
            shift 2
            ;;
        --help|-h)
            cat << EOF
GitHub Actions Runner Cleanup

USAGE:
    $(basename "$0") [OPTIONS]

OPTIONS:
    --path PATTERN     Custom path/pattern for runner directories
                       (default: ~/.gha-runners/groovy-lsp-*)
    --all              Also remove cached runner downloads
    --skip-unregister  Skip GitHub unregistration (faster, leaves orphaned entries)
    --help             Show this help

EXAMPLES:
    # Clean up groovy-lsp runners (default)
    $(basename "$0")

    # Clean up legacy runners
    $(basename "$0") --path "~/actions*"

    # Clean specific directory
    $(basename "$0") --path ~/actions-runner

DESCRIPTION:
    Removes GitHub Actions runners from this machine:
    - Stops and uninstalls services
    - Unregisters from GitHub (unless --skip-unregister)
    - Removes runner directories
    - Optionally removes cache (--all)

EOF
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            exit 1
            ;;
    esac
done

echo "GitHub Actions Runner Cleanup"
echo ""

# Determine search pattern
if [ -n "$CUSTOM_PATH" ]; then
    # Expand tilde manually for custom paths
    SEARCH_PATTERN="${CUSTOM_PATH/#\~/$HOME}"
    echo "Searching: $CUSTOM_PATH"
else
    SEARCH_PATTERN="$HOME/.gha-runners/groovy-lsp-*"
    echo "Searching: ~/.gha-runners/groovy-lsp-*"
fi
echo ""

# Find runner directories using glob expansion
shopt -s nullglob dotglob
mapfile -t RUNNER_DIRS < <(compgen -G "$SEARCH_PATTERN")
shopt -u nullglob dotglob


if [ ${#RUNNER_DIRS[@]} -eq 0 ]; then
    echo "No runner directories found matching pattern."

    if [ "$CLEAN_CACHE" = true ]; then
        echo "Checking for cache..."
    else
        echo "Nothing to clean up."
        exit 0
    fi
else
    echo "Found ${#RUNNER_DIRS[@]} runner(s):"
    for dir in "${RUNNER_DIRS[@]}"; do
        echo "  - $(basename "$dir")"
    done
    echo ""

    read -p "Remove all runners? (y/N) " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        echo "Cancelled."
        exit 0
    fi
    echo ""

    # Process each runner directory
    for RUNNER_DIR in "${RUNNER_DIRS[@]}"; do
        echo "Processing: $(basename "$RUNNER_DIR")"
        cd "$RUNNER_DIR"

        # Stop and uninstall service
        if [ -f "./svc.sh" ]; then
            echo "  Stopping service..."
            ./svc.sh stop 2>/dev/null || echo "  (service not running)"

            echo "  Uninstalling service..."
            ./svc.sh uninstall 2>/dev/null || echo "  (service not installed)"
        fi

        # Unregister from GitHub
        if [ "$SKIP_UNREGISTER" = false ]; then
            if [ -f "./config.sh" ]; then
                echo "  Unregistering from GitHub..."

                # Generate removal token via gh CLI
                if command -v gh &> /dev/null && gh auth status &> /dev/null; then
                    REMOVAL_TOKEN=$(gh api \
                        --method POST \
                        -H "Accept: application/vnd.github+json" \
                        "/repos/${REPO_OWNER}/${REPO_NAME}/actions/runners/remove-token" \
                        --jq '.token' 2>/dev/null || true)

                    if [ -n "$REMOVAL_TOKEN" ]; then
                        ./config.sh remove --token "$REMOVAL_TOKEN" 2>/dev/null || echo "  (unregistration failed - may be orphaned)"
                    else
                        echo "  (could not get removal token - skipping unregistration)"
                    fi
                else
                    echo "  (gh CLI not available - skipping unregistration)"
                fi
            fi
        else
            echo "  Skipping unregistration (--skip-unregister)"
        fi

        echo "  Removing directory..."
        cd ~
        rm -rf "$RUNNER_DIR"
        echo "  Done."
        echo ""
    done
fi

# Clean cache if requested
if [ "$CLEAN_CACHE" = true ]; then
    SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
    PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
    CACHE_DIR="$PROJECT_ROOT/.github/.cache/runners"

    if [ -d "$CACHE_DIR" ]; then
        echo "Cleaning cache: $CACHE_DIR"
        rm -rf "$CACHE_DIR"
        echo "Cache removed."
    else
        echo "No cache found at: $CACHE_DIR"
    fi
fi

echo ""
echo "Cleanup complete!"
