// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import { createHash } from 'node:crypto';
import { canonicalizeContext, verifyRawArtifact } from './context.mjs';

const MAX_RAW_BYTES = 16 * 1024 * 1024;
const MAX_PLAN_BYTES = 32 * 1024;
const MAX_METRIC = 1e15;
const MAX_SAMPLES = 65536;
const PERCENTILES = ['0.0', '50.0', '90.0', '95.0', '99.0', '99.9', '99.99', '99.999', '99.9999', '100.0'];
const fail = () => { throw new Error('INVALID_JMH_RAW'); };
const integer = (value, low, high) => {
  if (!Number.isSafeInteger(value) || value < low || value > high) fail();
};
const metric = (value, low = Number.MIN_VALUE) => {
  if (typeof value !== 'number' || !Number.isFinite(value) || value < low || value > MAX_METRIC) fail();
};
function record(value, fields) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) fail();
  const keys = Object.keys(value);
  if (keys.length !== fields.length || keys.some(key => !fields.includes(key))) fail();
}
function list(value, low, high) {
  if (!Array.isArray(value) || value.length < low || value.length > high) fail();
}

// Bound nesting and token count before JSON.parse allocates the full value. Only
// strings followed by ':' are keys; standard JSON.parse still owns JSON grammar.
// Decoding key literals also catches escaped aliases such as "m\u006fde".
function scanJson(text) {
  const stack = [];
  let tokens = 0;
  for (let index = 0; index < text.length; index++) {
    const char = text[index];
    if (char === '"') {
      let end = index + 1;
      while (end < text.length && text[end] !== '"') end += text[end] === '\\' ? 2 : 1;
      if (end >= text.length) fail();
      let next = end + 1;
      while (/\s/.test(text[next] ?? '') && next < text.length) next++;
      if (text[next] === ':') {
        const frame = stack.at(-1);
        if (frame?.kind !== '{') fail();
        const key = JSON.parse(text.slice(index, end + 1));
        if (frame.keys.has(key) || frame.keys.size >= 32) fail();
        frame.keys.add(key);
      }
      index = end;
      tokens++;
    } else if (char === '{' || char === '[') {
      stack.push({ kind: char, keys: new Set() });
      if (stack.length > 12) fail();
      tokens++;
    } else if (char === '}' || char === ']') {
      if (stack.pop()?.kind !== (char === '}' ? '{' : '[')) fail();
      tokens++;
    } else if (char === ',' || char === ':') tokens++;
    if (tokens > 262144) fail();
  }
  if (stack.length) fail();
}
function decode(bytes, limit) {
  if (!Buffer.isBuffer(bytes) || !bytes.length || bytes.length > limit) fail();
  const text = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(bytes);
  scanJson(text);
  return JSON.parse(text);
}
function params(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value) || Object.keys(value).length > 32) fail();
  for (const [key, item] of Object.entries(value)) {
    if (!/^[A-Za-z_$][A-Za-z0-9_$]{0,63}$/.test(key) || typeof item !== 'string' ||
        !/^[A-Za-z0-9][A-Za-z0-9._+-]{0,127}$/.test(item)) fail();
  }
}
const caseKey = value => JSON.stringify([value.benchmark, Object.entries(value.params).sort(([a], [b]) => a < b ? -1 : a > b ? 1 : 0)]);
function duration(value) {
  if (typeof value !== 'string') fail();
  const match = /^([1-9][0-9]{0,6}) (ms|s)$/.exec(value);
  if (!match || Number(match[1]) * (match[2] === 's' ? 1000 : 1) > 3600000) fail();
}
function validatePlan(plan) {
  record(plan, ['schemaVersion', 'profile', 'forks', 'warmupIterations', 'warmupTime', 'measurementIterations', 'measurementTime', 'cases']);
  if (plan.schemaVersion !== 1 || plan.profile !== 'jmh-1.37-avgt-ns-op-v1') fail();
  integer(plan.forks, 1, 32); integer(plan.warmupIterations, 0, 100); integer(plan.measurementIterations, 1, 100);
  duration(plan.warmupTime); duration(plan.measurementTime); list(plan.cases, 1, 32);
  if (plan.cases.length * plan.forks * plan.measurementIterations > MAX_SAMPLES) fail();
  const keys = new Map();
  plan.cases.forEach((item, index) => {
    record(item, ['benchmark', 'params']);
    if (typeof item.benchmark !== 'string' || item.benchmark.length > 256 ||
        !/^[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)+$/.test(item.benchmark)) fail();
    params(item.params);
    const key = caseKey(item);
    if (keys.has(key)) fail();
    keys.set(key, index);
  });
  return keys;
}

