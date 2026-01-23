#!/usr/bin/env bash
# publish-rules-kotlin-fork.sh
#
# Publishes a pre-built rules_kotlin release to our fork.
# This allows using Kotlin versions not yet released in upstream rules_kotlin.
#
# Usage:
#   ./tools/scripts/publish-rules-kotlin-fork.sh [--build] [--version VERSION]
#
# Options:
#   --build      Build rules_kotlin from source (otherwise uses existing tarball)
#   --version    Version tag (default: v2.3.0-fork.1)
#
# Prerequisites:
#   - gh CLI authenticated
#   - bazel (if --build is used)
#
# Example:
#   # Use existing tarball
#   ./tools/scripts/publish-rules-kotlin-fork.sh
#
#   # Build from source and publish
#   ./tools/scripts/publish-rules-kotlin-fork.sh --build --version v2.3.0-fork.2

set -euo pipefail

# Configuration
FORK_REPO="albertocavalcante/fork-rules_kotlin"
FORK_BRANCH="fork/main"
UPSTREAM_REPO="bazelbuild/rules_kotlin"
UPSTREAM_BRANCH="master"
DEFAULT_VERSION="v2.3.0-fork.1"
TARBALL_NAME="rules_kotlin_release.tgz"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

log_info() { echo -e "${GREEN}[INFO]${NC} ${1}"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} ${1}"; }
log_error() { echo -e "${RED}[ERROR]${NC} ${1}"; }

# Parse arguments
BUILD_FROM_SOURCE=false
VERSION="${DEFAULT_VERSION}"

while [[ $# -gt 0 ]]; do
    case ${1} in
        --build)
            BUILD_FROM_SOURCE=true
            shift
            ;;
        --version)
            VERSION="${2}"
            shift 2
            ;;
        *)
            log_error "Unknown option: ${1}"
            exit 1
            ;;
    esac
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
WORK_DIR="/tmp/rules_kotlin_fork_build"
TARBALL_PATH="${WORK_DIR}/${TARBALL_NAME}"

# -----------------------------------------------------------------------------
# Step 1: Verify prerequisites
# -----------------------------------------------------------------------------
log_info "Step 1: Verifying prerequisites..."

if ! command -v gh &> /dev/null; then
    log_error "gh CLI not found. Install with: brew install gh"
    exit 1
fi

if ! gh auth status &> /dev/null; then
    log_error "gh CLI not authenticated. Run: gh auth login"
    exit 1
fi

log_info "Prerequisites OK"

# -----------------------------------------------------------------------------
# Step 2: Check fork state and create fork/main branch
# -----------------------------------------------------------------------------
log_info "Step 2: Setting up fork branch '${FORK_BRANCH}'..."

# Check if fork/main branch exists
if gh api "repos/${FORK_REPO}/branches/${FORK_BRANCH}" &> /dev/null; then
    log_info "Branch '${FORK_BRANCH}' already exists"
else
    log_info "Creating branch '${FORK_BRANCH}' from '${UPSTREAM_BRANCH}'..."

    # Get the SHA of master branch
    MASTER_SHA=$(gh api "repos/${FORK_REPO}/git/refs/heads/${UPSTREAM_BRANCH}" --jq '.object.sha')

    if [[ -z "${MASTER_SHA}" ]]; then
        log_error "Could not get SHA of ${UPSTREAM_BRANCH} branch"
        exit 1
    fi

    # Create the new branch
    gh api "repos/${FORK_REPO}/git/refs" \
        -f ref="refs/heads/${FORK_BRANCH}" \
        -f sha="${MASTER_SHA}" > /dev/null

    log_info "Created branch '${FORK_BRANCH}' at ${MASTER_SHA}"
fi

# -----------------------------------------------------------------------------
# Step 3: Build or locate tarball
# -----------------------------------------------------------------------------
log_info "Step 3: Preparing tarball..."

mkdir -p "${WORK_DIR}"

