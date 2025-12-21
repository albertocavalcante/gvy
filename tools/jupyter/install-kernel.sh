#!/usr/bin/env bash
#
# Install the Groovy Jupyter kernel
#
# Usage:
#   ./install-kernel.sh [--user]
#
# Options:
#   --user    Install for current user only (default: system-wide)
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
JAR_NAME="groovy-jupyter-all.jar"
KERNEL_NAME="groovy"

# Color output
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

# Determine installation mode
if [[ $EUID -eq 0 ]]; then
    # Running as root - system install
    USER_INSTALL=""
    log_info "Running as root. Performing system-wide installation."
elif [[ "${1:-}" == "--user" ]]; then
    # Explicit user install
    USER_INSTALL="--user"
    log_info "User flag detected. Performing user installation."
else
    # Default to user install for non-root users
    USER_INSTALL="--user"
    log_info "Not running as root. Defaulting to user installation."
fi

# Verify dependencies
check_dependencies() {
    if ! command -v java &> /dev/null; then
        log_error "Java is required but not installed."
        exit 1
    fi

    if ! command -v jupyter &> /dev/null; then
        log_error "Jupyter is required but not installed."
        print_install_help
        exit 1
    fi

    if ! jupyter kernelspec --version &> /dev/null; then
        log_error "'jupyter kernelspec' command not found."
        print_install_help
        exit 1
    fi

    log_info "Dependencies verified: java, jupyter"
}

print_install_help() {
    log_info "Recommended installation with uv:"
    log_info "  uv tool install jupyter-core"
    log_info "  uv tool install jupyter-client  # For kernelspec"
    log_info "  uv tool install jupyterlab      # For interface"
    log_info ""
    log_info "Or with pip:"
    log_info "  pip install jupyter"
}

# Build the fat JAR
build_jar() {
    log_info "Building fat JAR..."

    local gradlew="$PROJECT_ROOT/gradlew"

    if [[ ! -f "$gradlew" ]]; then
        log_error "gradlew not found at $gradlew"
        exit 1
    fi

    # Run from project root
    (cd "$PROJECT_ROOT" && ./gradlew :groovy-jupyter:shadowJar --quiet)

    JAR_PATH="$PROJECT_ROOT/groovy-jupyter/build/libs/$JAR_NAME"
    if [[ ! -f "$JAR_PATH" ]]; then
        log_error "JAR build failed. Expected: $JAR_PATH"
        exit 1
    fi

    log_info "JAR built successfully: $JAR_PATH"
}

# Install the kernel
install_kernel() {
    log_info "Installing Groovy kernel..."

    # Create a temporary directory for kernel files
    TEMP_KERNEL_DIR=$(mktemp -d)
    trap 'rm -rf "$TEMP_KERNEL_DIR"' EXIT

    # Copy kernel.json
    cp "$PROJECT_ROOT/groovy-jupyter/src/main/resources/kernel/kernel.json" "$TEMP_KERNEL_DIR/"

    # Copy the JAR
    cp "$PROJECT_ROOT/groovy-jupyter/build/libs/$JAR_NAME" "$TEMP_KERNEL_DIR/"

    # Install using jupyter kernelspec
    # shellcheck disable=SC2086
    jupyter kernelspec install "$TEMP_KERNEL_DIR" --name="$KERNEL_NAME" $USER_INSTALL --replace

    log_info "Kernel installed successfully!"
}

# Verify installation
verify_installation() {
    log_info "Verifying installation..."

    if jupyter kernelspec list | grep -q "$KERNEL_NAME"; then
        log_info "✓ Groovy kernel is available"
        jupyter kernelspec list | grep "$KERNEL_NAME"
    else
        log_error "Kernel installation verification failed"
        exit 1
    fi
}

# Main
main() {
    log_info "Installing Groovy Jupyter Kernel"
    log_info "================================="

    check_dependencies
    build_jar
    install_kernel
    verify_installation

    echo ""
    log_info "Installation complete!"
    log_info "Start a notebook with: jupyter notebook"
    log_info "Or use JupyterLab with: jupyter lab"
}

main "$@"
