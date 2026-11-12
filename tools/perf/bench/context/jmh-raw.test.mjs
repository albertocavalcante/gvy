// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import test from 'node:test';
import { bindRawArtifact } from './context.mjs';
import { readJmhRaw } from './jmh-raw.mjs';

const bytes = value => Buffer.from(JSON.stringify(value));
const hash = value => createHash('sha256').update(value).digest('hex');
const digest = 'a'.repeat(64);
// Independent literal follows the pinned 1.37 JSONResultFormat field names and nesting.
// It is a format control, not an observed JVM run or performance result.
const rawLiteral = `[
  {
    "jmhVersion": "1.37",
    "benchmark": "org.example.ParserBench.convert",
    "mode": "avgt",
    "threads": 1,
    "forks": 2,
    "jvm": "/private/canary-private-token/bin/java",
    "jvmArgs": ["-Xms256m", "-Xmx1g", "-XX:+UseG1GC", "-XX:+TieredCompilation", "-XX:+BackgroundCompilation", "-Xshare:auto"],
    "jdkVersion": "17.0.17",
    "vmName": "OpenJDK 64-Bit Server VM",
    "vmVersion": "17.0.17+10-LTS",
    "warmupIterations": 2,
    "warmupTime": "1 s",
    "warmupBatchSize": 1,
    "measurementIterations": 2,
    "measurementTime": "100 ms",
    "measurementBatchSize": 1,
    "params": {"fixture": "tiny"},
    "primaryMetric": {
      "score": 15.5,
      "scoreError": 2.5,
      "scoreConfidence": [13.0, 18.0],
      "scorePercentiles": {"0.0": 10.0, "50.0": 15.5, "90.0": 21.0, "95.0": 21.0, "99.0": 21.0, "99.9": 21.0, "99.99": 21.0, "99.999": 21.0, "99.9999": 21.0, "100.0": 21.0},
      "scoreUnit": "ns/op",
      "rawData": [[10.0, 11.0], [20.0, 21.0]]
    },
    "secondaryMetrics": {}
  }
]`;
const rawValue = () => JSON.parse(rawLiteral);
function context(expectedPlan = plan()) {
  return {
    schemaVersion: 1, scope: 'benchmark-context-declaration', implementation: 'gvy-facade',
    source: { commit: 'b'.repeat(40), dirty: false }, artifact: { kind: 'jar', sha256: digest },
    runtime: {
      kind: 'jvm', vendor: 'temurin', vm: 'hotspot', version: [17, 0, 17, 0, 10], earlyAccess: false,
      distributionSha256: digest, launcherSha256: digest, vmSha256: digest,
      heapMinBytes: 268435456, heapMaxBytes: 1073741824, gc: 'g1',
      options: { tieredCompilation: 'on', backgroundCompilation: 'on', cds: 'default', otherOptions: 'none' },
      cacheSha256: null,
    },
    grammar: null,
    workload: { corpusSha256: digest, scenarioSha256: hash(bytes(expectedPlan)), classpathSha256: digest, oracleSha256: digest },
    measurement: { lane: 'P', boundary: 'parse', compilePhase: 'conversion' },
    state: { process: 'warm', jit: 'unknown', structure: 'absent', resultCache: 'off', osPages: 'unknown' },
    host: { os: 'linux', arch: 'x64', cpuModelSha256: digest, powerMode: 'ac', thermal: 'unknown', contention: 'unknown' },
    instrumentation: 'none',
  };
}
function plan() {
  return {
    schemaVersion: 1, profile: 'jmh-1.37-avgt-ns-op-v1', forks: 2,
    warmupIterations: 2, warmupTime: '1 s', measurementIterations: 2, measurementTime: '100 ms',
    cases: [{ benchmark: 'org.example.ParserBench.convert', params: { fixture: 'tiny' } }],
  };
}
function read(raw = rawValue(), declaration = context(), expected = plan()) {
  const content = Buffer.isBuffer(raw) ? raw : bytes(raw);
  const ctx = bytes(declaration);
  return readJmhRaw(ctx, bindRawArtifact(ctx, 'jmh-json', content), content, bytes(expected));
}
const rejected = fn => assert.throws(fn, error => {
  assert.equal(error.message, 'INVALID_JMH_RAW');
  assert.equal(error.cause, undefined);
  assert.ok(!String(error.stack).includes('canary-private-token'));
  return true;
});
function mutateRow(mutate) { const raw = rawValue(); mutate(raw[0]); return raw; }

