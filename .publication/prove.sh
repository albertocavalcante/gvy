#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail
umask 077

: "${RUNNER_TEMP:?}" "${GITHUB_WORKSPACE:?}" "${EXPECTED_BASE:?}" "${EXPECTED_TREE:?}"
evidence="${RUNNER_TEMP}/gvy-pr03a-proof"
support="${GITHUB_WORKSPACE}/support/.publication"
source_paths=(
    tests/BUILD.bazel
    tests/build.gradle.kts
    tests/e2e/kotlin/com/github/albertocavalcante/gvy/gls/e2e/LanguageServerSession.kt
    tests/e2e/kotlin/com/github/albertocavalcante/gvy/gls/e2e/LanguageServerSessionFactory.kt
    tests/e2e/kotlin/com/github/albertocavalcante/gvy/gls/e2e/PackagedLaunch.kt
    tests/e2e/kotlin/com/github/albertocavalcante/gvy/gls/e2e/PackagedLaunchTest.kt
    tests/e2e/kotlin/com/github/albertocavalcante/gvy/gls/e2e/PackagedSessionFactoryTest.kt
)
phase=setup
red_exit=-1 green_exit=-1 lint_exit=-1
last_command_exit=-1 last_capture_exit=-1
mkdir -p "${evidence}"
printf '%s\0' "${source_paths[@]}" >"${evidence}/allowed-paths.nul"

scope() {
    node "${support}/check-results.mjs" scope "${evidence}/allowed-paths.nul"
}
snapshot() {
    local output=$1 file parent
    mkdir -p "${output}/sources"
    scope
    for file in "${source_paths[@]}"; do
        parent=$(dirname "${file}")
        mkdir -p "${output}/sources/${parent}"
        cp "${file}" "${output}/sources/${file}"
    done
    sha256sum "${source_paths[@]}" >"${output}/source.sha256"
    git add "${source_paths[@]}"
    git diff --cached --check
    git diff --quiet
    git diff --cached --name-only -z >"${output}/paths.nul"
    cmp "${evidence}/allowed-paths.nul" "${output}/paths.nul"
    git write-tree >"${output}/tree.txt"
    git diff --cached --binary "${EXPECTED_BASE}" >"${output}/source.patch"
    sha256sum "${output}/source.patch" >"${output}/patch.sha256"
}
finish() {
    local original_exit=$1
    set +e
    git status --porcelain=v1 --untracked-files=all >"${evidence}/final-status.txt"
    git diff --binary HEAD >"${evidence}/final.patch"
    sha256sum "${source_paths[@]}" >"${evidence}/final-source.sha256"
    printf '{"exit":%d,"redExit":%d,"greenExit":%d,"lintExit":%d,"phase":"%s"}\n' \
        "${original_exit}" "${red_exit}" "${green_exit}" "${lint_exit}" "${phase}" >"${evidence}/result.json"
    date -u +%Y-%m-%dT%H:%M:%SZ >"${evidence}/finished.txt"
}
trap 'original_exit=$?; finish "${original_exit}"; trap - EXIT; exit "${original_exit}"' EXIT
trap 'exit 143' TERM
trap 'exit 130' INT

