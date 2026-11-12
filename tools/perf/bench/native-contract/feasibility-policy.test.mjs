import assert from 'node:assert/strict';
import test from 'node:test';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { sha256 } from './source-files.mjs';
import { probeSourceFiles } from './probe-sources.mjs';
import { validateFeasibility } from './feasibility-policy.mjs';

const digest = 'a'.repeat(64);
const controls = ['valid-parse', 'exact-variable-identifier-boundary', 'malformed-error-location', 'complete-highlight-event-stream', 'regex-positive-negative', 'unknown-predicate-rejected', 'embedded-injection-explicitly-unsupported'];
const identity = engine => ({ grammar: { revision: engine, files: [{ path: 'src/parser.c', sha256: digest }] }, runtime: [{ version: engine, revision: engine }], queryDigests: { highlights: { rawSha256: digest, effectiveSha256: digest, policy: 'upstream-unchanged' } }, cargoLockSha256: digest, manifestSha256: digest, fixtureDigests: { valid: digest, malformed: digest }, probeSourceDigests: { 'contract.rs': digest } });
const fixtures = { valid: "int value = 1\nString label = '🙂'\ndef MAX_VALUE = 2\ndef Max_Value = MAX_VALUE\n", malformed: 'def broken = (\n' };
const limitations = { embeddedLanguages: 'unsupported-rejected', incrementalHighlighting: 'not-tested', performance: 'not-measured', prebuiltTreeHighlighting: 'unavailable-in-official-api', unrecognizedLocalDefinitionCaptures: ['local.definition.parameter', 'local.definition.var', 'local.definition.field'], upstreamLocalBindingParity: 'not-established' };
const policy = { schemaVersion: 1, policyId: 'b01-stock-secondary-rejected-v1', identities: { primary: identity('primary'), secondary: identity('secondary') }, nativeExpectations: { fixtures, primary: { grammarAbi: 15, limitations } } };
for (const engine of ['primary', 'secondary']) policy.identities[engine].fixtureDigests = Object.fromEntries(Object.entries(fixtures).map(([name, source]) => [name, sha256(Buffer.from(source))]));
const inputs = () => ({
  primary: { schemaVersion: 1, scope: 'native-feasibility-only', engine: 'primary', ...structuredClone(policy.identities.primary), binarySha256: digest, native: { schemaVersion: 1, scope: 'native-feasibility-only', engine: 'primary', runtimeVersion: 'primary', queries: 'upstream-unchanged', controlCount: 7, controls, grammarAbi: 15, limitations: structuredClone(limitations), validSpans: [{startByte:0,endByte:3,category:'type.builtin'},{startByte:3,endByte:14,category:null},{startByte:14,endByte:20,category:'type'},{startByte:20,endByte:80,category:null}], malformedIssues: [{kind:'ERROR',startByte:11,endByte:14}] } },
  secondary: { schemaVersion: 1, scope: 'native-feasibility-attempt', engine: 'secondary', ...structuredClone(policy.identities.secondary), binarySha256: digest, outcome: 'rejected-candidate', failureCode: 'NATIVE_PROBE_IDENTIFIER_BOUNDARY_FAILED_SECONDARY_VARIABLE', fixtureBytesVerified: true, completedControls: ['unknown-predicate-rejected', 'valid-parse'], failedControl: 'exact-variable-identifier-boundary', notRunAfterRejection: ['malformed-error-location', 'complete-highlight-event-stream', 'regex-positive-negative', 'embedded-injection-explicitly-unsupported'] },
  observed: { secondaryExitCode: 1, secondaryBinarySha256: digest },
});

test('only the identity-bound known secondary rejection permits a feasibility decision', () => {
  const { primary, secondary, observed } = inputs();
  assert.deepEqual(validateFeasibility(primary, secondary, observed, policy), { schemaVersion: 1, scope: 'native-feasibility-decision', policyId: policy.policyId, primary: 'passed-seven-controls', secondary: 'rejected-candidate', failureCode: secondary.failureCode, performance: 'not-measured' });
});

