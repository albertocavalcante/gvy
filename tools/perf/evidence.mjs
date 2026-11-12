import { createHash } from 'node:crypto';
import { constants } from 'node:fs';
import fs from 'node:fs/promises';
import { join } from 'node:path';

const MAX_BYTES = 4 * 1024 * 1024;
const MAX_OUTCOMES = 10000;
const fail = () => { throw new Error('INVALID_EVIDENCE'); };
const object = (value, keys, optional = []) => {
  if (!value || typeof value !== 'object' || Array.isArray(value)) fail();
  for (const key of Object.keys(value)) if (!keys.includes(key)) fail();
  for (const key of keys) if (!optional.includes(key) && !Object.hasOwn(value, key)) fail();
};
const digest = value => { if (typeof value !== 'string' || !/^[a-f0-9]{64}$/.test(value)) fail(); };
const number = value => { if (!Number.isSafeInteger(value) || value < 0) fail(); };
const oneOf = (value, values) => { if (!values.includes(value)) fail(); };
const bool = value => { if (typeof value !== 'boolean') fail(); };

function validate(run) {
  object(run, ['manifest', 'outcomes', 'summary']);
  const m = run.manifest;
  object(m, ['schemaVersion', 'source', 'artifact', 'workload', 'runtime', 'cacheState', 'instrumentation', 'launch']);
  if (m.schemaVersion !== 1) fail();
  object(m.source, ['commit', 'dirty']);
  if (typeof m.source.commit !== 'string' || !/^[a-f0-9]{40}$/.test(m.source.commit)) fail();
  bool(m.source.dirty);
  object(m.artifact, ['sha256']); digest(m.artifact.sha256);
  object(m.workload, ['id', 'version', 'corpusSha256', 'scenarioSha256']);
  oneOf(m.workload.id, ['startup', 'navigation', 'editing', 'formatting', 'workspace-scale', 'project-churn', 'soak']);
  number(m.workload.version); digest(m.workload.corpusSha256); digest(m.workload.scenarioSha256);
  object(m.runtime, ['os', 'arch', 'vendor', 'majorVersion', 'heapBytes', 'gc']);
  oneOf(m.runtime.os, ['macos', 'linux', 'windows']); oneOf(m.runtime.arch, ['arm64', 'x64']);
  oneOf(m.runtime.vendor, ['oracle', 'openjdk', 'graalvm', 'other']);
  number(m.runtime.majorVersion); number(m.runtime.heapBytes);
  oneOf(m.runtime.gc, ['g1', 'serial', 'parallel', 'zgc', 'shenandoah', 'other']);
  oneOf(m.cacheState, ['cold', 'warm', 'unknown']); oneOf(m.instrumentation, ['none', 'jfr', 'async-profiler', 'other']);
  object(m.launch, ['redactions', 'reproducible']);
  if (m.launch.redactions !== true) fail(); bool(m.launch.reproducible);
  if (!Array.isArray(run.outcomes) || !run.outcomes.length || run.outcomes.length > MAX_OUTCOMES) fail();
  const sequences = new Map();
  for (const o of run.outcomes) {
    object(o, ['sequence', 'operation', 'documentVersion', 'plannedNs', 'sendNs', 'finishNs', 'status', 'correctness', 'failureCategory', 'supersededBy'], ['documentVersion', 'failureCategory', 'supersededBy']);
    number(o.sequence); if (sequences.has(o.sequence)) fail(); sequences.set(o.sequence, o);
    oneOf(o.operation, ['initialize', 'first-answer', 'completion', 'hover', 'definition', 'references', 'rename', 'symbols', 'semantic-tokens', 'diagnostics', 'formatting', 'edit', 'open', 'close', 'workspace', 'other']);
    if (o.documentVersion !== undefined) number(o.documentVersion);
    if (o.plannedNs !== null) number(o.plannedNs);
    number(o.sendNs); number(o.finishNs); if (o.finishNs < o.sendNs) fail();
    oneOf(o.status, ['success', 'failed', 'timeout', 'cancelled', 'coalesced']);
    oneOf(o.correctness, ['pass', 'fail', 'not-evaluated']);
    if (o.failureCategory !== undefined) oneOf(o.failureCategory, ['protocol', 'timeout', 'process', 'assertion', 'other']);
    if (o.supersededBy !== undefined) number(o.supersededBy);
    if ((o.status === 'coalesced') !== (o.supersededBy !== undefined)) fail();
  }
  for (const o of run.outcomes) if (o.status === 'coalesced' && (o.supersededBy <= o.sequence || !sequences.has(o.supersededBy))) fail();
  object(run.summary, ['completed', 'expectedOutcomes']);
  if (run.summary.completed !== true) fail(); number(run.summary.expectedOutcomes);
  if (run.summary.expectedOutcomes !== run.outcomes.length) fail();
}
const hash = data => createHash('sha256').update(data).digest('hex');
const encode = value => Buffer.from(JSON.stringify(value) + '\n');
const bounded = data => { if (data.length > MAX_BYTES) fail(); return data; };

