#!/usr/bin/env bash
# ext-latest: install the VS Code extension + server jar built from the newest
# origin/main commit whose CI is green (#1576).
#
#   tools/ext-latest.sh [latest]        build + install the newest green main
#   tools/ext-latest.sh rollback [sha]  step back to the next older kept VSIX
#
# The build happens in a dedicated clone under the cache dir, never in a
# developer worktree. Configuration is environment-only; see
# editors/code/README.md ("Run the latest green main").
#
# Pure helpers (sha7, commit_states, verdicts, rollback_pick, ...) are at the
# top and are exercised by tools/ext-latest_test.sh; IO lives in the *_io
# functions and main below. Written for bash 3.2 (macOS system bash).
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

# sha_matches <wanted> <sha7 from a VSIX name>: full shas must agree on the
# first 7 characters; shorter prefixes are matched literally.
sha_matches() {
  if [[ ${#1} -ge 7 ]]; then same_sha "$1" "$2"; else [[ -n $1 && $2 == "$1"* ]]; fi
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

# commit_states <required csv> <optional csv> <proof spec> <runs json> <jobs json>
# One commit's workflow state as a JSON object {workflow: state}. <runs json> is
# the `actions/runs?head_sha=` response (or its workflow_runs array); <jobs json>
# maps run id -> [{name, conclusion}]. States:
#   ok        completed, succeeded, and a proof job actually ran and passed
#   unproven  the workflow did not run (optional) or succeeded without running
#             its proof job (path filter skipped the build): inherits its parent
#   pending | failed | missing   never acceptable
# <proof spec> is "Workflow=Job name prefix;Other=Prefix" (a workflow without an
# entry needs no proof). Only the newest run (highest id) of a workflow counts;
# a rerun keeps its id and bumps run_attempt, and the API reports the latest.
commit_states() {
  # The two JSON documents go through stdin: a real runs response is ~13 KB per
  # run, and a single argv entry is capped at 128 KB on Linux (E2BIG).
  { printf '%s\n' "$4" && printf '%s\n' "$5"; } | jq -n -c --arg req "$1" --arg opt "$2" --arg proof "$3" '
    input as $runs | input as $jobs |
    def names(s): s | split(",") | map(select(length > 0));
    def prefixes($wf): [ $proof | split(";")[] | select(length > 0)
                         | split("=") | select(.[0] == $wf) | .[1] ];
    ($runs | if type == "object" then .workflow_runs else . end) as $rs
    | names($opt) as $optional
    | (names($req) + $optional | unique) as $all
    | reduce $all[] as $n ({}; . + { ($n): (
        ([ $rs[] | select(.name == $n) ] | sort_by(.id) | last) as $r
        | prefixes($n) as $p
        | if $r == null then (if ($optional | index($n)) then "unproven" else "missing" end)
          elif $r.status != "completed" then "pending"
          elif $r.conclusion != "success" then "failed"
          elif ($p | length) == 0 then "ok"
          elif ([ ($jobs[($r.id | tostring)] // [])[]
                  | select(.conclusion == "success")
                  | .name as $j | select($p | any(. as $x | $j | startswith($x))) ] | length) > 0
          then "ok"
          else "unproven" end) })'
}

# verdicts <states json array, newest commit first>: one line per commit,
# "green", "unknown" (waiting on ancestors outside the window) or "red: <wf> <state>".
# An unproven workflow takes the state its parent commit has for it.
verdicts() {
  jq -r '
    . as $S
    | def eff($i; $k):
        if $i >= ($S | length) then "unknown"
        else ($S[$i][$k]) as $s | if $s == "unproven" then eff($i + 1; $k) else $s end end;
      def verdict($i):
        [ $S[$i] | keys[] as $k | { k: $k, s: eff($i; $k) } ] as $e
        | ([ $e[] | select(.s == "failed") ] + [ $e[] | select(.s == "pending") ]
           + [ $e[] | select(.s == "missing") ]) as $bad
        | if ($bad | length) > 0 then "red: " + $bad[0].k + " " + $bad[0].s
          elif any($e[]; .s == "unknown") then "unknown"
          else "green" end;
      range(0; $S | length) | verdict(.)
  ' <<<"$1"
}

# choose <latest|force> <verdict lines>: "green <index>", "red <index> <why>",
# "more" (needs older commits) or "none".
choose() {
  local mode=$1 i=0 line
  while IFS= read -r line; do
    case ${line} in
      green) echo "green ${i}" && return 0 ;;
      unknown) echo more && return 0 ;;
      *) [[ ${mode} != force ]] || {
        echo "red ${i} ${line}"
        return 0
      } ;;
    esac
    i=$((i + 1))
  done <<<"$2"
  echo none
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

# vsix_provenance_ok <vsix> <sha>: the VSIX's own server record names <sha>
# (full, or the 7 characters of a kept file name) and is a clean build.
vsix_provenance_ok() {
  local rec
  rec=$(unzip -p "$1" extension/server/.gls-version 2>/dev/null || true)
  [[ -n ${rec} ]] && provenance_ok "${rec}" "$2"
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

# prune_list <keep> <protected sha7 csv> <names newest-first on stdin>: names to
# delete. The newest <keep> are kept, and so is any name whose sha7 is protected.
prune_list() {
  local keep=$1 protected=$2 n=0 name s
  while IFS= read -r name; do
    n=$((n + 1))
    [[ ${n} -gt ${keep} ]] || continue
    s=$(vsix_sha7 "${name}" || true)
    if [[ -n ${s} ]]; then
      case ",${protected}," in *",${s},"*) continue ;; *) ;; esac
    fi
    echo "${name}"
  done
}

# rollback_pick <installed sha or ""> <wanted or ""> <names newest-first on stdin>:
# the VSIX to install. With <wanted>, the first name matching that sha prefix.
# Otherwise one step back in build order from the installed sha (the next older
# name); when the installed sha is not among the kept files, the newest name.
# Prints nothing and fails when there is nothing to roll back to.
rollback_pick() {
  local installed=$1 wanted=$2 name s found=0
  while IFS= read -r name; do
    s=$(vsix_sha7 "${name}" || true)
    [[ -n ${s} ]] || continue
    if [[ -n ${wanted} ]]; then
      sha_matches "${wanted}" "${s}" && echo "${name}" && return 0
    elif [[ -z ${installed} ]]; then
      echo "${name}" && return 0
    elif [[ ${found} == 1 ]]; then
      echo "${name}" && return 0
    elif same_sha "${s}" "${installed}"; then
      found=1
    fi
  done
  return 1
}

# in_kept <installed sha> <names on stdin>: the installed sha is among the kept files.
in_kept() {
  local installed=$1 name s
  while IFS= read -r name; do
    s=$(vsix_sha7 "${name}" || true)
    [[ -n ${s} ]] && same_sha "${s}" "${installed}" && return 0
  done
  return 1
}

# build_env_clean: names of variables that would redirect prepare-server away
# from the jar we built (same list as editors/code/tools/source-install.js).
build_env_clean() {
  echo GLS_TAG GLS_CHANNEL GLS_URL GLS_CHECKSUM GLS_USE_PINNED USE_LATEST_GLS USE_LATEST_GROOVY_LSP PREFER_LOCAL BUILD_LOCAL
}

# ------------------------------------------------------------------------- IO

log() { printf 'ext-latest: %s\n' "$*" >&2; }
die() {
  printf 'ext-latest: error: %s\n' "$*" >&2
  exit 1
}
need() { command -v "$1" >/dev/null 2>&1 || die "required tool not found: $1"; }

TMP_JAR=""
TMP_VSIX=""
LOCK_DIR=""

# cleanup: remove temp files and release the lock if this process owns them.
cleanup() {
  [[ -z ${TMP_JAR} ]] || rm -f "${TMP_JAR}"
  [[ -z ${TMP_VSIX} ]] || rm -f "${TMP_VSIX}"
  if [[ -n ${LOCK_DIR} && $(cat "${LOCK_DIR}/pid" 2>/dev/null || true) == "$$" ]]; then
    rm -rf "${LOCK_DIR}"
  fi
}

# reap_stale_lock <dir> <dead pid or ""> : remove the lock only if it still
# names the dead holder we saw. Serialized by a guard directory so two
# contenders cannot both remove, and so the re-check cannot be raced by another
# contender that already took over and wrote its own pid.
reap_stale_lock() {
  local dir=$1 seen=$2 guard="$1.takeover" now
  mkdir "${guard}" 2>/dev/null || return 1
  now=$(cat "${dir}/pid" 2>/dev/null || true)
  if [[ ${now} == "${seen}" && -d ${dir} ]]; then
    rm -rf "${dir}"
    rmdir "${guard}"
    return 0
  fi
  rmdir "${guard}"
  return 1
}

# acquire_lock_io <dir>: one ext-latest at a time (mkdir is atomic). A lock
# whose pid is no longer running is stale and is taken over via reap_stale_lock.
acquire_lock_io() {
  local dir=$1 pid="" tries=0
  mkdir -p "$(dirname "${dir}")"
  while ! mkdir "${dir}" 2>/dev/null; do
    pid=$(cat "${dir}/pid" 2>/dev/null || true)
    if [[ -z ${pid} ]]; then
      sleep 1 # the holder may be between mkdir and writing its pid
      pid=$(cat "${dir}/pid" 2>/dev/null || true)
    fi
    if [[ -n ${pid} ]] && kill -0 "${pid}" 2>/dev/null; then
      die "another ext-latest run is in progress (pid ${pid}, lock ${dir}); wait for it to finish"
    fi
    tries=$((tries + 1))
    [[ ${tries} -le 10 ]] || die "cannot take lock ${dir}; if no ext-latest is running, remove ${dir} and ${dir}.takeover"
    if reap_stale_lock "${dir}" "${pid}"; then
      log "removed stale lock (pid ${pid:-unknown} is not running)"
    else
      sleep 1
    fi
  done
  echo "$$" >"${dir}/pid"
  LOCK_DIR=${dir}
}

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
  PROOF=${EXT_LATEST_PROOF:-CI=Build and Test;Extension CI=Build current server}
  EDITORS=${EXT_EDITORS:-code}
  DRY_RUN=${DRY_RUN:-0}
  FORCE=${FORCE:-0}
  [[ ${KEEP} =~ ^[1-9][0-9]*$ ]] || die "EXT_LATEST_KEEP must be a positive integer"
  [[ ${MAX_WALK} =~ ^[1-9][0-9]*$ ]] || die "EXT_LATEST_MAX_COMMITS must be a positive integer"
  if [[ ${MAX_WALK} -gt 100 ]]; then
    log "EXT_LATEST_MAX_COMMITS=${MAX_WALK} capped at 100 (GitHub page size)"
    MAX_WALK=100
  fi
  ORIGIN_URL=${EXT_LATEST_REPO_URL:-$(git -C "${REPO_ROOT}" remote get-url origin 2>/dev/null || true)}
  [[ -n ${ORIGIN_URL} ]] || die "cannot determine the origin URL; set EXT_LATEST_REPO_URL"
  SLUG=${EXT_LATEST_REPO:-$(repo_slug "${ORIGIN_URL}")} ||
    die "origin is not a GitHub URL; set EXT_LATEST_REPO=owner/repo"
}

# commit_states_io <sha>: live workflow states for one commit. Job lists are
# fetched only for succeeded runs of the workflows we care about.
commit_states_io() {
  local runs jobs='{}' id j
  runs=$(gh api "repos/${SLUG}/actions/runs?head_sha=$1&per_page=100" \
    --jq '[.workflow_runs[] | {id, name, status, conclusion, run_attempt, head_sha}]') ||
    die "gh api failed for runs of $1"
  for id in $(jq -r --arg names "${REQUIRED},${OPTIONAL}" '
      (if type == "object" then .workflow_runs else . end)[]
      | select(.conclusion == "success")
      | select(.name as $n | $names | split(",") | index($n))
      | .id' <<<"${runs}"); do
    j=$(gh api "repos/${SLUG}/actions/runs/${id}/jobs?per_page=100" --jq '[.jobs[] | {name, conclusion}]') ||
      die "gh api failed for jobs of run ${id}"
    jobs=$(jq -c --arg id "${id}" --argjson j "${j}" '. + {($id): $j}' <<<"${jobs}")
  done
  commit_states "${REQUIRED}" "${OPTIONAL}" "${PROOF}" "${runs}" "${jobs}"
}

# pick_target_io: set TARGET_SHA and TARGET_CI. Walks the first-parent history
# from the branch head (or from EXT_LATEST_SHA, whose own verdict then decides),
# at most MAX_WALK commits, evaluating each commit's workflows and letting a
# skipped build inherit its parent's state.
pick_target_io() {
  local ref=${BRANCH} mode=latest shas sha states='[]' st v choice=none i=0 skipped_note=""
  if [[ -n ${EXT_LATEST_SHA:-} ]]; then
    mode=force
    ref=$(gh api "repos/${SLUG}/commits/${EXT_LATEST_SHA}" --jq .sha) ||
      die "unknown commit: ${EXT_LATEST_SHA}"
  fi
  shas=$(gh api "repos/${SLUG}/commits?sha=${ref}&per_page=${MAX_WALK}" --jq '.[].sha') ||
    die "cannot list commits of ${ref}"
  SHAS=()
  while IFS= read -r sha; do [[ -z ${sha} ]] || SHAS+=("${sha}"); done <<<"${shas}"
  [[ ${#SHAS[@]} -gt 0 ]] || die "no commits found for ${ref}"
  for sha in "${SHAS[@]}"; do
    st=$(commit_states_io "${sha}")
    log "$(sha7 "${sha}"): $(jq -r 'to_entries | map("\(.key)=\(.value)") | join(", ")' <<<"${st}")"
    states=$(jq -c --argjson s "${st}" '. + [$s]' <<<"${states}")
    v=$(verdicts "${states}")
    choice=$(choose "${mode}" "${v}")
    case ${choice} in
      green*)
        i=${choice#green }
        TARGET_SHA=${SHAS[${i}]}
        TARGET_CI=green
        [[ ${i} -eq 0 ]] || skipped_note=" (skipped ${i} newer commit(s) not green)"
        log "target $(sha7 "${TARGET_SHA}")${skipped_note}"
        return
        ;;
      red*)
        TARGET_SHA=${SHAS[0]}
        TARGET_CI=${choice#red 0 }
        break
        ;;
      *) ;;
    esac
  done
  # Nothing decided green.
  local why
  TARGET_SHA=${SHAS[0]}
  case ${choice} in
    red*) why=${choice#red 0 } ;;
    more) why="undetermined: skipped builds inherit from parents outside the last ${MAX_WALK} commits; raise EXT_LATEST_MAX_COMMITS (max 100)" ;;
    *) why="no green commit within the last ${MAX_WALK} commits of ${ref}" ;;
  esac
  TARGET_CI=${why}
  if [[ ${EXT_LATEST_ALLOW_UNGREEN:-0} == 1 ]]; then
    TARGET_CI="ungreen (allowed): ${why}"
    log "EXT_LATEST_ALLOW_UNGREEN=1: using $(sha7 "${TARGET_SHA}") -- ${TARGET_CI}"
    return
  fi
  die "$(sha7 "${TARGET_SHA}") is not green (${why}). Set EXT_LATEST_ALLOW_UNGREEN=1 to override"
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

# installed_sha7s_io <editors>: csv of the sha7 installed in each editor.
installed_sha7s_io() {
  local e prov s out=""
  for e in $1; do
    prov=$(installed_provenance "${e}")
    [[ -n ${prov} ]] || continue
    s=$(sha7 "$(jq_field "${prov}" '.gitSha')")
    [[ -z ${s} ]] || out="${out},${s}"
  done
  echo "${out#,}"
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

# ensure_clone_io <sha>: the only place that fetches/checks out/cleans, and only
# in a clone this script created (marked .git/ext-latest-owned).
ensure_clone_io() {
  local marker=${SRC_DIR}/.git/ext-latest-owned
  if [[ ! -e ${SRC_DIR} || (-d ${SRC_DIR} && -z $(ls -A "${SRC_DIR}" 2>/dev/null || true)) ]]; then
    log "cloning ${ORIGIN_URL} into ${SRC_DIR} (first run)"
    mkdir -p "$(dirname "${SRC_DIR}")"
    git clone --filter=blob:none "${ORIGIN_URL}" "${SRC_DIR}" ||
      {
        rm -rf "${SRC_DIR}"
        die "git clone failed"
      }
    : >"${marker}"
  fi
  # Safety: fetch --force/checkout --force/clean -ffdx destroy local state, so
  # they must only ever run in a clone this script made, never in a checkout.
  [[ -f ${marker} && -d ${SRC_DIR}/.git ]] ||
    die "${SRC_DIR} was not created by ext-latest (no .git/ext-latest-owned marker); refusing to touch it. Point EXT_LATEST_SRC_DIR at an empty or missing directory"
  [[ $(cd "${SRC_DIR}" && pwd -P) != "$(cd "${REPO_ROOT}" && pwd -P)" ]] ||
    die "SRC_DIR must not be the developer checkout"
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
  local ext=${SRC_DIR}/editors/code v
  need pnpm
  need unzip
  (
    cd "${ext}"
    # Same environment source-install.js gives prepare-server: our jar, nothing
    # that could redirect it to a download. (An explicit GLS_LOCAL_JAR is
    # exempt from freshness checks; the provenance check above is the gate.)
    for v in $(build_env_clean); do unset "${v}"; done
    export GLS_LOCAL_JAR=${JAR} FORCE_DOWNLOAD=true SKIP_PREPARE_SERVER=false GLS_ALLOW_PINNED_FALLBACK=false
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

# store_vsix_io <sha> <built vsix>: copy under a provenance name (temp file in
# the same directory, then rename) and re-verify the stored copy against $JAR.
store_vsix_io() {
  local version
  version=$(jq -r .version "${SRC_DIR}/editors/code/package.json")
  mkdir -p "${VSIX_DIR}"
  STORED_VSIX=${VSIX_DIR}/$(vsix_name "${version}" "$1")
  TMP_VSIX=$(mktemp "${VSIX_DIR}/.gvy-tmp.XXXXXX")
  cp "$2" "${TMP_VSIX}"
  mv -f "${TMP_VSIX}" "${STORED_VSIX}"
  TMP_VSIX=""
  unzip -p "${STORED_VSIX}" extension/server/gls.jar | cmp -s - "${JAR}" || {
    rm -f "${STORED_VSIX}"
    die "stored VSIX jar differs from the verified jar"
  }
}

list_vsix_io() {
  # shellcheck disable=SC2012  # names are ours (gvy-<ver>+g<sha7>.vsix); newest first
  ls -t "${VSIX_DIR}"/gvy-*+g*.vsix 2>/dev/null || true
}

# prune_io <editors>: keep the newest KEEP files plus anything installed. Only
# called after a verified install.
prune_io() {
  local f protected
  protected=$(installed_sha7s_io "$1")
  while IFS= read -r f; do
    [[ -n ${f} ]] || continue
    log "pruning old VSIX $(basename "${f}")"
    rm -f "${f}"
  done < <(list_vsix_io | prune_list "${KEEP}" "${protected}")
}

# install_vsix_io <vsix> <editors> [expected jar]: install and verify the
# installed server/gls.jar byte-for-byte against the expected jar (the verified
# build output, or the jar extracted from a provenance-checked kept VSIX).
install_vsix_io() {
  local vsix=$1 e cli dir want_sha jar=${3:-}
  want_sha=$(vsix_sha7 "${vsix}") || die "not a provenance-named VSIX: ${vsix}"
  if [[ -z ${jar} ]]; then
    TMP_JAR=$(mktemp "${TMPDIR:-/tmp}/ext-latest-jar.XXXXXX")
    jar=${TMP_JAR}
    unzip -p "${vsix}" extension/server/gls.jar >"${jar}"
  fi
  for e in $2; do
    cli=$(editor_cli "${e}")
    log "installing $(basename "${vsix}") into ${e} (${cli})"
    "${cli}" --install-extension "${vsix}" --force
    dir=$(installed_dir "${e}")
    [[ -n ${dir} ]] || die "${e}: extension directory not found after install"
    cmp -s "${dir}/server/gls.jar" "${jar}" || die "${e}: installed server/gls.jar differs from the expected jar"
    log "verified ${e}: server/gls.jar matches byte-for-byte"
  done
  log "Reload the window (Developer: Reload Window) to use g${want_sha}"
}

# newest_kept_io <sha>: newest kept VSIX built from <sha>, else empty.
newest_kept_io() {
  list_vsix_io | grep -F "+g$(sha7 "$1").vsix" | head -n 1 || true
}

cmd_latest() {
  need git
  need gh
  need jq
  need java
  need unzip
  pick_target_io
  local editors kept
  editors=$(selected_editors_io)
  [[ -n ${editors} ]] || die "none of the selected editors (${EDITORS}) has a CLI on PATH"
  log "target: ${TARGET_SHA} (CI: ${TARGET_CI})"
  if [[ ${FORCE} != 1 ]] && is_up_to_date_io "${TARGET_SHA}" "${editors}"; then
    log "already up to date ($(sha7 "${TARGET_SHA}")) in:${editors}"
    return 0
  fi
  if [[ ${DRY_RUN} == 1 ]]; then
    log "DRY_RUN: would build $(sha7 "${TARGET_SHA}") in ${SRC_DIR}, store the VSIX in ${VSIX_DIR} (keep ${KEEP}), install into:${editors}"
    return 0
  fi
  acquire_lock_io "${CACHE_ROOT}/lock"
  kept=$(newest_kept_io "${TARGET_SHA}")
  if [[ -n ${kept} && ${FORCE} != 1 ]]; then
    if vsix_provenance_ok "${kept}" "${TARGET_SHA}"; then
      log "reusing kept VSIX $(basename "${kept}")"
      install_vsix_io "${kept}" "${editors}"
      prune_io "${editors}"
      return 0
    fi
    log "kept VSIX $(basename "${kept}") does not carry ${TARGET_SHA}; rebuilding"
  fi
  ensure_clone_io "${TARGET_SHA}"
  build_jar_io "${TARGET_SHA}"
  package_vsix_io
  store_vsix_io "${TARGET_SHA}" "${BUILT_VSIX}"
  install_vsix_io "${STORED_VSIX}" "${editors}" "${JAR}"
  prune_io "${editors}"
}

cmd_rollback() {
  need jq
  need unzip
  local editors current="" e prov target names
  editors=$(selected_editors_io)
  [[ -n ${editors} ]] || die "none of the selected editors (${EDITORS}) has a CLI on PATH"
  for e in ${editors}; do
    prov=$(installed_provenance "${e}")
    [[ -n ${prov} ]] || continue
    current=$(jq_field "${prov}" '.gitSha')
    [[ -n ${current} ]] && break
  done
  names=$(list_vsix_io)
  if [[ -z ${1:-} && -n ${current} ]] && ! in_kept "${current}" <<<"${names}"; then
    log "installed $(sha7 "${current}") is not among the kept VSIX files; using the newest"
    current=""
  fi
  target=$(rollback_pick "${current}" "${1:-}" <<<"${names}") ||
    die "no older kept VSIX to roll back to in ${VSIX_DIR}"
  if [[ ${DRY_RUN} == 1 ]]; then
    log "DRY_RUN: would reinstall $(basename "${target}") into:${editors}"
    return 0
  fi
  acquire_lock_io "${CACHE_ROOT}/lock"
  # The file name is only a label: trust the record inside the VSIX.
  vsix_provenance_ok "${target}" "$(vsix_sha7 "${target}")" ||
    die "$(basename "${target}") does not contain a clean server build for that commit; refusing to install"
  install_vsix_io "${target}" "${editors}"
}

main() {
  trap cleanup EXIT
  load_config
  case ${1:-latest} in
    latest) cmd_latest ;;
    rollback) cmd_rollback "${2:-}" ;;
    -h | --help)
      sed -n '2,9p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      ;;
    *) die "unknown command '$1' (latest | rollback [sha])" ;;
  esac
}

# Sourced by the self-test: do not run.
if [[ ${BASH_SOURCE[0]} == "$0" ]]; then
  main "$@"
fi
