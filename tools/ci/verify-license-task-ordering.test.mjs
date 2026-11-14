// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import test from 'node:test';
import { verifyCacheLog, verifyInvocation } from './verify-license-task-ordering.mjs';

const producer = 'license-ordering-missing-order';
const control = { name: 'missing-order', expected: 'LICENSE_ORDERING_MISSING' };
const log = 'LICENSE_ORDERING_MISSING\nBUILD FAILED in 1s\n';
const hash = text => createHash('sha256').update(text).digest('hex');
function originalFailure() {
  return { schemaVersion: 1, producer, complete: true, captureExitCode: 0, commandExitCode: 1,
    errors: ['Gradle command failed with exit 1'], unexpected: [], ok: false,
    log: { bytes: Buffer.byteLength(log), sha256: hash(log) } };
}

test('an intended RED retains the canonical original-exit failure rather than requiring empty errors', () => {
  assert.doesNotThrow(() => verifyInvocation(originalFailure(), 1, log, control, producer));
});

for (const [name, change] of [
  ['missing original error', value => { value.errors = []; }],
  ['another error', value => { value.errors.push('Other failure'); }],
  ['different original error exit', value => { value.errors = ['Gradle command failed with exit 2']; }],
  ['capture failure', value => { value.captureExitCode = 1; }],
  ['incomplete capture', value => { value.complete = false; }],
  ['unexpected warning', value => { value.unexpected = ['new warning']; }],
  ['successful producer verdict', value => { value.ok = true; }],
  ['wrong producer', value => { value.producer = 'other'; }],
  ['wrong log hash', value => { value.log.sha256 = 'a'.repeat(64); }],
  ['wrong log bytes', value => { value.log.bytes++; }],
]) {
  test(`an intended RED rejects ${name}`, () => {
    const summary = originalFailure();
    change(summary);
    assert.throws(() => verifyInvocation(summary, 1, log, control, producer));
  });
}

test('an intended RED needs its exact witness and a matching actual process exit', () => {
  assert.throws(() => verifyInvocation(originalFailure(), 2, log, control, producer));
  assert.throws(() => verifyInvocation(originalFailure(), 0, log, control, producer));
  assert.throws(() => verifyInvocation(originalFailure(), 1, log, { ...control, expected: 'OTHER_WITNESS' }, producer));
});

for (const exit of [undefined, null, '1', false, NaN, -1, 0, 256]) {
  test(`an intended RED rejects malformed original exit ${String(exit)}`, () => {
    const summary = originalFailure();
    summary.commandExitCode = exit;
    summary.errors = [`Gradle command failed with exit ${String(exit)}`];
    assert.throws(() => verifyInvocation(summary, exit, log, control, producer));
  });
}

test('GREEN requires actual original exit zero, empty errors and successful complete capture', () => {
  const greenLog = 'BUILD SUCCESSFUL in 1s\n';
  const summary = { ...originalFailure(), commandExitCode: 0, errors: [], ok: true,
    log: { bytes: Buffer.byteLength(greenLog), sha256: hash(greenLog) } };
  const success = { name: 'ordered' };
  assert.doesNotThrow(() => verifyInvocation(summary, 0, greenLog, success, producer));
  assert.throws(() => verifyInvocation({ ...summary, errors: ['Other failure'] }, 0, greenLog, success, producer));
  assert.throws(() => verifyInvocation({ ...summary, commandExitCode: 1 }, 0, greenLog, success, producer));
  assert.throws(() => verifyInvocation({ ...summary, captureExitCode: 1 }, 0, greenLog, success, producer));
  assert.throws(() => verifyInvocation({ ...summary, ok: false }, 0, greenLog, success, producer));
});

const cacheMarkers = 'LICENSE_CACHE_LICENSE\nLICENSE_CACHE_PRODUCER\nLICENSE_CACHE_CONSUMER\n';
const stored = `${cacheMarkers}Configuration cache entry stored.\n`;
const reused = `Reusing configuration cache.\n${cacheMarkers}`;

test('cache fixture requires a real store then reuse with ordered task actions', () => {
  assert.doesNotThrow(() => verifyCacheLog(stored, 'store'));
  assert.doesNotThrow(() => verifyCacheLog(reused, 'reuse'));
  for (const bad of [
    stored,
    reused.replace('Reusing configuration cache.', 'Calculating task graph as configuration cache cannot be reused.'),
    reused.replace('LICENSE_CACHE_CONSUMER\n', ''),
    reused.replace('LICENSE_CACHE_LICENSE\n', ''),
    reused.replace('LICENSE_CACHE_LICENSE\nLICENSE_CACHE_PRODUCER', 'LICENSE_CACHE_PRODUCER\nLICENSE_CACHE_LICENSE'),
    reused + 'LICENSE_CACHE_LICENSE\n',
    reused + 'Configuration cache entry stored.\n',
  ]) assert.throws(() => verifyCacheLog(bad, 'reuse'));
  assert.throws(() => verifyCacheLog(reused, 'store'));
});
