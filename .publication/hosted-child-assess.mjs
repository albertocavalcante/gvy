// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';

export function assessSeal(sealText, nonce, payload) {
  assert.equal(typeof sealText, 'string');
  assert.ok(Buffer.byteLength(sealText) <= 512 && Buffer.isBuffer(payload) && payload.length <= 4 * 1024 * 1024);
  const seal = JSON.parse(sealText);
  assert.deepEqual(Object.keys(seal), ['schemaVersion', 'nonce', 'payload', 'length', 'sha256']);
  assert.equal(JSON.stringify(seal) + '\n', sealText);
  assert.match(nonce, /^[a-f0-9]{64}$/);
  assert.equal(seal.schemaVersion, 1);
  assert.equal(seal.nonce, nonce);
  assert.equal(seal.payload, 'outcome.json');
  assert.equal(seal.length, payload.length);
  assert.equal(seal.sha256, createHash('sha256').update(payload).digest('hex'));
}

export function assessObservations(outcome, mode) {
  assert.ok(['green', 'wrong-location'].includes(mode));
  assert.deepEqual(outcome.planned, ['declaration-start-line', 'declaration-start-character']);
  assert.equal(outcome.observed.length, 2);
  assert.deepEqual(outcome.observed.map(item => item.id), outcome.planned);
  assert.equal(new Set(outcome.observed.map(item => item.id)).size, 2);
  assert.equal(outcome.completion.status, mode === 'green' ? 'completed' : 'failed');
  const checks = new Map(outcome.observed.map(item => [item.id, item]));
  assert.equal(checks.get('declaration-start-line')?.status, 'passed');
  const character = checks.get('declaration-start-character');
  assert.equal(character?.status, mode === 'green' ? 'passed' : 'failed');
  assert.equal(character.comparison?.status, 'observed');
  assert.equal(character.comparison.kind, 'EQUALS');
  assert.equal(character.comparison.path, '$.items[0].range.start.character');
  assert.equal(character.comparison.pathExists, true);
  assert.equal(character.comparison.truncated, false);
  assert.equal(JSON.parse(character.comparison.expected), mode === 'green' ? 4 : 5);
  assert.equal(JSON.parse(character.comparison.actual), 4);
}
