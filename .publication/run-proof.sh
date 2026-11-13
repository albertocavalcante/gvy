#!/usr/bin/env bash
# SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail

: "${GITHUB_WORKSPACE:?}"
: "${GITHUB_SHA:?}"
: "${GITHUB_RUN_ID:?}"
: "${GITHUB_RUN_ATTEMPT:?}"
: "${RUNNER_TEMP:?}"
: "${JAVA_HOME_17_X64:?}"
: "${JAVA_HOME_21_X64:?}"
[[ ${GITHUB_ACTIONS:-} == true && ${RUNNER_OS:-} == Linux && ${RUNNER_ENVIRONMENT:-} == github-hosted ]]
[[ -z ${LEFTHOOK:-} && -z ${LEFTHOOK_EXCLUDE:-} && -z ${LEFTHOOK_SKIP:-} ]]

support="${GITHUB_WORKSPACE}/support/.publication"
candidate="${GITHUB_WORKSPACE}/candidate"
proof="${RUNNER_TEMP}/gvy-q04-authority-proof"
tools="${support}/proof-tools.mjs"
base=2a762e982c227ee806f3a96cf3d8216bfdee0b4b
baseline_tree=74a92727885379d4e0682dbd161bea4b600b2261
tests_tree=238f1304c542168ab76239fdeeb9d2facc4444c0
mkdir "${proof}"
node "${tools}" prepare "${support}" "${proof}"
support_head=$(git -C "${GITHUB_WORKSPACE}/support" rev-parse HEAD)
[[ ${support_head} == "${GITHUB_SHA}" ]]
printf '%s\n' "${support_head}" >"${proof}/support-head.txt"

cd "${candidate}"
current_head=$(git rev-parse HEAD)
current_status=$(git status --porcelain --untracked-files=all)
[[ ${current_head} == "${base}" && -z ${current_status} ]]
set +e
hooks_path=$(git config --get core.hooksPath)
hooks_config_status=$?
set -e
[[ (${hooks_config_status} == 0 && -z ${hooks_path}) || ${hooks_config_status} == 1 ]]
git switch -c test/analysis-quarantine-hosted-proof
lefthook install
[[ -x .git/hooks/pre-commit && -x .git/hooks/commit-msg ]]
direnv allow
direnv exec . java -version

# Applied only to this job's validation commands, after repository afterEvaluate configuration.
cat >"${proof}/bounds.gradle" <<'GRADLE'
// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import org.gradle.api.tasks.testing.Test
gradle.projectsEvaluated {
    rootProject.allprojects {
        tasks.withType(Test).configureEach {
            maxParallelForks = 1
            minHeapSize = '128m'
            maxHeapSize = '512m'
        }
    }
}
GRADLE

export_source() {
    # Preserve complete source and formatter deltas even if a later gate fails. No commit is created.
    git diff --binary --full-index HEAD >"${proof}/actual-source.patch"
    git diff --binary --full-index >"${proof}/formatter.patch"
    git diff --name-only -z >"${proof}/formatter-paths.nul"
    git diff --cached --name-only -z >"${proof}/staged-paths.nul"
    git write-tree >"${proof}/indexed-tree.txt"
    git status --porcelain --untracked-files=all >"${proof}/source-status.txt"
}
trap export_source EXIT

prove_tree() {
    local head tree untracked
    head=$(git rev-parse HEAD)
    tree=$(git write-tree)
    untracked=$(git ls-files --others --exclude-standard)
    [[ ${head} == "${base}" && ${tree} == "$1" ]]
    git diff --quiet
    git diff --cached --check
    [[ -z ${untracked} ]]
}

stage() {
    local label=$1
    shift
    mkdir "${proof}/${label}"
    set +e
    GITHUB_SHA="${base}" direnv exec . node tools/ci/run-gradle.mjs \
        --report-root "${proof}/${label}" --producer "q04-authority-${label}" \
        "$@" --no-daemon --no-parallel --no-build-cache --max-workers=1 \
        -Dorg.gradle.java.installations.fromEnv=JAVA_HOME_17_X64,JAVA_HOME_21_X64 \
        -PtestJavaVersion=17 --init-script "${proof}/bounds.gradle" \
        '-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=768m -XX:+UseSerialGC'
    local status=$?
    set -e
    # Copy original XML and command status before applying any diagnostics or assertion gate.
    node "${tools}" snapshot "${proof}/${label}" "${status}"
}

