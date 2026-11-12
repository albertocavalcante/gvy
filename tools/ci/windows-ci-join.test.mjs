// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { checkGradleDiagnostics, collectDiagnostics, loadNativeDiagnosticPolicy, validateNativeDiagnosticPolicy } from './check-gradle-diagnostics.mjs';
import { evaluate } from './bazel-diagnostics.mjs';

const now = new Date('2026-11-12T00:00:00Z');
const fixture = name => readFileSync(new URL(`./fixtures/${name}`, import.meta.url), 'utf8');
const entries = () => JSON.parse(readFileSync(new URL('./gradle-diagnostics.json', import.meta.url)));
const check = (log, exceptions, extra = {}) => checkGradleDiagnostics({ log, exceptions, now,
  rootDir: 'D:/a/gvy/gvy', commandExitCode: 0, captureExitCode: 0, ...extra });

test('Windows historical deprecations preserve provenance and require exactly one new notation identity', () => {
  const log = fixture('gradle-native-windows-x64.txt');
  const provenance = JSON.parse(fixture('gradle-native-windows-provenance.json'));
  assert.equal(provenance.sourceHead, '67ff51dc47c7fdc328d6518dcd45a99e263ac8cd');
  assert.equal(provenance.runId, '43178483058');
  assert.equal(provenance.fixtures[0].jobConclusion, 'failure');
  assert.equal(provenance.fixtures[0].sha256, createHash('sha256').update(log).digest('hex'));
  assert.equal(provenance.fixtures[0].events.length, 13);
  assert.equal(log.match(/has been deprecated/g).length, 13);
  const diagnostics = collectDiagnostics(log, { rootDir: 'D:/a/gvy/gvy' });
  assert.equal(diagnostics.length, 13);
  const windows = entries().filter(entry => entry.diagnostic.includes('windows-x86_64@zip'));
  assert.equal(windows.length, 1);
  assert.equal(evaluate(diagnostics, entries(), now).accepted.length, 13);
  assert.equal(evaluate(diagnostics, entries().filter(entry => !windows.some(value => value.diagnostic === entry.diagnostic)), now).unexpected.length, 1);
  assert.equal(check(log, entries()).complete, false, 'selective historical traces do not invent command completion');
});

test('all Windows Native events retain first-party compiler debt and unproved overwrite diagnostics', () => {
  const log = fixture('native-windows-x64.txt');
  const result = check(log, [...entries(), ...loadNativeDiagnosticPolicy(now)]);
  assert.equal(result.diagnostics.length, 15);
  assert.equal(result.accepted.length, 13);
  assert.equal(result.unexpected.length, 2);
  assert.ok(result.unexpected.some(value => value.includes('WindowsPaths.kt:95:22 Redundant call')));
  assert.ok(result.unexpected.some(value => value.startsWith('[emitter kotlin-native bundle provisioning]')));
  assert.equal(result.complete, false);
  assert.equal(result.ok, false);
});

test('Windows conditional debt remains denied by default with exact owned expiring metadata', () => {
  const policy = loadNativeDiagnosticPolicy(now);
  assert.equal(policy.length, 3);
  const entry = policy.find(value => value.diagnostic.includes('windows-x86_64-2.3.0'));
  assert.equal(entry.requiresProof, 'native-provisioning-v1');
  assert.equal(entry.issue, 'https://github.com/albertocavalcante/gvy/issues/1603');
  assert.equal(entry.owner, 'gvy build and CI and Native maintainers');
  assert.equal(entry.expires, '2026-12-11');
  const warning = 'Kotlin/Native bundle directory C:\\owned\\.konan\\kotlin-native-prebuilt-windows-x86_64-2.3.0 is not empty. Native bundle files will be overwritten.';
  const log = warning + '\nBUILD SUCCESSFUL in 1s\n';
  const result = check(log, [...entries(), ...policy]);
  assert.equal(result.complete, true);
  assert.equal(result.ok, false);
  assert.deepEqual(result.unexpected, [entry.diagnostic]);
  assert.equal(result.accepted.length, 0);
  for (const changed of [policy.slice(0, 2), [...policy, entry], policy.map(value => ({ ...value, requiresProof: undefined })),
    policy.map(value => ({ ...value, unexpectedMetadata: true }))]) assert.throws(() => validateNativeDiagnosticPolicy(changed, now));
  assert.throws(() => validateNativeDiagnosticPolicy(policy, new Date('2026-12-12T00:00:00Z')), /Expired/);
  for (const status of [{ commandExitCode: 7 }, { captureExitCode: 1 }]) assert.equal(check(log, [...entries(), ...policy], status).ok, false);
  assert.equal(check('BUILD SUCCESSFUL in 1s\n', policy.map(value => ({ ...value, requiresProof: 'unknown' }))).ok, false);
});

test('Windows notation exception cannot adopt other versions, platforms, origins or altered messages', () => {
  const log = fixture('gradle-native-windows-x64.txt');
  for (const [from, to] of [['windows-x86_64@zip', 'windows-arm64@zip'], ['2.3.0:windows', '2.3.1:windows'],
    ['KotlinNativeBundleArtifactFormat.kt:79', 'KotlinNativeBundleArtifactFormat.kt:80'],
    ['Please use single-string notation', 'Please use another notation']]) {
    assert.ok(log.includes(from));
    assert.equal(evaluate(collectDiagnostics(log.replaceAll(from, to)), entries(), now).ok, false, from);
  }
});
