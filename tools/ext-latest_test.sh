#!/usr/bin/env bash
# SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
# SPDX-License-Identifier: Apache-2.0

# Self-test for ext-latest.sh: pure helpers with fixture JSON (CI evaluation,
# skipped-build inheritance, provenance, VSIX naming, pruning, rollback choice),
# the stubbed-`gh` target walk, the clone ownership guard, the lock, and the
# provenance check on kept VSIX files. No network, no editor, no build.
# SC2310/SC2312: see ext-latest.sh. SC2034/SC2154: the sourced script reads and
# sets these globals (SLUG, BRANCH, TARGET_SHA, ...), which ShellCheck cannot see.
# shellcheck disable=SC2310,SC2312,SC2034,SC2154
set -euo pipefail

script="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/ext-latest.sh"
# shellcheck source=/dev/null
source "${script}"

work=$(mktemp -d)
trap 'rm -rf "${work}"; jobs -p | xargs kill 2>/dev/null || true' EXIT

fails=0
# expect <description> <expected> <actual>
expect() {
    if [[ $2 == "$3" ]]; then
        echo "ok   - $1"
    else
        echo "FAIL - $1: expected '$2', got '$3'"
        fails=$((fails + 1))
    fi
}
# yes_no <command...>: "yes" when it succeeds, else "no".
yes_no() { if ("$@") >/dev/null 2>&1; then echo yes; else echo no; fi; }

# ------------------------------------------------------- commit_states / verdicts

req="CI"
opt="Extension CI"
proof="CI=Build and Test;Extension CI=Build current server"

