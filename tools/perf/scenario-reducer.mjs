// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import { performance } from 'node:perf_hooks';
import { types } from 'node:util';

const id = value => typeof value === 'string' && /^[A-Za-z0-9_.:-]{1,128}$/.test(value);
const digest = value => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value);
const invalid = () => { throw new Error('INVALID_REDUCTION_INPUT'); };

// The oracle is trusted integration code, but its result never executes accessors
// or exports arbitrary error strings. This cannot sandbox synchronous JavaScript.
function dataRecord(value, allowed) {
  if (!value || typeof value !== 'object' || types.isProxy(value) ||
      ![Object.prototype, null].includes(Object.getPrototypeOf(value))) return null;
  const result = Object.create(null);
  for (const key of Reflect.ownKeys(value)) {
    const property = Object.getOwnPropertyDescriptor(value, key);
    if (!allowed.includes(key) || !Object.hasOwn(property, 'value')) return null;
    result[key] = property.value;
  }
  return result;
}

const signatureKeys = ['checkId', 'expectationSha256', 'failureSha256', 'failureKind', 'origin'];
function signatureValid(value) {
  return id(value.checkId) && digest(value.expectationSha256) && digest(value.failureSha256) &&
    ['assertion', 'transport', 'provider', 'harness'].includes(value.failureKind) &&
    ['product', 'transport', 'provider', 'harness'].includes(value.origin);
}

function outcomeFor(value) {
  const output = dataRecord(value, ['state', 'complete', 'cleanup', ...signatureKeys]);
  if (!output || !['succeeded', 'failed', 'unconfirmed'].includes(output.cleanup)) return null;
  if (output.state === 'failed') return output.complete === true && signatureValid(output) ? output : null;
  if (signatureKeys.some(key => Object.hasOwn(output, key))) return null;
  if (output.state === 'passed' && output.complete === true) return output;
  return output.state === 'incomplete' && output.complete === false ? output : null;
}

function copyUnitIds(value) {
  if (!Array.isArray(value) || types.isProxy(value) || value.length > 512) return null;
  const copy = [];
  let bytes = 0;
  for (let at = 0; at < value.length; at++) {
    const property = Object.getOwnPropertyDescriptor(value, String(at));
    if (!property || !Object.hasOwn(property, 'value') || !id(property.value)) return null;
    bytes += Buffer.byteLength(property.value);
    if (bytes > 32768) return null;
    copy.push(property.value);
  }
  return new Set(copy).size === copy.length ? Object.freeze(copy) : null;
}

function settingsFor(options) {
  const settings = dataRecord(options, ['unitIds', 'target', 'evaluate', 'maxAttempts', 'timeoutMs', 'signal']);
  if (!settings) invalid();
  const { evaluate, signal } = settings;
  const unitIds = copyUnitIds(settings.unitIds);
  const target = dataRecord(settings.target, signatureKeys);
  if (!target || !signatureValid(target) || target.origin !== 'product' || target.failureKind !== 'assertion' ||
      typeof evaluate !== 'function' || !unitIds ||
      (signal !== undefined && !(signal instanceof AbortSignal))) invalid();
  const maxAttempts = settings.maxAttempts ?? 128;
  const timeoutMs = settings.timeoutMs ?? 30000;
  if (!Number.isSafeInteger(maxAttempts) || maxAttempts < 1 || maxAttempts > 1024 ||
      !Number.isSafeInteger(timeoutMs) || timeoutMs < 1 || timeoutMs > 60000) invalid();
  return { unitIds, target: Object.freeze(target), evaluate, signal, maxAttempts, timeoutMs };
}

/**
 * Reduce ordered opaque IDs using the existing replay adapter as a trusted oracle.
 * The adapter must pin artifact, configuration and check definitions across ALL
 * attempts; mandatory prerequisites/checks stay outside removable units. Only an
 * exact complete product assertion signature with confirmed cleanup is accepted.
 * A final replay is required within the same budget. Signatures preserve symptoms,
 * not root causes. This helper performs no IO and emits no verification receipt.
 * Async waiting is bounded; synchronous oracle code cannot be preempted here.
 */
