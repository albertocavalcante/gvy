import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import test from 'node:test';
import { collectDiagnostics, evaluate } from './bazel-diagnostics.mjs';

const now = new Date('2026-11-11T00:00:00Z');
const diagnostic = '[//indexer/scip:scip_proto] generated/scip/Descriptor.kt:226:9: warning: duplicate branch condition in \'when\'.';
const exception = {
  diagnostic,
  owner: 'build tooling maintainers',
  issue: 'https://github.com/albertocavalcante/gvy/issues/1',
  reason: 'Wire emits duplicate branches for numeric aliases.',
  nextAction: 'Verify an upstream generator fix.',
  acceptance: 'Generated code compiles without this warning.',
  expires: '2026-12-11',
};

test('deduplicates worker replay and normalizes only generated temporary paths', () => {
  const line = `/tmp/random/_kotlinc/target/temp/_srcjars/scip/Descriptor.kt:226:9: warning: duplicate branch condition in 'when'.`;
  assert.deepEqual(collectDiagnostics(`INFO: From KotlinCompile //indexer/scip:scip_proto { kt: 0 }:\n\u001b[35m${line}\u001b[0m\n${line}`), [diagnostic]);
  assert.deepEqual(collectDiagnostics(`src/Descriptor.kt:226:9: warning: duplicate branch condition in 'when'.`),
    [`src/Descriptor.kt:226:9: warning: duplicate branch condition in 'when'.`]);
});

test('the same generated warning in a different action target is new debt', () => {
  const line = `/tmp/random/_srcjars/scip/Descriptor.kt:226:9: warning: duplicate branch condition in 'when'.`;
  const found = collectDiagnostics(`INFO: From KotlinCompile //indexer/scip:scip_proto { kt: 0 }:\n${line}\nINFO: From KotlinCompile //other:proto { kt: 0 }:\n${line}`);
  assert.equal(found.length, 2);
  assert.equal(evaluate(found, [exception], now).unexpected.length, 1);
});

test('catches compiler, repository, dependency and unknown warning formats', () => {
  const result = collectDiagnostics([
    'INFO: Build completed successfully, 12 total actions',
    'Writing scip.Index to bazel-out/bin/generated',
    'DEBUG: x.bzl:1:2: Found duplicate artifact versions',
    'DEBUG: Repo wire indicated that a canonical reproducible form can be obtained by modifying arguments integrity = "sha256-abc"',
    'DEBUG: x.bzl:2:3: WARNING: disabling workers',
    ' ** Please remove the following dependencies: @maven//:unused from //some:target',
    ' ** Please add the following dependencies:',
    '@@//common:common to //some:test',
    'new-tool: warning: unexpected warning',
  ].join('\n'));
  assert.equal(result.length, 6);
  assert.ok(result.some(value => value.includes('@@//common:common to //some:test')));
});

test('fails on every unaccounted diagnostic, including another path with the same warning', () => {
  assert.equal(evaluate([diagnostic], [exception], now).ok, true);
  assert.equal(evaluate([diagnostic.replace('Descriptor.kt', 'Other.kt')], [exception], now).ok, false);
  assert.equal(evaluate(['WARNING: new'], [], now).ok, false);
});

test('requires ownership, issue, impact, next action, acceptance and expiry', () => {
  for (const field of Object.keys(exception)) {
    const invalid = { ...exception };
    delete invalid[field];
    assert.throws(() => evaluate([], [invalid], now), /exception/i);
  }
  assert.throws(() => evaluate([], [{ ...exception, expires: '2026-11-10' }], now), /expired/i);
  assert.throws(() => evaluate([], [{ ...exception, expires: '2026-02-31' }], now), /date/i);
  assert.throws(() => evaluate([], [exception, exception], now), /duplicate/i);
});

test('CLI fails closed for absent, empty, incomplete and newly noisy logs and writes reports', t => {
  const dir = mkdtempSync(join(tmpdir(), 'gvy-diagnostics-test-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const baseline = join(dir, 'baseline.json');
  const log = join(dir, 'build.log');
  const report = join(dir, 'report');
  writeFileSync(baseline, JSON.stringify([]));
  const run = () => spawnSync(process.execPath, ['tools/ci/bazel-diagnostics.mjs', '--baseline', baseline,
    '--report', report, log], { encoding: 'utf8' });
  assert.notEqual(run().status, 0);
  writeFileSync(log, '');
  assert.notEqual(run().status, 0);
  writeFileSync(log, 'INFO: Analyzing targets');
  assert.notEqual(run().status, 0);
  writeFileSync(log, 'WARNING: new\nINFO: Build completed successfully, 1 total action\n');
  assert.notEqual(run().status, 0);
  assert.match(readFileSync(join(report, 'summary.md'), 'utf8'), /WARNING: new/);
  assert.equal(JSON.parse(readFileSync(join(report, 'diagnostics.json'), 'utf8')).unexpected.length, 1);
  writeFileSync(log, 'INFO: Build completed successfully, 1 total action\n');
  assert.equal(run().status, 0);
});

test('a failed build still reports its warnings when the subsequent test log is missing', t => {
  const dir = mkdtempSync(join(tmpdir(), 'gvy-diagnostics-test-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const baseline = join(dir, 'baseline.json');
  const log = join(dir, 'build.log');
  const report = join(dir, 'report');
  const expires = new Date(Date.now() + 30 * 86400000).toISOString().slice(0, 10);
  writeFileSync(baseline, JSON.stringify([{ ...exception, expires }]));
  writeFileSync(log, 'WARNING: newly introduced warning\nERROR: build failed\n');
  const result = spawnSync(process.execPath, ['tools/ci/bazel-diagnostics.mjs', '--baseline', baseline,
    '--report', report, log, join(dir, 'missing-test.log')], { encoding: 'utf8' });
  assert.notEqual(result.status, 0);
  const evidence = JSON.parse(readFileSync(join(report, 'diagnostics.json'), 'utf8'));
  assert.deepEqual(evidence.unexpected, ['WARNING: newly introduced warning']);
  assert.equal(evidence.exceptions.length, 1);
});
