// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import { createHash } from 'node:crypto';

const CONTEXT_LIMIT = 32 * 1024;
const RAW_LIMIT = 16 * 1024 * 1024;
const fail = () => { throw new Error('INVALID_BENCHMARK_CONTEXT'); };
const bindingFail = () => { throw new Error('INVALID_BENCHMARK_BINDING'); };
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const member = (value, values) => { if (!values.includes(value)) fail(); };
const integer = (value, minimum = 0, maximum = Number.MAX_SAFE_INTEGER) => {
  if (!Number.isSafeInteger(value) || value < minimum || value > maximum) fail();
};
const bool = value => { if (typeof value !== 'boolean') fail(); };
const digest = value => { if (typeof value !== 'string' || !/^[a-f0-9]{64}$/.test(value)) fail(); };
const revision = value => { if (typeof value !== 'string' || !/^[a-f0-9]{40}$/.test(value)) fail(); };
function record(value, fields) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) fail();
  const keys = Object.keys(value);
  if (keys.length !== fields.length || keys.some(key => !fields.includes(key))) fail();
}
function version(value, count, minimumMajor = 1) {
  if (!Array.isArray(value) || value.length !== count) fail();
  value.forEach(item => integer(item, 0, 1000000));
  integer(value[0], minimumMajor, 1000000);
}
function decode(bytes, limit) {
  if (!Buffer.isBuffer(bytes) || !bytes.length || bytes.length > limit) fail();
  return JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes));
}
function ordered(value) {
  if (Array.isArray(value)) return value.map(ordered);
  if (value !== null && typeof value === 'object') {
    return Object.fromEntries(Object.keys(value).sort().map(key => [key, ordered(value[key])]));
  }
  return value;
}
const encode = value => Buffer.from(JSON.stringify(ordered(value)) + '\n');
const compatibleKind = (kind, runtime) => kind === 'quality-json' ||
  (kind === 'jmh-json' && runtime === 'jvm') || (kind === 'native-json' && runtime === 'native');

function validateRuntime(runtime) {
  if (runtime?.kind === 'native') {
    record(runtime, ['kind', 'compiler', 'cCompiler', 'profile', 'targetCpu', 'optLevel', 'lto', 'codegenUnits', 'options']);
    record(runtime.compiler, ['version', 'revision', 'sha256']);
    version(runtime.compiler.version, 3); revision(runtime.compiler.revision); digest(runtime.compiler.sha256);
    record(runtime.cCompiler, ['kind', 'version', 'sha256']);
    member(runtime.cCompiler.kind, ['clang', 'gcc']); version(runtime.cCompiler.version, 3); digest(runtime.cCompiler.sha256);
    member(runtime.profile, ['release', 'debug']); member(runtime.targetCpu, ['generic', 'native', 'unknown']);
    integer(runtime.optLevel, 0, 3); member(runtime.lto, ['off', 'thin', 'fat', 'unknown']); integer(runtime.codegenUnits, 1, 512);
    record(runtime.options, ['rustFlags', 'cFlags']);
    for (const setting of Object.values(runtime.options)) member(setting, ['default', 'unknown']);
  } else if (runtime?.kind === 'jvm') {
    record(runtime, ['kind', 'vendor', 'vm', 'version', 'earlyAccess', 'distributionSha256', 'launcherSha256', 'vmSha256', 'heapMinBytes', 'heapMaxBytes', 'gc', 'options', 'cacheSha256']);
    member(runtime.vendor, ['openjdk', 'temurin', 'oracle', 'graalvm', 'zulu']);
    member(runtime.vm, ['hotspot', 'graal']); version(runtime.version, 5); bool(runtime.earlyAccess);
    digest(runtime.distributionSha256); digest(runtime.launcherSha256); digest(runtime.vmSha256);
    integer(runtime.heapMinBytes, 1); integer(runtime.heapMaxBytes, runtime.heapMinBytes);
    member(runtime.gc, ['g1', 'serial', 'parallel', 'zgc', 'shenandoah']);
    record(runtime.options, ['tieredCompilation', 'backgroundCompilation', 'cds', 'otherOptions']);
    member(runtime.options.tieredCompilation, ['on', 'off', 'unknown']);
    member(runtime.options.backgroundCompilation, ['on', 'off', 'unknown']);
    member(runtime.options.cds, ['default', 'off', 'application', 'unknown']);
    member(runtime.options.otherOptions, ['none', 'unknown']);
    if (runtime.cacheSha256 !== null) digest(runtime.cacheSha256);
  } else fail();
}

