// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict';
import { createServer } from 'node:net';
import { inspectSockets } from './inspect-sockets.mjs';
import { spawnSync } from 'node:child_process';
import { test } from 'node:test';
import { mkdtemp, realpath, readFile, writeFile, rm, readdir } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { manifest, verifyArtifact, withHome, runProcess, verifyGraph, validateLock, parseLock, captureInput, stageInput, digest } from './oracle.mjs';


async function assertProcessDrained({ pid, start }) {
  let survived = false;
  try { process.kill(pid, 0); survived = true; }
  catch (error) { if (error.code !== 'ESRCH') throw error; }
  let identityMatches = false;
  if (survived) {
    const observed = spawnSync('/bin/ps', ['-p', String(pid), '-o', 'stat=,lstart='], { encoding: 'utf8' });
    const match = observed.stdout.trim().match(/^(\S+)\s+(.+)$/);
    if (observed.error) throw observed.error;
    identityMatches = match?.[2] === start;
    if ((observed.status === 1 && !observed.stdout.trim()) ||
        (match && !identityMatches) || match?.[1].startsWith('Z')) survived = false;
  }
  try {
    assert.equal(survived, false, 'owned descendant survived process-group drain');
  } finally {
    // A regression must not leak the test's own recorded descendant into later CI steps.
    if (survived && identityMatches) {
      process.kill(pid, 'SIGKILL');
      const deadline = Date.now() + 2000;
      while (true) {
        try { process.kill(pid, 0); }
        catch (error) { if (error.code === 'ESRCH') break; throw error; }
        if (Date.now() >= deadline) throw Error('test descendant did not drain: ' + pid);
        await new Promise(resolve => setTimeout(resolve, 20));
      }
    }
  }
}

test('manifest unfolds continuations and preserves optional dependencies', () => {
  assert.deepEqual(manifest('Short-Name: sample\r\nPlugin-Version: 1\r\nPlugin-Dependencies: a:1,\r\n b:2;resolution:=optional\r\n\r\n').dependencies,
    [{ id: 'a', minimumVersion: '1', optional: false }, { id: 'b', minimumVersion: '2', optional: true }]);
});
test('missing and corrupted artifacts fail explicitly', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'gvy-oracle-test-'));
  try {
    const file = join(dir, 'input');
    await assert.rejects(verifyArtifact(file, '0'.repeat(64)), /unavailable/);
    await writeFile(file, 'wrong');
    await assert.rejects(verifyArtifact(file, '0'.repeat(64)), /SHA-256/);
  } finally { await rm(dir, { recursive: true, force: true }); }
});
test('graph rejects missing mandatory dependency and duplicate identities', () => {
  const plugin = { id: 'a', version: '1', dependencies: [{ id: 'b', minimumVersion: '1', optional: false }] };
  assert.throws(() => verifyGraph([plugin]), /missing mandatory/);
  assert.throws(() => verifyGraph([plugin, plugin]), /duplicate/);
  verifyGraph([{ ...plugin, dependencies: [{ id: 'b', minimumVersion: '1', optional: true }] }]);
});
test('unique owned homes are removed on failure and success', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'gvy-oracle-test-'));
  try {
    await assert.rejects(withHome(dir, async home => { await writeFile(join(home, 'marker'), 'x'); throw Error('failed boot'); }), /failed boot/);
    await withHome(dir, async () => {});
    assert.deepEqual(await readdir(dir), []);
  } finally { await rm(dir, { recursive: true, force: true }); }
});
test('failed startup and timed out owned process cannot report success', async () => {
  await assert.rejects(runProcess('/no/such/oracle-java', [], { timeout: 100 }), /unavailable/);
  await assert.rejects(runProcess(process.execPath, ['-e', 'process.exit(2)'], { timeout: 1000 }), /exit 2/);
  await assert.rejects(runProcess(process.execPath, ['-e', 'setTimeout(() => process.exit(0), 15000);setInterval(() => {}, 1000)'], { timeout: 50 }), /timed out/);
});

test('interruption during home preparation removes owned data', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'gvy-oracle-test-'));
  try {
    await assert.rejects(withHome(dir, async home => {
      await writeFile(join(home, 'preparing'), 'x');
      process.kill(process.pid, 'SIGTERM');
      await new Promise(resolve => setTimeout(resolve, 20));
    }), /interrupted/);
    assert.deepEqual(await readdir(dir), []);
  } finally { await rm(dir, { recursive: true, force: true }); }
});
test('resource failure terminates the process and cleans its home', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'gvy-oracle-test-'));
  try {
    let checks = 0;
    await assert.rejects(withHome(dir, async (home, signal) => {
      await runProcess(process.execPath, ['-e', 'setTimeout(() => process.exit(0), 15000);setInterval(() => {}, 1000)'], {
        timeout: 3000, signal, log: join(home, 'process.log'),
        guard: () => { if (++checks > 1) throw Error('resource floor'); },
      });
    }), /resource floor/);
    assert.deepEqual(await readdir(dir), []);
  } finally { await rm(dir, { recursive: true, force: true }); }
});

