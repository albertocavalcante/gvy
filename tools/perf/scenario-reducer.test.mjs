// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import assert from 'node:assert/strict';
import test from 'node:test';
import { reduceScenarioUnits } from './scenario-reducer.mjs';

const digest = 'a'.repeat(64);
const target = Object.freeze({
  checkId: 'hover.type', expectationSha256: digest, failureSha256: 'c'.repeat(64), failureKind: 'assertion', origin: 'product',
});
const failed = (overrides = {}) => ({ state: 'failed', complete: true, cleanup: 'succeeded', ...target, ...overrides });
const passed = () => ({ state: 'passed', complete: true, cleanup: 'succeeded' });
const incomplete = () => ({ state: 'incomplete', complete: false, cleanup: 'succeeded' });
const run = (evaluate, options = {}) => reduceScenarioUnits({ unitIds: ['open', 'noise', 'fault'], target, evaluate, ...options });

test('reduces irrelevant units while preserving order and exact named assertion identity', async () => {
  const visited = [];
  const result = await run(async units => {
    visited.push([...units]);
    if (!units.includes('open')) return passed();
    return units.includes('fault') ? failed() : passed();
  });
  assert.equal(result.status, 'reduced');
  assert.deepEqual(result.unitIds, ['open', 'fault']);
  assert.equal(result.removedUnits, 1);
  assert.equal(result.cleanup, 'succeeded');
  assert.deepEqual(visited[0], ['open', 'noise', 'fault']);
  assert.equal(result.confirmed, true);
  assert.deepEqual(visited.at(-1), ['open', 'fault']);
  assert.ok(result.rejected.passed > 0);
});

test('a different check expectation or failure origin cannot replace the target failure', async () => {
  for (const changed of [
    { checkId: 'other' }, { expectationSha256: 'b'.repeat(64) }, { failureSha256: 'b'.repeat(64) },
    { failureKind: 'transport' }, { origin: 'harness' },
  ]) {
    const result = await run(async units => units.length === 3 ? failed() : failed(changed));
    assert.equal(result.status, 'unchanged');
    assert.deepEqual(result.unitIds, ['open', 'noise', 'fault']);
    assert.ok(result.rejected.differentFailure > 0);
  }
});

test('a complete non-target baseline stops without searching for another failure', async () => {
  for (const baseline of [passed(), failed({ checkId: 'other' })]) {
    let calls = 0;
    const result = await run(async () => { calls++; return baseline; });
    assert.equal(result.status, 'baseline_not_reproduced');
    assert.equal(calls, 1);
    assert.equal(result.removedUnits, 0);
  }
});

test('incomplete baseline and final confirmation remain unknown rather than non-reproduced', async () => {
  const baseline = await run(async () => incomplete());
  assert.equal(baseline.status, 'oracle_incomplete');
  assert.equal(baseline.confirmed, false);
  let emptyCalls = 0;
  const final = await run(async units => {
    if (units.length !== 0) return failed();
    return ++emptyCalls === 1 ? failed() : incomplete();
  });
  assert.equal(final.status, 'oracle_incomplete');
  assert.equal(final.confirmed, false);
  assert.equal(emptyCalls, 2);
});

test('attempt ceiling includes the mandatory baseline and returns the best confirmed candidate', async () => {
  let calls = 0;
  const result = await run(async () => { calls++; return failed(); }, { maxAttempts: 2 });
  assert.equal(result.status, 'attempt_limit');
  assert.equal(result.attempts, 2);
  assert.equal(calls, 2);
  assert.deepEqual(result.unitIds, ['fault']);
});

test('failed or unconfirmed cleanup stops all later attempts even when the named failure matches', async () => {
  for (const cleanup of ['failed', 'unconfirmed']) {
    let calls = 0;
    const result = await run(async () => { calls++; return failed({ cleanup }); });
    assert.equal(result.status, cleanup === 'failed' ? 'cleanup_failed' : 'cleanup_unconfirmed');
    assert.equal(result.cleanup, cleanup);
    assert.equal(calls, 1);
    assert.equal(result.removedUnits, 0);
  }
});

test('time budget aborts the pending oracle and late completion cannot change the returned best', async () => {
  let finish;
  let observedSignal;
  const result = await run((_units, signal) => {
    observedSignal = signal;
    return new Promise(resolve => { finish = resolve; });
  }, { timeoutMs: 5 });
  assert.equal(result.status, 'time_limit');
  assert.equal(result.cleanup, 'unconfirmed');
  assert.equal(result.attempts, 1);
  assert.equal(observedSignal.aborted, true);
  finish(failed());
  await Promise.resolve();
  assert.deepEqual(result.unitIds, ['open', 'noise', 'fault']);
  assert.equal(result.removedUnits, 0);
});

test('external cancellation stops waiting and requests oracle cancellation', async () => {
  const controller = new AbortController();
  let observedSignal;
  const promise = run((_units, signal) => {
    observedSignal = signal;
    queueMicrotask(() => controller.abort());
    return new Promise(() => {});
  }, { signal: controller.signal });
  const result = await promise;
  assert.equal(result.status, 'cancelled');
  assert.equal(observedSignal.aborted, true);
  assert.equal(result.cleanup, 'unconfirmed');
});