function validate(value) {
  record(value, ['schemaVersion', 'scope', 'implementation', 'source', 'artifact', 'runtime', 'grammar', 'workload', 'measurement', 'state', 'host', 'instrumentation']);
  if (value.schemaVersion !== 1 || value.scope !== 'benchmark-context-declaration') fail();
  member(value.implementation, ['gvy-compiler', 'gvy-facade', 'gvy-semantic', 'tree-sitter']);
  record(value.source, ['commit', 'dirty']); revision(value.source.commit); bool(value.source.dirty);
  record(value.artifact, ['kind', 'sha256']); digest(value.artifact.sha256);
  validateRuntime(value.runtime);
  const native = value.runtime.kind === 'native';
  if (native !== (value.implementation === 'tree-sitter') || value.artifact.kind !== (native ? 'native' : 'jar')) fail();
  if (native) {
    const grammar = value.grammar;
    record(grammar, ['repository', 'revision', 'parserSha256', 'runtimeVersion', 'runtimeRevision', 'queries']);
    member(grammar.repository, ['dekobon/tree-sitter-groovy', 'murtaza64/tree-sitter-groovy']);
    revision(grammar.revision); digest(grammar.parserSha256); version(grammar.runtimeVersion, 3, 0); revision(grammar.runtimeRevision);
    record(grammar.queries, ['highlights', 'locals', 'injections']);
    for (const query of Object.values(grammar.queries)) {
      record(query, ['rawSha256', 'effectiveSha256', 'policy']); digest(query.rawSha256); digest(query.effectiveSha256);
      member(query.policy, ['upstream-unchanged', 'adapted']);
      if (query.policy === 'upstream-unchanged' && query.rawSha256 !== query.effectiveSha256) fail();
    }
  } else if (value.grammar !== null) fail();
  record(value.workload, ['corpusSha256', 'scenarioSha256', 'classpathSha256', 'oracleSha256']);
  Object.values(value.workload).forEach(digest);
  record(value.measurement, ['lane', 'boundary', 'compilePhase']);
  const boundaries = { P: 'parse', H: 'highlight-existing', F: 'parse-highlight', E: 'edit-highlight', C: 'startup' };
  if (!Object.hasOwn(boundaries, value.measurement.lane) || value.measurement.boundary !== boundaries[value.measurement.lane]) fail();
  if (value.implementation === 'gvy-compiler' && value.measurement.lane !== 'P') fail();
  member(value.measurement.compilePhase, native ? ['not-applicable'] : ['conversion', 'canonicalization']);
  record(value.state, ['process', 'jit', 'structure', 'resultCache', 'osPages']);
  member(value.state.process, ['fresh', 'warm', 'unknown']);
  member(value.state.jit, native ? ['not-applicable'] : ['cold', 'warm', 'unknown']);
  member(value.state.structure, ['absent', 'existing', 'incremental', 'unknown']);
  member(value.state.resultCache, ['off', 'hit', 'miss', 'unknown']); member(value.state.osPages, ['cold', 'warm', 'unknown']);
  record(value.host, ['os', 'arch', 'cpuModelSha256', 'powerMode', 'thermal', 'contention']);
  member(value.host.os, ['macos', 'linux', 'windows']); member(value.host.arch, ['arm64', 'x64']); digest(value.host.cpuModelSha256);
  member(value.host.powerMode, ['ac', 'battery', 'unknown']); member(value.host.thermal, ['nominal', 'elevated', 'unknown']);
  member(value.host.contention, ['observed-idle', 'observed-busy', 'unknown']);
  member(value.instrumentation, ['none', 'gc', 'jfr', 'async-profiler', 'allocator']);
}

/** Validate a bounded declaration. This does not observe an execution or certify comparability. */
export function canonicalizeContext(bytes) {
  try { const value = decode(bytes, CONTEXT_LIMIT); validate(value); return encode(value); }
  catch { fail(); }
}

/** Bind opaque raw bytes to a declaration. Format, completeness and correctness require separate readers. */
export function bindRawArtifact(contextBytes, kind, rawBytes) {
  const context = canonicalizeContext(contextBytes);
  const contextSha256 = hash(context);
  if (!compatibleKind(kind, JSON.parse(context).runtime.kind) || !Buffer.isBuffer(rawBytes) || !rawBytes.length || rawBytes.length > RAW_LIMIT) bindingFail();
  return encode({ contextSha256, kind, bytes: rawBytes.length, sha256: hash(rawBytes) });
}

/** Verify byte identity only; a producer's labels cannot attest successful benchmark work. */
export function verifyRawArtifact(contextBytes, bindingBytes, rawBytes) {
  const context = canonicalizeContext(contextBytes);
  const contextSha256 = hash(context);
  try {
    const binding = decode(bindingBytes, 1024);
    record(binding, ['contextSha256', 'kind', 'bytes', 'sha256']);
    if (!compatibleKind(binding.kind, JSON.parse(context).runtime.kind) || !Buffer.isBuffer(rawBytes) || !rawBytes.length || rawBytes.length > RAW_LIMIT ||
        binding.contextSha256 !== contextSha256 || binding.bytes !== rawBytes.length || binding.sha256 !== hash(rawBytes)) bindingFail();
  } catch { bindingFail(); }
}