test('all frozen source identities reject drift on either candidate', () => {
  for (const engine of ['primary', 'secondary']) {
    for (const mutate of [
      e => { e.grammar.revision = 'changed'; }, e => { e.grammar.files[0].sha256 = 'b'.repeat(64); },
      e => { e.runtime[0].version = 'changed'; }, e => { e.runtime[0].revision = 'changed'; },
      e => { e.queryDigests.highlights.rawSha256 = 'b'.repeat(64); }, e => { e.queryDigests.highlights.effectiveSha256 = 'b'.repeat(64); },
      e => { e.queryDigests.highlights.policy = 'adapted'; }, e => { e.cargoLockSha256 = 'b'.repeat(64); },
      e => { e.manifestSha256 = 'b'.repeat(64); }, e => { e.fixtureDigests.valid = 'b'.repeat(64); },
      e => { e.probeSourceDigests['contract.rs'] = 'b'.repeat(64); }, e => { e.probeSourceDigests['new.rs'] = digest; },
    ]) {
      const value = inputs(); mutate(value[engine]);
      assert.throws(() => validateFeasibility(value.primary, value.secondary, value.observed, policy));
    }
  }
});

test('unexpected success, other errors, partial primary and missing actual exit or binary proof fail', () => {
  for (const mutate of [
    v => { v.secondary.outcome = 'passed'; }, v => { v.secondary.failureCode = 'NATIVE_PROBE_VALID_PARSE_FAILED'; },
    v => { v.secondary.failureCode = 'NATIVE_COMMAND_TIMEOUT'; }, v => { v.secondary.failureCode = 'NATIVE_COMMAND_OUTPUT_LIMIT'; },
    v => { v.secondary.failureCode = 'NATIVE_COMMAND_CLEANUP_UNKNOWN'; }, v => { v.secondary.failureCode = 'NATIVE_PROBE_FAILED'; },
    v => { v.secondary.fixtureBytesVerified = false; }, v => { delete v.secondary.notRunAfterRejection; },
    v => { v.secondary.completedControls.push('regex-positive-negative'); },
    v => { v.primary.native.controlCount = 6; }, v => { v.primary.native.controls = []; },
    v => { v.observed.secondaryExitCode = 0; }, v => { v.observed.secondaryExitCode = 2; },
    v => { v.observed.secondaryExitCode = '1'; }, v => { delete v.observed.secondaryExitCode; },
    v => { v.observed.secondaryBinarySha256 = 'b'.repeat(64); }, v => { delete v.observed.secondaryBinarySha256; },
    v => { v.secondary.binarySha256 = ''; }, v => { v.primary.binarySha256 = ''; },
  ]) {
    const value = inputs(); mutate(value);
    assert.throws(() => validateFeasibility(value.primary, value.secondary, value.observed, policy));
  }
});