test('reads the literal JMH 1.37 shape and preserves fork groups without summary statistics', () => {
  assert.deepEqual(read(Buffer.from(rawLiteral)), {
    schemaVersion: 1, scope: 'jmh-raw-structural-only',
    cases: [{ caseIndex: 0, forkGroups: [[10, 11], [20, 21]] }],
  });
});

test('matches exact public parameter combinations in plan order, independent of raw row order', () => {
  const raw = rawValue();
  const other = structuredClone(raw[0]); other.params.fixture = 'larger'; other.primaryMetric.rawData = [[30, 31], [40, 41]];
  const expected = plan(); expected.cases.push({ benchmark: other.benchmark, params: { fixture: 'larger' } });
  assert.deepEqual(read([other, raw[0]], context(expected), expected).cases, [
    { caseIndex: 0, forkGroups: [[10, 11], [20, 21]] },
    { caseIndex: 1, forkGroups: [[30, 31], [40, 41]] },
  ]);
});

test('accepts the writer omission of params for a case without public parameters', () => {
  const raw = mutateRow(row => { delete row.params; });
  const expected = plan(); expected.cases[0].params = {};
  assert.equal(read(raw, context(expected), expected).cases.length, 1);
});

test('one-iteration zero-warmup wiring smoke accepts legitimate NaN summary uncertainty only', () => {
  const raw = mutateRow(row => {
    row.forks = 1; row.warmupIterations = 0; row.measurementIterations = 1;
    row.primaryMetric.rawData = [[10]]; row.primaryMetric.score = 10;
    row.primaryMetric.scoreError = 'NaN'; row.primaryMetric.scoreConfidence = ['NaN', 'NaN'];
    for (const key of Object.keys(row.primaryMetric.scorePercentiles)) row.primaryMetric.scorePercentiles[key] = 10;
  });
  const expected = plan(); expected.forks = 1; expected.warmupIterations = 0; expected.measurementIterations = 1;
  const observed = read(raw, context(expected), expected);
  assert.deepEqual(observed.cases[0].forkGroups, [[10]]);
  assert.equal(observed.scope, 'jmh-raw-structural-only');
  assert.equal('hotJvm' in observed, false);
});

test('verifies raw byte binding before accepting a parseable substituted result or context', () => {
  const ctx = bytes(context()); const raw = Buffer.from(rawLiteral);
  const binding = bindRawArtifact(ctx, 'jmh-json', raw);
  const changedContext = context(); changedContext.source.commit = 'c'.repeat(40);
  for (const [declaration, record, content] of [
    [ctx, binding, Buffer.from(rawLiteral.replace('10.0, 11.0', '10.0, 12.0'))],
    [bytes(changedContext), binding, raw], [ctx, binding, raw.subarray(1)],
    [ctx, Buffer.from('{"kind":"canary-private-token"}'), raw],
  ]) rejected(() => readJmhRaw(declaration, record, content, bytes(plan())));
});

test('a quality-json binding cannot impersonate the JMH raw kind', () => {
  const ctx = bytes(context()); const raw = Buffer.from(rawLiteral);
  rejected(() => readJmhRaw(ctx, bindRawArtifact(ctx, 'quality-json', raw), raw, bytes(plan())));
});