# run <name> <status> <conclusion json> <id>
run() { printf '{"id":%s,"name":"%s","status":"%s","conclusion":%s}' "$4" "$1" "$2" "$3"; }
runs() {
    local IFS=,
    printf '{"workflow_runs":[%s]}' "$*"
}
# jobs <id> <job name> <conclusion> ... -> {"id":[{name,conclusion}...]}
jobmap() {
    local id=$1 out="" sep=""
    shift
    while [[ $# -gt 0 ]]; do
        out="${out}${sep}{\"name\":\"$1\",\"conclusion\":\"$2\"}"
        sep=,
        shift 2
    done
    printf '{"%s":[%s]}' "${id}" "${out}"
}
st() {
    local j=${2-}
    [[ -n ${j} ]] || j='{}'
    commit_states "${req}" "${opt}" "${proof}" "$1" "${j}"
}

ci_ok=$(run CI completed '"success"' 1)
ext_ok=$(run 'Extension CI' completed '"success"' 2)
bt_pass=$(jobmap 1 "Lint" success "Build and Test (ubuntu-24.04, 17)" success)
bt_skip=$(jobmap 1 "Lint" skipped "Build and Test" skipped)
ext_jobs=$(jobmap 2 "Build current server" success)

expect "build ran and passed" '{"CI":"ok","Extension CI":"ok"}' "$(st "$(runs "${ci_ok}" "${ext_ok}")" "$(jq -c -s 'add' <<<"${bt_pass}${ext_jobs}")")"
expect "build skipped by path filter is unproven, not ok" '{"CI":"unproven","Extension CI":"ok"}' "$(st "$(runs "${ci_ok}" "${ext_ok}")" "$(jq -c -s 'add' <<<"${bt_skip}${ext_jobs}")")"
expect "no job data is unproven" '{"CI":"unproven","Extension CI":"unproven"}' "$(st "$(runs "${ci_ok}" "${ext_ok}")")"
expect "extension CI missing is unproven" '{"CI":"ok","Extension CI":"unproven"}' "$(st "$(runs "${ci_ok}")" "${bt_pass}")"
expect "required workflow missing" '{"CI":"missing","Extension CI":"ok"}' "$(st "$(runs "${ext_ok}")" "${ext_jobs}")"
expect "CI failed" '{"CI":"failed","Extension CI":"ok"}' "$(st "$(runs "$(run CI completed '"failure"' 1)" "${ext_ok}")" "${ext_jobs}")"
expect "CI pending" '{"CI":"pending","Extension CI":"unproven"}' "$(st "$(runs "$(run CI in_progress null 1)")")"
expect "cancelled extension CI is failed" '{"CI":"ok","Extension CI":"failed"}' "$(st "$(runs "${ci_ok}" "$(run 'Extension CI' completed '"cancelled"' 2)")" "${bt_pass}")"
# A rerun keeps its run id and bumps run_attempt; the API reports the latest
# attempt, so a single entry with run_attempt 2 and success simply counts.
expect "rerun (same id, run_attempt 2) counts as its latest outcome" '{"CI":"ok","Extension CI":"unproven"}' "$(st '[{"id":1,"run_attempt":2,"name":"CI","status":"completed","conclusion":"success"}]' "${bt_pass}")"
expect "of two runs the higher id wins" '{"CI":"failed","Extension CI":"unproven"}' "$(st "$(runs "$(run CI completed '"success"' 1)" "$(run CI completed '"failure"' 9)")" "${bt_pass}")"
expect "bare array accepted" '{"CI":"ok","Extension CI":"unproven"}' "$(st "[${ci_ok}]" "${bt_pass}")"

# verdicts over a window (newest first). s_<kind>: CI state, ext state.
ok='{"CI":"ok","Extension CI":"ok"}'
ci_skip='{"CI":"unproven","Extension CI":"ok"}'
ci_red='{"CI":"failed","Extension CI":"ok"}'
ext_miss='{"CI":"ok","Extension CI":"unproven"}'
vd() {
    local IFS=,
    verdicts "[$*]" | paste -sd'|' -
}

expect "build skipped, green parent: green" "green|green" "$(vd "${ci_skip}" "${ok}")"
expect "build skipped, red parent: red" "red: CI failed|red: CI failed" "$(vd "${ci_skip}" "${ci_red}")"
expect "scenario: A breaks server, B touches only the extension" "red: CI failed|red: CI failed|green" "$(vd "${ci_skip}" "${ci_red}" "${ok}")"
expect "chain of skipped builds resolves through to a green ancestor" "green|green|green" "$(vd "${ci_skip}" "${ci_skip}" "${ok}")"
expect "skipped build with no ancestor in window is unknown" "unknown" "$(vd "${ci_skip}")"
expect "extension CI missing inherits a green parent" "green|green" "$(vd "${ext_miss}" "${ok}")"
expect "extension CI missing inherits a red parent" "red: Extension CI failed|red: Extension CI failed" "$(vd "${ext_miss}" '{"CI":"ok","Extension CI":"failed"}')"
expect "pending is red" "red: CI pending" "$(vd '{"CI":"pending","Extension CI":"ok"}')"
expect "failed outranks pending" "red: CI failed" "$(vd '{"CI":"failed","Extension CI":"pending"}')"

expect "choose latest: first green" "green 1" "$(choose latest "$(printf 'red: CI failed\ngreen\ngreen')")"
expect "choose latest: unknown stops" "more" "$(choose latest "$(printf 'red: CI failed\nunknown')")"
expect "choose latest: all red" "none" "$(choose latest "$(printf 'red: a\nred: b')")"
expect "choose force: red head" "red 0 red: CI failed" "$(choose force "$(printf 'red: CI failed\ngreen')")"
expect "choose force: green head" "green 0" "$(choose force "$(printf 'green\nred: x')")"

# --------------------------------------------------------------- small helpers

full=0123456789abcdef0123456789abcdef01234567
expect "sha7" 0123456 "$(sha7 "${full}")"
expect "same_sha prefix" yes "$(yes_no same_sha 0123456 "${full}")"
expect "same_sha mismatch" no "$(yes_no same_sha 0123457 "${full}")"
expect "same_sha unknown" no "$(yes_no same_sha unknown "${full}")"
expect "same_sha too short" no "$(yes_no same_sha 012 "${full}")"
expect "sha_matches full sha vs sha7" yes "$(yes_no sha_matches "${full}" 0123456)"
expect "sha_matches different full sha, same 7 only if equal" no "$(yes_no sha_matches 0123457aaaa 0123456)"
expect "sha_matches short prefix" yes "$(yes_no sha_matches 0123 0123456)"
expect "sha_matches empty" no "$(yes_no sha_matches '' 0123456)"

p() { yes_no provenance_ok "$1" "${full}"; }
expect "jar json clean" yes "$(p '{"gitSha":"'"${full}"'","gitDirty":false}')"
expect "jar json dirty" no "$(p '{"gitSha":"'"${full}"'","gitDirty":true}')"
expect "record json clean" yes "$(p '{"gitSha":"'"${full}"'","dirty":false}')"
expect "dirty unknown (null)" no "$(p '{"gitSha":"'"${full}"'","gitDirty":null}')"
expect "other sha" no "$(p '{"gitSha":"ffffffffffffffffffffffffffffffffffffffff","gitDirty":false}')"
expect "legacy non-json" no "$(p 'local')"

expect "slug https" albertocavalcante/gvy "$(repo_slug https://github.com/albertocavalcante/gvy.git)"
expect "slug ssh" albertocavalcante/gvy "$(repo_slug git@github.com:albertocavalcante/gvy.git)"
expect "slug non-github rejected" no "$(yes_no repo_slug https://example.com/a/b)"

expect "vsix name" "gvy-0.5.4+g0123456.vsix" "$(vsix_name 0.5.4 "${full}")"
expect "vsix sha7 roundtrip" 0123456 "$(vsix_sha7 "/x/$(vsix_name 0.5.4 "${full}")")"
expect "vsix sha7 absent" no "$(yes_no vsix_sha7 gvy-0.5.4.vsix)"

expect "editor root code" /h/.vscode/extensions "$(editor_ext_root code /h)"
expect "windsurf CLIs" "windsurf surf" "$(editor_cli_candidates windsurf)"
expect "unknown editor rejected" no "$(yes_no editor_cli_candidates vim)"
expect "build env clean list drops GLS_TAG" yes "$(yes_no grep -q GLS_TAG <<<"$(build_env_clean)")"

# --------------------------------------------------- pruning and rollback choice

names=$'gvy-0.5.4+gdddddd4.vsix\ngvy-0.5.4+gcccccc3.vsix\ngvy-0.5.4+gbbbbbb2.vsix\ngvy-0.5.4+gaaaaaa1.vsix'
expect "prune keeps newest K" $'gvy-0.5.4+gbbbbbb2.vsix\ngvy-0.5.4+gaaaaaa1.vsix' "$(prune_list 2 '' <<<"${names}")"
expect "prune never removes the installed sha" "gvy-0.5.4+gbbbbbb2.vsix" "$(prune_list 2 aaaaaa1 <<<"${names}")"
expect "prune protects several" "" "$(prune_list 2 'aaaaaa1,bbbbbb2' <<<"${names}")"
expect "prune nothing when under K" "" "$(prune_list 9 '' <<<"${names}")"

expect "rollback steps back one from installed" "gvy-0.5.4+gcccccc3.vsix" "$(rollback_pick dddddd4aaaa '' <<<"${names}")"
expect "rollback again steps further back (no toggling)" "gvy-0.5.4+gbbbbbb2.vsix" "$(rollback_pick cccccc3aaaa '' <<<"${names}")"
expect "rollback from the oldest has nothing" no "$(yes_no rollback_pick aaaaaa1zzzz '' <<<"${names}")"
expect "rollback with nothing installed: newest" "gvy-0.5.4+gdddddd4.vsix" "$(rollback_pick '' '' <<<"${names}")"
expect "rollback explicit sha prefix" "gvy-0.5.4+gbbbbbb2.vsix" "$(rollback_pick dddddd4aaaa bbbb <<<"${names}")"
expect "rollback explicit long sha matches on its first 7" "gvy-0.5.4+gbbbbbb2.vsix" "$(rollback_pick '' bbbbbb2ffffffffffffffffffffffffffffffffff <<<"${names}")"
expect "rollback explicit unknown sha" no "$(yes_no rollback_pick '' eeeeeee <<<"${names}")"
expect "in_kept finds installed" yes "$(yes_no in_kept cccccc3aaaa <<<"${names}")"
expect "in_kept misses legacy install" no "$(yes_no in_kept 9999999aaaa <<<"${names}")"

# ------------------------------------------- VSIX provenance (kept-file reuse)

mkvsix() { # <file> <record json or ->
    local d
    d=$(mktemp -d "${work}/vsix.XXXXXX")
    mkdir -p "${d}/extension/server"
    echo jar >"${d}/extension/server/gls.jar"
    [[ $2 == - ]] || printf '%s' "$2" >"${d}/extension/server/.gls-version"
    (cd "${d}" && zip -qr "$1" extension)
}
mkvsix "${work}/good.vsix" '{"gitSha":"'"${full}"'","dirty":false,"source":"local"}'
mkvsix "${work}/other.vsix" '{"gitSha":"ffffffffffffffffffffffffffffffffffffffff","dirty":false}'
mkvsix "${work}/dirty.vsix" '{"gitSha":"'"${full}"'","dirty":true}'
mkvsix "${work}/legacy.vsix" local
mkvsix "${work}/norec.vsix" -
expect "kept VSIX with the target sha is trusted" yes "$(yes_no vsix_provenance_ok "${work}/good.vsix" "${full}")"
expect "kept VSIX is accepted by its sha7 label (rollback)" yes "$(yes_no vsix_provenance_ok "${work}/good.vsix" 0123456)"
expect "kept VSIX named for the target but built from another sha is rejected" no "$(yes_no vsix_provenance_ok "${work}/other.vsix" "${full}")"
expect "dirty kept VSIX is rejected" no "$(yes_no vsix_provenance_ok "${work}/dirty.vsix" "${full}")"
expect "legacy record is rejected" no "$(yes_no vsix_provenance_ok "${work}/legacy.vsix" "${full}")"
expect "VSIX without a record is rejected" no "$(yes_no vsix_provenance_ok "${work}/norec.vsix" "${full}")"
expect "missing file is rejected" no "$(yes_no vsix_provenance_ok "${work}/nope.vsix" "${full}")"

# ------------------------------------------------------ ownership guard + clone

origin="${work}/origin"
git init -q -b main "${origin}"
git -C "${origin}" -c user.name=t -c user.email=t@example.com -c commit.gpgsign=false commit -q --allow-empty -m one
head_sha=$(git -C "${origin}" rev-parse HEAD)
REPO_ROOT="${work}/dev-checkout"
ORIGIN_URL=${origin}

# A pre-existing checkout the script did not create must be left alone.
foreign="${work}/foreign"
git init -q -b main "${foreign}"
echo precious >"${foreign}/untracked.txt"
SRC_DIR=${foreign}
expect "unowned existing checkout is refused" no "$(yes_no ensure_clone_io "${head_sha}")"
expect "unowned checkout untouched (untracked file survives)" precious "$(cat "${foreign}/untracked.txt")"

# A missing directory is cloned, marked, and then cleaned on later runs.
SRC_DIR="${work}/owned/src"
expect "fresh clone succeeds" yes "$(yes_no ensure_clone_io "${head_sha}")"
expect "clone carries the ownership marker" yes "$(yes_no test -f "${SRC_DIR}/.git/ext-latest-owned")"
expect "clone is at the target commit" "${head_sha}" "$(git -C "${SRC_DIR}" rev-parse HEAD)"
echo junk >"${SRC_DIR}/junk.txt"
expect "owned clone is cleaned on the next run" yes "$(ensure_clone_io "${head_sha}" >/dev/null 2>&1 && yes_no test ! -e "${SRC_DIR}/junk.txt")"
SRC_DIR=${work}/dev-checkout
mkdir -p "${SRC_DIR}"
git init -q -b main "${SRC_DIR}"
: >"${SRC_DIR}/.git/ext-latest-owned"
expect "SRC_DIR equal to the invoking checkout is refused even if marked" no "$(REPO_ROOT=${SRC_DIR} yes_no ensure_clone_io "${head_sha}")"

# ---------------------------------------------------------------------- lock

lockdir="${work}/cache/lock"
(
    acquire_lock_io "${lockdir}"
    [[ $(cat "${lockdir}/pid") == "$$" ]] && cleanup && [[ ! -e ${lockdir} ]]
) && r=yes || r=no
expect "lock is taken, then released by cleanup" yes "${r}"

sleep 30 &
holder=$!
mkdir -p "${lockdir}"
echo "${holder}" >"${lockdir}/pid"
out=$( (acquire_lock_io "${lockdir}") 2>&1) && r=yes || r=no
expect "lock held by a live process is refused" no "${r}"
expect "refusal says another run is in progress" yes "$(yes_no grep -q 'in progress' <<<"${out}")"
kill "${holder}" 2>/dev/null || true
wait "${holder}" 2>/dev/null || true
(acquire_lock_io "${lockdir}" && [[ $(cat "${lockdir}/pid") == "$$" ]]) >/dev/null 2>&1 && r=yes || r=no
expect "stale lock (holder gone) is taken over" yes "${r}"
rm -rf "${lockdir}"

# Takeover race: contender B saw dead pid 999999, but contender A has since
# taken over and written its own (live) pid. B must not remove A's fresh lock.
mkdir -p "${lockdir}"
echo "$$" >"${lockdir}/pid"
expect "reap refuses when the lock now names another holder" no "$(yes_no reap_stale_lock "${lockdir}" 999999)"
expect "...and the fresh lock survives" yes "$(yes_no test -f "${lockdir}/pid")"
expect "...and the guard is released" no "$(yes_no test -d "${lockdir}.takeover")"
echo 999999 >"${lockdir}/pid"
expect "reap removes a lock still naming the dead holder" yes "$(yes_no reap_stale_lock "${lockdir}" 999999)"
expect "...lock is gone" no "$(yes_no test -d "${lockdir}")"
mkdir -p "${lockdir}" "${lockdir}.takeover"
expect "reap yields while another contender holds the guard" no "$(yes_no reap_stale_lock "${lockdir}" '')"
rm -rf "${lockdir}" "${lockdir}.takeover"

# Large runs payload (real responses are ~13 KB per run): must not hit E2BIG.
big=$(jq -n -c '{workflow_runs: ([range(0; 20) | {id: (. + 100), name: "pad", status: "completed", conclusion: "success", blob: ("x" * 15000)}] + [{id: 1, name: "CI", status: "completed", conclusion: "success", blob: ("y" * 15000)}])}')
expect "runs payload is over 200 KB" yes "$(yes_no test "${#big}" -gt 200000)"
expect "large runs payload is handled" '{"CI":"ok","Extension CI":"unproven"}' "$(st "${big}" "${bt_pass}")"

# ----------------------------------------------- walk with a stubbed gh (fixtures)

stub="${work}/stub"
fx="${work}/fx"
mkdir -p "${stub}" "${fx}"
cat >"${stub}/gh" <<'STUB'
#!/usr/bin/env bash
# Serves fixtures from $FX: commits-<ref>.txt (one sha per line), runs-<sha>.json,
# jobs-<id>.json ({"jobs":[...]}). Applies --jq like the real CLI.
ep=$2
filter=""
shift 2
while [[ $# -gt 0 ]]; do
  [[ $1 == --jq ]] && filter=$2
  shift
done
case ${ep} in
  *"commits?sha="*) ref=${ep#*sha=}; ref=${ref%%&*}; body=$(jq -R -s -c 'split("\n") | map(select(length>0) | {sha: .})' "${FX}/commits-${ref}.txt") ;;
  *"commits/"*) body="{\"sha\":\"${ep#*commits/}\"}" ;;
  *"actions/runs?head_sha="*) sha=${ep#*head_sha=}; sha=${sha%%&*}; body=$(cat "${FX}/runs-${sha}.json" 2>/dev/null || echo '{"workflow_runs":[]}') ;;
  *"actions/runs/"*"/jobs"*) id=${ep#*actions/runs/}; id=${id%%/*}; body=$(cat "${FX}/jobs-${id}.json") ;;
  *) echo "unexpected gh ${ep}" >&2; exit 1 ;;
esac
if [[ -n ${filter} ]]; then jq -r "${filter}" <<<"${body}"; else echo "${body}"; fi
STUB
chmod +x "${stub}/gh"
PATH="${stub}:${PATH}"
export FX=${fx}

# fx_commit <sha> <ci: pass|skip|fail|none> <ext: pass|none>; run ids derive from the sha.
fx_commit() {
    local sha=$1 ci=$2 ext=$3 rs="" id
    id=$(printf '%d' "'${sha:0:1}")
    case ${ci} in
        pass)
            rs="${rs}$(run CI completed '"success"' "${id}1"),"
            echo '{"jobs":[{"name":"Build and Test (ubuntu-24.04, 17)","conclusion":"success"}]}' >"${fx}/jobs-${id}1.json"
            ;;
        skip)
            rs="${rs}$(run CI completed '"success"' "${id}1"),"
            echo '{"jobs":[{"name":"Build and Test","conclusion":"skipped"}]}' >"${fx}/jobs-${id}1.json"
            ;;
        fail) rs="${rs}$(run CI completed '"failure"' "${id}1")," ;;
        *) ;;
    esac
    if [[ ${ext} == pass ]]; then
        rs="${rs}$(run 'Extension CI' completed '"success"' "${id}2"),"
        echo '{"jobs":[{"name":"Build current server","conclusion":"success"}]}' >"${fx}/jobs-${id}2.json"
    fi
    echo "{\"workflow_runs\":[${rs%,}]}" >"${fx}/runs-${sha}.json"
}
# Window, newest first: dddddddd (extension-only) cccccccc bbbbbbbb aaaaaaaa
printf 'dddddddd\ncccccccc\nbbbbbbbb\naaaaaaaa\n' >"${fx}/commits-main.txt"
printf 'cccccccc\nbbbbbbbb\naaaaaaaa\n' >"${fx}/commits-cccccccc.txt"
printf 'dddddddd\ncccccccc\nbbbbbbbb\naaaaaaaa\n' >"${fx}/commits-dddddddd.txt"