if [[ "${BUILD_FROM_SOURCE}" == "true" ]]; then
    log_info "Building rules_kotlin from source..."

    # Clone if needed
    if [[ ! -d "${WORK_DIR}/rules_kotlin" ]]; then
        git clone --depth 1 "https://github.com/${UPSTREAM_REPO}.git" "${WORK_DIR}/rules_kotlin"
    fi

    cd "${WORK_DIR}/rules_kotlin"
    git fetch origin "${UPSTREAM_BRANCH}"
    git checkout "origin/${UPSTREAM_BRANCH}"

    log_info "Running: bazel build //:rules_kotlin_release"
    bazel build //:rules_kotlin_release

    cp bazel-bin/rules_kotlin_release.tgz "${TARBALL_PATH}"
    log_info "Built tarball at ${TARBALL_PATH}"
else
    # Check for existing tarball in common locations
    EXISTING_TARBALL=""
    for path in \
        "${PROJECT_ROOT}/tools/rules_kotlin_2.3.0.tgz" \
        "${WORK_DIR}/${TARBALL_NAME}" \
        "/tmp/rules_kotlin_build/bazel-bin/rules_kotlin_release.tgz"; do
        if [[ -f "${path}" ]]; then
            EXISTING_TARBALL="${path}"
            break
        fi
    done

    if [[ -z "${EXISTING_TARBALL}" ]]; then
        log_error "No existing tarball found. Use --build to build from source."
        log_error "Searched locations:"
        log_error "  - ${PROJECT_ROOT}/tools/rules_kotlin_2.3.0.tgz"
        log_error "  - ${WORK_DIR}/${TARBALL_NAME}"
        log_error "  - /tmp/rules_kotlin_build/bazel-bin/rules_kotlin_release.tgz"
        exit 1
    fi

    log_info "Using existing tarball: ${EXISTING_TARBALL}"
    cp "${EXISTING_TARBALL}" "${TARBALL_PATH}"
fi

# Calculate integrity hash
TARBALL_SHA256=$(shasum -a 256 "${TARBALL_PATH}" | awk '{print $1}')
log_info "Tarball SHA256: ${TARBALL_SHA256}"

# -----------------------------------------------------------------------------
# Step 4: Create GitHub Release
# -----------------------------------------------------------------------------
log_info "Step 4: Creating GitHub release '${VERSION}'..."

# Check if release already exists
if gh release view "${VERSION}" --repo "${FORK_REPO}" &> /dev/null; then
    log_warn "Release '${VERSION}' already exists. Deleting and recreating..."
    gh release delete "${VERSION}" --repo "${FORK_REPO}" --yes
fi

# Get Kotlin version from the tarball (if possible)
KOTLIN_VERSION="2.3.0"  # Default, could be extracted from tarball

# Calculate integrity for release notes
INTEGRITY_BASE64=$(echo "${TARBALL_SHA256}" | xxd -r -p | base64 || true)

# Create release
gh release create "${VERSION}" "${TARBALL_PATH}" \
    --repo "${FORK_REPO}" \
    --target "${FORK_BRANCH}" \
    --title "rules_kotlin with Kotlin ${KOTLIN_VERSION}" \
    --notes "## Pre-built rules_kotlin with Kotlin ${KOTLIN_VERSION}

Built from [bazelbuild/rules_kotlin](https://github.com/bazelbuild/rules_kotlin) master branch.

### Usage in MODULE.bazel

\`\`\`starlark
bazel_dep(name = \"rules_kotlin\", version = \"2.2.2\")

archive_override(
    module_name = \"rules_kotlin\",
    urls = [\"https://github.com/${FORK_REPO}/releases/download/${VERSION}/${TARBALL_NAME}\"],
    integrity = \"sha256-${INTEGRITY_BASE64}\",
)
\`\`\`

### Integrity
- SHA256: \`${TARBALL_SHA256}\`
"

log_info "Created release: https://github.com/${FORK_REPO}/releases/tag/${VERSION}"

# -----------------------------------------------------------------------------
# Step 5: Output MODULE.bazel configuration
# -----------------------------------------------------------------------------
echo ""
echo "============================================================================"
echo "SUCCESS! Release published."
echo "============================================================================"
echo ""
echo "Add this to your MODULE.bazel:"
echo ""
echo "bazel_dep(name = \"rules_kotlin\", version = \"2.2.2\")"
echo ""
echo "archive_override("
echo "    module_name = \"rules_kotlin\","
echo "    urls = [\"https://github.com/${FORK_REPO}/releases/download/${VERSION}/${TARBALL_NAME}\"],"
echo "    integrity = \"sha256-${INTEGRITY_BASE64}\","
echo ")"
echo ""
echo "============================================================================"
