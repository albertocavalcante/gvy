#!/usr/bin/env bash
# ext-latest: install the VS Code extension + server jar built from the newest
# origin/main commit whose CI is green (#1576).
#
#   tools/ext-latest.sh [latest]        build + install the newest green main
#   tools/ext-latest.sh rollback [sha]  reinstall the previous kept VSIX
#
# The build happens in a dedicated clone under the cache dir, never in a
# developer worktree. Configuration is environment-only; see
# editors/code/README.md ("Run the latest green main").
#
# Pure helpers (sha7, vsix_name, evaluate_ci, ...) are at the top and are
# exercised by tools/ext-latest_test.sh; all IO lives in the *_io functions
# and main below. Written for bash 3.2 (macOS system bash).
# The repo ShellCheck profile enables every optional check. SC2310/SC2312 ask
# for helpers not to be used in conditions / command substitutions; the helpers
# here are pure, side-effect-free predicates and printers, and every failing
# path is handled explicitly, so those two checks only add noise.
# shellcheck disable=SC2310,SC2312
set -euo pipefail

# ---------------------------------------------------------------- pure helpers

# sha7 <sha>: first 7 characters.
sha7() { printf '%s' "${1:0:7}"; }

# same_sha <a> <b>: true when one is a non-empty prefix of the other (>= 7 chars).
same_sha() {
  local a=$1 b=$2
  [[ ${#a} -ge 7 && ${#b} -ge 7 ]] || return 1
  [[ ${a} == "${b}"* || ${b} == "${a}"* ]]
}

# repo_slug <url>: owner/repo from an https or ssh GitHub remote URL.
repo_slug() {
  local url=${1%.git}
  url=${url%/}
  case ${url} in
    *github.com[:/]*/*) url=${url#*github.com[:/]} ;;
    *) return 1 ;;
  esac
  printf '%s' "${url}"
}

# vsix_name <version> <sha>: provenance-carrying file name.
vsix_name() { printf 'gvy-%s+g%s.vsix' "$1" "$(sha7 "$2")"; }

# vsix_sha7 <file name or path>: the sha7 embedded in a kept VSIX name.
vsix_sha7() {
  local base=${1##*/}
  [[ ${base} =~ \+g([0-9a-f]{7})\.vsix$ ]] || return 1
  printf '%s' "${BASH_REMATCH[1]}"
}

# evaluate_ci <required csv> <optional csv> <runs json> -> "green" or
# "<state>: <names>" with state failed|pending|missing. <runs json> is the
# GitHub `actions/runs?head_sha=` response (or just its workflow_runs array).
# <optional csv> workflows may be absent (path-filtered) but, if present, must
# have passed. For each workflow only the newest run counts (reruns win).
evaluate_ci() {
  local required=$1 optional=$2 runs=$3
  jq -r --arg req "${required}" --arg opt "${optional}" '
    def names(s): s | split(",") | map(select(length > 0));
    (if type == "object" then .workflow_runs else . end) as $runs
    | (names($req) + names($opt) | unique) as $all
    | names($opt) as $optional
    | [ $all[] as $n
        | ([ $runs[] | select(.name == $n) ] | sort_by(.id) | last) as $r
        | { name: $n,
            state: (if $r == null then (if ($optional | index($n)) then "ok" else "missing" end)
                    elif $r.status != "completed" then "pending"
                    elif $r.conclusion == "success" then "ok"
                    else "failed" end) } ] as $s
    | def pick(st): [ $s[] | select(.state == st) | .name ] | join(", ");
      if   (pick("failed")  != "") then "failed: "  + pick("failed")
      elif (pick("pending") != "") then "pending: " + pick("pending")
      elif (pick("missing") != "") then "missing: " + pick("missing")
      else "green" end
  ' <<<"${runs}"
}

# jq_field <json> <jq filter>: string value, empty when null/absent/invalid.
jq_field() { jq -r "$2 // empty" <<<"$1" 2>/dev/null || true; }

# provenance_ok <json> <target sha>: jar/record is exactly the target and clean.
# Accepts both `gls version --json` (gitDirty) and .gls-version (dirty).
provenance_ok() {
  local json=$1 target=$2 sha dirty
  sha=$(jq_field "${json}" '.gitSha')
  dirty=$(jq -r '(if has("gitDirty") then .gitDirty else .dirty end) | tostring' <<<"${json}" 2>/dev/null || echo "?")
  same_sha "${sha}" "${target}" && [[ ${dirty} == "false" ]]
}

# editor_cli_candidates <editor>: CLI names to try, in order.
editor_cli_candidates() {
  case $1 in
    code) echo code ;;
    cursor) echo cursor ;;
    windsurf) echo "windsurf surf" ;;
    agy) echo agy ;;
    kiro) echo kiro ;;
    *) return 1 ;;
  esac
}

