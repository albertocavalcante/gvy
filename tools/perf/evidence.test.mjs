// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { execFileSync } from 'node:child_process';
import { writeRun, readRun } from './evidence.mjs';

const digest = 'a'.repeat(64);
const sample = () => ({
  manifest: { schemaVersion: 1, source: { commit: 'b'.repeat(40), dirty: false }, artifact: { sha256: digest }, workload: { id: 'startup', version: 1, corpusSha256: digest, scenarioSha256: digest }, runtime: { os: 'macos', arch: 'arm64', vendor: 'other', majorVersion: 21, heapBytes: 1024, gc: 'g1' }, cacheState: 'cold', instrumentation: 'none', launch: { redactions: true, reproducible: false } },
  outcomes: [{ sequence: 0, operation: 'initialize', plannedNs: 0, sendNs: 1, finishNs: 2, status: 'success', correctness: 'pass' }],
  summary: { completed: true, expectedOutcomes: 1 },
});
async function withDir(fn) { const dir = await fs.mkdtemp(join(tmpdir(), 'gvy-evidence-')); try { await fn(dir); } finally { await fs.rm(dir, { recursive: true, force: true }); } }

test('round trips structurally valid evidence without certifying correctness', async () => withDir(async dir => {
  const run = sample(); run.outcomes[0].status = 'failed'; run.outcomes[0].correctness = 'fail';
  await writeRun(join(dir, 'run'), run); assert.deepEqual(await readRun(join(dir, 'run')), run);
}));
test('rejects canaries in fields and errors without exporting them', async () => withDir(async dir => {
  for (const key of ['argv', 'env', 'property', 'SECRET_CANARY']) {
    const run = sample(); run.manifest.launch[key] = 'SECRET_CANARY';
    await assert.rejects(writeRun(join(dir, 'bad'), run), e => !String(e).includes('SECRET_CANARY'));
  }
  const run = sample(); run.outcomes[0].operation = 'SECRET_CANARY';
  await assert.rejects(writeRun(join(dir, 'bad'), run), e => !String(e).includes('SECRET_CANARY'));
  await assert.rejects(readRun(join(dir, 'SECRET_CANARY')), e => !String(e).includes('SECRET_CANARY'));
  await writeRun(join(dir, 'good'), sample());
  for (const name of await fs.readdir(join(dir, 'good'))) assert.ok(!(await fs.readFile(join(dir, 'good', name), 'utf8')).includes('SECRET_CANARY'));
}));
test('rejects unsupported schema, incomplete or empty runs and invalid counts', async () => withDir(async dir => {
  for (const change of [r => r.manifest.schemaVersion++, r => r.summary.completed = false, r => { r.outcomes = []; r.summary.expectedOutcomes = 0; }, r => r.summary.expectedOutcomes++]) {
    const run = sample(); change(run); await assert.rejects(writeRun(join(dir, 'bad'), run));
  }
}));
test('rejects nonfinite, oversized and duplicate outcomes and unsupported supersession', async () => withDir(async dir => {
  for (const change of [r => r.outcomes[0].finishNs = Infinity, r => r.outcomes[0].operation = 'x'.repeat(300), r => { r.outcomes.push({ ...r.outcomes[0] }); r.summary.expectedOutcomes++; }, r => { r.outcomes[0].status = 'coalesced'; r.outcomes[0].supersededBy = 1; }]) {
    const run = sample(); change(run); await assert.rejects(writeRun(join(dir, 'bad'), run));
  }
  const run = sample(); run.outcomes[0].status = 'coalesced'; run.outcomes[0].supersededBy = 1; run.outcomes.push({ ...sample().outcomes[0], sequence: 1 }); run.summary.expectedOutcomes = 2;
  await writeRun(join(dir, 'good'), run); assert.deepEqual(await readRun(join(dir, 'good')), run);
}));
test('rejects truncated, symlink and FIFO evidence without blocking', async () => withDir(async dir => {
  for (const kind of ['truncated', 'symlink', 'fifo']) {
    const path = join(dir, kind); await writeRun(path, sample()); const target = join(path, 'outcomes.jsonl'); await fs.rm(target);
    if (kind === 'truncated') await fs.writeFile(target, '{');
    if (kind === 'symlink') await fs.symlink(join(path, 'manifest.json'), target);
    if (kind === 'fifo') execFileSync('mkfifo', [target]);
    await assert.rejects(readRun(path));
  }
}));
test('cleans failed publication and permits retry, without overwriting existing runs', async () => withDir(async dir => {
  const path = join(dir, 'run'); const original = fs.writeFile; let calls = 0;
  const mock = test.mock.method(fs, 'writeFile', async (...args) => { if (++calls === 2) throw new Error('SECRET_CANARY'); return original(...args); });
  try { await assert.rejects(writeRun(path, sample()), e => !String(e).includes('SECRET_CANARY')); } finally { mock.mock.restore(); }
  await assert.rejects(fs.stat(path)); await writeRun(path, sample());
  await assert.rejects(writeRun(path, sample())); assert.deepEqual(await readRun(path), sample());
}));
