#!/bin/bash
# validate-standalone.sh: Test that the assembled standalone repo is buildable
#
# This script simulates what Copybara does and validates the result can build.
# Run this before pushing to catch issues early.
#
# Usage: ./validate-standalone.sh [--keep]
#   --keep: Don't delete the temp directory (for debugging)

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MONOREPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
KEEP_TEMP=false

[[ "$1" == "--keep" ]] && KEEP_TEMP=true

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
BLUE='\033[0;34m'
NC='\033[0m'

log_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
log_success() { echo -e "${GREEN}[OK]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; exit 1; }

# Create temp directory
TEMP_DIR=$(mktemp -d)
log_info "Assembling standalone repo in: ${TEMP_DIR}"

cleanup() {
    if [[ "${KEEP_TEMP}" == "false" ]]; then
        rm -rf "${TEMP_DIR}"
        log_info "Cleaned up temp directory"
    else
        log_info "Temp directory kept at: ${TEMP_DIR}"
    fi
}
trap cleanup EXIT

# --- Simulate Copybara transformation ---

log_info "Copying module source code..."
cp -r "${MONOREPO_ROOT}/refactor/openrewrite/rewrite-codenarc/src" "${TEMP_DIR}/"
cp "${MONOREPO_ROOT}/refactor/openrewrite/rewrite-codenarc/build.gradle.kts" "${TEMP_DIR}/"

# Copy standalone gradle config (from infra/copybara/rewrite-codenarc/)
log_info "Copying standalone gradle configuration..."
cp "${SCRIPT_DIR}/settings.gradle.kts" "${TEMP_DIR}/"
cp "${SCRIPT_DIR}/gradle.properties" "${TEMP_DIR}/"
mkdir -p "${TEMP_DIR}/gradle"
cp "${SCRIPT_DIR}/gradle/libs.versions.toml" "${TEMP_DIR}/gradle/"

# Copy gradle wrapper from monorepo root
log_info "Copying gradle wrapper..."
cp "${MONOREPO_ROOT}/gradlew" "${TEMP_DIR}/"
cp "${MONOREPO_ROOT}/gradlew.bat" "${TEMP_DIR}/"
mkdir -p "${TEMP_DIR}/gradle/wrapper"
cp "${MONOREPO_ROOT}/gradle/wrapper/"* "${TEMP_DIR}/gradle/wrapper/"

# Copy LICENSE
cp "${MONOREPO_ROOT}/LICENSE" "${TEMP_DIR}/"

# Copy README and CONTRIBUTING if they exist
[[ -f "${MONOREPO_ROOT}/refactor/openrewrite/rewrite-codenarc/README.md" ]] && \
    cp "${MONOREPO_ROOT}/refactor/openrewrite/rewrite-codenarc/README.md" "${TEMP_DIR}/"
[[ -f "${MONOREPO_ROOT}/refactor/openrewrite/rewrite-codenarc/CONTRIBUTING.md" ]] && \
    cp "${MONOREPO_ROOT}/refactor/openrewrite/rewrite-codenarc/CONTRIBUTING.md" "${TEMP_DIR}/"

# --- Validate ---

log_info "Validating standalone repo structure..."
cd "${TEMP_DIR}"

# Check required files exist
for f in build.gradle.kts settings.gradle.kts gradle.properties gradlew gradle/libs.versions.toml; do
    [[ -f "${f}" ]] || log_error "Missing required file: ${f}"
done
log_success "All required files present"

# Make gradlew executable
chmod +x gradlew

# Run build
log_info "Running gradle build..."
if ./gradlew build --no-daemon -q; then
    log_success "Build succeeded!"
else
    log_error "Build failed!"
fi

# Run tests
log_info "Running tests..."
if ./gradlew test --no-daemon -q; then
    log_success "Tests passed!"
else
    log_error "Tests failed!"
fi

echo ""
echo -e "${GREEN}=== Standalone repo validation PASSED ===${NC}"
echo "The assembled repo is buildable and tests pass."