git apply --check --index "${proof}/baseline.patch"
git apply --index "${proof}/baseline.patch"
prove_tree "${baseline_tree}"
stage baseline :gls:test --rerun \
    --tests 'com.github.albertocavalcante.gvy.gls.compilation.AnalysisPublicationCoordinatorTest'
node "${tools}" verify "${proof}/baseline" baseline
prove_tree "${baseline_tree}"

git apply --check --index "${proof}/testsFirst.patch"
git apply --index "${proof}/testsFirst.patch"
prove_tree "${tests_tree}"
stage compile :gls:testClasses
node "${tools}" verify "${proof}/compile" compile
prove_tree "${tests_tree}"

# These are disposable derived results from this job's owned checkout; the earlier XML is already frozen.
rm -rf gls/build/test-results/test
stage red :gls:test --rerun \
    --tests 'com.github.albertocavalcante.gvy.gls.compilation.AnalysisSourceQuarantineTest.quarantined companion blocks fresh capture after unrelated successful mutation' \
    --tests 'com.github.albertocavalcante.gvy.gls.compilation.AnalysisVerificationBoundaryTest.publish verification permits another writer and rejects its stale candidate'
node "${tools}" verify "${proof}/red" red
prove_tree "${tests_tree}"
printf '%s\n' 'BASELINE_11_GREEN_AND_SELECTED_2_ASSERTION_RED' >"${proof}/behavior-status.txt"

mkdir "${proof}/license"
set +e
make license-check 2>&1 | tee "${proof}/license/check.log"
license_check_status=("${PIPESTATUS[@]}")
make license-test 2>&1 | tee "${proof}/license/test.log"
license_test_status=("${PIPESTATUS[@]}")
set -e
printf '%s %s %s %s\n' "${license_check_status[@]}" "${license_test_status[@]}" >"${proof}/license/status.txt"
[[ ${license_check_status[0]} == 0 && ${license_check_status[1]} == 0 && ${license_test_status[0]} == 0 && ${license_test_status[1]} == 0 ]]

stage lint-fix lintFix
export_source
if ! git diff --quiet; then
    printf '%s\n' 'Formatter changed the reviewed source; inspect exported delta before any further publication.' >&2
    exit 1
fi
node "${tools}" verify "${proof}/lint-fix" lint-fix
prove_tree "${tests_tree}"
stage lint :gls:lint
node "${tools}" verify "${proof}/lint" lint

# Run the installed, unmodified hooks without creating a product commit.
set +e
.git/hooks/pre-commit 2>&1 | tee "${proof}/pre-commit.log"
hook_status=("${PIPESTATUS[@]}")
node tools/ci/check-gradle-diagnostics.mjs --log "${proof}/pre-commit.log" \
    --exit-code "${hook_status[0]}" --capture-exit-code "${hook_status[1]}" \
    --producer q04-authority-hook --baseline tools/ci/gradle-diagnostics.json --root "${PWD}" \
    --report "${proof}/hook-diagnostics"
hook_checker=$?
printf '%s\n' 'test(semantics): prepare quarantine assertion controls' >"${proof}/commit-message.txt"
.git/hooks/commit-msg "${proof}/commit-message.txt" 2>&1 | tee "${proof}/commit-msg.log"
message_status=("${PIPESTATUS[@]}")
set -e
printf '%s %s %s %s %s\n' "${hook_status[@]}" "${hook_checker}" "${message_status[@]}" >"${proof}/hook-status.txt"
[[ ${hook_status[0]} == 0 && ${hook_status[1]} == 0 && ${hook_checker} == 0 && ${message_status[0]} == 0 && ${message_status[1]} == 0 ]]
prove_tree "${tests_tree}"
printf '%s\n' 'SOURCE_VALIDATED_TESTS_FIRST_NO_PRODUCT_COMMIT' >"${proof}/validation-status.txt"