test('native declarations and instrumented contexts cannot enter the narrow JVM profile', () => {
  const native = context();
  native.implementation = 'tree-sitter'; native.artifact.kind = 'native';
  native.runtime = {
    kind: 'native', compiler: { version: [1, 96, 1], revision: 'b'.repeat(40), sha256: digest },
    cCompiler: { kind: 'clang', version: [17, 0, 0], sha256: digest },
    profile: 'release', targetCpu: 'generic', optLevel: 3, lto: 'off', codegenUnits: 16,
    options: { rustFlags: 'default', cFlags: 'default' },
  };
  native.grammar = {
    repository: 'dekobon/tree-sitter-groovy', revision: 'b'.repeat(40), parserSha256: digest,
    runtimeVersion: [0, 27, 2], runtimeRevision: 'b'.repeat(40),
    queries: Object.fromEntries(['highlights', 'locals', 'injections'].map(key => [key,
      { rawSha256: digest, effectiveSha256: digest, policy: 'upstream-unchanged' }])),
  };
  native.measurement.compilePhase = 'not-applicable'; native.state.jit = 'not-applicable';
  const ctx = bytes(native); const raw = Buffer.from(rawLiteral);
  rejected(() => readJmhRaw(ctx, bindRawArtifact(ctx, 'native-json', raw), raw, bytes(plan())));
  for (const instrumentation of ['gc', 'jfr', 'async-profiler']) {
    const declaration = context(); declaration.instrumentation = instrumentation;
    rejected(() => read(rawValue(), declaration));
  }
});

test('rejects missing, duplicate, extra and wrong public benchmark or parameter cases', () => {
  for (const raw of [
    [], [...rawValue(), ...rawValue()], mutateRow(row => { row.benchmark = 'org.example.ParserBench.other'; }),
    mutateRow(row => { row.params.fixture = 'missing'; }), mutateRow(row => { row.params.extra = 'tiny'; }),
  ]) rejected(() => read(raw));
  const expected = plan(); expected.cases.push({ benchmark: 'org.example.ParserBench.other', params: {} });
  rejected(() => read(rawValue(), context(expected), expected));
});

test('rejects unknown and producer certification fields in every structured record', () => {
  for (const mutate of [
    row => { row.comparable = true; }, row => { row.primaryMetric.correctnessPassed = true; },
    row => { row.secondaryMetrics.gc = { score: 1 }; }, row => { row.params.path = '/canary-private-token'; },
  ]) rejected(() => read(mutateRow(mutate)));
  for (const mutate of [
    value => { value.comparable = true; }, value => { value.cases[0].pass = true; },
    value => { value.profile = 'future'; }, value => { value.schemaVersion = 2; },
  ]) { const expected = plan(); mutate(expected); rejected(() => read(rawValue(), context(expected), expected)); }
});

test('rejects incompatible JMH versions, modes, units, threads and batch sizes', () => {
  for (const [field, value] of [
    ['jmhVersion', '1.36'], ['mode', 'thrpt'], ['mode', 'sample'], ['threads', 2],
    ['warmupBatchSize', 2], ['measurementBatchSize', 2],
  ]) rejected(() => read(mutateRow(row => { row[field] = value; })));
  rejected(() => read(mutateRow(row => { row.primaryMetric.scoreUnit = 'us/op'; })));
  rejected(() => read(mutateRow(row => { row.primaryMetric.rawDataHistogram = []; })));
});

test('requires declared fork, warmup and measurement dimensions and exact time spelling', () => {
  for (const [field, value] of [
    ['forks', 1], ['forks', 0], ['warmupIterations', 1], ['measurementIterations', 1],
    ['warmupTime', '1000 ms'], ['warmupTime', '1s'], ['measurementTime', '0.1 s'],
  ]) rejected(() => read(mutateRow(row => { row[field] = value; })));
  for (const data of [[[10, 11]], [[10], [20, 21]], [[10, 11], [20, 21], [30, 31]], [], [10, 20]]) {
    rejected(() => read(mutateRow(row => { row.primaryMetric.rawData = data; })));
  }
});

