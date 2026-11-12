// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict';
import test from 'node:test';
import { provisionWithBudget } from './vscode-provisioning-policy.mjs';

const context = { headSha: 'a'.repeat(40), testedSha: 'b'.repeat(40), runId: '123', runAttempt: 2, jobKey: 'validate', matrix: 'ubuntu-24.04' };

test('one version-resolution timeout can recover before any test assertion is invoked', async () => {
  const calls = [];
  let clock = 0;
  const result = await provisionWithBudget(context, async input => {
    calls.push(input);
    return { number: input.number, outcome: input.number === 1 ? 'version-resolution-timeout' : 'success', cleanupComplete: true };
  }, { now: () => clock, sleep: async ms => { clock += ms; } });
  assert.equal(result.ok, true);
  assert.equal(calls.length, 2);
  assert.deepEqual(result.attempts.map(attempt => attempt.outcome), ['version-resolution-timeout', 'success']);
});