# editor_ext_root <editor> <home>: where the editor keeps installed extensions.
editor_ext_root() {
  case $1 in
    code) echo "$2/.vscode/extensions" ;;
    cursor) echo "$2/.cursor/extensions" ;;
    windsurf) echo "$2/.windsurf/extensions" ;;
    agy) echo "$2/.antigravity/extensions" ;;
    kiro) echo "$2/.kiro/extensions" ;;
    *) return 1 ;;
  esac
}

# prune_list <keep> <names newest-first on stdin>: names to delete.
prune_list() { tail -n +"$(($1 + 1))"; }

# ------------------------------------------------------------------------- IO

log() { printf 'ext-latest: %s\n' "$*" >&2; }
die() {
  printf 'ext-latest: error: %s\n' "$*" >&2
  exit 1
}
need() { command -v "$1" >/dev/null 2>&1 || die "required tool not found: $1"; }

# Configuration (all overridable from the environment).
load_config() {
  REPO_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
  CACHE_ROOT=${EXT_LATEST_CACHE_DIR:-${XDG_CACHE_HOME:-${HOME}/.cache}/gvy/ext-latest}
  SRC_DIR=${EXT_LATEST_SRC_DIR:-${CACHE_ROOT}/src}
  VSIX_DIR=${EXT_LATEST_VSIX_DIR:-${XDG_DATA_HOME:-${HOME}/.local/share}/gvy/vsix}
  KEEP=${EXT_LATEST_KEEP:-3}
  MAX_WALK=${EXT_LATEST_MAX_COMMITS:-20}
  BRANCH=${EXT_LATEST_BRANCH:-main}
  REQUIRED=${EXT_LATEST_REQUIRED:-CI}
  OPTIONAL=${EXT_LATEST_OPTIONAL:-Extension CI}
  EDITORS=${EXT_EDITORS:-code}
  DRY_RUN=${DRY_RUN:-0}
  FORCE=${FORCE:-0}
  [[ ${KEEP} =~ ^[1-9][0-9]*$ ]] || die "EXT_LATEST_KEEP must be a positive integer"
  [[ ${MAX_WALK} =~ ^[1-9][0-9]*$ ]] || die "EXT_LATEST_MAX_COMMITS must be a positive integer"
  ORIGIN_URL=${EXT_LATEST_REPO_URL:-$(git -C "${REPO_ROOT}" remote get-url origin 2>/dev/null || true)}
  [[ -n ${ORIGIN_URL} ]] || die "cannot determine the origin URL; set EXT_LATEST_REPO_URL"
  SLUG=${EXT_LATEST_REPO:-$(repo_slug "${ORIGIN_URL}")} ||
    die "origin is not a GitHub URL; set EXT_LATEST_REPO=owner/repo"
}

# ci_status_io <sha>: evaluate_ci over the live workflow runs for the commit.
ci_status_io() {
  local runs
  runs=$(gh api "repos/${SLUG}/actions/runs?head_sha=$1&per_page=100") ||
    die "gh api failed for runs of $1"
  evaluate_ci "${REQUIRED}" "${OPTIONAL}" "${runs}"
}

