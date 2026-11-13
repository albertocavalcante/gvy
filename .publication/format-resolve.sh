#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail
[[ $# -eq 1 ]]
proof=${1:?}
base=$(git rev-parse HEAD)
index_tree=$(git write-tree)
[[ ${base} == "${EXPECTED_BASE:?}" && ${index_tree} == "${EXPECTED_TREE:?}" ]]
git diff --exit-code
mkdir -p "${proof}"
bazel_output_base="${RUNNER_TEMP:?}/gvy-xfail-bazel-${GITHUB_RUN_ID:?}-${GITHUB_RUN_ATTEMPT:?}"
[[ ! -e ${bazel_output_base} ]]
bazel_started=false
cp MODULE.bazel.lock "${proof}/module-lock-before.json"
cp "${GITHUB_WORKSPACE:?}/support/.publication/receipt.json" "${proof}/input-receipt.json"
cp "${GITHUB_WORKSPACE:?}/support/.publication/paths.nul" "${proof}/input-paths.nul"
export_source() {
    local status=$?
    trap - EXIT
    if [[ ${bazel_started} == true ]]; then
        set +e
        bazel --nohome_rc --output_base="${bazel_output_base}" --host_jvm_args=-Xmx1024m shutdown \
            >"${proof}/bazel-shutdown.log" 2>&1
        shutdown_status=$?
        set -e
        printf '{"commandExitCode":%s}\n' "${shutdown_status}" >"${proof}/bazel-shutdown-status.json"
        if [[ ${status} -eq 0 && ${shutdown_status} -ne 0 ]]; then status=${shutdown_status}; fi
    fi
    cp MODULE.bazel.lock "${proof}/module-lock-after.json"
    git diff --binary "${base}" >"${proof}/prepared-source.patch"
    git diff --name-only -z "${base}" >"${proof}/prepared-paths.nul"
    python3 - "${proof}" "${status}" <<'PYEXPORT'
import hashlib, json, pathlib, sys
proof = pathlib.Path(sys.argv[1])
allowed = set((proof / 'input-paths.nul').read_bytes().split(b'\0')) - {b''}
allowed.add(b'MODULE.bazel.lock')
paths = set((proof / 'prepared-paths.nul').read_bytes().split(b'\0')) - {b''}
assert paths <= allowed, ('unexpected changed paths', paths - allowed)
files = {}
for raw in sorted(paths):
    name = raw.decode('utf8')
    source = pathlib.Path(name)
    assert source.is_file() and not source.is_symlink()
    assert source.stat().st_size <= 4 * 1024 * 1024
    data = source.read_bytes()
    target = proof / 'source' / name
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(data)
    files[name] = hashlib.sha256(data).hexdigest()
receipt = dict(preparationExitCode=int(sys.argv[2]), files=files,
    patchSha256=hashlib.sha256((proof / 'prepared-source.patch').read_bytes()).hexdigest(),
    lockBeforeSha256=hashlib.sha256((proof / 'module-lock-before.json').read_bytes()).hexdigest(),
    lockAfterSha256=hashlib.sha256((proof / 'module-lock-after.json').read_bytes()).hexdigest(),
    sourceCommitted=False, finalReviewAndNativeNegativeControlsPending=True)
(proof / 'prepared-source-receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
PYEXPORT
    return "${status}"
}
trap export_source EXIT
node tools/ci/run-gradle.mjs \
    --report-root "${RUNNER_TEMP:?}/gvy-xfail-gradle-evidence" --producer xfail-source-format \
    lintFix --no-daemon --no-parallel --max-workers=1 \
    '-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=768m -XX:+UseSerialGC'
node tools/ci/run-gradle.mjs \
    --report-root "${RUNNER_TEMP:?}/gvy-xfail-gradle-evidence" --producer xfail-source-compile \
    :tests:compileE2eTestKotlin -PtestJavaVersion=17 --no-build-cache \
    --no-daemon --no-parallel --max-workers=1 \
    '-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=768m -XX:+UseSerialGC'
# Resolving is not a build. Keep original statuses and evaluate diagnostics without inventing a completion marker.
bazel --version >"${proof}/bazel-version.log" 2>&1
[[ $(<.bazelversion) == 9.0.0 ]]
grep -Fx 'bazel 9.0.0' "${proof}/bazel-version.log"
printf '%s\n' 'BAZELISK_GITHUB_TOKEN=' >>"${GITHUB_ENV:?}"
unset BAZELISK_GITHUB_TOKEN
printf '%s\0' bazel --nohome_rc --output_base="${bazel_output_base}" --host_jvm_args=-Xmx1024m mod deps \
    --lockfile_mode=update --repo_env=JAVA_TOOL_OPTIONS=-Xmx512m --color=no --curses=no >"${proof}/resolve-command.nul"
bazel_started=true
set +e
bazel --nohome_rc --output_base="${bazel_output_base}" --host_jvm_args=-Xmx1024m mod deps \
    --lockfile_mode=update --repo_env=JAVA_TOOL_OPTIONS=-Xmx512m --color=no --curses=no 2>&1 | tee "${proof}/resolve.log"
statuses=("${PIPESTATUS[@]}")
set -e
printf '{"commandExitCode":%s,"captureExitCode":%s}\n' "${statuses[0]}" "${statuses[1]}" >"${proof}/resolve-status.json"
node --input-type=module - "${proof}" <<'JSRESOLVE'
import assert from 'node:assert/strict';
import { isUtf8 } from 'node:buffer';
import { createHash } from 'node:crypto';
import { readFileSync, statSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { collectDiagnostics, evaluate } from './tools/ci/bazel-diagnostics.mjs';
const proof = process.argv[2];
const path = join(proof, 'resolve.log');
assert(statSync(path).size <= 32 * 1024 * 1024);
const bytes = readFileSync(path);
assert(isUtf8(bytes) && bytes.at(-1) === 10);
const status = JSON.parse(readFileSync(join(proof, 'resolve-status.json'), 'utf8'));
const result = evaluate(collectDiagnostics(bytes.toString('utf8')),
    JSON.parse(readFileSync('tools/ci/bazel-diagnostics.json', 'utf8')));
const context = { runId: process.env.GITHUB_RUN_ID, runAttempt: Number(process.env.GITHUB_RUN_ATTEMPT),
    base: process.env.EXPECTED_BASE, stagedInputTree: process.env.EXPECTED_TREE,
    inputPatchSha256: process.env.PATCH_SHA };
assert.match(context.runId, /^[0-9]+$/);
assert(Number.isSafeInteger(context.runAttempt) && context.runAttempt > 0);
const report = { ...result, ...status, context,
    log: { bytes: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex') },
    scope: 'Actual resolve-only invocation, not build/test success; no graph accountability fingerprint changed.' };
writeFileSync(join(proof, 'resolve-diagnostics.json'), JSON.stringify(report, null, 2) + '\n');
assert.equal(status.commandExitCode, 0);
assert.equal(status.captureExitCode, 0);
assert.equal(result.ok, true);
JSRESOLVE
printf '%s\0' bazel --nohome_rc "--output_base=${bazel_output_base}" --host_jvm_args=-Xmx1024m \
    query 'deps(@maven//:com_google_re2j_re2j, 1)' --output=build --lockfile_mode=error \
    --repo_env=JAVA_TOOL_OPTIONS=-Xmx512m --color=no --curses=no >"${proof}/coordinate-query-command.nul"
set +e
bazel --nohome_rc --output_base="${bazel_output_base}" --host_jvm_args=-Xmx1024m \
    query 'deps(@maven//:com_google_re2j_re2j, 1)' --output=build --lockfile_mode=error \
    --repo_env=JAVA_TOOL_OPTIONS=-Xmx512m --color=no --curses=no 2>&1 | tee "${proof}/coordinate-query.log"
query_statuses=("${PIPESTATUS[@]}")
set -e
printf '{"commandExitCode":%s,"captureExitCode":%s}\n' "${query_statuses[0]}" "${query_statuses[1]}" \
    >"${proof}/coordinate-query-status.json"
node --input-type=module - "${proof}" <<'JSQUERY'
import assert from 'node:assert/strict';
import { isUtf8 } from 'node:buffer';
import { createHash } from 'node:crypto';
import { readFileSync, statSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { collectDiagnostics, evaluate } from './tools/ci/bazel-diagnostics.mjs';
const proof = process.argv[2];
const path = join(proof, 'coordinate-query.log');
assert(statSync(path).size <= 4 * 1024 * 1024);
const bytes = readFileSync(path);
assert(isUtf8(bytes) && bytes.at(-1) === 10);
const status = JSON.parse(readFileSync(join(proof, 'coordinate-query-status.json'), 'utf8'));
const result = evaluate(collectDiagnostics(bytes.toString('utf8')),
    JSON.parse(readFileSync('tools/ci/bazel-diagnostics.json', 'utf8')));
const context = JSON.parse(readFileSync(join(proof, 'resolve-diagnostics.json'), 'utf8')).context;
const report = { ...result, ...status, context,
    log: { bytes: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex') },
    scope: 'Generated Maven target metadata query, not build/test execution.' };
writeFileSync(join(proof, 'coordinate-query-diagnostics.json'), JSON.stringify(report, null, 2) + '\n');
assert.equal(status.commandExitCode, 0);
assert.equal(status.captureExitCode, 0);
assert.equal(result.ok, true);
assert(bytes.toString('utf8').includes('"maven_coordinates=com.google.re2j:re2j:1.8"'));
JSQUERY
cp MODULE.bazel.lock "${proof}/module-lock-after-resolve.json"
node tools/ci/run-bazel.mjs \
    --report-root "${RUNNER_TEMP:?}/gvy-xfail-bazel-evidence" \
    --private-report-root "${RUNNER_TEMP:?}/gvy-xfail-bazel-private" \
    --producer xfail-re2-build --startup-arg "--output_base=${bazel_output_base}" --startup-arg --host_jvm_args=-Xmx1024m \
    build @maven//:com_google_re2j_re2j --lockfile_mode=error --repo_env=JAVA_TOOL_OPTIONS=-Xmx512m --action_env=JAVA_TOOL_OPTIONS=-Xmx512m \
    --jobs=1 --local_resources=cpu=1 --local_resources=memory=3072 \
    --host_jvmopt=-Xmx768m --jvmopt=-Xmx256m --worker_max_instances=1 --disk_cache=
node tools/ci/run-bazel.mjs \
    --report-root "${RUNNER_TEMP:?}/gvy-xfail-bazel-evidence" \
    --private-report-root "${RUNNER_TEMP:?}/gvy-xfail-bazel-private" \
    --producer xfail-jackson-runtime --startup-arg "--output_base=${bazel_output_base}" --startup-arg --host_jvm_args=-Xmx1024m \
    test //tools/ci/jackson:jackson_runtime_test //tools/ci/jackson:jackson_yaml_runtime_test \
    --lockfile_mode=error --repo_env=JAVA_TOOL_OPTIONS=-Xmx512m --action_env=JAVA_TOOL_OPTIONS=-Xmx512m \
    --nocache_test_results --jobs=1 \
    --local_resources=cpu=1 --local_resources=memory=3072 \
    --host_jvmopt=-Xmx768m --jvmopt=-Xmx256m --worker_max_instances=1 --disk_cache=
shopt -s nullglob
bazel_logs=("${RUNNER_TEMP:?}"/gvy-xfail-bazel-private/*/console.log)
[[ ${#bazel_logs[@]} -eq 2 ]]
node tools/ci/bazel-diagnostics.mjs --baseline tools/ci/bazel-diagnostics.json \
    --report "${proof}/bazel-diagnostics" "${bazel_logs[@]}"
cmp "${proof}/module-lock-after-resolve.json" MODULE.bazel.lock
# Export only the real observed source and generated lock. Final graph review, controls, hooks and publication come later.
