#!/usr/bin/env bash
# Self-test for ext-latest.sh: exercises the pure helpers (CI evaluation with
# stubbed workflow runs, sha/provenance checks, VSIX naming, pruning) and the
# fully stubbed `gh` target walk. No network, no editor, no build.
# SC2310/SC2312: see ext-latest.sh. SC2034/SC2154: the sourced script reads and
# sets these globals (SLUG, BRANCH, TARGET_SHA, ...), which ShellCheck cannot see.
# shellcheck disable=SC2310,SC2312,SC2034,SC2154
set -euo pipefail

script="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/ext-latest.sh"
# shellcheck source=/dev/null
source "${script}"

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

# run(name, status, conclusion, id) as a workflow_runs entry.
run() { printf '{"id":%s,"name":"%s","status":"%s","conclusion":%s}' "$4" "$1" "$2" "$3"; }
runs() {
  local IFS=,
  printf '{"workflow_runs":[%s]}' "$*"
}

req="CI"
opt="Extension CI"
ci() { evaluate_ci "${req}" "${opt}" "$1"; }

expect "all green" green "$(ci "$(runs "$(run CI completed '"success"' 1)" "$(run 'Extension CI' completed '"success"' 2)")")"
expect "optional workflow absent (path-filtered) is fine" green "$(ci "$(runs "$(run CI completed '"success"' 1)")")"
expect "required workflow absent" "missing: CI" "$(ci "$(runs "$(run 'Extension CI' completed '"success"' 2)")")"
expect "CI failed" "failed: CI" "$(ci "$(runs "$(run CI completed '"failure"' 1)" "$(run 'Extension CI' completed '"success"' 2)")")"
expect "CI pending" "pending: CI" "$(ci "$(runs "$(run CI in_progress null 1)")")"
expect "Extension CI cancelled is not green" "failed: Extension CI" "$(ci "$(runs "$(run CI completed '"success"' 1)" "$(run 'Extension CI' completed '"cancelled"' 2)")")"
expect "Extension CI pending" "pending: Extension CI" "$(ci "$(runs "$(run CI completed '"success"' 1)" "$(run 'Extension CI' queued null 2)")")"
expect "failure outranks pending" "failed: CI" "$(ci "$(runs "$(run CI completed '"failure"' 1)" "$(run 'Extension CI' in_progress null 2)")")"
expect "rerun (newer id) wins over failed attempt" green "$(ci "$(runs "$(run CI completed '"failure"' 1)" "$(run CI completed '"success"' 5)")")"
expect "bare array accepted" green "$(ci "[$(run CI completed '"success"' 1)]")"
expect "no runs at all" "missing: CI" "$(ci '{"workflow_runs":[]}')"

full=0123456789abcdef0123456789abcdef01234567
expect "sha7" 0123456 "$(sha7 "${full}")"
same_sha 0123456 "${full}" && r=yes || r=no
expect "same_sha prefix" yes "${r}"
same_sha 0123457 "${full}" && r=yes || r=no
expect "same_sha mismatch" no "${r}"
same_sha unknown "${full}" && r=yes || r=no
expect "same_sha unknown" no "${r}"
same_sha 012 "${full}" && r=yes || r=no
expect "same_sha too short" no "${r}"

p() { provenance_ok "$1" "${full}" && echo yes || echo no; }
expect "jar json clean" yes "$(p '{"gitSha":"'"${full}"'","gitDirty":false}')"
expect "jar json dirty" no "$(p '{"gitSha":"'"${full}"'","gitDirty":true}')"
expect "record json clean" yes "$(p '{"gitSha":"'"${full}"'","dirty":false}')"
expect "dirty unknown (null)" no "$(p '{"gitSha":"'"${full}"'","gitDirty":null}')"
expect "other sha" no "$(p '{"gitSha":"ffffffffffffffffffffffffffffffffffffffff","gitDirty":false}')"
expect "legacy non-json" no "$(p 'local')"

expect "slug https" albertocavalcante/gvy "$(repo_slug https://github.com/albertocavalcante/gvy.git)"
expect "slug ssh" albertocavalcante/gvy "$(repo_slug git@github.com:albertocavalcante/gvy.git)"
repo_slug https://example.com/a/b >/dev/null && r=yes || r=no
expect "slug non-github rejected" no "${r}"

expect "vsix name" "gvy-0.5.4+g0123456.vsix" "$(vsix_name 0.5.4 "${full}")"
expect "vsix sha7 roundtrip" 0123456 "$(vsix_sha7 "/x/$(vsix_name 0.5.4 "${full}")")"
vsix_sha7 gvy-0.5.4.vsix >/dev/null && r=yes || r=no
expect "vsix sha7 absent" no "${r}"

expect "prune keeps newest K" "c
d" "$(printf 'a\nb\nc\nd\n' | prune_list 2)"
expect "prune nothing" "" "$(printf 'a\nb\n' | prune_list 3)"

expect "editor root code" /h/.vscode/extensions "$(editor_ext_root code /h)"
expect "windsurf CLIs" "windsurf surf" "$(editor_cli_candidates windsurf)"
editor_cli_candidates vim >/dev/null && r=yes || r=no
expect "unknown editor rejected" no "${r}"

# Target walk with a stubbed gh: newest commit pending, next failed, third green.
stub=$(mktemp -d)
trap 'rm -rf "${stub}"' EXIT
cat >"${stub}/gh" <<'STUB'
#!/usr/bin/env bash
case "$2" in
  *"commits?sha="*) printf 'aaaaaaa1\nbbbbbbb2\nccccccc3\nddddddd4\n' ;;
  *"head_sha=aaaaaaa1"*) echo '{"workflow_runs":[{"id":1,"name":"CI","status":"in_progress","conclusion":null}]}' ;;
  *"head_sha=bbbbbbb2"*) echo '{"workflow_runs":[{"id":2,"name":"CI","status":"completed","conclusion":"failure"}]}' ;;
  *"head_sha=ccccccc3"*) echo '{"workflow_runs":[{"id":3,"name":"CI","status":"completed","conclusion":"success"}]}' ;;
  *"head_sha=ddddddd4"*) echo '{"workflow_runs":[{"id":4,"name":"CI","status":"completed","conclusion":"success"}]}' ;;
  *"commits/bbbbbbb2"*) echo bbbbbbb2 ;;
  *) echo "unexpected gh $*" >&2; exit 1 ;;
esac
STUB
chmod +x "${stub}/gh"
SLUG=o/r BRANCH=main MAX_WALK=20 REQUIRED=CI OPTIONAL="Extension CI"
unset EXT_LATEST_SHA EXT_LATEST_ALLOW_UNGREEN || true
PATH="${stub}:${PATH}"
pick_target_io 2>/dev/null
expect "walk skips pending+failed, picks first green" ccccccc3 "${TARGET_SHA}"
EXT_LATEST_SHA=bbbbbbb2
(pick_target_io >/dev/null 2>&1) && r=yes || r=no
expect "forced failing sha is refused" no "${r}"
EXT_LATEST_ALLOW_UNGREEN=1 pick_target_io 2>/dev/null
expect "forced failing sha allowed with override" bbbbbbb2 "${TARGET_SHA}"
unset EXT_LATEST_SHA EXT_LATEST_ALLOW_UNGREEN
if [[ ${fails} -ne 0 ]]; then
  echo "${fails} failure(s)"
  exit 1
fi
echo "all passed"
