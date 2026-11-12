// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import assert from 'node:assert/strict';
import test from 'node:test';
import { parseRuntimeFacts } from './runtime-facts.mjs';

const facts = () => ({
  schemaVersion: 1,
  runtime: { processState: 'known', processId: 42, javaState: 'known', javaFeature: 21, javaVersion: '21.0.5+11', heapState: 'known', maxHeapBytes: 1073741824 },
  build: { versionState: 'known', version: '0.5.4-dev+gabcdef0', commitState: 'known', commit: 'a'.repeat(40), dirtyState: 'known', dirty: false },
  hoverEngine: { configured: 'core', state: 'known', instantiated: 'core' },
});
const parse = value => parseRuntimeFacts(JSON.stringify(value));

test('accepts and freezes bounded observed facts without asserting request attribution', () => {
  const result = parse(facts());
  assert.equal(result.runtime.javaVersion, '21.0.5+11');
  assert.equal(result.hoverEngine.instantiated, 'core');
  assert.equal(Object.isFrozen(result), true);
  assert.equal(Object.isFrozen(result.hoverEngine), true);
});

test('unknown and malformed states require absent values, never explicit null', () => {
  for (const [group, stateKey, valueKey] of [
    ['runtime', 'processState', 'processId'], ['runtime', 'javaState', 'javaVersion'],
    ['runtime', 'javaState', 'javaFeature'], ['runtime', 'heapState', 'maxHeapBytes'],
    ['build', 'versionState', 'version'], ['build', 'commitState', 'commit'], ['build', 'dirtyState', 'dirty'],
    ['hoverEngine', 'state', 'instantiated'],
  ]) {
    const missing = facts(); delete missing[group][valueKey];
    assert.throws(() => parse(missing), { message: 'INVALID_RUNTIME_FACTS' });
    const explicitNull = facts(); explicitNull[group][valueKey] = null;
    assert.throws(() => parse(explicitNull), { message: 'INVALID_RUNTIME_FACTS' });
    const unknown = facts(); unknown[group][stateKey] = 'unknown';
    if (stateKey === 'javaState') { delete unknown.runtime.javaFeature; delete unknown.runtime.javaVersion; }
    else delete unknown[group][valueKey];
    assert.doesNotThrow(() => parse(unknown));
    unknown[group][valueKey] = null;
    assert.throws(() => parse(unknown), { message: 'INVALID_RUNTIME_FACTS' });
  }
});

test('rejects extra or unsafe fields and never exposes their contents', () => {
  for (const mutate of [
    value => { value.runtime.processId = Number.MAX_SAFE_INTEGER + 1; },
    value => { value.build.commit = 'b'.repeat(39); },
    value => { value.runtime.javaVersion = 'SECRET_CANARY/path'; },
    value => { value.runtime.arguments = 'SECRET_CANARY'; },
    value => { value.hoverEngine.state = 'other'; },
  ]) {
    const value = facts(); mutate(value);
    assert.throws(() => parse(value), error => error.message === 'INVALID_RUNTIME_FACTS' && !String(error).includes('SECRET_CANARY'));
  }
  assert.throws(() => parseRuntimeFacts(' '.repeat(20_000)), { message: 'INVALID_RUNTIME_FACTS' });
});

test('rejects duplicate object keys including alternate escaped spellings', () => {
  const json = JSON.stringify(facts());
  const duplicate = json.replace('"processId":42', '"processId":42,"process\\u0049d":42');
  assert.throws(() => parseRuntimeFacts(duplicate), { message: 'INVALID_RUNTIME_FACTS' });
});

test('rejects rounded or non-integral numeric lexemes', () => {
  const json = JSON.stringify(facts());
  const rounded = json.replace('"processId":42', '"processId":9007199254740991.1');
  const exponential = json.replace('"processId":42', '"processId":4.2e1');
  for (const input of [rounded, exponential]) {
    assert.throws(() => parseRuntimeFacts(input), { message: 'INVALID_RUNTIME_FACTS' });
  }
});

test('accepts the maximum exact integer and rejects the next integer', () => {
  const value = facts(); value.runtime.processId = Number.MAX_SAFE_INTEGER;
  assert.equal(parse(value).runtime.processId, Number.MAX_SAFE_INTEGER);
  const outside = JSON.stringify(value).replace('9007199254740991', '9007199254740992');
  assert.throws(() => parseRuntimeFacts(outside), { message: 'INVALID_RUNTIME_FACTS' });
});

test('bounds version text and rejects extreme nested inputs', () => {
  const value = facts(); value.build.version = '1.2.3-dev+g' + 'a'.repeat(53);
  assert.equal(parse(value).build.version.length, 64);
  value.build.version += 'a';
  assert.throws(() => parse(value), { message: 'INVALID_RUNTIME_FACTS' });
  const nested = JSON.stringify(facts()).replace('"runtime":{', '"runtime":{"extra":' + '['.repeat(2000) + '0' + ']'.repeat(2000) + ',');
  assert.throws(() => parseRuntimeFacts(nested), { message: 'INVALID_RUNTIME_FACTS' });
});
