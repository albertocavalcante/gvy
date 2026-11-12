import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { writeRun, readRun, compareRuns } from './evidence.mjs';

const digest = 'a'.repeat(64);
const sample = () => ({
  manifest: { schemaVersion: 1, source: { commit: 'b'.repeat(40), dirty: false }, artifact: { sha256: digest }, workload: { id: 'editing', version: '1', corpusSha256: digest, scenarioSha256: digest }, runtime: { os: 'darwin', arch: 'arm64', vendor: 'jdk', version: '21', heapBytes: 1024, gc: 'g1' }, cacheState: 'cold', instrumentation: 'none', launch: { redactions: true, reproducible: false } },
  outcomes: [{ sequence: 0, operation: 'initialize', plannedNs: 0, sendNs: 1, finishNs: 2, status: 'success', correctness: 'pass' }],
  summary: { validity: 'valid', completed: true, expectedOutcomes: 1 },
});
async function withDir(fn) { const dir = await mkdtemp(join(tmpdir(), 'gvy-evidence-')); try { await fn(dir); } finally { await rm(dir, { recursive: true, force: true }); } }

test('round trips complete evidence and compares compatible runs', async () => withDir(async dir => {
  await writeRun(join(dir, 'a'), sample()); await writeRun(join(dir, 'b'), sample());
  assert.deepEqual(await readRun(join(dir, 'a')), sample());
  assert.doesNotThrow(() => compareRuns(sample(), sample()));
}));
test('rejects unknown launch inputs and secret canaries without writing them', async () => withDir(async dir => {
  for (const key of ['argv', 'env', 'property']) {
    const run = sample(); run.manifest.launch[key] = 'SECRET_CANARY';
    await assert.rejects(writeRun(join(dir, key), run));
  }
}));
test('rejects incompatible identities and schema', () => {
  for (const change of [r => r.manifest.schemaVersion++, r => r.manifest.artifact.sha256 = 'c'.repeat(64), r => r.manifest.workload.scenarioSha256 = 'c'.repeat(64)]) {
    const other = sample(); change(other); assert.throws(() => compareRuns(sample(), other));
  }
});
test('rejects incomplete, truncated, nonfinite and oversized evidence', async () => withDir(async dir => {
  const run = sample(); run.outcomes[0].finishNs = Infinity; await assert.rejects(writeRun(join(dir, 'bad'), run));
  const incomplete = sample(); incomplete.summary.completed = false; assert.throws(() => compareRuns(sample(), incomplete));
  const tooLarge = sample(); tooLarge.outcomes[0].operation = 'x'.repeat(300); await assert.rejects(writeRun(join(dir, 'large'), tooLarge));
  await writeRun(join(dir, 'truncated'), sample()); await writeFile(join(dir, 'truncated', 'outcomes.jsonl'), '{');
  await assert.rejects(readRun(join(dir, 'truncated')));
  await assert.rejects(readRun(join(dir, 'missing')));
}));
test('rejects duplicate sequences, failed correctness and unsupported coalescing', () => {
  const duplicate = sample(); duplicate.outcomes.push({ ...duplicate.outcomes[0] }); duplicate.summary.expectedOutcomes = 2;
  assert.throws(() => compareRuns(duplicate, sample()));
  const failed = sample(); failed.outcomes[0].correctness = 'fail'; assert.throws(() => compareRuns(failed, sample()));
  const coalesced = sample(); coalesced.outcomes[0].status = 'coalesced'; coalesced.summary.validity = 'invalid'; assert.throws(() => compareRuns(coalesced, sample()));
});
test('requires explicit cross-runtime comparison and rejects long paths', async () => withDir(async dir => {
  const other = sample(); other.manifest.runtime.version = '22';
  assert.throws(() => compareRuns(sample(), other));
  assert.equal(compareRuns(sample(), other, { crossRuntime: true }), true);
  await assert.rejects(writeRun(join(dir, 'x'.repeat(1100)), sample()));
}));
test('does not overwrite an existing run', async () => withDir(async dir => {
  const path = join(dir, 'run'); await writeRun(path, sample());
  await assert.rejects(writeRun(path, sample())); assert.deepEqual(await readRun(path), sample());
}));