function privateString(value) {
  if (typeof value !== 'string' || !value.length || Buffer.byteLength(value) > 4096 || /[\u0000-\u001f\u007f]/.test(value)) fail();
  for (const char of value) {
    const scalar = char.codePointAt(0);
    if (scalar >= 0xd800 && scalar <= 0xdfff) fail();
  }
}
function version(value, runtime, requireBuild) {
  privateString(value);
  const match = /^([1-9][0-9]{0,5})(?:\.([0-9]{1,6}))?(?:\.([0-9]{1,6}))?(?:\.([0-9]{1,6}))?(-ea)?(?:\+([0-9]{1,6})(?:-[A-Za-z0-9][A-Za-z0-9._-]{0,63})?)?$/.exec(value);
  if (!match || runtime.version[0] < 17 || Boolean(match[5]) !== runtime.earlyAccess) fail();
  for (let index = 0; index < 4; index++) if (Number(match[index + 1] ?? 0) !== runtime.version[index]) fail();
  if (requireBuild && match[6] === undefined) fail();
  if (match[6] !== undefined && Number(match[6]) !== runtime.version[4]) fail();
}
function heapBytes(value) {
  const match = /^([1-9][0-9]{0,14})([kKmMgG]?)$/.exec(value);
  if (!match) fail();
  const result = Number(match[1]) * (1024 ** ({ k: 1, m: 2, g: 3 }[match[2].toLowerCase()] ?? 0));
  integer(result, 1, Number.MAX_SAFE_INTEGER);
  return result;
}
function metadata(row, runtime) {
  privateString(row.jvm); privateString(row.vmName);
  if (runtime.vm !== 'hotspot' || !['OpenJDK 64-Bit Server VM', 'Java HotSpot(TM) 64-Bit Server VM'].includes(row.vmName)) fail();
  version(row.jdkVersion, runtime, false); version(row.vmVersion, runtime, true);
  list(row.jvmArgs, 0, 128);
  const seen = new Set();
  const once = key => { if (seen.has(key)) fail(); seen.add(key); };
  const setting = (key, observed) => {
    once(key);
    if (runtime.options[key] !== 'unknown' && runtime.options[key] !== observed) fail();
  };
  const collectors = { UseG1GC: 'g1', UseSerialGC: 'serial', UseParallelGC: 'parallel', UseZGC: 'zgc', UseShenandoahGC: 'shenandoah' };
  for (const argument of row.jvmArgs) {
    privateString(argument);
    let match;
    if ((match = /^-Xm(s|x)(.+)$/.exec(argument))) {
      const key = match[1] === 's' ? 'heapMinBytes' : 'heapMaxBytes';
      once(key);
      if (heapBytes(match[2]) !== runtime[key]) fail();
    } else if ((match = /^-XX:\+(UseG1GC|UseSerialGC|UseParallelGC|UseZGC|UseShenandoahGC)$/.exec(argument))) {
      once('gc');
      if (collectors[match[1]] !== runtime.gc) fail();
    } else if ((match = /^-XX:([+-])(TieredCompilation|BackgroundCompilation)$/.exec(argument))) {
      const key = match[2] === 'TieredCompilation' ? 'tieredCompilation' : 'backgroundCompilation';
      setting(key, match[1] === '+' ? 'on' : 'off');
    } else if ((match = /^-Xshare:(auto|off)$/.exec(argument))) {
      setting('cds', match[1] === 'auto' ? 'default' : 'off');
    } else fail();
  }
}
function primaryMetric(value, plan) {
  record(value, ['score', 'scoreError', 'scoreConfidence', 'scorePercentiles', 'scoreUnit', 'rawData']);
  if (value.scoreUnit !== 'ns/op') fail();
  metric(value.score); list(value.scoreConfidence, 2, 2);
  if (value.scoreError === 'NaN') {
    if (plan.forks * plan.measurementIterations >= 3 || value.scoreConfidence.some(item => item !== 'NaN')) fail();
  } else {
    metric(value.scoreError, 0);
    value.scoreConfidence.forEach(item => metric(item, -MAX_METRIC));
    if (value.scoreConfidence[0] > value.scoreConfidence[1]) fail();
  }
  record(value.scorePercentiles, PERCENTILES);
  Object.values(value.scorePercentiles).forEach(item => metric(item));
  list(value.rawData, plan.forks, plan.forks);
  return value.rawData.map(group => {
    list(group, plan.measurementIterations, plan.measurementIterations);
    return group.map(item => { metric(item); return item; });
  });
}