test('raw iteration means must be finite positive bounded numbers, never NaN strings or summaries', () => {
  for (const value of [0, -1, 'NaN', '+INF', '-INF', '1.0', null, {}, 1e16]) {
    rejected(() => read(mutateRow(row => { row.primaryMetric.rawData[0][0] = value; })));
  }
  const overflow = rawLiteral.replace('10.0, 11.0', '1e999, 11.0');
  rejected(() => read(Buffer.from(overflow)));
});

test('summary metrics stay structurally bounded and NaN uncertainty is restricted to tiny samples', () => {
  for (const mutate of [
    row => { row.primaryMetric.score = 'NaN'; }, row => { row.primaryMetric.score = 0; },
    row => { row.primaryMetric.scoreError = '+INF'; }, row => { row.primaryMetric.scoreError = -1; },
    row => { row.primaryMetric.scoreError = 'NaN'; row.primaryMetric.scoreConfidence = ['NaN', 'NaN']; },
    row => { row.primaryMetric.scoreConfidence = [1]; }, row => { row.primaryMetric.scoreConfidence = [20, 10]; },
    row => { row.primaryMetric.scorePercentiles['90.0'] = 'NaN'; },
    row => { delete row.primaryMetric.scorePercentiles['0.0']; },
  ]) rejected(() => read(mutateRow(mutate)));
});

test('undefined summary uncertainty has the pinned two-sample boundary without admitting nonfinite raw means', () => {
  const raw = mutateRow(row => {
    row.forks = 1; row.primaryMetric.rawData = [[10, 11]];
    row.primaryMetric.scoreError = 'NaN'; row.primaryMetric.scoreConfidence = ['NaN', 'NaN'];
  });
  const expected = plan(); expected.forks = 1;
  assert.deepEqual(read(raw, context(expected), expected).cases[0].forkGroups, [[10, 11]]);
  raw[0].measurementIterations = 3; raw[0].primaryMetric.rawData[0].push(12); expected.measurementIterations = 3;
  rejected(() => read(raw, context(expected), expected));
});

test('supported JDK numeric components, build and early-access metadata must match the declaration', () => {
  for (const [field, value] of [
    ['jdkVersion', '21.0.1'], ['vmVersion', '17.0.17+11-LTS'], ['jdkVersion', '17.0.17-ea'],
    ['vmVersion', 'private/canary-private-token'], ['vmName', 'GraalVM CE'],
  ]) rejected(() => read(mutateRow(row => { row[field] = value; })));
  const raw = mutateRow(row => { row.jdkVersion = '17.0.17.0+10'; row.vmVersion = '17.0.17+10'; });
  assert.equal(read(raw).cases.length, 1);
});

test('accepts declared early-access versions and exact byte heap flags without inferring vendor identity', () => {
  const declaration = context(); declaration.runtime.earlyAccess = true; declaration.runtime.vendor = 'oracle';
  const raw = mutateRow(row => {
    row.jdkVersion = '17.0.17-ea'; row.vmVersion = '17.0.17-ea+10';
    row.vmName = 'Java HotSpot(TM) 64-Bit Server VM'; row.jvmArgs = ['-Xms268435456', '-Xmx1073741824'];
  });
  assert.equal(read(raw, declaration).cases.length, 1);
});

test('heap, collector, compilation and CDS flag contradictions are rejected without echoing argv', () => {
  for (const args of [
    ['-Xms128m'], ['-Xmx2g'], ['-XX:+UseSerialGC'], ['-XX:-TieredCompilation'],
    ['-XX:-BackgroundCompilation'], ['-Xshare:off'], ['-Xmx1g', '-Xmx2g'],
    ['-Xmxinvalid'], ['-Dprivate=canary-private-token'], ['-XX:UnknownFlag=canary-private-token'],
  ]) rejected(() => read(mutateRow(row => { row.jvmArgs = args; })));
});