# pick_target_io: set TARGET_SHA and TARGET_CI.
pick_target_io() {
  if [[ -n ${EXT_LATEST_SHA:-} ]]; then
    TARGET_SHA=$(gh api "repos/${SLUG}/commits/${EXT_LATEST_SHA}" --jq .sha) ||
      die "unknown commit: ${EXT_LATEST_SHA}"
    TARGET_CI=$(ci_status_io "${TARGET_SHA}")
    if [[ ${TARGET_CI} != green && ${EXT_LATEST_ALLOW_UNGREEN:-0} != 1 ]]; then
      die "$(sha7 "${TARGET_SHA}") is not green (${TARGET_CI}); set EXT_LATEST_ALLOW_UNGREEN=1 to override"
    fi
    return
  fi
  local shas sha status skipped=0
  shas=$(gh api "repos/${SLUG}/commits?sha=${BRANCH}&per_page=${MAX_WALK}" --jq '.[].sha') ||
    die "cannot list commits of ${BRANCH}"
  for sha in ${shas}; do
    status=$(ci_status_io "${sha}")
    if [[ ${status} == green ]]; then
      TARGET_SHA=${sha}
      TARGET_CI=green
      log "target $(sha7 "${sha}") (skipped ${skipped} newer commit(s) not green)"
      return
    fi
    log "skip $(sha7 "${sha}"): ${status}"
    skipped=$((skipped + 1))
  done
  if [[ ${EXT_LATEST_ALLOW_UNGREEN:-0} == 1 ]]; then
    TARGET_SHA=${shas%%$'\n'*}
    TARGET_CI="ungreen (allowed)"
    log "no green commit in the last ${MAX_WALK}; EXT_LATEST_ALLOW_UNGREEN=1, using $(sha7 "${TARGET_SHA}")"
    return
  fi
  die "no commit on ${BRANCH} within the last ${MAX_WALK} has green CI (${REQUIRED}); raise EXT_LATEST_MAX_COMMITS or wait"
}

# editor_cli <editor>: first CLI that exists, else empty.
editor_cli() {
  local c
  for c in $(editor_cli_candidates "$1"); do
    if command -v "${c}" >/dev/null 2>&1; then
      echo "${c}"
      return
    fi
  done
}

# installed_dir <editor>: newest installed extension directory, else empty.
installed_dir() {
  local root
  root=$(editor_ext_root "$1" "${HOME}")
  # shellcheck disable=SC2012  # ls -td is the portable newest-first listing here
  ls -td "${root}"/albertocavalcante.gvy-* 2>/dev/null | head -n 1 || true
}

# installed_provenance <editor>: provenance JSON of the installed server.
installed_provenance() {
  local dir rec
  dir=$(installed_dir "$1")
  [[ -n ${dir} ]] || return 0
  rec=$(cat "${dir}/server/.gls-version" 2>/dev/null || true)
  if [[ -n $(jq_field "${rec}" '.gitSha') ]]; then
    printf '%s' "${rec}"
  elif [[ -f ${dir}/server/gls.jar ]]; then
    # Legacy record (plain text) or none: ask the jar itself.
    java -jar "${dir}/server/gls.jar" version --json 2>/dev/null | tail -n 1 || true
  fi
}