test('primary result integrity rejects missing, partial, invalid ranges and false compatibility claims', () => {
  for (const mutate of [
    n => { delete n.validSpans; }, n => { n.validSpans = []; }, n => { n.validSpans.pop(); },
    n => { n.validSpans[0].category = 'type'; }, n => { n.validSpans[2].category = 'type.builtin'; },
    n => { n.validSpans[1].startByte = 4; }, n => { n.validSpans[1].startByte = 2; },
    n => { n.validSpans[0].endByte = 0; }, n => { n.validSpans[0].startByte = 0.5; },
    n => { n.validSpans[3].endByte = 32; n.validSpans.push({startByte:32,endByte:80,category:null}); },
    n => { n.validSpans[3].category = 'x'.repeat(129); }, n => { n.validSpans[3].category = {}; },
    n => { n.validSpans[3].endByte = 81; }, n => { n.validSpans.reverse(); },
    n => { delete n.malformedIssues; }, n => { n.malformedIssues = []; },
    n => { n.malformedIssues = [{kind:'ERROR',startByte:0,endByte:1}]; },
    n => { n.malformedIssues.push({kind:'OTHER',startByte:11,endByte:14}); },
    n => { n.malformedIssues.push({kind:'ERROR',startByte:14,endByte:99}); },
    n => { n.malformedIssues.push({kind:'MISSING',startByte:13,endByte:14}); },
    n => { n.malformedIssues = [{kind:'ERROR',startByte:15,endByte:15}]; },
    n => { n.grammarAbi = 14; }, n => { delete n.limitations; },
    n => { n.limitations.upstreamLocalBindingParity = 'established'; },
    n => { n.limitations.embeddedLanguages = 'supported'; },
  ]) {
    const value = inputs(); mutate(value.primary.native);
    assert.throws(() => validateFeasibility(value.primary, value.secondary, value.observed, policy));
  }
});

test('primary recovery accepts legitimate overlapping errors and zero-width missing EOF nodes', () => {
  for (const issues of [
    [{kind:'ERROR',startByte:11,endByte:14},{kind:'ERROR',startByte:12,endByte:14}],
    [{kind:'MISSING',startByte:14,endByte:14}], [{kind:'MISSING',startByte:15,endByte:15}],
  ]) {
    const value = inputs(); value.primary.native.malformedIssues = issues;
    assert.equal(validateFeasibility(value.primary, value.secondary, value.observed, policy).primary, 'passed-seven-controls');
  }
});

test('every malformed issue boundary and frozen fixture hash is checked', () => {
  const changed = structuredClone(policy);
  changed.nativeExpectations.fixtures.malformed = 'def broken = (🙂\n';
  const value = inputs();
  for (const engine of ['primary', 'secondary']) {
    changed.identities[engine].fixtureDigests.malformed = sha256(Buffer.from(changed.nativeExpectations.fixtures.malformed));
    value[engine].fixtureDigests.malformed = changed.identities[engine].fixtureDigests.malformed;
  }
  value.primary.native.malformedIssues = [{kind:'ERROR',startByte:11,endByte:18},{kind:'ERROR',startByte:15,endByte:16}];
  assert.throws(() => validateFeasibility(value.primary, value.secondary, value.observed, changed));
  const wrongText = structuredClone(policy); wrongText.nativeExpectations.fixtures.valid += ' ';
  const original = inputs();
  assert.throws(() => validateFeasibility(original.primary, original.secondary, original.observed, wrongText));
});