/** Validate one supported raw structure, not execution, quality, warmup adequacy or comparability. */
export function readJmhRaw(contextBytes, bindingBytes, rawBytes, planBytes) {
  try {
    // Binding verification deliberately precedes interpreting producer-controlled results.
    verifyRawArtifact(contextBytes, bindingBytes, rawBytes);
    const context = decode(canonicalizeContext(contextBytes), MAX_PLAN_BYTES);
    if (decode(bindingBytes, 1024).kind !== 'jmh-json' || context.runtime.kind !== 'jvm' || context.instrumentation !== 'none' ||
        context.measurement.lane !== 'P') fail();
    const plan = decode(planBytes, MAX_PLAN_BYTES);
    const expected = validatePlan(plan);
    // This profile defines scenario identity as the exact validated public plan bytes.
    if (createHash('sha256').update(planBytes).digest('hex') !== context.workload.scenarioSha256) fail();
    const raw = decode(rawBytes, MAX_RAW_BYTES);
    list(raw, plan.cases.length, plan.cases.length);
    const cases = new Array(plan.cases.length);
    for (const row of raw) {
      const hasParams = row && Object.hasOwn(row, 'params');
      record(row, ['jmhVersion', 'benchmark', 'mode', 'threads', 'forks', 'jvm', 'jvmArgs', 'jdkVersion', 'vmName', 'vmVersion',
        'warmupIterations', 'warmupTime', 'warmupBatchSize', 'measurementIterations', 'measurementTime', 'measurementBatchSize',
        'primaryMetric', 'secondaryMetrics', ...(hasParams ? ['params'] : [])]);
      if (row.jmhVersion !== '1.37' || row.mode !== 'avgt' || row.threads !== 1 || row.warmupBatchSize !== 1 || row.measurementBatchSize !== 1) fail();
      for (const key of ['forks', 'warmupIterations', 'warmupTime', 'measurementIterations', 'measurementTime']) if (row[key] !== plan[key]) fail();
      const publicParams = hasParams ? row.params : {};
      params(publicParams);
      if (hasParams && !Object.keys(publicParams).length) fail();
      const caseIndex = expected.get(caseKey({ benchmark: row.benchmark, params: publicParams }));
      if (caseIndex === undefined || cases[caseIndex] !== undefined) fail();
      record(row.secondaryMetrics, []);
      metadata(row, context.runtime);
      cases[caseIndex] = { caseIndex, forkGroups: primaryMetric(row.primaryMetric, plan) };
    }
    return { schemaVersion: 1, scope: 'jmh-raw-structural-only', cases };
  } catch { fail(); }
}
