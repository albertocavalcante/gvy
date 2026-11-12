#!/bin/bash
set -euo pipefail

# Colors for output
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Default values
DIST_DIR="dist"
EXTENSION_DIR="editors/code"
DRY_RUN=false
VERSION=""
release_package_json=""
release_package_backup=""

log_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
log_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

cleanup_package_json() {
  if [[ -n "${release_package_backup}" && -f "${release_package_backup}" ]]; then
    mv "${release_package_backup}" "${release_package_json}"
    release_package_backup=""
  fi
}

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
    VERSION="${2:?--version requires a value}"
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
COMMAND="${1:-}"

check_version() {
  if [[ -z "${VERSION}" ]]; then
    log_error "Version is required. Use --version <tag>"
    exit 1
  fi
}

cmd_build() {
  check_version
  cmd_validate
  log_info "Starting build for version: ${VERSION}"
  local release_version="${VERSION#vscode-groovy-v}"
  release_version="${release_version#v}"
  local root_dir target_jar target_vsix source_commit
  root_dir="$(pwd)"
  target_jar="${root_dir}/${DIST_DIR}/gls-${release_version}.jar"
  target_vsix="${root_dir}/${DIST_DIR}/gvy-${release_version}.vsix"

  # Clean and prepare dist
  rm -rf "${DIST_DIR}"
  mkdir -p "${DIST_DIR}"

  # Dry runs retain placeholder artifacts without installing dependencies or building.
  if [[ "${DRY_RUN}" = true ]]; then
    log_warn "DRY RUN MODE: Skipping builds, packaging, and verification"
    touch "${target_jar}" "${target_vsix}"
    return
  fi

  source_commit="$(git rev-parse HEAD)"
  log_info "Testing and staging the current server..."
  GITHUB_REF_TYPE=tag ./gradlew --no-daemon test :gls:stageEditorServer
  cp "gls/build/editor-server/gls.jar" "${target_jar}"

  # The hard local override reaches both postinstall and VSCE prepublish.
  # FORCE_DOWNLOAD replaces any previously bundled JAR; fallback is forbidden.
  export GLS_LOCAL_JAR="${target_jar}"
  export FORCE_DOWNLOAD=true
  export GLS_ALLOW_PINNED_FALLBACK=false
  export SKIP_PREPARE_SERVER=false
  log_info "Building VS Code Extension..."
  pushd "${EXTENSION_DIR}" >/dev/null || exit 1

  if [[ ! -d "node_modules" ]]; then
    log_info "Installing extension dependencies..."
    pnpm install --frozen-lockfile
  fi

  release_package_json="${root_dir}/${EXTENSION_DIR}/package.json"
  release_package_backup="$(mktemp "${release_package_json}.release.XXXXXX")"
  cp "${release_package_json}" "${release_package_backup}"
  trap cleanup_package_json EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  node -e 'const fs = require("fs"); const p = JSON.parse(fs.readFileSync("package.json", "utf8")); p.version = process.argv[1]; fs.writeFileSync("package.json", JSON.stringify(p, null, 2) + "\n");' "${release_version}"
  pnpm run package --out "${target_vsix}"
  cleanup_package_json
  trap - EXIT INT TERM
  popd >/dev/null || exit 1

  node tools/ci/release-artifacts.mjs "${target_jar}" "${target_vsix}" "${release_version}" "${source_commit}"
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
    java -Xmx2g -jar "${JAR_FILE}" --help >/dev/null
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
  pushd "${DIST_DIR}" >/dev/null || exit 1
  if ls ./* >/dev/null 2>&1; then
    shasum -a 256 ./* >checksums.txt
    log_success "Checksums generated in ${DIST_DIR}/checksums.txt"
    cat checksums.txt
  else
    log_warn "No files in ${DIST_DIR} to checksum"
  fi
  popd >/dev/null || exit 1
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