run_gradle() {
    local output=$1 label=$2 remaining
    shift 2
    remaining=$((35 * 60 - SECONDS - 30))
    if ((remaining < 1)); then
        last_command_exit=124
        last_capture_exit=0
        return 0
    fi
    if ((remaining > 900)); then remaining=900; fi
    set +e
    timeout --signal=TERM --kill-after=10s "${remaining}s" direnv exec . ./gradlew \
        --no-daemon --no-parallel --max-workers=1 --console=plain --warning-mode=all --no-scan --info \
        '-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=768m -XX:+UseSerialGC' \
        -PtestJavaVersion=17 "$@" 2>&1 | tee "${output}/${label}.log"
    local statuses=("${PIPESTATUS[@]}")
    set -e
    last_command_exit=${statuses[0]}
    last_capture_exit=${statuses[1]}
    return 0
}
tests() {
    local output=$1 report
    mkdir -p "${output}"
    for report in PackagedLaunchTest PackagedSessionFactoryTest; do
        rm -f "tests/build/test-results/harnessTest/TEST-com.github.albertocavalcante.gvy.gls.e2e.${report}.xml"
    done
    run_gradle "${output}" tests :tests:harnessTest --rerun \
        --tests '*.PackagedLaunchTest' --tests '*.PackagedSessionFactoryTest'
    printf '{"testExit":%d,"captureExit":%d}\n' "${last_command_exit}" "${last_capture_exit}" >"${output}/command.json"
    for report in PackagedLaunchTest PackagedSessionFactoryTest; do
        if [[ -f "tests/build/test-results/harnessTest/TEST-com.github.albertocavalcante.gvy.gls.e2e.${report}.xml" ]]; then
            cp "tests/build/test-results/harnessTest/TEST-com.github.albertocavalcante.gvy.gls.e2e.${report}.xml" "${output}/"
        fi
    done
}

observed_head=$(git rev-parse HEAD)
observed_tree=$(git write-tree)
observed_branch=$(git branch --show-current)
[[ "${observed_head}" == "${EXPECTED_BASE}" ]]
[[ "${observed_tree}" == "${EXPECTED_TREE}" ]]
[[ "${observed_branch}" == feat/verified-packaged-launch ]]
git worktree list >"${evidence}/worktrees.txt"
scope
direnv allow
direnv exec . java -version >"${evidence}/java-version.txt" 2>&1
sha256sum "${source_paths[@]}" >"${evidence}/input.sha256"

phase=red-format
mkdir -p "${evidence}/red"
run_gradle "${evidence}/red" format lintFix
((last_capture_exit == 0)) || exit 74
((last_command_exit == 0)) || exit "${last_command_exit}"
snapshot "${evidence}/red"
observed_tree=$(cat "${evidence}/red/tree.txt")
[[ "${observed_tree}" == "${EXPECTED_TREE}" ]]
phase=red-tests
tests "${evidence}/red"
red_exit=${last_command_exit}
sha256sum -c "${evidence}/red/source.sha256" >"${evidence}/red/source-stability.txt"
scope
node "${support}/check-results.mjs" red "${evidence}/red"
observed_tree=$(git write-tree)
[[ "${observed_tree}" == "${EXPECTED_TREE}" ]]

# The exact candidate and tiny fix were reviewed before dispatch. Apply only
# after the actual 40-control RED satisfies the fail-closed named-failure gate.
phase=apply-reviewed-fix
fix_digest=$(jq -er '.fixSha256' "${support}/receipt.json")
printf '%s  %s\n' "${fix_digest}" "${support}/alias-socket.patch" | sha256sum -c -
git apply --check "${support}/alias-socket.patch"
git apply "${support}/alias-socket.patch"
phase=green-format
mkdir -p "${evidence}/green"
run_gradle "${evidence}/green" format lintFix
((last_capture_exit == 0)) || exit 74
((last_command_exit == 0)) || exit "${last_command_exit}"
snapshot "${evidence}/green"
observed_tree=$(cat "${evidence}/green/tree.txt")
expected_green_tree=$(jq -er '.greenTree' "${support}/receipt.json")
[[ "${observed_tree}" == "${expected_green_tree}" ]]
phase=green-tests
tests "${evidence}/green"
green_exit=${last_command_exit}
sha256sum -c "${evidence}/green/source.sha256" >"${evidence}/green/source-stability.txt"
scope
node "${support}/check-results.mjs" green "${evidence}/green"
phase=lint
run_gradle "${evidence}" lint lint --rerun-tasks
lint_exit=${last_command_exit}
((last_capture_exit == 0)) || exit 74
((lint_exit == 0)) || exit "${lint_exit}"
sha256sum -c "${evidence}/green/source.sha256" >"${evidence}/post-lint-source-stability.txt"
scope
git diff --quiet
observed_tree=$(git write-tree)
[[ "${observed_tree}" == "${expected_green_tree}" ]]
phase=complete
