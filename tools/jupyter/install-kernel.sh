#!/usr/bin/env bash
#
# Install the Groovy Jupyter kernel (Standard or Jenkins variant)
#
# Usage:
#   ./install-kernel.sh [--user] [--variant <groovy|jenkins>]
#
# Options:
#   --user     Install for current user only (default: system-wide if root, else user)
#   --variant  Kernel variant to install: 'groovy' (default) or 'jenkins'
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# Default values
INSTALL_OPTS=()
VARIANT="groovy"

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

# Determine installation mode and parse arguments
parse_args() {
    while [[ $# -gt 0 ]]; do
        case $1 in
            --user)
                INSTALL_OPTS+=("--user")
                shift
                ;;
            --variant)
                if [[ -z "$2" ]]; then
                    log_error "Option --variant requires an argument."
                    exit 1
                fi
                VARIANT="$2"
                shift 2
                ;;
            *)
                echo "Unknown option: $1"
                exit 1
                ;;
        esac
    done

    if [[ ${#INSTALL_OPTS[@]} -eq 0 ]]; then
         if [[ $EUID -eq 0 ]]; then
            log_info "Running as root. Performing system-wide installation."
        else
            INSTALL_OPTS+=("--user")
            log_info "Not running as root. Defaulting to user installation."
        fi
    else
        log_info "User flag explicitly set."
    fi
}

configure_variant() {
    if [[ "$VARIANT" == "groovy" ]]; then
        GRADLE_MODULE_PATH="jupyter:kernels:groovy"
        JAR_NAME="groovy-kernel-all.jar"
        KERNEL_NAME="groovy"
        DISPLAY_NAME_PREFIX="Groovy"
        SRC_KERNEL_JSON="jupyter/kernels/groovy/src/main/resources/kernel/kernel.json"
        BUILD_LIBS_DIR="jupyter/kernels/groovy/build/libs"
    elif [[ "$VARIANT" == "jenkins" ]]; then
        GRADLE_MODULE_PATH="jupyter:kernels:jenkins"
        JAR_NAME="jenkins-kernel-all.jar"
        KERNEL_NAME="jenkins-groovy"
        DISPLAY_NAME_PREFIX="Jenkins Pipeline (Groovy 2.4)"
        SRC_KERNEL_JSON="jupyter/kernels/jenkins/src/main/resources/kernel/kernel.json"
        BUILD_LIBS_DIR="jupyter/kernels/jenkins/build/libs"
    else
        log_error "Unknown variant: $VARIANT. Supported variants: groovy, jenkins"
        exit 1
    fi

    log_info "Configured for variant: $VARIANT"
    log_info "  Module: $GRADLE_MODULE_PATH"
    log_info "  Kernel Name: $KERNEL_NAME"
}

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
    log_info "  uv tool install jupyter-client"
    log_info "  uv tool install jupyterlab"
}

# Build the fat JAR
build_jar() {
    log_info "Building fat JAR for $VARIANT kernel..."

    local gradlew="$PROJECT_ROOT/gradlew"

    if [[ ! -f "$gradlew" ]]; then
        log_error "gradlew not found at $gradlew"
        exit 1
    fi

    # Run from project root
    (cd "$PROJECT_ROOT" && ./gradlew ":$GRADLE_MODULE_PATH:shadowJar" --quiet)

    # Artifact path
    JAR_PATH="$PROJECT_ROOT/$BUILD_LIBS_DIR/$JAR_NAME"
    if [[ ! -f "$JAR_PATH" ]]; then
        log_error "JAR build failed. Expected: $JAR_PATH"
        exit 1
    fi

    log_info "JAR built successfully: $JAR_PATH"
}

# Install the kernel
install_kernel() {
    log_info "Installing $VARIANT kernel..."

    # Create a temporary directory for kernel files
    TEMP_KERNEL_DIR=$(mktemp -d)
    trap 'rm -rf "$TEMP_KERNEL_DIR"' EXIT

    # Copy kernel.json
    cp "$PROJECT_ROOT/$SRC_KERNEL_JSON" "$TEMP_KERNEL_DIR/"

    # Generate logos from assets (requires ImageMagick 'convert')
    # Use common assets for now, maybe variant specific logic later
    if command -v convert &> /dev/null; then
        log_info "Generating kernel logos from SVG..."
        convert -background none -resize 64x64 "$PROJECT_ROOT/assets/groovy-logo.svg" "$TEMP_KERNEL_DIR/logo-64x64.png"
        convert -background none -resize 32x32 "$PROJECT_ROOT/assets/groovy-logo.svg" "$TEMP_KERNEL_DIR/logo-32x32.png"
    else
        log_warn "ImageMagick 'convert' not found. Skipping PNG logo generation."
    fi

    # Copy SVG logo for supported UIs
    cp "$PROJECT_ROOT/assets/groovy-logo.svg" "$TEMP_KERNEL_DIR/logo-svg.svg"

    # For Groovy variant, try to detect version
    if [[ "$VARIANT" == "groovy" ]]; then
        GROOVY_VERSION=$(grep 'groovy = "' "$PROJECT_ROOT/gradle/libs.versions.toml" | head -n 1 | sed -E 's/.*groovy = "(.*)"/\1/')
        if [[ -n "$GROOVY_VERSION" ]]; then
           log_info "Detected Groovy version: $GROOVY_VERSION"
           # Update display_name
           sed "s/\"display_name\": \"Groovy\"/\"display_name\": \"Groovy $GROOVY_VERSION\"/" "$TEMP_KERNEL_DIR/kernel.json" > "$TEMP_KERNEL_DIR/kernel.json.tmp"
           mv "$TEMP_KERNEL_DIR/kernel.json.tmp" "$TEMP_KERNEL_DIR/kernel.json"
        fi
    fi

    # Copy the JAR
    cp "$JAR_PATH" "$TEMP_KERNEL_DIR/"

    # Install using jupyter kernelspec
    jupyter kernelspec install "$TEMP_KERNEL_DIR" --name="$KERNEL_NAME" "${INSTALL_OPTS[@]}" --replace

    log_info "Kernel installed successfully!"
}

# Verify installation
verify_installation() {
    log_info "Verifying installation..."

    if output=$(jupyter kernelspec list | grep "$KERNEL_NAME"); then
        log_info "✓ $VARIANT kernel ($KERNEL_NAME) is available"
        echo "$output"
    else
        log_error "Kernel installation verification failed"
        exit 1
    fi
}

# Main
main() {
    log_info "Installing Groovy Jupyter Kernel"
    log_info "================================="

    parse_args "$@"
    configure_variant
    check_dependencies
    build_jar
    install_kernel
    verify_installation

    echo ""
    log_info "Installation complete!"
    log_info "Start a notebook with: jupyter notebook"
}

main "$@"
