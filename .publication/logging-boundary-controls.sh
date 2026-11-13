#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail
[[ $# -eq 4 ]]
expected_tree=${1:?}
negative_patch=${2:?}
negative_spec=${3:?}
proof_dir=${4:?}
printf '%s  %s\n' '9385ed6df36ae869d8c41e1ff6f91351d006fc66ef45dbc739dfc76c09b33b77' "${negative_patch}" | sha256sum -c -
printf '%s  %s\n' 'd673751ba27cf34decb2fc3ee9312b63eccfae87653545894d1bb323fc66c427' "${negative_spec}" | sha256sum -c -
negative_bytes=$(stat -c %s "${negative_patch}")
[[ ${negative_bytes} -le 8192 ]]
spec_tree=$(jq -er '.greenTree' "${negative_spec}")
[[ ${spec_tree} == "${expected_tree}" ]]
base=$(git rev-parse HEAD)
spec_base=$(jq -er '.base' "${negative_spec}")
[[ ${base} == "${spec_base}" ]]
index_tree=$(git write-tree)
[[ ${index_tree} == "${expected_tree}" ]]
git diff --exit-code
mkdir -p "${proof_dir}/junit"
cp "${negative_patch}" "${proof_dir}/negative-control.patch"
cp "${negative_spec}" "${proof_dir}/negative-control-spec.json"
restore_mutation() {
    git apply --reverse --check "${negative_patch}" && git apply --reverse "${negative_patch}"
}
git apply --check "${negative_patch}"
git apply "${negative_patch}"
trap restore_mutation EXIT
python3 - "${negative_spec}" "${proof_dir}" <<'PYSOURCE'
import hashlib, json, pathlib, sys
spec = json.loads(pathlib.Path(sys.argv[1]).read_text())
proof = pathlib.Path(sys.argv[2])
for name, expected in spec['sourceSha256'].items():
    data = pathlib.Path(name).read_bytes()
    assert hashlib.sha256(data).hexdigest() == expected, name
    target = proof / 'source' / name
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(data)
PYSOURCE
set +e
direnv exec . node tools/ci/run-gradle.mjs \
    --report-root "${proof_dir}/gradle-evidence" --producer logging-boundary-red \
    :tests:e2eTest \
    --tests '*DiagnosticsTerminalProtocolTest.in-process terminal session restores process logging levels' \
    --tests '*.LoggingConfigTest' \
    -Pe2eParallelForks=1 -PtestJavaVersion=17 --no-build-cache --rerun-tasks --info \
    --no-daemon --no-parallel --max-workers=1 \
    '-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=768m -XX:+UseSerialGC'
runner_status=$?
set -e
printf '{"runnerExitCode":%s}\n' "${runner_status}" >"${proof_dir}/runner-status.json"
for report in DiagnosticsTerminalProtocolTest LoggingConfigTest; do
    report_path="tests/build/test-results/e2eTest/TEST-com.github.albertocavalcante.gvy.gls.e2e.${report}.xml"
    if [[ -f ${report_path} ]]; then
        cp "${report_path}" "${proof_dir}/junit/"
    fi
done
restore_mutation
trap - EXIT
git diff --exit-code
index_tree=$(git write-tree)
[[ ${index_tree} == "${expected_tree}" ]]
node --input-type=module - "${proof_dir}" "${runner_status}" <<'JSDIAGNOSTICS'
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
const [proof, runnerStatus] = process.argv.slice(2);
const evidenceRoot = join(proof, 'gradle-evidence');
const dirs = readdirSync(evidenceRoot);
assert.equal(dirs.length, 1);
assert.match(dirs[0], /^logging-boundary-red-[0-9a-f-]{36}$/);
const dir = join(evidenceRoot, dirs[0]);
const result = JSON.parse(readFileSync(join(dir, 'diagnostics.json'), 'utf8'));
const context = JSON.parse(readFileSync(join(dir, 'context.json'), 'utf8'));
const spec = JSON.parse(readFileSync(join(proof, 'negative-control-spec.json'), 'utf8'));
const bytes = readFileSync(join(dir, 'console.log'));
assert.equal(Number(runnerStatus), result.commandExitCode);
assert.notEqual(result.commandExitCode, 0);
assert.equal(result.captureExitCode, 0);
assert.equal(result.commandSignal, null);
assert.equal(result.complete, true);
assert.equal(result.ok, false);
assert.equal(result.worktreeDirty, true);
assert.equal(result.producer, 'logging-boundary-red');
assert.deepEqual(result.errors, [`Gradle command failed with exit ${result.commandExitCode}`]);
assert.deepEqual(result.unexpected, []);
assert.deepEqual(result.context, context);
assert.equal(context.headSha, spec.base);
assert.equal(context.testedSha, spec.base);
assert.equal(context.runId, process.env.GITHUB_RUN_ID);
assert.equal(context.runAttempt, Number(process.env.GITHUB_RUN_ATTEMPT));
assert.deepEqual(result.log, { bytes: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex') });
const lines = bytes.toString('utf8').split(/\r?\n/);
const headers = lines.filter(line => /^> Task :tests:e2eTest(?: |$)/.test(line));
assert(headers.includes('> Task :tests:e2eTest FAILED'));
assert(headers.every(line => line === '> Task :tests:e2eTest' || line === '> Task :tests:e2eTest FAILED'));
assert.equal(headers.filter(line => line.endsWith(' FAILED')).length, 1);
assert(lines.some(line => line.startsWith('BUILD FAILED')));
writeFileSync(join(proof, 'verified-canonical-context.json'), JSON.stringify({ context, log: result.log,
    commandExitCode: result.commandExitCode, captureExitCode: result.captureExitCode }, null, 2) + '\n');
JSDIAGNOSTICS
python3 - "${proof_dir}" <<'PYRESULT'
import hashlib, json, pathlib, sys, xml.etree.ElementTree as ET
proof = pathlib.Path(sys.argv[1])
spec = json.loads((proof / 'negative-control-spec.json').read_text())
reports = sorted((proof / 'junit').glob('TEST-*.xml'))
assert len(reports) == 2, 'missing targeted reports'
totals = dict(tests=0, failures=0, errors=0, skipped=0)
failed, passed, identities = set(), set(), []
report_hashes = {}
for path in reports:
    assert path.stat().st_size <= 1048576
    root = ET.parse(path).getroot()
    cases = root.findall('testcase')
    assert len(cases) == int(root.attrib['tests'])
    for key in totals:
        totals[key] += int(root.attrib.get(key, '0'))
    assert not root.findall('testcase/error') and not root.findall('testcase/skipped')
    for case in cases:
        identities.append((case.attrib['classname'], case.attrib['name']))
        faults = case.findall('failure')
        name = case.attrib['name']
        if faults:
            assert len(faults) == 1
            assert faults[0].attrib['type'] in ('java.lang.AssertionError', 'org.opentest4j.AssertionFailedError')
            assert spec['expectedFailureMessage'] in faults[0].attrib.get('message', '')
            failed.add(name)
        else:
            passed.add(name)
    report_hashes[path.name] = hashlib.sha256(path.read_bytes()).hexdigest()
assert totals == spec['totals'], totals
assert len(identities) == len(set(identities)) == 4
assert failed == set(spec['expectedFailureNames']), failed
assert passed == set(spec['expectedPositiveNames']), passed
receipt = dict(greenTree=spec['greenTree'], declaredNegativeTree=spec['negativeTree'],
               negativeSourceSha256=spec['sourceSha256'], totals=totals,
               failureNames=sorted(failed), positiveNames=sorted(passed), reports=report_hashes,
               greenSourceRestoredAndTreeChecked=True)
(proof / 'verified-red-result.json').write_text(json.dumps(receipt, indent=2) + '\n')
print('Logging control verified: 4 unique cases, 1 specific leak assertion, 3 healthy logging controls; green restored.')
PYRESULT
