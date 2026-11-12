// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import { types } from 'node:util';

const fields = ['processId', 'javaFeature', 'javaVersion', 'maxHeapBytes'];
const unavailable = () => result('unavailable', []);
const result = (status, mismatches) => Object.freeze({ status, mismatches: Object.freeze(mismatches) });

// Inspect only own data properties; never invoke user-supplied getters or proxy traps.
function record(value, required, optional = []) {
  if (value === null || typeof value !== 'object' || types.isProxy(value) || Array.isArray(value) ||
      ![Object.prototype, null].includes(Object.getPrototypeOf(value))) throw new Error();
  const keys = Reflect.ownKeys(value);
  if (keys.some(key => typeof key !== 'string' || !required.includes(key) && !optional.includes(key)) ||
      required.some(key => !keys.includes(key))) throw new Error();
  const copy = Object.create(null);
  for (const key of keys) {
    const property = Object.getOwnPropertyDescriptor(value, key);
    if (!property || !Object.hasOwn(property, 'value')) throw new Error();
    copy[key] = property.value;
  }
  return copy;
}

function valid(field, value) {
  if (field === 'javaVersion') return typeof value === 'string' && value.length <= 64 && /^[0-9][A-Za-z0-9.+_-]*$/.test(value);
  return Number.isSafeInteger(value) && value > 0 && (field !== 'javaFeature' || value <= 999);
}

function expectedField(field, input) {
  const entry = record(input, ['state'], ['value']);
  if (entry.state === 'unknown' && !Object.hasOwn(entry, 'value')) return null;
  if (entry.state !== 'known' || !Object.hasOwn(entry, 'value') || !valid(field, entry.value)) throw new Error();
  return entry.value;
}

/**
 * Compare a parseRuntimeFacts result with independently captured server-child expectations.
 * A caller maps unsupported, legacy or malformed probe results to null. This point-in-time
 * comparison cannot establish transport provenance or attribute an engine to a request.
 */
export function assessObservedRuntime(facts, independentExpected) {
  try {
    if (facts === null || independentExpected === null) return unavailable();
    const top = record(facts, ['schemaVersion', 'runtime', 'build', 'hoverEngine']);
    if (top.schemaVersion !== 1) return unavailable();
    const runtime = record(top.runtime, ['processState', 'javaState', 'heapState'], fields);
    const expected = record(independentExpected, fields);
    const observed = Object.create(null);
    for (const [stateKey, keys] of [['processState', ['processId']], ['javaState', ['javaFeature', 'javaVersion']], ['heapState', ['maxHeapBytes']]]) {
      const state = runtime[stateKey];
      if (!['known', 'unknown', 'malformed'].includes(state)) throw new Error();
      for (const key of keys) {
        if (state === 'known') {
          if (!Object.hasOwn(runtime, key) || !valid(key, runtime[key])) throw new Error();
          observed[key] = runtime[key];
        } else {
          if (Object.hasOwn(runtime, key)) throw new Error();
          observed[key] = null;
        }
      }
    }
    const mismatch = []; let incomplete = false;
    for (const field of fields) {
      const want = expectedField(field, expected[field]);
      if (want === null || observed[field] === null) incomplete = true;
      else if (want !== observed[field]) mismatch.push(field);
    }
    return mismatch.length ? result('mismatch', mismatch) : incomplete ? unavailable() : result('verified', []);
  } catch {
    return unavailable();
  }
}