test('unknown typed settings remain unverified, and missing flags do not establish observed defaults', () => {
  const declaration = context();
  declaration.runtime.options = { tieredCompilation: 'unknown', backgroundCompilation: 'unknown', cds: 'unknown', otherOptions: 'unknown' };
  const raw = mutateRow(row => { row.jvmArgs = ['-XX:-TieredCompilation', '-XX:-BackgroundCompilation', '-Xshare:off']; });
  assert.equal(read(raw, declaration).scope, 'jmh-raw-structural-only');
  assert.equal(read(mutateRow(row => { row.jvmArgs = []; })).cases.length, 1);
  rejected(() => read(mutateRow(row => { row.jvmArgs = ['-Dprivate=canary-private-token']; }), declaration));
});

test('outputs only numeric case indices and fork samples, retaining no private strings or hashes', () => {
  const observed = read(); const encoded = JSON.stringify(observed);
  for (const value of ['canary-private-token', 'org.example', 'tiny', digest, 'jdkVersion', 'jvmArgs', 'percentile']) {
    assert.ok(!encoded.includes(value));
  }
  assert.deepEqual(Object.keys(observed).sort(), ['cases', 'schemaVersion', 'scope']);
  assert.deepEqual(Object.keys(observed.cases[0]).sort(), ['caseIndex', 'forkGroups']);
});

test('requires bounded Buffer inputs and valid UTF-8 JSON rather than truncated or empty content', () => {
  for (const raw of [Buffer.alloc(0), Buffer.from([0xff]), Buffer.from('[{'), Buffer.from('null'), Buffer.alloc(16 * 1024 * 1024 + 1)]) {
    if (!raw.length || raw.length > 16 * 1024 * 1024) {
      rejected(() => readJmhRaw(bytes(context()), bytes({}), raw, bytes(plan())));
    } else rejected(() => read(raw));
  }
  const raw = Buffer.from(rawLiteral); const ctx = bytes(context()); const binding = bindRawArtifact(ctx, 'jmh-json', raw);
  for (const expected of ['unencoded', Buffer.from([0xff]), Buffer.from('{'), Buffer.alloc(32769)]) {
    rejected(() => readJmhRaw(ctx, binding, raw, expected));
  }
  rejected(() => readJmhRaw(ctx, binding, rawLiteral, bytes(plan())));
});

test('rejects duplicate JSON keys including escaped aliases and bounded-depth attacks', () => {
  for (const content of [
    rawLiteral.replace('"mode": "avgt"', '"mode":"thrpt", "mode":"avgt"'),
    rawLiteral.replace('"mode": "avgt"', '"m\\u006fde":"thrpt", "mode":"avgt"'),
    rawLiteral.replace('"secondaryMetrics": {}', '"secondaryMetrics": {"x":' + '['.repeat(100) + '0' + ']'.repeat(100) + '}'),
  ]) rejected(() => read(Buffer.from(content)));
  const ctx = bytes(context()); const raw = Buffer.from(rawLiteral); const binding = bindRawArtifact(ctx, 'jmh-json', raw);
  const duplicatePlan = bytes(plan()).toString().replace('"forks":2', '"forks":1,"forks":2');
  rejected(() => readJmhRaw(ctx, binding, raw, Buffer.from(duplicatePlan)));
});

test('uses B03a canonical context interpretation while rejecting ambiguous raw binding fields', () => {
  const raw = Buffer.from(rawLiteral);
  const ctx = Buffer.from(bytes(context()).toString().replace('"instrumentation":"none"', '"instrumentation":"gc","instrumentation":"none"'));
  const binding = bindRawArtifact(ctx, 'jmh-json', raw);
  assert.equal(readJmhRaw(ctx, binding, raw, bytes(plan())).cases.length, 1);
  const ordinary = bytes(context());
  const duplicated = Buffer.from(bindRawArtifact(ordinary, 'jmh-json', raw).toString().replace('"kind":"jmh-json"', '"kind":"quality-json","kind":"jmh-json"'));
  rejected(() => readJmhRaw(ordinary, duplicated, raw, bytes(plan())));
});

