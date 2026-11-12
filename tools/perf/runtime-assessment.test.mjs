// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import assert from 'node:assert/strict';
import test from 'node:test';
import { parseRuntimeFacts } from './runtime-facts.mjs';
import { assessObservedRuntime } from './runtime-assessment.mjs';

const observed = () => parseRuntimeFacts(JSON.stringify({ schemaVersion: 1,
  runtime: { processState: 'known', processId: 42, javaState: 'known', javaFeature: 21, javaVersion: '21.0.5+11', heapState: 'known', maxHeapBytes: 1073741824 },
  build: { versionState: 'unknown', commitState: 'unknown', dirtyState: 'unknown' },
  hoverEngine: { configured: 'native', state: 'known', instantiated: 'native' },
}));
const expected = () => ({ processId: { state: 'known', value: 42 }, javaFeature: { state: 'known', value: 21 },
  javaVersion: { state: 'known', value: '21.0.5+11' }, maxHeapBytes: { state: 'known', value: 1073741824 } });

test('independent exact runtime facts verify without inferring an engine', () => {
  const result = assessObservedRuntime(observed(), expected());
  assert.deepEqual(result, { status: 'verified', mismatches: [] });
  assert.ok(Object.isFrozen(result) && Object.isFrozen(result.mismatches));
  assert.equal(Object.hasOwn(result, 'engine'), false);
});

for (const [field, wrong] of [['processId', 43], ['javaFeature', 17], ['javaVersion', '17.0.1'], ['maxHeapBytes', 512]]) {
  test(`wrong independent ${field} is a fixed-field mismatch`, () => {
    const want = expected(); want[field].value = wrong;
    assert.deepEqual(assessObservedRuntime(observed(), want), { status: 'mismatch', mismatches: [field] });
  });
}

test('known mismatch dominates unavailable and missing values never verify', () => {
  const want = expected(); want.processId.value = 43; want.maxHeapBytes = { state: 'unknown' };
  assert.deepEqual(assessObservedRuntime(observed(), want), { status: 'mismatch', mismatches: ['processId'] });
  want.processId.value = 42;
  assert.deepEqual(assessObservedRuntime(observed(), want), { status: 'unavailable', mismatches: [] });
  assert.deepEqual(assessObservedRuntime(null, want), { status: 'unavailable', mismatches: [] });
});

test('untrusted getters proxies and malformed inputs fail closed without invocation', () => {
  let calls = 0; const want = expected(); Object.defineProperty(want.processId, 'value', { get() { calls++; return 42; } });
  assert.equal(assessObservedRuntime(observed(), want).status, 'unavailable');
  assert.equal(assessObservedRuntime(new Proxy(observed(), { get() { calls++; return null; } }), expected()).status, 'unavailable');
  assert.equal(assessObservedRuntime(observed(), new Proxy(expected(), { ownKeys() { calls++; return []; } })).status, 'unavailable');
  assert.equal(assessObservedRuntime(observed(), { ...expected(), maxHeapBytes: { state: 'known', value: 0 } }).status, 'unavailable');
  assert.equal(calls, 0);
});


test('unknown observed fields stay unavailable without hiding a known mismatch', () => {
  const wire = JSON.parse(JSON.stringify(observed()));
  wire.runtime.heapState = 'unknown'; delete wire.runtime.maxHeapBytes;
  const facts = parseRuntimeFacts(JSON.stringify(wire)); const want = expected();
  assert.equal(assessObservedRuntime(facts, want).status, 'unavailable');
  want.processId.value = 43;
  assert.deepEqual(assessObservedRuntime(facts, want), { status: 'mismatch', mismatches: ['processId'] });
});

test('legacy and malformed responses cannot be mistaken for observed facts', () => {
  for (const wire of ['null', '{"error":{"code":-32601}}', '{"schemaVersion":1}']) {
    assert.throws(() => parseRuntimeFacts(wire), { message: 'INVALID_RUNTIME_FACTS' });
    assert.deepEqual(assessObservedRuntime(null, expected()), { status: 'unavailable', mismatches: [] });
  }
});