test('already-cancelled input invokes no oracle and starts no cleanup obligation', async () => {
  const controller = new AbortController();
  controller.abort();
  const result = await run(() => assert.fail('must not run'), { signal: controller.signal });
  assert.equal(result.status, 'cancelled');
  assert.equal(result.attempts, 0);
  assert.equal(result.cleanup, 'not_started');
});

test('oracle exceptions are fixed errors without raw payload disclosure or further attempts', async () => {
  let calls = 0;
  const result = await run(async () => { calls++; throw new Error('private-secret'); });
  assert.equal(result.status, 'oracle_error');
  assert.equal(result.cleanup, 'unconfirmed');
  assert.equal(calls, 1);
  assert.equal(JSON.stringify(result).includes('private-secret'), false);
});

test('untrusted oracle getters and malformed states never execute or become target evidence', async () => {
  let invoked = false;
  for (const output of [
    { ...failed(), complete: false },
    { ...failed(), unexpected: 'private-secret' },
    { ...failed(), get cleanup() { invoked = true; return 'succeeded'; } },
    { state: 'failed', complete: true, cleanup: 'succeeded' },
  ]) {
    const result = await run(async () => output);
    assert.equal(result.status, 'oracle_contract_error');
    assert.equal(result.removedUnits, 0);
  }
  assert.equal(invoked, false);
});

test('invalid budgets duplicate IDs and oversized inputs fail before invoking the oracle', async () => {
  for (const options of [
    { maxAttempts: 0 }, { maxAttempts: 1025 }, { timeoutMs: 0 }, { timeoutMs: 60001 },
    { unitIds: ['same', 'same'] }, { unitIds: Array.from({ length: 513 }, (_, at) => 'u' + at) },
    { unitIds: Array(1) },
    { unitIds: ['x'.repeat(129)] },
    { unitIds: Array.from({ length: 512 }, (_, at) => String(at).padStart(3, '0') + 'x'.repeat(125)) },
    { target: { ...target, expectationSha256: 'bad' } },
  ]) {
    await assert.rejects(run(() => assert.fail('must not run'), options), { message: 'INVALID_REDUCTION_INPUT' });
  }
});

test('unit array accessors are rejected without invoking caller code', async () => {
  let invoked = false;
  const unitIds = [];
  Object.defineProperty(unitIds, 0, { get() { invoked = true; return 'unit'; } });
  await assert.rejects(run(() => assert.fail('must not run'), { unitIds }), { message: 'INVALID_REDUCTION_INPUT' });
  assert.equal(invoked, false);
});

test('an incomplete candidate stops rather than accepting an unrelated smaller run', async () => {
  let calls = 0;
  const result = await run(async () => ++calls === 1 ? failed() : incomplete());
  assert.equal(result.status, 'oracle_incomplete');
  assert.equal(result.confirmed, false);
  assert.equal(calls, 2);
  assert.equal(result.removedUnits, 0);
});

test('a late synchronous oracle result is rejected even before timers can run', async () => {
  const result = await run(() => {
    const end = performance.now() + 12;
    while (performance.now() < end) { /* controlled synchronous stall */ }
    return failed();
  }, { timeoutMs: 5 });
  assert.equal(result.status, 'time_limit');
  assert.equal(result.confirmed, false);
  assert.equal(result.removedUnits, 0);
});

test('the final candidate must reproduce again rather than trusting an earlier acceptance', async () => {
  let emptyCalls = 0;
  const result = await run(async units => {
    if (units.length !== 0) return failed();
    return ++emptyCalls === 1 ? failed() : passed();
  });
  assert.equal(result.status, 'final_not_reproduced');
  assert.equal(result.confirmed, false);
  assert.equal(emptyCalls, 2);
});

test('cancellation wins a simultaneous completed result', async () => {
  const controller = new AbortController();
  const result = await run(() => { controller.abort(); return failed(); }, { signal: controller.signal });
  assert.equal(result.status, 'cancelled');
  assert.equal(result.removedUnits, 0);
  assert.equal(result.confirmed, false);
});

test('cancellation before the queued callback invokes no replay or cleanup obligation', async () => {
  const controller = new AbortController();
  let calls = 0;
  const pending = run(() => { calls++; return failed(); }, { signal: controller.signal });
  controller.abort();
  const result = await pending;
  assert.equal(result.status, 'cancelled');
  assert.equal(calls, 0);
  assert.equal(result.attempts, 0);
  assert.equal(result.cleanup, 'not_started');
});

test('input snapshots and returned decisions are immutable without freezing caller-owned arrays', async () => {
  const unitIds = ['open', 'noise', 'fault'];
  const result = await run(async units => {
    assert.throws(() => units.push('mutation'), TypeError);
    return units.includes('fault') ? failed() : passed();
  }, { unitIds });
  unitIds.push('later');
  assert.deepEqual(result.unitIds, ['fault']);
  assert.throws(() => result.unitIds.push('bad'), TypeError);
  assert.throws(() => { result.rejected.passed = 999; }, TypeError);
});
