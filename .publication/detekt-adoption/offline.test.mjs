// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import test from 'node:test';
import assert from 'node:assert/strict';
import { exclusive, intent, retainOutcome } from './io.mjs';
import { commands, runStages, remainingBudget, failureExit } from './stages.mjs';
import { requirePreserved, verifyBytes, ROOT } from './verify-source.mjs';
import { observeJava, waitForJava } from './observe-java.mjs';

test('intent fsyncs file before directory durability or effect admission', () => {
  const calls = [], io = { openSync: (p, flags, mode) => { calls.push(['open', p, flags, mode]); return p; },
    writeFileSync: (fd, bytes) => calls.push(['write', fd, bytes.length]), fsyncSync: fd => calls.push(['fsync', fd]), closeSync: fd => calls.push(['close', fd]) };
  intent('/evidence/stage', { planned: true }, io);
  const fileSync = calls.findIndex(c => c[0] === 'fsync' && c[1].endsWith('intent.json'));
  const dirSync = calls.findIndex(c => c[0] === 'fsync' && c[1] === '/evidence/stage');
  assert.ok(fileSync >= 0 && fileSync < dirSync);
  assert.deepEqual(calls[0].slice(2), ['wx', 0o600]);
});
test('file fsync failure prevents intent admission', () => {
  let closed = false;
  assert.throws(() => exclusive('/owned', Buffer.from('x'), { openSync: () => 1, writeFileSync() {}, fsyncSync() { throw Error('disk'); }, closeSync() { closed = true; } }), /disk/);
  assert.equal(closed, true);
});
test('raw storage faults retain original outcome and attempt every stream', () => {
  const writes = [], known = { status: 17, signal: null, error: null, lifecycle: { rawComplete: true, cleanupComplete: true } };
  const retained = retainOutcome(known, Buffer.from('out'), Buffer.from('err'), (name, value) => { writes.push([name, value]); if (name === 'stdout') throw Error('full'); });
  assert.equal(writes[0][0], 'command.json');
  assert.ok(writes.some(([name]) => name === 'stderr'));
  assert.equal(retained.status, 17); assert.equal(retained.ok, false);
  assert.deepEqual(writes.find(([name]) => name === 'persistence.json')[1].knownCommand, known);
  assert.ok(writes.some(([name]) => name === 'failure.json'));
});
test('no optional producer follows the first failed fixture stage', async () => {
  const seen = [];
  const result = await runStages({ checkpoint: async () => {}, stage: async name => { seen.push(name); return { ok: name !== 'ordering', status: name === 'ordering' ? 1 : 0 }; } });
  assert.deepEqual(seen, ['format', 'ordering']); assert.equal(result.complete, false); assert.equal(result.exitCode, 1);
});
test('fixed five-stage command plan preserves task selection with mocked execution', async () => {
  const seen = [], result = await runStages({ checkpoint: async () => {}, stage: async name => { seen.push(name); return { ok: true, status: 0 }; } });
  assert.deepEqual(seen, ['format', 'ordering', 'fixture', 'detekt-health', 'formatting-health']); assert.equal(result.productCommitAllowed, false);
  const selected = commands(['--no-daemon', '--max-workers=1'], '/owned/graph', '/owned/evidence');
  assert.deepEqual(selected.ordering.slice(-5), [':spotlessApply', ':tools:lint:analysis-health:lintFix', ':parser:lintFix', ':parser:core:lintFix', '--dry-run']);
  assert.ok(selected['formatting-health'].includes('tools/lint/kotlin-formatting-health.mjs'));
});
test('rollback cannot refresh the original envelope and exit256 cannot become OS0', () => {
  assert.equal(remainingBudget(1000, 10, 500, 1010, 2000), 1000);
  for (const value of [0, 256, -1, null, undefined]) assert.equal(failureExit(value), 75);
});
test('source alias, baseline changes and unrelated tracked changes fail preservation', () => {
  const before = { supportHead: 'head', files: [{ path: 'baseline.xml', sha256: 'old' }, { path: 'allowed.kt', sha256: 'old' }] };
  const receipt = { files: [{ path: '.github/workflows/ci.yml' }, { path: 'allowed.kt' }], allowedFormattingPaths: ['allowed.kt'] };
  const clean = { ...before, untracked: '', changedPaths: ['allowed.kt'] };
  assert.doesNotThrow(() => requirePreserved(before, { ...clean, files: [before.files[0], { path: 'allowed.kt', sha256: 'formatted' }] }, receipt, true));
  assert.throws(() => requirePreserved(before, { ...clean, files: [{ path: 'baseline.xml', sha256: 'new' }, before.files[1]] }, receipt, true), /baseline/);
  assert.throws(() => requirePreserved(before, { ...clean, changedPaths: ['other.kt'] }, receipt, true), /outside/);
  assert.throws(() => verifyBytes(Buffer.from('wrong'), { path: 'fixed.patch', bytes: 5, sha256: '0'.repeat(64) }), /Wrong bound bytes/);
});
test('interruption between stages does not admit another producer', async () => {
  let check = 0, effects = 0;
  await assert.rejects(runStages({ checkpoint: async () => { if (++check === 2) throw Error('SIGTERM'); }, stage: async () => { effects++; return { ok: true, status: 0 }; } }), /SIGTERM/);
  assert.equal(effects, 1);
});
test('Java observation skips foreign UID before inaccessible comm and never reads nonJava environment', () => {
  const reads = [], entries = [{ name: '1' }, { name: '2' }], io = {
    opendirSync: () => ({ readSync: () => entries.shift(), closeSync() {} }),
    statSync: path => ({ uid: path.endsWith('/1') ? 0 : 1000 }),
    openSync: path => { reads.push(path); return path; }, fstatSync: () => ({ isFile: () => true, uid: 1000 }),
    readSync: (fd, out, offset) => { if (offset) return 0; out.write('node\n'); return 5; }, closeSync() {},
  };
  const result = observeJava('a'.repeat(32), ['/jdk/bin/java'], io, 1000);
  assert.equal(result.complete, true); assert.deepEqual(result.owned, []); assert.deepEqual(reads, ['/proc/2/comm']);
});
test('uncertain Java observation blocks success and drain never extends reserve', async () => {
  const bad = await waitForJava('a'.repeat(32), [], () => 500, async () => {}, () => ({ complete: false, owned: [], uncertain: [{ pid: 4 }] }), async () => {});
  assert.equal(bad.ok, false);
  let time = 0;
  const live = await waitForJava('a'.repeat(32), [], () => 500 - time, async () => {}, () => ({ complete: true, owned: [{ pid: 4 }] }), async ms => { time += ms; }, () => time);
  assert.equal(live.ok, false); assert.equal(time, 500); assert.equal(live.signalsSent, false);
});

test('offline sources resolve the actual GitHub checkout layout', () => {
  assert.equal(process.env.GITHUB_ACTIONS, 'true');
  assert.equal(ROOT, process.env.GITHUB_WORKSPACE);
});
