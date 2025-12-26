#!/bin/bash
set -e

# Colors for output
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Default values
DIST_DIR="dist"
BUILD_DIR="groovy-lsp/build/libs"
EXTENSION_DIR="editors/code"
DRY_RUN=false
VERSION=""

log_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
log_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

print_usage() {
    echo "Usage: $(basename "$0") [COMMAND] [OPTIONS]"
    echo ""
    echo "Commands:"
    echo "  build       Build release artifacts (JAR & VSIX) - Runs tests"
    echo "  validate    Validate release requirements (tags)"
    echo "  smoke-test  Run smoke test on built JAR in dist/"
    echo "  checksums   Generate checksums for dist/ content"
    echo ""
    echo "Options:"
    echo "  --version <ver>   Specify version tag (e.g., v1.2.3)"
    echo "  --dry-run         Simulate actions without permanent changes"
    echo "  --help            Show this help message"
}

# Parse global options
ARGS=()
while [[ $# -gt 0 ]]; do
    case "$1" in
        --version)
            VERSION="$2"
            shift 2
            ;;
        --dry-run)
            DRY_RUN=true
            shift
            ;;
        --help)
            print_usage
            exit 0
            ;;
        *)
            ARGS+=("$1")
            shift
            ;;
    esac
done

# Restore positional arguments
set -- "${ARGS[@]}"
COMMAND="$1"

check_version() {
    if [[ -z "${VERSION}" ]]; then
        log_error "Version is required. Use --version <tag>"
        exit 1
    fi
}

cmd_build() {
    check_version
    log_info "Starting build for version: ${VERSION}"
    if [[ "${DRY_RUN}" = true ]]; then log_warn "DRY RUN MODE: No artifacts will be published"; fi

    # Clean and prepare dist
    rm -rf "${DIST_DIR}"
    mkdir -p "${DIST_DIR}"

    # 0. Run Tests
    log_info "Running unit tests..."
    if [[ "${DRY_RUN}" = true ]]; then
        log_info "(Dry run) Skipping tests"
    else
        ./gradlew --no-daemon test
    fi

    # 1. Build Shadow JAR
    log_info "Building Groovy Language Server JAR..."
    if [[ "${DRY_RUN}" = true ]]; then
        log_info "(Dry run) Skipping Gradle build, creating dummy JAR"
        mkdir -p "${BUILD_DIR}"
        touch "${BUILD_DIR}/gls-0.0.0-all.jar"
    else
        ./gradlew --no-daemon shadowJar
    fi

    # 2. Identify and Copy JAR
    # Find the built JAR (handling version variances)
    JAR_FILE=$(find "${BUILD_DIR}" -name "gls-*-all.jar" | head -n 1 || true)
    if [[ ! -f "${JAR_FILE}" ]]; then
        log_error "No JAR file found in ${BUILD_DIR}"
        exit 1
    fi

    local VERSION_NO_PRFIX="${VERSION#vscode-groovy-v}"
    local VERSION_NO_V="${VERSION_NO_PRFIX#v}"
    
    # Java JARs are platform-independent - no need for platform suffix
    local TARGET_JAR="${DIST_DIR}/gls-${VERSION_NO_V}.jar"
    log_info "Copying JAR to ${TARGET_JAR}"
    cp "${JAR_FILE}" "${TARGET_JAR}"

    # 3. Build VSIX
    log_info "Building VS Code Extension..."
    pushd "${EXTENSION_DIR}" > /dev/null || exit 1
    
    # Ensure dependencies are installed
    if [[ ! -d "node_modules" ]]; then
        log_info "Installing extension dependencies..."
        npm ci
    fi

    if [[ "${DRY_RUN}" = true ]]; then
         log_info "(Dry run) Skipping VSIX package, creating dummy VSIX"
         touch "gvy-${VERSION_NO_V}.vsix"
    else
        log_info "Setting extension version to ${VERSION_NO_V}"
        
        # Define cleanup function to restore package.json
        cleanup_package_json() {
            if [[ -f "package.json.bak" ]]; then
                log_warn "Restoring package.json from backup..."
                mv "package.json.bak" "package.json"
            fi
        }
        
        # Set trap to ensure cleanup happens on exit or error
        trap cleanup_package_json EXIT INT TERM

        # Backup package.json to restore later (safe for local dev)
        cp package.json package.json.bak
        
        # Stamp version without creating a git commit/tag
        # We use --allow-same-version in case we are rebuilding same version
        npm version "${VERSION_NO_V}" --no-git-tag-version --allow-same-version
        
        # Package
        npm run package
        
        # Restore original package.json (trap will handle this, but manual is fine too)
        # We explicitly call it here to clear the trap if successful
        cleanup_package_json
        trap - EXIT INT TERM
        
        log_info "Restored original package.json"
    fi
    
    # Move VSIX to dist
    local VSIX_FILE
    VSIX_FILE=$(find . -maxdepth 1 -name "*.vsix" | head -n 1 || true)
    if [[ -f "${VSIX_FILE}" ]]; then
        log_success "Found VSIX: ${VSIX_FILE}"
        cp "${VSIX_FILE}" "../../${DIST_DIR}/"
    else 
        log_warn "No VSIX file created."
    fi
    
    popd > /dev/null || exit 1

    log_success "Build complete. Artifacts in ${DIST_DIR}:"
    ls -lh "${DIST_DIR}"
}

cmd_validate() {
    if [[ -n "${VERSION}" ]]; then
        # Check tag format (v1.2.3 or vscode-groovy-v1.2.3)
        if [[ ! "${VERSION}" =~ ^(v|vscode-groovy-v)[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.-]+)?$ ]]; then
            log_error "Invalid version format: ${VERSION}. Expected vX.Y.Z or vscode-groovy-vX.Y.Z"
            exit 1
        fi
        log_success "Version format valid: ${VERSION}"
    fi
}

cmd_smoke_test() {
    log_info "Running JAR smoke tests..."
    # Find built JAR in dist
    local JAR_FILE
    JAR_FILE=$(find "${DIST_DIR}" -name "gls-*.jar" | head -n 1 || true)
    
    if [[ -f "${JAR_FILE}" ]]; then
        log_info "Smoke testing JAR: ${JAR_FILE}"
        java -jar "${JAR_FILE}" --help > /dev/null
        log_success "JAR smoke test passed"
    else
        log_error "No JAR found in ${DIST_DIR} for smoke test. Run build first."
        exit 1
    fi
}

cmd_checksums() {
    if [[ ! -d "${DIST_DIR}" ]]; then
        log_error "Directory ${DIST_DIR} does not exist."
        exit 1
    fi

    log_info "Generating checksums..."
    pushd "${DIST_DIR}" > /dev/null || exit 1
    if ls ./* > /dev/null 2>&1; then
        shasum -a 256 ./* > checksums.txt
        log_success "Checksums generated in ${DIST_DIR}/checksums.txt"
        cat checksums.txt
    else
        log_warn "No files in ${DIST_DIR} to checksum"
    fi
    popd > /dev/null || exit 1
}

case "${COMMAND}" in
    build)
        cmd_build
        ;;
    validate)
        cmd_validate
        ;;
    smoke-test)
        cmd_smoke_test
        ;;
    checksums)
        cmd_checksums
        ;;
    *)
        print_usage
        exit 1
        ;;
esac
