import { createHash, randomBytes } from 'node:crypto';
import { mkdir, open, rename, rm, writeFile } from 'node:fs/promises';
import { basename, dirname, join } from 'node:path';

// Version 1 is deliberately closed: unknown fields cannot silently become shareable evidence.
const MAX_BYTES = 4 * 1024 * 1024;
const MAX_OUTCOMES = 10000;
const fail = message => { throw new Error(message); };
const object = (value, keys, optional = []) => {
  if (!value || typeof value !== 'object' || Array.isArray(value)) fail('Expected object');
  for (const key of Object.keys(value)) if (!keys.includes(key)) fail(`Unknown field: ${key}`);
  for (const key of keys) if (!optional.includes(key) && !Object.hasOwn(value, key)) fail(`Missing field: ${key}`);
};
const label = value => { if (typeof value !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/.test(value)) fail('Invalid label'); };
const digest = value => { if (typeof value !== 'string' || !/^[a-f0-9]{64}$/.test(value)) fail('Invalid digest'); };
const number = value => { if (!Number.isSafeInteger(value) || value < 0) fail('Invalid number'); };
const oneOf = (value, values) => { if (!values.includes(value)) fail('Invalid value'); };
const bool = value => { if (typeof value !== 'boolean') fail('Invalid boolean'); };

function validate(run) {
  object(run, ['manifest', 'outcomes', 'summary']);
  const m = run.manifest;
  object(m, ['schemaVersion', 'source', 'artifact', 'workload', 'runtime', 'cacheState', 'instrumentation', 'launch']);
  if (m.schemaVersion !== 1) fail('Unsupported schema');
  object(m.source, ['commit', 'dirty']);
  if (typeof m.source.commit !== 'string' || !/^[a-f0-9]{40}$/.test(m.source.commit)) fail('Invalid commit');
  bool(m.source.dirty);
  object(m.artifact, ['sha256']); digest(m.artifact.sha256);
  object(m.workload, ['id', 'version', 'corpusSha256', 'scenarioSha256']);
  label(m.workload.id); label(m.workload.version); digest(m.workload.corpusSha256); digest(m.workload.scenarioSha256);
  object(m.runtime, ['os', 'arch', 'vendor', 'version', 'heapBytes', 'gc']);
  for (const key of ['os', 'arch', 'vendor', 'version', 'gc']) label(m.runtime[key]);
  number(m.runtime.heapBytes); label(m.cacheState); label(m.instrumentation);
  object(m.launch, ['redactions', 'reproducible']);
  if (m.launch.redactions !== true) fail('Redactions must be declared');
  bool(m.launch.reproducible);
  if (!Array.isArray(run.outcomes) || run.outcomes.length > MAX_OUTCOMES) fail('Invalid outcomes');
  const sequences = new Set();
  for (const o of run.outcomes) {
    object(o, ['sequence', 'operation', 'documentVersion', 'plannedNs', 'sendNs', 'finishNs', 'status', 'correctness', 'failureCategory', 'supersededBy'], ['documentVersion', 'failureCategory', 'supersededBy']);
    number(o.sequence); if (sequences.has(o.sequence)) fail('Duplicate sequence'); sequences.add(o.sequence);
    label(o.operation); if (o.documentVersion !== undefined) number(o.documentVersion);
    if (o.plannedNs !== null) number(o.plannedNs);
    number(o.sendNs); number(o.finishNs);
    if (o.finishNs < o.sendNs) fail('Invalid timing');
    oneOf(o.status, ['success', 'failed', 'timeout', 'cancelled', 'coalesced']);
    oneOf(o.correctness, ['pass', 'fail', 'not-evaluated']);
    if (o.failureCategory !== undefined) label(o.failureCategory);
    if (o.supersededBy !== undefined) number(o.supersededBy);
    if (o.status === 'coalesced' && (o.supersededBy === undefined || o.supersededBy === o.sequence)) fail('Missing supersession');
  }
  object(run.summary, ['validity', 'completed', 'expectedOutcomes']);
  oneOf(run.summary.validity, ['valid', 'invalid']); bool(run.summary.completed); number(run.summary.expectedOutcomes);
  if (run.summary.expectedOutcomes !== run.outcomes.length) fail('Outcome count mismatch');
  if (run.summary.validity === 'valid' && (!run.summary.completed || run.outcomes.some(o => o.status !== 'success' || o.correctness !== 'pass'))) fail('Invalid successful summary');
}
const hash = data => createHash('sha256').update(data).digest('hex');
const encode = value => Buffer.from(JSON.stringify(value) + '\n');
const bounded = data => { if (data.length > MAX_BYTES) fail('Evidence exceeds size limit'); return data; };

