#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail
[[ $# -eq 4 ]]
expected_tree=${1:?}
negative_patch=${2:?}
negative_spec=${3:?}
proof_dir=${4:?}
printf '%s  %s\n' 'cbb784d585e9718b72a929aa23ac0d51bc6375402f01d2d028725b04da930094' "${negative_patch}" | sha256sum -c -
printf '%s  %s\n' '8e9dda712102f19810a67c0df8646821f4fb0f83152773cc2083ab3b754fbb23' "${negative_spec}" | sha256sum -c -
negative_bytes=$(stat -c %s "${negative_patch}")
[[ ${negative_bytes} -le 8192 ]]
spec_tree=$(jq -er '.greenTree' "${negative_spec}")
[[ ${spec_tree} == "${expected_tree}" ]]
git apply --check "${negative_patch}"
git apply "${negative_patch}"
index_tree=$(git write-tree)
[[ ${index_tree} == "${expected_tree}" ]]
mkdir -p "${proof_dir}/junit"
cp "${negative_patch}" "${proof_dir}/negative-control.patch"
cp "${negative_spec}" "${proof_dir}/negative-control-spec.json"
python3 - "${negative_spec}" "${proof_dir}" <<'PYREDSOURCE'
import hashlib, json, pathlib, sys
spec = json.loads(pathlib.Path(sys.argv[1]).read_text())
proof = pathlib.Path(sys.argv[2])
for name, expected in spec['sourceSha256'].items():
    source = pathlib.Path(name)
    data = source.read_bytes()
    assert hashlib.sha256(data).hexdigest() == expected, name
    target = proof / 'source' / name
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(data)
PYREDSOURCE
set +e
direnv exec . ./gradlew :gls:test \
    --tests '*DiagnosticsRequestHandlerTest.oversized*' \
    --tests '*DiagnosticsRequestRegistrationTest.terminal*' \
    --tests '*DiagnosticsRequestRegistrationTest.shutdown*' \
    --no-daemon --no-parallel --max-workers=1 \
    '-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=768m -XX:+UseSerialGC' \
    --console=plain --warning-mode=all -Dorg.gradle.deprecation.trace=true \
    2>&1 | tee "${proof_dir}/gvy-diagnostics-boundary-red.log"
pipeline_status=("${PIPESTATUS[@]}")
native_status=${pipeline_status[0]}
capture_status=${pipeline_status[1]}
set -e
for report in DiagnosticsRequestHandlerTest DiagnosticsRequestRegistrationTest; do
    report_path="gls/build/test-results/test/TEST-com.github.albertocavalcante.gvy.gls.diagnostics.${report}.xml"
    if [[ -f ${report_path} ]]; then
        cp "${report_path}" "${proof_dir}/junit/"
    fi
done
git apply --reverse --check "${negative_patch}"
git apply --reverse "${negative_patch}"
git diff --exit-code
index_tree=$(git write-tree)
[[ ${index_tree} == "${expected_tree}" ]]
node --input-type=module - "${proof_dir}" "${native_status}" "${capture_status}" <<'JSREDDIAGNOSTICS'
import assert from 'node:assert/strict';
import { isUtf8 } from 'node:buffer';
import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { checkGradleDiagnostics, loadNativeDiagnosticPolicy } from './tools/ci/check-gradle-diagnostics.mjs';
const [proof, commandCode, captureCode] = process.argv.slice(2);
const bytes = readFileSync(join(proof, 'gvy-diagnostics-boundary-red.log'));
const exceptions = [...JSON.parse(readFileSync('tools/ci/gradle-diagnostics.json', 'utf8')), ...loadNativeDiagnosticPolicy()];
const result = checkGradleDiagnostics({ log: bytes.toString('utf8'), commandExitCode: Number(commandCode),
    captureExitCode: Number(captureCode), encodingValid: isUtf8(bytes), exceptions, rootDir: process.cwd() });
assert.equal(result.complete, true);
assert.equal(result.ok, false);
assert.notEqual(result.commandExitCode, 0);
assert.equal(result.captureExitCode, 0);
assert.deepEqual(result.errors, [`Gradle command failed with exit ${Number(commandCode)}`]);
assert.deepEqual(result.unexpected, []);
result.log = { bytes: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex') };
writeFileSync(join(proof, 'canonical-gradle-diagnostics.json'), JSON.stringify(result, null, 2) + '\n');
JSREDDIAGNOSTICS
python3 - "${proof_dir}" "${native_status}" "${capture_status}" <<'PYREDRESULT'
import hashlib, json, pathlib, sys, xml.etree.ElementTree as ET
proof = pathlib.Path(sys.argv[1])
spec = json.loads((proof / 'negative-control-spec.json').read_text())
assert int(sys.argv[2]) != 0, 'negative control unexpectedly passed'
assert int(sys.argv[3]) == 0, 'negative control output capture failed'
reports = sorted((proof / 'junit').glob('TEST-*.xml'))
assert len(reports) == 2, 'missing targeted negative-control reports'
totals = dict(tests=0, failures=0, errors=0, skipped=0)
failed, passed = set(), set()
report_hashes = {}
for path in reports:
    assert path.stat().st_size <= 1048576, 'oversized targeted report'
    root = ET.parse(path).getroot()
    cases = root.findall('testcase')
    assert len(cases) == int(root.attrib['tests'])
    for key in totals:
        totals[key] += int(root.attrib.get(key, '0'))
    assert not root.findall('testcase/error') and not root.findall('testcase/skipped')
    for case in cases:
        faults = case.findall('failure')
        if faults:
            assert len(faults) == 1
            assert faults[0].attrib['type'] in ('java.lang.AssertionError', 'org.opentest4j.AssertionFailedError')
            failed.add(case.attrib['name'])
        else:
            passed.add(case.attrib['name'])
    report_hashes[path.name] = hashlib.sha256(path.read_bytes()).hexdigest()
assert totals == dict(tests=7, failures=5, errors=0, skipped=0), totals
assert failed == set(spec['expectedFailureNames']), failed
assert passed == set(spec['expectedPositiveNames']), passed
log = (proof / 'gvy-diagnostics-boundary-red.log').read_text()
assert '> Task :gls:test FAILED' in log and 'BUILD FAILED' in log
receipt = dict(greenTree=spec['greenTree'], negativeTree=spec['negativeTree'],
               negativeSourceSha256=spec['sourceSha256'], totals=totals,
               failureNames=sorted(failed), positiveNames=sorted(passed), reports=report_hashes,
               greenSourceRestoredAndTreeChecked=True)
(proof / 'verified-red-result.json').write_text(json.dumps(receipt, indent=2) + '\n')
print('Bounded negative controls verified: 7 tests, 5 assertion failures, 2 positive controls; green source restored.')
PYREDRESULT