# selected_editors_io: editors from EXT_EDITORS whose CLI exists.
selected_editors_io() {
  local e out=""
  for e in ${EDITORS//,/ }; do
    editor_cli_candidates "${e}" >/dev/null || die "unknown editor '${e}' (code cursor windsurf agy kiro)"
    if [[ -n $(editor_cli "${e}") ]]; then
      out="${out} ${e}"
    else
      log "skip ${e}: CLI not found"
    fi
  done
  echo "${out}"
}

# is_up_to_date_io <sha> <editors>: every editor already runs exactly <sha>.
is_up_to_date_io() {
  local sha=$1 e prov
  [[ -n $2 ]] || return 1
  for e in $2; do
    prov=$(installed_provenance "${e}")
    [[ -n ${prov} ]] && provenance_ok "${prov}" "${sha}" || return 1
  done
}

ensure_clone_io() {
  if [[ ! -d ${SRC_DIR}/.git ]]; then
    log "cloning ${ORIGIN_URL} into ${SRC_DIR} (first run)"
    mkdir -p "$(dirname "${SRC_DIR}")"
    git clone --filter=blob:none "${ORIGIN_URL}" "${SRC_DIR}"
  fi
  # Safety: everything below destroys local state, so it must only ever run in
  # the dedicated clone, never in a developer checkout.
  [[ $(cd "${SRC_DIR}" && pwd -P) != "$(cd "${REPO_ROOT}" && pwd -P)" ]] ||
    die "SRC_DIR must not be the developer checkout"
  [[ -d ${SRC_DIR}/.git ]] || die "${SRC_DIR} is not a standalone clone (a worktree has a .git file)"
  git -C "${SRC_DIR}" remote set-url origin "${ORIGIN_URL}"
  git -C "${SRC_DIR}" fetch --quiet --prune origin
  git -C "${SRC_DIR}" cat-file -e "$1^{commit}" 2>/dev/null ||
    git -C "${SRC_DIR}" fetch --quiet origin "$1"
  git -C "${SRC_DIR}" checkout --quiet --force --detach "$1"
  # Keep node_modules (pnpm store hardlinks make reinstalls cheap anyway);
  # everything else, including build outputs and old VSIXes, goes.
  git -C "${SRC_DIR}" clean -ffdxq -e node_modules
}

build_jar_io() {
  local gradle=(./gradlew :gls:stageEditorServer -Dorg.gradle.daemon.idletimeout=300000)
  if command -v build-slot >/dev/null 2>&1; then
    (cd "${SRC_DIR}" && build-slot -- "${gradle[@]}")
  else
    log "warning: build-slot not on PATH; running Gradle without the host build-slot guard"
    (cd "${SRC_DIR}" && "${gradle[@]}")
  fi
  JAR=${SRC_DIR}/gls/build/editor-server/gls.jar
  [[ -f ${JAR} ]] || die "Gradle did not produce ${JAR}"
  local prov
  prov=$(java -jar "${JAR}" version --json 2>/dev/null | tail -n 1) ||
    die "built jar cannot report 'version --json'; commit $(sha7 "$1") predates build provenance (#1579), so its provenance cannot be verified. Use a newer commit"
  provenance_ok "${prov}" "$1" ||
    die "built jar provenance mismatch: expected clean $(sha7 "$1"), got: ${prov}"
}

package_vsix_io() {
  local ext=${SRC_DIR}/editors/code
  need pnpm
  need unzip
  (
    cd "${ext}"
    export GLS_LOCAL_JAR=${JAR} GLS_REQUIRE_FRESH=1
    pnpm install --frozen-lockfile
    pnpm run package
  )
  local vsixes=()
  local f
  for f in "${ext}"/gvy-*.vsix; do [[ -f ${f} ]] && vsixes+=("${f}"); done
  [[ ${#vsixes[@]} -eq 1 ]] || die "expected exactly one VSIX in ${ext}, found ${#vsixes[@]}"
  BUILT_VSIX=${vsixes[0]}
  unzip -p "${BUILT_VSIX}" extension/server/gls.jar | cmp -s - "${JAR}" ||
    die "VSIX does not contain the verified jar"
}

# store_vsix_io <sha> <built vsix>: copy under a provenance name, prune old.
store_vsix_io() {
  local version
  version=$(jq -r .version "${SRC_DIR}/editors/code/package.json")
  mkdir -p "${VSIX_DIR}"
  STORED_VSIX=${VSIX_DIR}/$(vsix_name "${version}" "$1")
  cp "$2" "${STORED_VSIX}.tmp"
  mv -f "${STORED_VSIX}.tmp" "${STORED_VSIX}"
  touch "${STORED_VSIX}"
  prune_io
}

list_vsix_io() {
  # shellcheck disable=SC2012  # names are ours (gvy-<ver>+g<sha7>.vsix); newest first
  ls -t "${VSIX_DIR}"/gvy-*+g*.vsix 2>/dev/null || true
}

prune_io() {
  local f
  while IFS= read -r f; do
    [[ -n ${f} ]] || continue
    log "pruning old VSIX $(basename "${f}")"
    rm -f "${f}"
  done < <(list_vsix_io | prune_list "${KEEP}")
}

# install_vsix_io <vsix> <editors>: install and verify byte-for-byte.
install_vsix_io() {
  local vsix=$1 e cli dir want_sha jar
  jar=$(mktemp "${TMPDIR:-/tmp}/ext-latest-jar.XXXXXX")
  TMP_JAR=${jar}
  unzip -p "${vsix}" extension/server/gls.jar >"${jar}"
  want_sha=$(vsix_sha7 "${vsix}") || die "not a provenance-named VSIX: ${vsix}"
  for e in $2; do
    cli=$(editor_cli "${e}")
    log "installing $(basename "${vsix}") into ${e} (${cli})"
    "${cli}" --install-extension "${vsix}" --force
    dir=$(installed_dir "${e}")
    [[ -n ${dir} ]] || die "${e}: extension directory not found after install"
    cmp -s "${dir}/server/gls.jar" "${jar}" || die "${e}: installed server/gls.jar differs from the VSIX jar"
    log "verified ${e}: server/gls.jar matches byte-for-byte"
  done
  rm -f "${jar}"
  log "Reload the window (Developer: Reload Window) to use g${want_sha}"
}

cmd_latest() {
  need git
  need gh
  need jq
  need java
  pick_target_io
  local editors
  editors=$(selected_editors_io)
  [[ -n ${editors} ]] || die "none of the selected editors (${EDITORS}) has a CLI on PATH"
  log "target: ${TARGET_SHA} (CI: ${TARGET_CI})"
  if [[ ${FORCE} != 1 ]] && is_up_to_date_io "${TARGET_SHA}" "${editors}"; then
    log "already up to date ($(sha7 "${TARGET_SHA}")) in:${editors}"
    exit 0
  fi
  if [[ ${DRY_RUN} == 1 ]]; then
    log "DRY_RUN: would build $(sha7 "${TARGET_SHA}") in ${SRC_DIR}, store the VSIX in ${VSIX_DIR} (keep ${KEEP}), install into:${editors}"
    exit 0
  fi
  local kept
  kept=$(list_vsix_io | grep -F "+g$(sha7 "${TARGET_SHA}").vsix" | head -n 1 || true)
  if [[ -n ${kept} && ${FORCE} != 1 ]]; then
    log "reusing kept VSIX $(basename "${kept}")"
    touch "${kept}"
    install_vsix_io "${kept}" "${editors}"
    return
  fi
  ensure_clone_io "${TARGET_SHA}"
  build_jar_io "${TARGET_SHA}"
  package_vsix_io
  store_vsix_io "${TARGET_SHA}" "${BUILT_VSIX}"
  install_vsix_io "${STORED_VSIX}" "${editors}"
}

cmd_rollback() {
  need jq
  need unzip
  local editors current="" e prov want=${1:-} f
  editors=$(selected_editors_io)
  [[ -n ${editors} ]] || die "none of the selected editors (${EDITORS}) has a CLI on PATH"
  for e in ${editors}; do
    prov=$(installed_provenance "${e}")
    [[ -n ${prov} ]] || continue
    current=$(jq_field "${prov}" '.gitSha')
    [[ -n ${current} ]] && break
  done
  local target=""
  while IFS= read -r f; do
    [[ -n ${f} ]] || continue
    local s
    s=$(vsix_sha7 "${f}") || continue
    if [[ -n ${want} ]]; then
      [[ ${s} == "${want}"* ]] && target=${f} && break
    elif [[ -z ${current} ]] || ! same_sha "${s}" "${current}"; then
      target=${f}
      break
    fi
  done < <(list_vsix_io)
  [[ -n ${target} ]] || die "no kept VSIX to roll back to in ${VSIX_DIR}"
  if [[ ${DRY_RUN} == 1 ]]; then
    log "DRY_RUN: would reinstall $(basename "${target}") into:${editors}"
    exit 0
  fi
  install_vsix_io "${target}" "${editors}"
}

TMP_JAR=""
cleanup() { [[ -z ${TMP_JAR} ]] || rm -f "${TMP_JAR}"; }

main() {
  trap cleanup EXIT
  load_config
  case ${1:-latest} in
    latest) cmd_latest ;;
    rollback) cmd_rollback "${2:-}" ;;
    -h | --help)
      sed -n '2,10p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      ;;
    *) die "unknown command '$1' (latest | rollback [sha])" ;;
  esac
}

# Sourced by the self-test: do not run.
if [[ ${BASH_SOURCE[0]} == "$0" ]]; then
  main "$@"
fi