test('lock validation rejects paths and insecure download identities', () => {
  const plugin = { id: 'sample', version: '1.0', requiredCore: '2.479.3',
    file: 'sample-1.0.hpi', url: 'https://updates.jenkins.io/download/plugins/sample/1.0/sample.hpi', dependencies: [] };
  const lock = { schemaVersion: 1, profile: 'legacy', core: { version: '2.479.3',
    file: 'jenkins-2.479.3.war', url: 'https://get.jenkins.io/war-stable/2.479.3/jenkins.war' }, plugins: [plugin] };
  validateLock(lock);
  assert.throws(() => validateLock({ ...lock, plugins: [{ ...plugin, id: '../escape' }] }), /identity/);
  assert.throws(() => validateLock({ ...lock, plugins: [{ ...plugin, file: '../escape' }] }), /filename/);
  assert.throws(() => validateLock({ ...lock, plugins: [{ ...plugin, url: 'http://example.com/plugin' }] }), /URL/);
  assert.throws(() => validateLock({ ...lock, schemaVersion: 2 }), /unsupported/);
});

for (const exitMode of ['term', 'success']) {
  test('owned descendants drain before cleanup when leader exits on ' + exitMode, async () => {
    const dir = await mkdtemp(join(tmpdir(), 'gvy-oracle-test-'));
    try {
      await assert.rejects(withHome(dir, async home => {
        const pidFile = join(home, 'descendant.pid');
        const descendant = 'process.on("SIGTERM", () => {});' +
          'require("node:fs").writeFileSync(' + JSON.stringify(pidFile) + ', JSON.stringify({pid:process.pid,start:require("node:child_process").execFileSync("/bin/ps",["-p",String(process.pid),"-o","lstart="],{encoding:"utf8"}).trim()}));' +
          'process.send("ready");setTimeout(() => process.exit(0), 15000);setInterval(() => {}, 1000);';
        const leader = 'const child=require("node:child_process").spawn(process.execPath,' +
          JSON.stringify(['-e', descendant]) + ',{stdio:["ignore","ignore","ignore","ipc"]});' +
          'process.on("SIGTERM", () => process.exit(0));' +
          (exitMode === 'success' ? 'child.on("message", () => process.exit(0));' : '') +
          'setTimeout(() => process.exit(0), 15000);setInterval(() => {}, 1000);';
        try {
          await runProcess(process.execPath, ['-e', leader], { timeout: 1000 });
          assert.fail('descendant leak must fail');
        } finally {
          const identity = JSON.parse(await readFile(pidFile, 'utf8'));
          await assertProcessDrained(identity);
          assert.ok((await readdir(home)).includes('descendant.pid'));
        }
      }), exitMode === 'term' ? /timed out/ : /live descendants/);
      assert.deepEqual(await readdir(dir), []);
    } finally { await rm(dir, { recursive: true, force: true }); }
  });
}

test('a swapped compatibility profile is rejected before artifact work', () => {
  const lock = { schemaVersion: 1, profile: 'legacy', core: { version: '2.479.3',
    file: 'jenkins-2.479.3.war', url: 'https://get.jenkins.io/war-stable/2.479.3/jenkins.war' },
    plugins: [{ id: 'sample', version: '1.0', requiredCore: '2.479.3', file: 'sample-1.0.hpi',
      url: 'https://updates.jenkins.io/download/plugins/sample/1.0/sample.hpi', dependencies: [] }] };
  assert.throws(() => parseLock(Buffer.from(JSON.stringify(lock)), 'current'), /requested profile/);
  assert.equal(parseLock(Buffer.from(JSON.stringify(lock)), 'legacy').profile, 'legacy');
});
test('provenance and staged input describe the consumed bytes after a source mutation', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'gvy-oracle-test-'));
  try {
    const source = join(dir, 'source');
    const staged = join(dir, 'staged');
    await writeFile(source, 'original');
    const captured = await captureInput(source);
    await writeFile(source, 'replacement');
    await stageInput(staged, captured);
    assert.equal((await readFile(staged)).toString(), 'original');
    assert.equal(captured.sha256, digest(Buffer.from('original')));
    assert.notEqual(captured.sha256, digest(await readFile(source)));
  } finally { await rm(dir, { recursive: true, force: true }); }
});

test('child working directories stay inside the owned home', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'gvy-oracle-test-'));
  try {
    await withHome(dir, async home => {
      await runProcess(process.execPath, ['-e',
        'require("node:fs").writeFileSync("cwd.json", JSON.stringify({cwd:process.cwd()}))'],
      { cwd: home, timeout: 1000 });
      assert.equal(JSON.parse(await readFile(join(home, 'cwd.json'), 'utf8')).cwd, await realpath(home));
    });
    assert.deepEqual(await readdir(dir), []);
  } finally { await rm(dir, { recursive: true, force: true }); }
});

test('OS socket inspection rejects a real listener and succeeds after closure', async () => {
  const server = createServer();
  try {
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    assert.throws(() => inspectSockets(process.pid), /has Internet sockets/);
  } finally {
    await new Promise((resolve, reject) => server.close(error => error ? reject(error) : resolve()));
  }
  assert.equal(inspectSockets(process.pid).internetSockets, 0);
  assert.throws(() => inspectSockets(-1), /invalid/);
});
