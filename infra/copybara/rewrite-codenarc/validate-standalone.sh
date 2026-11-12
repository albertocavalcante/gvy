#!/bin/bash
# SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
# SPDX-License-Identifier: Apache-2.0

# validate-standalone.sh: Test that the assembled standalone repo is buildable
#
# This script simulates what Copybara does and validates the result can build.
# Run this before pushing to catch issues early.
#
# Usage: ./validate-standalone.sh [--keep] [--license-only] [--worktree-preview]
#   --keep: Don't delete the temp directory (for debugging)
#   --license-only: Check assembled licensing without running Gradle
#   --worktree-preview: Use tracked worktree changes; not evidence of a Git export

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MONOREPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
KEEP_TEMP=false
LICENSE_ONLY=false
WORKTREE_PREVIEW=false

for arg in "$@"; do
    case "${arg}" in
        --keep) KEEP_TEMP=true ;;
        --license-only) LICENSE_ONLY=true ;;
        --worktree-preview) WORKTREE_PREVIEW=true ;;
        *)
            echo "Unknown option: ${arg}" >&2
            exit 2
            ;;
    esac
done

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
BLUE='\033[0;34m'
NC='\033[0m'

log_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
log_success() { echo -e "${GREEN}[OK]${NC} $1"; }
log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
    exit 1
}

# Copybara reads a Git commit. By default reject pending changes to its inputs,
# so a local untracked license file cannot make an incomplete export look valid.
if [[ "${WORKTREE_PREVIEW}" == "false" ]]; then
    pending=$(git -C "${MONOREPO_ROOT}" status --porcelain --untracked-files=all -- \
        refactor/openrewrite/rewrite-codenarc/ infra/copybara/rewrite-codenarc/ \
        infra/copybara/rewrite-codenarc.bara.sky gradlew gradlew.bat gradlew.license gradlew.bat.license \
        gradle/wrapper/ LICENSE LICENSES/Apache-2.0.txt)
    [[ -z "${pending}" ]] || log_error "Export inputs have uncommitted changes; commit them or use --worktree-preview"
else
    log_info "Worktree preview uses tracked changes and does not validate a committed Git export"
fi

# A preview may use modified tracked bytes, but cannot rely on untracked metadata.
git -C "${MONOREPO_ROOT}" ls-files --error-unmatch -- \
    refactor/openrewrite/rewrite-codenarc/REUSE.toml gradlew.license gradlew.bat.license \
    gradle/wrapper/gradle-wrapper.jar.license LICENSES/Apache-2.0.txt >/dev/null ||
    log_error "Required export licensing files must be tracked or staged"

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

# Assemble tracked files from the two selected subtrees.
# Match Copybara's module build exclusions and fail on destination collisions.
copy_subtree() {
    local prefix="$1" destination_prefix="${2:-}" path relative destination
    local manifest="${TEMP_DIR}/.copybara-file-list"
    git -C "${MONOREPO_ROOT}" ls-files -z --cached -- "${prefix}/" >"${manifest}"
    while IFS= read -r -d '' path; do
        relative="${path#"${prefix}"/}"
        if [[ "${prefix}" == "refactor/openrewrite/rewrite-codenarc" ]]; then
            case "${relative}" in
                build/* | .gradle/*) continue ;;
                *) ;;
            esac
        fi
        [[ -e "${MONOREPO_ROOT}/${path}" || -L "${MONOREPO_ROOT}/${path}" ]] || continue
        destination="${TEMP_DIR}/${destination_prefix}${relative}"
        [[ ! -e "${destination}" && ! -L "${destination}" ]] || log_error "Export path collision: ${relative}"
        mkdir -p "$(dirname "${destination}")"
        cp -P "${MONOREPO_ROOT}/${path}" "${destination}"
    done <"${manifest}"
    rm "${manifest}"
}

log_info "Copying module source and standalone configuration..."
copy_subtree "refactor/openrewrite/rewrite-codenarc"
copy_subtree "infra/copybara/rewrite-codenarc"

# Copy gradle wrapper from monorepo root
log_info "Copying gradle wrapper..."
cp "${MONOREPO_ROOT}/gradlew" "${TEMP_DIR}/"
cp "${MONOREPO_ROOT}/gradlew.bat" "${TEMP_DIR}/"
cp "${MONOREPO_ROOT}/gradlew.license" "${TEMP_DIR}/"
cp "${MONOREPO_ROOT}/gradlew.bat.license" "${TEMP_DIR}/"
copy_subtree "gradle/wrapper" "gradle/wrapper/"

# Copy LICENSE
cp "${MONOREPO_ROOT}/LICENSE" "${TEMP_DIR}/"
mkdir -p "${TEMP_DIR}/LICENSES"
cp "${MONOREPO_ROOT}/LICENSES/Apache-2.0.txt" "${TEMP_DIR}/LICENSES/"

# --- Validate ---

log_info "Validating standalone repo structure..."
cd "${TEMP_DIR}"

# Check required files exist
for f in build.gradle.kts settings.gradle.kts gradle.properties gradlew gradle/libs.versions.toml; do
    [[ -f "${f}" ]] || log_error "Missing required file: ${f}"
done
log_success "All required files present"

# Use the monorepo's locked REUSE environment without installing dependencies.
log_info "Checking standalone license coverage..."
uv run --project "${MONOREPO_ROOT}/.reuse" --locked --offline --no-dev reuse --root "${TEMP_DIR}" lint
log_success "Standalone license coverage passed"
if [[ "${LICENSE_ONLY}" == "true" ]]; then
    exit 0
fi

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