test('frozen policy hashes match the reviewed local sources and dependency pins', () => {
  const root = path.dirname(fileURLToPath(import.meta.url));
  const frozen = JSON.parse(fs.readFileSync(path.join(root, 'feasibility-policy.json'), 'utf8'));
  const pins = JSON.parse(fs.readFileSync(path.join(root, 'source-lock.json'), 'utf8'));
  const sourceNames = ['contract.rs', 'run.mjs', 'command.mjs', 'diagnostics.mjs', 'source-files.mjs', 'attempt.mjs', 'feasibility-policy.mjs', 'feasibility.mjs', 'primary-result.mjs', 'probe-sources.mjs', 'source-lock.json', 'tools/ci/check-rust-diagnostics.mjs', 'tools/ci/read-evidence.mjs'];
  for (const engine of ['primary', 'secondary']) {
    const value = frozen.identities[engine];
    const pin = pins.engines[engine];
    assert.equal(value.grammar.repository, pin.grammarRepository);
    assert.equal(value.grammar.revision, pin.grammarRevision);
    assert.deepEqual(Object.fromEntries(value.grammar.files.map(file => [file.path, file.sha256])), pin.files);
    assert.deepEqual(value.runtime, ['tree-sitter', 'tree-sitter-highlight'].map(name => ({ package: name, version: pin.runtimeVersion, revision: pin.runtimeRevision })));
    for (const name of ['highlights', 'locals', 'injections']) assert.deepEqual(value.queryDigests[name], { rawSha256: pin.files[`${pin.queryDirectory}/${name}.scm`], effectiveSha256: pin.files[`${pin.queryDirectory}/${name}.scm`], policy: 'upstream-unchanged' });
    assert.equal(value.cargoLockSha256, sha256(fs.readFileSync(path.join(root, engine, 'Cargo.lock'))));
    assert.equal(value.manifestSha256, sha256(fs.readFileSync(path.join(root, engine, 'Cargo.toml'))));
    for (const name of ['valid', 'malformed']) assert.equal(value.fixtureDigests[name], sha256(fs.readFileSync(path.join(root, 'fixtures', `${name}.groovy`))));
    assert.deepEqual(Object.keys(value.probeSourceDigests).sort(), [...sourceNames, `${engine}/src/main.rs`].sort());
    const trustedFiles = probeSourceFiles(engine);
    assert.deepEqual(Object.keys(trustedFiles).sort(), [...sourceNames, `${engine}/src/main.rs`].sort());
    // Reads come only from the fixed trusted map, never from receipt/policy path strings.
    for (const [name, file] of Object.entries(trustedFiles)) assert.equal(value.probeSourceDigests[name], sha256(fs.readFileSync(file)), name);
  }
  for (const name of ['valid', 'malformed']) assert.equal(frozen.nativeExpectations.fixtures[name], fs.readFileSync(path.join(root, 'fixtures', `${name}.groovy`), 'utf8'));
});

test('policy CLI rejects missing receipts and private arguments without exposing them', () => {
  const cli = fileURLToPath(new URL('./feasibility.mjs', import.meta.url));
  const canary = 'PRIVATE_CANARY_7821';
  const result = spawnSync(process.execPath, [cli, canary, canary, '0', digest], { timeout: 3000, maxBuffer: 4096, encoding: 'utf8' });
  assert.equal(result.status, 1);
  assert.equal(result.stdout, '');
  assert.equal(result.stderr, 'FEASIBILITY_POLICY_MISMATCH\n');
});

test('probe source map accepts only fixed engines and canonical dependency targets', () => {
  for (const engine of ['../private', 'PRIVATE_CANARY_7821', '', null]) assert.throws(() => probeSourceFiles(engine), /ENGINE_INVALID/);
  const files = probeSourceFiles('primary');
  assert.equal(files['tools/ci/check-rust-diagnostics.mjs'], fileURLToPath(new URL('../../../ci/check-rust-diagnostics.mjs', import.meta.url)));
  assert.equal(files['tools/ci/read-evidence.mjs'], fileURLToPath(new URL('../../../ci/read-evidence.mjs', import.meta.url)));
});

test('policy CLI reads the frozen policy and preserves rejected-candidate labels', t => {
  const frozen = JSON.parse(fs.readFileSync(new URL('./feasibility-policy.json', import.meta.url), 'utf8'));
  const value = inputs();
  for (const engine of ['primary', 'secondary']) Object.assign(value[engine], frozen.identities[engine]);
  value.primary.native.runtimeVersion = frozen.identities.primary.runtime[0].version;
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'gvy-feasibility-policy-'));
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  for (const engine of ['primary', 'secondary']) fs.writeFileSync(path.join(directory, `${engine}.json`), JSON.stringify(value[engine]));
  const cli = fileURLToPath(new URL('./feasibility.mjs', import.meta.url));
  const result = spawnSync(process.execPath, [cli, path.join(directory, 'primary.json'), path.join(directory, 'secondary.json'), '1', digest], { timeout: 3000, maxBuffer: 4096, encoding: 'utf8' });
  assert.equal(result.status, 0);
  assert.equal(result.stderr, '');
  assert.equal(JSON.parse(result.stdout).secondary, 'rejected-candidate');
});
