#!/usr/bin/env bash
set -euo pipefail
umask 077
: "${RUNNER_TEMP:?}" "${GITHUB_WORKSPACE:?}"
support="${GITHUB_WORKSPACE}/support/.publication"
proof="${RUNNER_TEMP}/gvy-publication-proof"
receipt="${support}/receipt.json"
base=$(jq -er '.base' "${receipt}")
mkdir -p "${proof}"
phase=setup
finish() {
    local status=$1
    set +e
    git status --porcelain=v1 --untracked-files=all >"${proof}/final-status.txt"
    printf '{"exit":%d,"phase":"%s"}\n' "${status}" "${phase}" >"${proof}/result.json"
}
trap 'status=$?; finish "${status}"; trap - EXIT; exit "${status}"' EXIT
trap 'exit 143' TERM
trap 'exit 130' INT
capture() {
    local label=$1 remaining
    shift
    remaining=$((55 * 60 - SECONDS - 30))
    if ((remaining < 1)); then
        printf '{"commandExit":124,"captureExit":0}\n' >"${proof}/${label}.json"
        return 0
    fi
    if ((remaining > 900)); then remaining=900; fi
    set +e
    timeout --signal=TERM --kill-after=10s "${remaining}s" "$@" 2>&1 | node "${support}/bounded-capture.mjs" "${proof}" "${proof}/${label}.log"
    local statuses=("${PIPESTATUS[@]}")
    set -e
    printf '{"commandExit":%d,"captureExit":%d}\n' "${statuses[0]}" "${statuses[1]}" >"${proof}/${label}.json"
}
require_success() {
    jq -e '.commandExit == 0 and .captureExit == 0' "${proof}/$1.json" >/dev/null
}
gradle() {
    local label=$1
    shift
    capture "${label}" direnv exec . ./gradlew --no-daemon --no-parallel --max-workers=1 \
        --console=plain --warning-mode=all --no-scan --info -Dorg.gradle.deprecation.trace=true \
        '-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=768m -XX:+UseSerialGC' \
        -PtestJavaVersion=17 "$@"
}
phase_check() {
    local current=$1 diff_exit
    set +e
    git diff --quiet
    diff_exit=$?
    set -e
    if ((diff_exit != 0)); then
        set +e
        git diff --binary | node "${support}/bounded-capture.mjs" "${proof}" "${proof}/format-delta-${phase}.patch"
        local statuses=("${PIPESTATUS[@]}")
        set -e
        printf '{"diffExit":%d,"captureExit":%d}\n' "${statuses[0]}" "${statuses[1]}" >"${proof}/format-delta-${phase}.json"
        return 78
    fi
    node "${support}/validate-phase.mjs" "${current}" "${receipt}"
}
diagnostic() {
    local label=$1 producer=$2 code capture_code
    code=$(jq -er '.commandExit' "${proof}/${label}.json")
    capture_code=$(jq -er '.captureExit' "${proof}/${label}.json")
    node "${support}/evidence-budget.mjs" "${proof}" 67108864
    set +e
    (
        ulimit -f 16384
        node tools/ci/check-gradle-diagnostics.mjs --log "${proof}/${label}.log" --exit-code "${code}" \
            --capture-exit-code "${capture_code}" --producer "${producer}" \
            --baseline tools/ci/gradle-diagnostics.json --report "${proof}/diagnostics-${label}" --root "${PWD}"
    )
    local diagnostic_exit=$?
    set -e
    node "${support}/evidence-budget.mjs" "${proof}"
    if [[ "${label}" == red ]]; then
        ((diagnostic_exit == 1))
        jq -e '.complete == true and .commandExitCode == 1 and .captureExitCode == 0 and .ok == false and (.unexpected | length == 0) and .errors == ["Gradle command failed with exit 1"]' "${proof}/diagnostics-${label}/diagnostics.json" >/dev/null
    else
        ((diagnostic_exit == 0))
        jq -e '.complete == true and .ok == true and (.unexpected | length == 0)' "${proof}/diagnostics-${label}/diagnostics.json" >/dev/null
    fi
}
run_tests() {
    local current=$1 name names
    names=$(jq -er --arg p "${current}" '.[$p].classes[].name' "${receipt}")
    local -a args=()
    mkdir -p "${proof}/${current}"
    while IFS= read -r name; do
        rm -f "tests/build/test-results/harnessTest/TEST-com.github.albertocavalcante.gvy.gls.e2e.${name}.xml"
        args+=(--tests "*.${name}")
    done <<<"${names}"
    ((${#args[@]} > 0))
    gradle "${current}" :tests:harnessTest --rerun "${args[@]}"
    node "${support}/evidence-budget.mjs" "${proof}" 16777216
    cp "${proof}/${current}.json" "${proof}/${current}/command.json"
    while IFS= read -r name; do
        node "${support}/evidence-budget.mjs" "${proof}" 4194304
        node "${support}/bounded-file.mjs" "tests/build/test-results/harnessTest/TEST-com.github.albertocavalcante.gvy.gls.e2e.${name}.xml" "${proof}/${current}/TEST-com.github.albertocavalcante.gvy.gls.e2e.${name}.xml"
    done <<<"${names}"
    node "${support}/evidence-budget.mjs" "${proof}"
    node "${support}/check-red.mjs" "${current}" "${proof}/${current}" "${receipt}"
    phase_check "${current}"
}
observed=$(git rev-parse HEAD)
[[ "${observed}" == "${base}" ]]
observed=$(git branch --show-current)
[[ "${observed}" == feat/manifest-sections-publication ]]
phase_check red
direnv allow
git config user.name 'gvy publication automation'
git config user.email '41898282+github-actions[bot]@users.noreply.github.com'
lefthook install
test -x .git/hooks/pre-commit && test -x .git/hooks/commit-msg
phase=red-format
gradle red-format lintFix
require_success red-format
phase_check red
diagnostic red-format review-fix-red-format
phase=red-tests
run_tests red
diagnostic red review-fix-red
phase=green-apply
git apply --check --index "${RUNNER_TEMP}/green.patch"
git apply --index "${RUNNER_TEMP}/green.patch"
phase_check green
phase=green-format
gradle lintFix lintFix
require_success lintFix
phase_check green
diagnostic lintFix review-fix-lintfix
phase=green-tests
run_tests green
diagnostic green review-fix-tests
phase=lint
gradle lint lint
require_success lint
phase_check green
diagnostic lint review-fix-lint
phase=commit
observed=$(git branch --show-current)
[[ "${observed}" == feat/manifest-sections-publication ]]
capture commit git commit -m 'fix(test): verify packaged manifest sections'
require_success commit
diagnostic commit review-fix-hooks
observed=$(git rev-parse HEAD^)
[[ "${observed}" == "${base}" ]]
expected_tree=$(jq -er '.green.tree' "${receipt}")
observed=$(git rev-parse 'HEAD^{tree}')
[[ "${observed}" == "${expected_tree}" ]]
observed=$(git status --porcelain=v1 --untracked-files=all)
[[ -z "${observed}" ]]
node "${support}/evidence-budget.mjs" "${proof}" 16777216
(
    ulimit -f 16384
    git bundle create "${proof}/candidate.bundle" HEAD "^${base}"
)
node "${support}/evidence-budget.mjs" "${proof}"
git bundle verify "${proof}/candidate.bundle"
sha256sum "${proof}/candidate.bundle" >"${proof}/bundle.sha256"
observed=$(git rev-parse HEAD)
jq -n --arg base "${base}" --arg tree "${expected_tree}" --arg head "${observed}" --arg support "${GITHUB_SHA:?}" '{base:$base,tree:$tree,head:$head,supportHead:$support}' >"${proof}/receipt.json"
phase=complete
