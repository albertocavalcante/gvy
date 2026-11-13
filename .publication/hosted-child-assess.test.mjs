// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { assessObservations, assessSeal } from './hosted-child-assess.mjs';
const payload = Buffer.from('{"synthetic":true}');
const nonce = 'a'.repeat(64);
const seal = () => JSON.stringify({ schemaVersion: 1, nonce, payload: 'outcome.json', length: payload.length, sha256: createHash('sha256').update(payload).digest('hex') }) + '\n';
test('synthetic seal rejects wrong nonce, digest, length and shape', () => {
  assessSeal(seal(), nonce, payload);
  for (const bad of [seal().replace(nonce, 'b'.repeat(64)), seal().replace('outcome.json', 'other.json'), seal().replace('"length":18', '"length":19'), seal().replace('"sha256":"', '"sha256":"f')]) assert.throws(() => assessSeal(bad, nonce, payload));
  assert.throws(() => assessSeal('x'.repeat(513), nonce, payload));
});
const observation = (mode = 'green') => ({ planned: ['declaration-start-line', 'declaration-start-character'], observed: [{ id: 'declaration-start-line', status: 'passed' }, { id: 'declaration-start-character', status: mode === 'green' ? 'passed' : 'failed', comparison: { status: 'observed', kind: 'EQUALS', path: '$.items[0].range.start.character', pathExists: true, truncated: false, expected: mode === 'green' ? '4' : '5', actual: '4' } }], completion: { status: mode === 'green' ? 'completed' : 'failed' } });
test('synthetic observation oracle rejects duplicate, missing, unexpected and wrong values', () => {
  assessObservations(observation(), 'green'); assessObservations(observation('wrong-location'), 'wrong-location');
  const duplicate = observation(); duplicate.observed[1].id = duplicate.observed[0].id; assert.throws(() => assessObservations(duplicate, 'green'));
  const missing = observation(); missing.observed.pop(); assert.throws(() => assessObservations(missing, 'green'));
  const extra = observation(); extra.observed.push({ id: 'unexpected' }); assert.throws(() => assessObservations(extra, 'green'));
  const wrong = observation('wrong-location'); wrong.observed[1].comparison.actual = '7'; assert.throws(() => assessObservations(wrong, 'wrong-location'));
  const blocked = observation(); blocked.observed[0].status = 'failed'; assert.throws(() => assessObservations(blocked, 'green'));
});
