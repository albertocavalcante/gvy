#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail
[[ $# -eq 1 ]]
proof=${1:?}
base=$(git rev-parse HEAD)
input_tree=$(git write-tree)
[[ ${base} == "${EXPECTED_BASE:?}" && ${input_tree} == "${EXPECTED_TREE:?}" ]]
git diff --exit-code
[[ ! -e ${proof} ]]
mkdir -p "${proof}"
cp MODULE.bazel.lock "${proof}/module-lock-before.json"
cp "${GITHUB_WORKSPACE:?}/support/.publication/receipt.json" "${proof}/input-receipt.json"
cp "${GITHUB_WORKSPACE:?}/support/.publication/paths.nul" "${proof}/input-paths.nul"
bazel_output_base="${RUNNER_TEMP:?}/gvy-pnpm-inspection-${GITHUB_RUN_ID:?}-${GITHUB_RUN_ATTEMPT:?}"
[[ ! -e ${bazel_output_base} ]]
bazel_started=false
bazel_command=(env -u JAVA_TOOL_OPTIONS -u JDK_JAVA_OPTIONS -u _JAVA_OPTIONS bazel)
bazel_startup=(--nohome_rc "--output_base=${bazel_output_base}" --host_jvm_args=-Xmx1024m)

export_preparation() {
    local body_status=$? shutdown_status=0 shutdown_report_status=0 export_status status_report_status status
    status=${body_status}
    trap - EXIT
    set +e
    if [[ ${bazel_started} == true ]]; then
        "${bazel_command[@]}" "${bazel_startup[@]}" shutdown >"${proof}/bazel-shutdown.log" 2>&1
        shutdown_status=$?
        printf '{"commandExitCode":%s}\n' "${shutdown_status}" >"${proof}/bazel-shutdown-status.json"
        shutdown_report_status=$?
        if [[ ${status} -eq 0 && ${shutdown_status} -ne 0 ]]; then status=${shutdown_status}; fi
        if [[ ${status} -eq 0 && ${shutdown_report_status} -ne 0 ]]; then status=${shutdown_report_status}; fi
    fi
    (
        set -e
        cp MODULE.bazel.lock "${proof}/module-lock-after.json"
        git diff --binary "${base}" >"${proof}/prepared-source.patch"
        git diff --name-only -z "${base}" >"${proof}/prepared-paths.nul"
        PROOF_STATUS="${status}" node --input-type=module - "${proof}" <<'JSEXPORT'
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { lstatSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
const proof = process.argv[2];
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const names = readFileSync(join(proof, 'prepared-paths.nul'), 'utf8').split('\0').filter(Boolean);
assert(names.every(name => ['MODULE.bazel', 'MODULE.bazel.lock'].includes(name)));
const files = {};
for (const name of names) {
    const stat = lstatSync(name);
    assert(stat.isFile() && !stat.isSymbolicLink() && stat.size <= 4 * 1024 * 1024);
    const bytes = readFileSync(name);
    const target = join(proof, 'source', name);
    mkdirSync(dirname(target), { recursive: true });
    writeFileSync(target, bytes);
    files[name] = { bytes: bytes.length, sha256: hash(bytes) };
}
const receipt = {
    preparationExitCode: Number(process.env.PROOF_STATUS),
    context: { runId: process.env.GITHUB_RUN_ID, runAttempt: Number(process.env.GITHUB_RUN_ATTEMPT),
        base: process.env.EXPECTED_BASE, stagedInputTree: process.env.EXPECTED_TREE,
        inputPatchSha256: process.env.PATCH_SHA },
    files, patchSha256: hash(readFileSync(join(proof, 'prepared-source.patch'))),
    lockBeforeSha256: hash(readFileSync(join(proof, 'module-lock-before.json'))),
    lockAfterSha256: hash(readFileSync(join(proof, 'module-lock-after.json'))),
    finalReturnedSourceReviewPending: true,
};
writeFileSync(join(proof, 'prepared-source-receipt.json'), JSON.stringify(receipt, null, 2) + '\n');
JSEXPORT
    )
    export_status=$?
    if [[ ${status} -eq 0 && ${export_status} -ne 0 ]]; then status=${export_status}; fi
    printf '{"bodyExitCode":%s,"shutdownExitCode":%s,"shutdownStatusWriteExitCode":%s,"exportExitCode":%s,"commandExitCode":%s}\n' \
        "${body_status}" "${shutdown_status}" "${shutdown_report_status}" "${export_status}" "${status}" \
        >"${proof}/preparation-status.json"
    status_report_status=$?
    if [[ ${status} -eq 0 && ${status_report_status} -ne 0 ]]; then status=${status_report_status}; fi
    exit "${status}"
}
trap export_preparation EXIT

node tools/ci/run-gradle.mjs \
    --report-root "${RUNNER_TEMP:?}/gvy-pnpm-gradle-evidence" --producer pnpm-inspection-lint-fix \
    lintFix --no-daemon --no-parallel --max-workers=1 \
    '-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=768m -XX:+UseSerialGC'
git diff --exit-code

# These are real inspection/tool invocations. Preserve their own statuses; do not invent build completion evidence.
inspect() {
    local producer=${1:?}
    shift
    printf '%s\0' "${bazel_command[@]}" "${bazel_startup[@]}" "$@" >"${proof}/${producer}-command.nul"
    bazel_started=true
    set +e
    "${bazel_command[@]}" "${bazel_startup[@]}" "$@" 2>&1 | tee "${proof}/${producer}.log"
    local statuses=("${PIPESTATUS[@]}")
    set -e
    printf '{"commandExitCode":%s,"captureExitCode":%s}\n' "${statuses[0]}" "${statuses[1]}" \
        >"${proof}/${producer}-status.json"
    node --input-type=module - "${proof}" "${producer}" <<'JSDIAGNOSTICS'
import assert from 'node:assert/strict';
import { isUtf8 } from 'node:buffer';
import { createHash } from 'node:crypto';
import { readFileSync, statSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { collectDiagnostics, evaluate } from './tools/ci/bazel-diagnostics.mjs';
const [proof, producer] = process.argv.slice(2);
const path = join(proof, `${producer}.log`);
assert(statSync(path).size <= 32 * 1024 * 1024);
const bytes = readFileSync(path);
assert(bytes.length > 0 && isUtf8(bytes) && bytes.at(-1) === 10);
assert(!bytes.includes(0) && !bytes.includes(27));
const status = JSON.parse(readFileSync(join(proof, `${producer}-status.json`), 'utf8'));
const result = evaluate(collectDiagnostics(bytes.toString('utf8')),
    JSON.parse(readFileSync('tools/ci/bazel-diagnostics.json', 'utf8')));
const context = { runId: process.env.GITHUB_RUN_ID, runAttempt: Number(process.env.GITHUB_RUN_ATTEMPT),
    base: process.env.EXPECTED_BASE, stagedInputTree: process.env.EXPECTED_TREE,
    inputPatchSha256: process.env.PATCH_SHA };
assert.match(context.runId, /^[0-9]+$/);
assert(Number.isSafeInteger(context.runAttempt) && context.runAttempt > 0);
const report = { ...result, ...status, context, producer,
    log: { bytes: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex') },
    scope: 'Actual inspection or tool invocation; no fabricated build/test completion marker.' };
writeFileSync(join(proof, `${producer}-diagnostics.json`), JSON.stringify(report, null, 2) + '\n');
assert.equal(status.commandExitCode, 0);
assert.equal(status.captureExitCode, 0);
assert.equal(result.ok, true);
JSDIAGNOSTICS
}

"${bazel_command[@]}" --version >"${proof}/bazel-version.log" 2>&1
[[ $(<.bazelversion) == 9.0.0 ]]
grep -Fx 'bazel 9.0.0' "${proof}/bazel-version.log"
printf '%s\n' 'BAZELISK_GITHUB_TOKEN=' >>"${GITHUB_ENV:?}"
unset BAZELISK_GITHUB_TOKEN
inspect mod-deps mod deps --lockfile_mode=update --repo_env=JAVA_TOOL_OPTIONS=-Xmx512m --color=no --curses=no
cp MODULE.bazel.lock "${proof}/module-lock-after-resolve.json"
inspect mod-graph mod graph --lockfile_mode=error --repo_env=JAVA_TOOL_OPTIONS=-Xmx512m --color=no --curses=no
inspect rules-kotlin-repository mod show_repo @rules_kotlin --lockfile_mode=error \
    --repo_env=JAVA_TOOL_OPTIONS=-Xmx512m --color=no --curses=no
inspect pnpm-version run @pnpm//:pnpm --lockfile_mode=error \
    --repo_env=JAVA_TOOL_OPTIONS=-Xmx512m --action_env=JAVA_TOOL_OPTIONS=-Xmx512m --color=no --curses=no -- --version
pnpm_version_matches=$(grep -c '^10\.27\.0$' "${proof}/pnpm-version.log")
[[ ${pnpm_version_matches} -eq 1 ]]
inspect buildifier run @buildifier_prebuilt//:buildifier --lockfile_mode=error \
    --repo_env=JAVA_TOOL_OPTIONS=-Xmx512m --action_env=JAVA_TOOL_OPTIONS=-Xmx512m --color=no --curses=no \
    -- -mode=check -lint=warn MODULE.bazel

node tools/ci/run-bazel.mjs \
    --report-root "${RUNNER_TEMP:?}/gvy-pnpm-bazel-evidence" \
    --private-report-root "${RUNNER_TEMP:?}/gvy-pnpm-bazel-private" \
    --producer pnpm-inspection-jackson-runtime \
    --startup-arg "--output_base=${bazel_output_base}" --startup-arg --host_jvm_args=-Xmx1024m \
    test //tools/ci/jackson:jackson_runtime_test //tools/ci/jackson:jackson_yaml_runtime_test \
    --lockfile_mode=error --repo_env=JAVA_TOOL_OPTIONS=-Xmx512m --action_env=JAVA_TOOL_OPTIONS=-Xmx512m \
    --nocache_test_results --jobs=1 --local_resources=cpu=1 --local_resources=memory=3072 \
    --host_jvmopt=-Xmx768m --jvmopt=-Xmx256m --worker_max_instances=1 --disk_cache=
shopt -s nullglob
bazel_logs=("${RUNNER_TEMP:?}"/gvy-pnpm-bazel-private/*/console.log)
[[ ${#bazel_logs[@]} -eq 1 ]]
node tools/ci/bazel-diagnostics.mjs --baseline tools/ci/bazel-diagnostics.json \
    --report "${proof}/bazel-diagnostics" "${bazel_logs[@]}"
cmp "${proof}/module-lock-after-resolve.json" MODULE.bazel.lock
node tools/ci/dependency-maintenance.mjs

# Only the actual successful generated lock may enter the candidate. Normal hooks remain enabled.
git add MODULE.bazel MODULE.bazel.lock
git diff --exit-code
git diff --cached --check
[[ -z ${LEFTHOOK:-} && -z ${LEFTHOOK_EXCLUDE:-} && -z ${LEFTHOOK_SKIP:-} ]]
[[ -z $(git config --get core.hooksPath || true) ]]
lefthook install
test -x .git/hooks/pre-commit && test -x .git/hooks/commit-msg
git config user.name 'Alberto Cavalcante'
git config user.email 'alberto@cavalcante.uk'
for target in license-check license-test; do
    set +e
    make "${target}" 2>&1 | tee "${proof}/${target}.log"
    statuses=("${PIPESTATUS[@]}")
    set -e
    printf '{"commandExitCode":%s,"captureExitCode":%s}\n' "${statuses[0]}" "${statuses[1]}" \
        >"${proof}/${target}-status.json"
    [[ ${statuses[0]} -eq 0 && ${statuses[1]} -eq 0 ]]
done
branch=$(git branch --show-current)
[[ ${branch} == ci/e2e-pnpm-inspection-preparation ]]
git commit -m 'fix(build): pin integrity for pnpm module inspection' 2>&1 | tee "${proof}/normal-commit.log"
git diff --exit-code
git diff --cached --exit-code
untracked=$(git ls-files --others --exclude-standard)
[[ -z ${untracked} ]]
head=$(git rev-parse HEAD)
parent=$(git rev-parse HEAD^)
tree=$(git rev-parse 'HEAD^{tree}')
[[ ${parent} == "${base}" ]]
git bundle create "${proof}/candidate.bundle" "${base}..refs/heads/${branch}"
git bundle verify "${proof}/candidate.bundle"
git bundle list-heads "${proof}/candidate.bundle" "refs/heads/${branch}" >"${proof}/bundle-head.txt"
[[ $(<"${proof}/bundle-head.txt") == "${head} refs/heads/${branch}" ]]
printf '{"head":"%s","parent":"%s","tree":"%s"}\n' "${head}" "${base}" "${tree}" \
    >"${proof}/candidate-identity.json"