/**
 * Persist a structurally valid, completed run in a new private directory.
 * Completion is not a correctness or performance certification. All strings are
 * closed enums or identity digests; producers must hash only public artifacts,
 * never secret values. The parent directory must be trusted against mutation.
 * Each file is bounded; the integrity marker is written last. A failed write
 * removes its owned directory. This is not crash-durable or an atomic snapshot.
 * Filesystem and validation errors intentionally expose only fixed error codes.
 */
export async function writeRun(directory, run) {
  let owned;
  try {
    validate(run);
    if (typeof directory !== 'string' || Buffer.byteLength(directory) > 1024) fail();
    const files = { 'manifest.json': bounded(encode(run.manifest)), 'outcomes.jsonl': bounded(Buffer.from(run.outcomes.map(o => JSON.stringify(o)).join('\n') + '\n')), 'summary.json': bounded(encode(run.summary)) };
    const marker = encode(Object.fromEntries(Object.entries(files).map(([name, data]) => [name, hash(data)])));
    await fs.mkdir(directory, { mode: 0o700 }); owned = await fs.lstat(directory);
    for (const [name, data] of Object.entries(files)) await fs.writeFile(join(directory, name), data, { flag: 'wx', mode: 0o600 });
    await fs.writeFile(join(directory, 'complete.json'), marker, { flag: 'wx', mode: 0o600 });
  } catch {
    if (owned) {
      const current = await fs.lstat(directory).catch(() => null);
      if (current?.isDirectory() && current.dev === owned.dev && current.ino === owned.ino) {
        await fs.rm(directory, { recursive: true, force: true }).catch(() => {});
      }
    }
    throw new Error('EVIDENCE_WRITE_FAILED');
  }
}

async function readBounded(path) {
  const file = await fs.open(path, constants.O_RDONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK);
  try {
    if (!(await file.stat()).isFile()) fail();
    const data = Buffer.alloc(MAX_BYTES + 1); let length = 0;
    while (length < data.length) {
      const result = await file.read(data, length, data.length - length, null);
      if (!result.bytesRead) break; length += result.bytesRead;
    }
    return bounded(data.subarray(0, length));
  } finally { await file.close(); }
}
/** Read a complete integrity-checked run from a trusted parent; no validity claim is made. */
export async function readRun(directory) {
  try {
    if (typeof directory !== 'string' || Buffer.byteLength(directory) > 1024 || !(await fs.lstat(directory)).isDirectory()) fail();
    const marker = JSON.parse(await readBounded(join(directory, 'complete.json')));
    object(marker, ['manifest.json', 'outcomes.jsonl', 'summary.json']);
    const files = {};
    for (const name of Object.keys(marker)) {
      digest(marker[name]); files[name] = await readBounded(join(directory, name));
      if (hash(files[name]) !== marker[name]) fail();
    }
    const lines = files['outcomes.jsonl'].toString();
    if (!lines.endsWith('\n')) fail();
    const run = { manifest: JSON.parse(files['manifest.json']), outcomes: lines.trimEnd().split('\n').map(line => JSON.parse(line)), summary: JSON.parse(files['summary.json']) };
    validate(run); return run;
  } catch { throw new Error('EVIDENCE_READ_FAILED'); }
}