/** Publish bounded files with a completion marker last; never overwrite an existing run. */
export async function writeRun(directory, run) {
  validate(run);
  const files = { 'manifest.json': bounded(encode(run.manifest)), 'outcomes.jsonl': bounded(Buffer.from(run.outcomes.map(o => JSON.stringify(o)).join('\n') + (run.outcomes.length ? '\n' : ''))), 'summary.json': bounded(encode(run.summary)) };
  const marker = encode(Object.fromEntries(Object.entries(files).map(([name, data]) => [name, hash(data)])));
  if (typeof directory !== 'string' || Buffer.byteLength(directory) > 1024 || basename(directory) === '.' || basename(directory) === '..') fail('Invalid run path');
  const parent = dirname(directory);
  const temp = join(parent, `.${basename(directory)}-${randomBytes(12).toString('hex')}`);
  await mkdir(temp, { mode: 0o700 });
  try {
    for (const [name, data] of Object.entries(files)) await writeFile(join(temp, name), data, { flag: 'wx', mode: 0o600 });
    await writeFile(join(temp, 'complete.json'), marker, { flag: 'wx', mode: 0o600 });
    await mkdir(directory, { mode: 0o700 });
    for (const name of Object.keys(files)) await rename(join(temp, name), join(directory, name));
    await rename(join(temp, 'complete.json'), join(directory, 'complete.json'));
  } finally { await rm(temp, { recursive: true, force: true }); }
}

async function readBounded(path) {
  const file = await open(path, 'r');
  try {
    const data = Buffer.alloc(MAX_BYTES + 1);
    let length = 0;
    while (length < data.length) {
      const result = await file.read(data, length, data.length - length, null);
      if (!result.bytesRead) break;
      length += result.bytesRead;
    }
    return bounded(data.subarray(0, length));
  } finally { await file.close(); }
}
/** Read only complete, integrity-checked runs. */
export async function readRun(directory) {
  const marker = JSON.parse(await readBounded(join(directory, 'complete.json')));
  object(marker, ['manifest.json', 'outcomes.jsonl', 'summary.json']);
  const files = {};
  for (const name of Object.keys(marker)) {
    digest(marker[name]); files[name] = await readBounded(join(directory, name));
    if (hash(files[name]) !== marker[name]) fail('Evidence integrity mismatch');
  }
  const lines = files['outcomes.jsonl'].toString();
  if (lines && !lines.endsWith('\n')) fail('Truncated outcomes');
  const run = { manifest: JSON.parse(files['manifest.json']), outcomes: lines ? lines.trimEnd().split('\n').map(line => JSON.parse(line)) : [], summary: JSON.parse(files['summary.json']) };
  validate(run); return run;
}
/** Reject comparisons whose measurement identities differ. */
export function compareRuns(base, candidate, { crossRuntime = false } = {}) {
  validate(base); validate(candidate);
  for (const run of [base, candidate]) if (!run.summary.completed || run.summary.validity !== 'valid') fail('Run is not valid and complete');
  const a = base.manifest; const b = candidate.manifest;
  for (const key of ['artifact', 'workload', 'cacheState', 'instrumentation', 'launch']) if (JSON.stringify(a[key]) !== JSON.stringify(b[key])) fail(`Incompatible ${key}`);
  if (!crossRuntime && JSON.stringify(a.runtime) !== JSON.stringify(b.runtime)) fail('Incompatible runtime');
  return true;
}
