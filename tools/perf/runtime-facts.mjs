// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

const MAX_BYTES = 16 * 1024;
const invalid = () => { throw new Error('INVALID_RUNTIME_FACTS'); };
const has = (value, key) => Object.hasOwn(value, key);

function fields(value, required, optional = []) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) invalid();
  if (required.some(key => !has(value, key)) || Object.keys(value).some(key => !required.includes(key) && !optional.includes(key))) invalid();
}

function stateFields(value, stateKey, keys, validate) {
  const state = value[stateKey];
  if (!['known', 'unknown', 'malformed'].includes(state)) invalid();
  if (state === 'known') {
    if (keys.some(key => !has(value, key) || value[key] === null)) invalid();
    validate();
  } else if (keys.some(key => has(value, key))) invalid();
}

const positive = value => Number.isSafeInteger(value) && value > 0;
const text = (value, pattern) => typeof value === 'string' && value.length <= 64 && pattern.test(value);

// JSON.parse has already checked grammar. Inspect the original spelling before any rounded number or
// overwritten duplicate can be accepted. Runtime-facts schema 1 has objects, strings and scalar values only.
function validateLexemes(json) {
  let at = 0; let keys = 0;
  const whitespace = () => { while (at < json.length && /[\t\n\r ]/.test(json[at])) at++; };
  const string = () => {
    const start = at++;
    while (at < json.length) {
      if (json[at] === '\\') { at += 2; continue; }
      if (json[at++] === '"') return JSON.parse(json.slice(start, at));
    }
    invalid();
  };
  const value = depth => {
    if (depth > 8) invalid();
    whitespace();
    if (json[at] === '"') { string(); return; }
    if (json[at] === '{') {
      at++; whitespace(); const names = new Set();
      while (json[at] !== '}') {
        if (++keys > 64 || json[at] !== '"') invalid();
        const name = string();
        if (names.has(name)) invalid();
        names.add(name); whitespace();
        if (json[at++] !== ':') invalid();
        value(depth + 1); whitespace();
        if (json[at] !== ',') break;
        at++; whitespace();
      }
      if (json[at++] !== '}') invalid();
      return;
    }
    if (json[at] === '[') invalid();
    const start = at;
    while (at < json.length && !/[,}\]\t\n\r ]/.test(json[at])) at++;
    const token = json.slice(start, at);
    if (token === 'true' || token === 'false' || token === 'null') return;
    if (!/^(?:0|[1-9][0-9]*)$/.test(token) || token.length > 16 || BigInt(token) > BigInt(Number.MAX_SAFE_INTEGER)) invalid();
  };
  value(0); whitespace();
  if (at !== json.length) invalid();
}

/** Validate an observed runtimeFacts response, not the engine used by any particular request. */
export function parseRuntimeFacts(json) {
  try {
    if (typeof json !== 'string' || json.length > MAX_BYTES || Buffer.byteLength(json) > MAX_BYTES) invalid();
    const value = JSON.parse(json);
    validateLexemes(json);
    fields(value, ['schemaVersion', 'runtime', 'build', 'hoverEngine']);
    if (value.schemaVersion !== 1) invalid();
    const runtime = value.runtime;
    fields(runtime, ['processState', 'javaState', 'heapState'], ['processId', 'javaFeature', 'javaVersion', 'maxHeapBytes']);
    stateFields(runtime, 'processState', ['processId'], () => { if (!positive(runtime.processId)) invalid(); });
    stateFields(runtime, 'javaState', ['javaFeature', 'javaVersion'], () => {
      if (!Number.isInteger(runtime.javaFeature) || runtime.javaFeature < 1 || runtime.javaFeature > 999 ||
          !text(runtime.javaVersion, /^[0-9][A-Za-z0-9.+_-]*$/)) invalid();
    });
    stateFields(runtime, 'heapState', ['maxHeapBytes'], () => { if (!positive(runtime.maxHeapBytes)) invalid(); });
    const build = value.build;
    fields(build, ['versionState', 'commitState', 'dirtyState'], ['version', 'commit', 'dirty']);
    stateFields(build, 'versionState', ['version'], () => {
      if (!text(build.version, /^[0-9]+\.[0-9]+\.[0-9]+(?:-SNAPSHOT|-dev\+(?:unknown|g[0-9a-fA-F]{7,64}(?:\.dirty)?))?$/)) invalid();
    });
    stateFields(build, 'commitState', ['commit'], () => {
      if (typeof build.commit !== 'string' || !/^(?:[0-9a-f]{40}|[0-9a-f]{64})$/.test(build.commit)) invalid();
    });
    stateFields(build, 'dirtyState', ['dirty'], () => { if (typeof build.dirty !== 'boolean') invalid(); });
    const hover = value.hoverEngine;
    fields(hover, ['configured', 'state'], ['instantiated']);
    if (!['native', 'core', 'openrewrite'].includes(hover.configured) ||
        !['known', 'unknown', 'not_instantiated'].includes(hover.state)) invalid();
    if (hover.state === 'known' ? !['native', 'core', 'rewrite'].includes(hover.instantiated) : has(hover, 'instantiated')) invalid();
    Object.freeze(runtime); Object.freeze(build); Object.freeze(hover);
    return Object.freeze(value);
  } catch {
    throw new Error('INVALID_RUNTIME_FACTS');
  }
}
