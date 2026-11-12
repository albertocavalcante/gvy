import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import test from 'node:test';
import { bindRawArtifact, canonicalizeContext, verifyRawArtifact } from './context.mjs';

const digest = 'a'.repeat(64);
const commit = 'b'.repeat(40);
const bytes = value => Buffer.from(JSON.stringify(value));
const hash = value => createHash('sha256').update(value).digest('hex');
function context() {
  return {
    schemaVersion: 1, scope: 'benchmark-context-declaration', implementation: 'tree-sitter',
    source: { commit, dirty: false }, artifact: { kind: 'native', sha256: digest },
    runtime: {
      kind: 'native', compiler: { version: [1, 96, 1], revision: commit, sha256: digest },
      cCompiler: { kind: 'clang', version: [17, 0, 0], sha256: digest },
      profile: 'release', targetCpu: 'generic', optLevel: 3, lto: 'off', codegenUnits: 16,
      options: { rustFlags: 'default', cFlags: 'default' },
    },
    grammar: {
      repository: 'dekobon/tree-sitter-groovy', revision: commit, parserSha256: digest,
      runtimeVersion: [0, 27, 2], runtimeRevision: commit,
      queries: Object.fromEntries(['highlights', 'locals', 'injections'].map(kind => [kind,
        { rawSha256: digest, effectiveSha256: digest, policy: 'upstream-unchanged' }])),
    },
    workload: { corpusSha256: digest, scenarioSha256: digest, classpathSha256: digest, oracleSha256: digest },
    measurement: { lane: 'P', boundary: 'parse', compilePhase: 'not-applicable' },
    state: { process: 'warm', jit: 'not-applicable', structure: 'absent', resultCache: 'off', osPages: 'unknown' },
    host: { os: 'linux', arch: 'x64', cpuModelSha256: digest, powerMode: 'ac', thermal: 'unknown', contention: 'unknown' },
    instrumentation: 'none',
  };
}
function jvmContext() {
  const value = context();
  value.implementation = 'gvy-facade';
  value.artifact.kind = 'jar';
  value.grammar = null;
  value.runtime = {
    kind: 'jvm', vendor: 'temurin', vm: 'hotspot', version: [17, 0, 17, 0, 10], earlyAccess: false,
    launcherSha256: digest, vmSha256: digest, heapMinBytes: 268435456, heapMaxBytes: 2147483648, gc: 'g1',
    options: { tieredCompilation: 'on', backgroundCompilation: 'on', cds: 'default', otherOptions: 'none' },
    cacheSha256: null,
  };
  value.measurement.compilePhase = 'conversion';
  value.state.jit = 'warm';
  return value;
}

test('native and JVM declarations retain exact typed runtime identity', () => {
  for (const value of [context(), jvmContext()]) assert.deepEqual(JSON.parse(canonicalizeContext(bytes(value))), value);
});

test('context identity is independent of JSON property insertion order', () => {
  const value = context();
  const reversed = Object.fromEntries(Object.entries(value).reverse());
  assert.equal(hash(canonicalizeContext(bytes(value))), hash(canonicalizeContext(bytes(reversed))));
});

test('each relevant runtime, phase, workload and state change changes context identity', () => {
  const original = hash(canonicalizeContext(bytes(jvmContext())));
  const mutations = [
    value => { value.runtime.version[2] += 1; },
    value => { value.runtime.heapMaxBytes *= 2; },
    value => { value.runtime.options.cds = 'off'; },
    value => { value.measurement.compilePhase = 'canonicalization'; },
    value => { value.workload.classpathSha256 = 'c'.repeat(64); },
    value => { value.state.resultCache = 'hit'; },
  ];
  for (const mutate of mutations) {
    const value = jvmContext(); mutate(value);
    assert.notEqual(hash(canonicalizeContext(bytes(value))), original);
  }
});

test('native work cannot impersonate JVM work or lose its grammar identity', () => {
  const wrongArtifact = context(); wrongArtifact.artifact.kind = 'jar';
  const noGrammar = context(); noGrammar.grammar = null;
  const wrongJit = context(); wrongJit.state.jit = 'warm';
  const wrongPhase = context(); wrongPhase.measurement.compilePhase = 'canonicalization';
  for (const value of [wrongArtifact, noGrammar, wrongJit, wrongPhase]) {
    assert.throws(() => canonicalizeContext(bytes(value)), /^Error: INVALID_BENCHMARK_CONTEXT$/);
  }
});