test('private metadata counts and scalar boundaries are bounded and required fields cannot disappear', () => {
  for (const mutate of [
    row => { row.jvm = 'x'.repeat(4097); }, row => { row.jvm = '\ud800'; },
    row => { row.jvmArgs = Array(129).fill('-Xmx1g'); }, row => { row.jvmArgs = ['-Xmx1g\n']; },
    row => { delete row.jvm; }, row => { delete row.primaryMetric; }, row => { row.params = {}; },
  ]) rejected(() => read(mutateRow(mutate)));
  const raw = mutateRow(row => { row.jvm = 'private/\ud83d\ude00/java'; });
  assert.equal(read(raw).cases.length, 1);
});

test('plan counts, public identifiers, durations and total work have explicit bounds', () => {
  for (const mutate of [
    value => { value.forks = 33; }, value => { value.measurementIterations = 101; },
    value => { value.warmupIterations = -1; }, value => { value.measurementTime = '3601 s'; },
    value => { value.warmupTime = '0 ms'; }, value => { value.forks = 1.5; },
    value => { value.cases = []; }, value => { value.cases.push(structuredClone(value.cases[0])); },
    value => { value.cases[0].benchmark = '/private/canary-private-token'; },
    value => { value.cases[0].params.fixture = '../private'; },
    value => { value.cases = Array.from({ length: 33 }, (_, index) => ({ benchmark: 'org.example.B.run', params: { fixture: String(index) } })); },
    value => { value.cases[0].params = Object.fromEntries(Array.from({ length: 33 }, (_, index) => [`p${index}`, 'x'])); },
    value => { value.forks = 32; value.measurementIterations = 100; value.cases = Array.from({ length: 32 }, (_, index) => ({ benchmark: 'org.example.B.run', params: { fixture: String(index) } })); },
  ]) { const expected = plan(); mutate(expected); rejected(() => read(rawValue(), context(expected), expected)); }
});

test('parameter key order does not alter identity and escaped private strings are not mistaken for keys', () => {
  const raw = mutateRow(row => { row.params = { phase: 'conversion', fixture: 'tiny' }; row.jvm = 'C:\\private\\canary-private-token\\java'; });
  const expected = plan(); expected.cases[0].params = { fixture: 'tiny', phase: 'conversion' };
  assert.equal(read(raw, context(expected), expected).cases.length, 1);
});

test('same-length public plan substitution cannot retain context identity', () => {
  const original = plan(); const changed = plan(); changed.cases[0].params.fixture = 'mini';
  assert.equal(bytes(original).length, bytes(changed).length);
  const changedRaw = bytes(mutateRow(row => { row.params.fixture = 'mini'; }));
  const ctx = bytes(context(original));
  rejected(() => readJmhRaw(ctx, bindRawArtifact(ctx, 'jmh-json', changedRaw), changedRaw, bytes(changed)));
});

test('shrinking the expected case set requires a new plan-bound context', () => {
  const larger = plan(); larger.cases.push({ benchmark: 'org.example.ParserBench.other', params: {} });
  const largerContext = bytes(context(larger)); const raw = Buffer.from(rawLiteral);
  rejected(() => readJmhRaw(largerContext, bindRawArtifact(largerContext, 'jmh-json', raw), raw, bytes(plan())));
});

test('plan identity binds exact bytes even when whitespace changes no parsed fields', () => {
  const ctx = bytes(context()); const raw = Buffer.from(rawLiteral); const binding = bindRawArtifact(ctx, 'jmh-json', raw);
  const changed = Buffer.concat([bytes(plan()), Buffer.from('\n')]);
  rejected(() => readJmhRaw(ctx, binding, raw, changed));
  const declaration = context(); declaration.workload.scenarioSha256 = hash(changed);
  const rebound = bytes(declaration);
  assert.equal(readJmhRaw(rebound, bindRawArtifact(rebound, 'jmh-json', raw), raw, changed).cases.length, 1);
});

test('the initial exact-plan mapping is restricted to the declared P parse lane', () => {
  const declaration = context(); declaration.measurement.lane = 'H'; declaration.measurement.boundary = 'highlight-existing';
  rejected(() => read(rawValue(), declaration));
});