SLUG=o/r BRANCH=main MAX_WALK=20 REQUIRED=CI OPTIONAL="Extension CI" PROOF=${proof}
unset EXT_LATEST_SHA EXT_LATEST_ALLOW_UNGREEN || true

# Case 1: a server-breaking commit (cccccccc, CI red) followed by an
# extension-only commit (dddddddd, CI build skipped) must not count as green.
fx_commit dddddddd skip pass
fx_commit cccccccc fail pass
fx_commit bbbbbbbb pass pass
fx_commit aaaaaaaa pass pass
pick_target_io 2>/dev/null
expect "extension-only commit on top of a red server commit is skipped" bbbbbbbb "${TARGET_SHA}"

# Case 2: the same extension-only commit on a green parent is green.
fx_commit cccccccc pass pass
pick_target_io 2>/dev/null
expect "skipped build on a green parent: newest commit is the target" dddddddd "${TARGET_SHA}"

# Case 3: extension CI missing (path-ignored) inherits from the parent.
fx_commit dddddddd pass none
pick_target_io 2>/dev/null
expect "missing optional workflow inherits a green parent" dddddddd "${TARGET_SHA}"
fx_commit cccccccc pass none
fx_commit dddddddd pass none
printf 'dddddddd\ncccccccc\n' >"${fx}/commits-main.txt"
MAX_WALK=2
expect "everything inherited, nothing proven in the window: refused" no "$(yes_no pick_target_io)"
EXT_LATEST_ALLOW_UNGREEN=1 pick_target_io 2>/dev/null
expect "...unless ungreen is allowed (newest commit)" dddddddd "${TARGET_SHA}"
unset EXT_LATEST_ALLOW_UNGREEN
MAX_WALK=20
printf 'dddddddd\ncccccccc\nbbbbbbbb\naaaaaaaa\n' >"${fx}/commits-main.txt"

# Case 4: forced commit
fx_commit cccccccc fail pass
fx_commit dddddddd skip pass
EXT_LATEST_SHA=dddddddd
expect "forced commit inheriting a red parent is refused" no "$(yes_no pick_target_io)"
EXT_LATEST_ALLOW_UNGREEN=1 pick_target_io 2>/dev/null
expect "forced red commit allowed with override" dddddddd "${TARGET_SHA}"
case ${TARGET_CI} in ungreen*) r=yes ;; *) r=no ;; esac
expect "...and reports its status" yes "${r}"
unset EXT_LATEST_ALLOW_UNGREEN
EXT_LATEST_SHA=bbbbbbbb
printf 'bbbbbbbb\naaaaaaaa\n' >"${fx}/commits-bbbbbbbb.txt"
pick_target_io 2>/dev/null
expect "forced green commit accepted" bbbbbbbb "${TARGET_SHA}"
unset EXT_LATEST_SHA

if [[ ${fails} -ne 0 ]]; then
    echo "${fails} failure(s)"
    exit 1
fi
echo "all passed"