test('unknown observed state remains unknown and cannot introduce certification flags', () => {
  const value = context(); value.state.process = 'unknown';
  assert.equal(JSON.parse(canonicalizeContext(bytes(value))).state.process, 'unknown');
  value.comparable = true;
  assert.throws(() => canonicalizeContext(bytes(value)), /^Error: INVALID_BENCHMARK_CONTEXT$/);
});

test('unsafe extra fields and malformed versions fail with fixed errors', () => {
  const mutations = [
    value => { value.runtime.environment = { SECRET: 'canary-private-token' }; },
    value => { value.host.path = '/private/canary-private-token'; },
    value => { value.runtime.compiler.version = 'rustc canary-private-token'; },
    value => { value.runtime.compiler.version = [1, 96]; },
    value => { value.grammar.queries.highlights.rawSha256 = 'invalid'; },
  ];
  for (const mutate of mutations) {
    const value = context(); mutate(value);
    assert.throws(() => canonicalizeContext(bytes(value)), error => {
      assert.equal(error.message, 'INVALID_BENCHMARK_CONTEXT');
      assert.ok(!error.message.includes('canary-private-token'));
      return true;
    });
  }
});

test('schema, input size, number and UTF-8 boundaries are enforced', () => {
  const wrongVersion = context(); wrongVersion.schemaVersion = 2;
  const fractional = context(); fractional.runtime.codegenUnits = 1.5;
  const invalidHeap = jvmContext(); invalidHeap.runtime.heapMinBytes = invalidHeap.runtime.heapMaxBytes + 1;
  for (const value of [bytes(wrongVersion), bytes(fractional), bytes(invalidHeap), Buffer.alloc(32769), Buffer.from([0xff]), 'unencoded']) {
    assert.throws(() => canonicalizeContext(value), /^Error: INVALID_BENCHMARK_CONTEXT$/);
  }
});

test('raw binding records actual bytes and the canonical context digest', () => {
  const declaration = bytes(jvmContext());
  const raw = Buffer.from('[{"benchmark":"example"}]\n');
  const binding = bindRawArtifact(declaration, 'jmh-json', raw);
  assert.deepEqual(JSON.parse(binding), {
    bytes: raw.length, contextSha256: hash(canonicalizeContext(declaration)), kind: 'jmh-json', sha256: hash(raw),
  });
  verifyRawArtifact(declaration, binding, raw);
});

test('same-length corruption, truncation and a changed context cannot reuse a raw binding', () => {
  const declaration = bytes(context());
  const raw = Buffer.from('native-record\n');
  const binding = bindRawArtifact(declaration, 'native-json', raw);
  const changed = context(); changed.source.commit = 'd'.repeat(40);
  for (const [ctx, content] of [
    [declaration, Buffer.from('Native-record\n')], [declaration, raw.subarray(1)], [bytes(changed), raw],
  ]) assert.throws(() => verifyRawArtifact(ctx, binding, content), /^Error: INVALID_BENCHMARK_BINDING$/);
});

test('unknown raw kinds, oversized records and producer certification fields are rejected', () => {
  const declaration = bytes(context());
  assert.throws(() => bindRawArtifact(declaration, 'other', Buffer.from('x')), /^Error: INVALID_BENCHMARK_BINDING$/);
  assert.throws(() => bindRawArtifact(declaration, 'native-json', Buffer.alloc(16 * 1024 * 1024 + 1)), /^Error: INVALID_BENCHMARK_BINDING$/);
  const binding = JSON.parse(bindRawArtifact(declaration, 'quality-json', Buffer.from('{}')));
  binding.correctnessPassed = true;
  assert.throws(() => verifyRawArtifact(declaration, bytes(binding), Buffer.from('{}')), /^Error: INVALID_BENCHMARK_BINDING$/);
});

test('a native raw-kind label cannot impersonate JMH or vice versa', () => {
  assert.throws(() => bindRawArtifact(bytes(context()), 'jmh-json', Buffer.from('[]')), /^Error: INVALID_BENCHMARK_BINDING$/);
  assert.throws(() => bindRawArtifact(bytes(jvmContext()), 'native-json', Buffer.from('{}')), /^Error: INVALID_BENCHMARK_BINDING$/);
});