export async function reduceScenarioUnits(options) {
  const { unitIds, target, evaluate, signal, maxAttempts, timeoutMs } = settingsFor(options);
  const deadline = performance.now() + timeoutMs;
  let best = unitIds;
  let attempts = 0;
  let cleanup = 'not_started';
  const rejected = { passed: 0, differentFailure: 0 };
  const sameFailure = outcome => outcome.state === 'failed' &&
    signatureKeys.every(key => outcome[key] === target[key]);
  const finish = (status, confirmed = false) => Object.freeze({
    schemaVersion: 1, status, confirmed, target, unitIds: best, attempts, cleanup,
    removedUnits: unitIds.length - best.length, rejected: Object.freeze({ ...rejected }),
  });
  const stopped = () => signal?.aborted ? 'cancelled' : performance.now() >= deadline ? 'time_limit' : null;

  async function attempt(candidate) {
    const stop = stopped();
    if (stop) return { stop };
    if (attempts === maxAttempts) return { stop: 'attempt_limit' };
    const controller = new AbortController();
    let interrupt;
    const interrupted = new Promise(resolve => { interrupt = reason => { controller.abort(); resolve({ stop: reason }); }; });
    const onAbort = () => interrupt('cancelled');
    signal?.addEventListener('abort', onAbort, { once: true });
    const timer = setTimeout(() => interrupt('time_limit'), Math.max(1, Math.ceil(deadline - performance.now())));
    try {
      const result = await Promise.race([
        interrupted,
        Promise.resolve().then(() => {
          if (controller.signal.aborted || stopped()) throw new Error('REDUCTION_STOPPED');
          attempts++;
          cleanup = 'unconfirmed';
          return evaluate(candidate, controller.signal);
        })
          .then(value => ({ value }), () => ({ stop: 'oracle_error' })),
      ]);
      // Cancellation/deadline wins even when synchronous work returns late.
      const after = stopped();
      if (after || result.stop) {
        controller.abort();
        return { stop: after ?? result.stop };
      }
      const outcome = outcomeFor(result.value);
      const afterValidation = stopped();
      if (afterValidation) { controller.abort(); return { stop: afterValidation }; }
      if (!outcome) return { stop: 'oracle_contract_error' };
      cleanup = outcome.cleanup;
      if (cleanup !== 'succeeded') return { stop: cleanup === 'failed' ? 'cleanup_failed' : 'cleanup_unconfirmed' };
      return { outcome };
    } finally {
      clearTimeout(timer);
      signal?.removeEventListener('abort', onAbort);
    }
  }

  const baseline = await attempt(best);
  if (baseline.stop) return finish(baseline.stop);
  if (baseline.outcome.state === 'incomplete') return finish('oracle_incomplete');
  if (!sameFailure(baseline.outcome)) return finish('baseline_not_reproduced');
  let granularity = 2;
  while (best.length > 0) {
    const chunk = Math.ceil(best.length / granularity);
    let changed = false;
    for (let at = 0; at < best.length; at += chunk) {
      const candidate = Object.freeze(best.filter((_unit, index) => index < at || index >= at + chunk));
      const result = await attempt(candidate);
      if (result.stop) return finish(result.stop);
      if (result.outcome.state === 'incomplete') return finish('oracle_incomplete');
      if (sameFailure(result.outcome)) {
        best = candidate;
        granularity = Math.max(2, granularity - 1);
        changed = true;
        break;
      }
      if (result.outcome.state === 'passed') rejected.passed++;
      else rejected.differentFailure++;
    }
    if (!changed) {
      if (granularity >= best.length) break;
      granularity = Math.min(best.length, granularity * 2);
    }
  }
  const final = await attempt(best);
  if (final.stop) return finish(final.stop);
  if (final.outcome.state === 'incomplete') return finish('oracle_incomplete');
  if (!sameFailure(final.outcome)) return finish('final_not_reproduced');
  return finish(best.length < unitIds.length ? 'reduced' : 'unchanged', true);
}
