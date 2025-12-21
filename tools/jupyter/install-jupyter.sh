#!/usr/bin/env bash
#
# Install Jupyter prerequisites using uv
#
# Usage:
#   ./install-jupyter.sh
#

set -euo pipefail

# Color output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_step() {
    echo -e "${CYAN}[STEP]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Check for uv
ensure_uv() {
    if command -v uv &> /dev/null; then
        log_info "uv is already installed."
        return
    fi

    log_warn "uv is not installed."

    if [[ "$(uname -s)" == "Darwin" ]]; then
        log_info "MacOS detected. Attempting to install uv via Homebrew..."
        if command -v brew &> /dev/null; then
            brew install uv
            log_info "uv installed successfully via Homebrew."
        else
            log_error "Homebrew not found. Please install uv manually: https://github.com/astral-sh/uv"
            exit 1
        fi
    else
        log_warn "Not on MacOS or manual installation required."
        log_info "Please install uv manually: curl -LsSf https://astral.sh/uv/install.sh | sh"
        exit 1
    fi
}

install_tool() {
    local package=$1
    log_step "Installing $package..."
    uv tool install "$package"
}

main() {
    log_info "Setting up Jupyter Environment"
    log_info "=============================="

    ensure_uv

    log_info "Installing Jupyter components..."

    install_tool "jupyter-core"
    install_tool "jupyter-client"
    install_tool "jupyterlab"
    install_tool "notebook"

    echo ""
    log_info "Jupyter environment setup complete!"
    log_info "You can now run:"
    log_info "  jupyter lab"
    log_info "  jupyter notebook"
}

main "$@"
