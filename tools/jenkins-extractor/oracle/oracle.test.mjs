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


async function assertProcessDrained({ pid, pgid, start }, report) {
  const observation = { observedAt: new Date().toISOString(), pid, expectedPgid: pgid, expectedStart: start };
  let survived = false;
  try { process.kill(pid, 0); survived = true; observation.signal0 = 'present'; }
  catch (error) {
    observation.signal0 = error.code;
    if (error.code !== 'ESRCH') { report(observation); throw error; }
  }
  let identityMatches = false;
  if (survived) {
    const observed = spawnSync('/bin/ps', ['-p', String(pid), '-o', 'pgid=,stat=,lstart='], { encoding: 'utf8' });
    const match = observed.stdout.trim().match(/^(\d+)\s+(\S+)\s+(.+)$/);
    Object.assign(observation, { psStatus: observed.status, psOutput: observed.stdout.trim(),
      observedPgid: match ? Number(match[1]) : null, state: match?.[2], start: match?.[3] });
    if (observed.error) { report(observation); throw observed.error; }
    identityMatches = match?.[3] === start;
    if ((observed.status === 1 && !observed.stdout.trim()) ||
        (match && !identityMatches) || match?.[2].startsWith('Z')) survived = false;
  }
  report({ ...observation, identityMatches, nonRunning: !survived });
  try {
    assert.equal(survived, false, 'owned descendant survived process-group drain');
  } finally {
    // Rescue only this test's recorded PID with the same observed start identity.
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

function groupRejection(error, expectedMessage, home) {
  assert.ok(error instanceof Error);
  if (error.code === 'EPERM') {
    assert.equal(error.syscall, 'kill');
    assert.equal(error.retainHome, true);
    assert.equal(error.message, 'kill EPERM; retained owned home: ' + home);
    return 'retained';
  }
  assert.equal(error.code, undefined);
  assert.equal(error.message, expectedMessage);
  assert.notEqual(error.retainHome, true);
  return 'drained';
}

async function awaitGroupAbsent(pgid, report) {
  assert.ok(Number.isSafeInteger(pgid) && pgid > 1);
  const deadline = Date.now() + 2000;
  let state;
  while (true) {
    try { process.kill(-pgid, 0); state = 'present'; }
    catch (error) {
      state = error.code;
      if (state === 'ESRCH') { report({ pgid, groupObservation: state }); return true; }
      if (state !== 'EPERM') { report({ pgid, groupObservation: state }); throw error; }
    }
    if (Date.now() >= deadline) {
      report({ pgid, groupObservation: state, cleanup: 'retained because group absence is unverified' });
      return false;
    }
    await new Promise(resolve => setTimeout(resolve, 20));
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

test('unverifiable process-group errors retain the owned home and publish no result', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'gvy-oracle-test-'));
  let home;
  let published = false;
  // Exercise withHome's existing error boundary; no syscall mocking or process is needed.
  const failure = Object.assign(Error('kill EPERM'), { code: 'EPERM', syscall: 'kill', retainHome: true });
  try {
    await assert.rejects(withHome(dir, async ownedHome => {
      home = ownedHome;
      await writeFile(join(home, 'marker'), 'recovery data');
      throw failure;
    }).then(async result => {
      published = true;
      await writeFile(join(dir, 'result.json'), JSON.stringify({ result }));
    }), error => {
      assert.equal(error, failure);
      assert.equal(groupRejection(error, 'oracle leader exited with live descendants', home), 'retained');
      return true;
    });
    assert.equal(published, false);
    assert.deepEqual(await readdir(dir), [home.slice(dir.length + 1)]);
    assert.equal(await readFile(join(home, 'marker'), 'utf8'), 'recovery data');
    assert.throws(() => groupRejection(Error('unrelated failure'), 'oracle leader exited with live descendants', home));
    assert.throws(() => groupRejection(Object.assign(Error('kill EPERM'), {
      code: 'EPERM', syscall: 'kill',
    }), 'oracle leader exited with live descendants', home));
    assert.throws(() => groupRejection(Object.assign(Error('kill EPERM; retained owned home: /other'), {
      code: 'EPERM', syscall: 'kill', retainHome: true,
    }), 'oracle leader exited with live descendants', home));
    assert.throws(() => groupRejection(Object.assign(Error('kill EACCES'), {
      code: 'EACCES', syscall: 'kill', retainHome: true,
    }), 'oracle leader exited with live descendants', home));
  } finally {
    // This deterministic contract control never starts a process.
    await rm(dir, { recursive: true, force: true });
  }
});

for (const exitMode of ['term', 'success']) {
  test('owned descendants drain before cleanup when leader exits on ' + exitMode, async t => {
    const dir = await mkdtemp(join(tmpdir(), 'gvy-oracle-test-'));
    const report = observation => t.diagnostic(JSON.stringify(observation));
    let home;
    let receipt;
    let cleanupAllowed = false;
    let published = false;
    let outcome;
    try {
      await assert.rejects(withHome(dir, async ownedHome => {
        home = ownedHome;
        const pidFile = join(home, 'descendant.pid');
        const descendant = 'process.on("SIGTERM", () => {});' +
          'const identity=require("node:child_process").execFileSync("/bin/ps",["-p",String(process.pid),"-o","pgid=,lstart="],{encoding:"utf8"}).trim().match(/^(\\d+)\\s+(.+)$/);' +
          'require("node:fs").writeFileSync(' + JSON.stringify(pidFile) + ', JSON.stringify({pid:process.pid,pgid:Number(identity[1]),start:identity[2]}));' +
          'process.send("ready");setTimeout(() => process.exit(0), 15000);setInterval(() => {}, 1000);';
        const leader = 'const child=require("node:child_process").spawn(process.execPath,' +
          JSON.stringify(['-e', descendant]) + ',{stdio:["ignore","ignore","ignore","ipc"]});' +
          'process.on("SIGTERM", () => process.exit(0));' +
          (exitMode === 'success' ? 'child.on("message", () => process.exit(0));' : '') +
          'setTimeout(() => process.exit(0), 15000);setInterval(() => {}, 1000);';
        let processFailure;
        try {
          await runProcess(process.execPath, ['-e', leader], { timeout: 1000, log: join(home, 'process.log') });
        } catch (error) {
          processFailure = error;
          throw error;
        } finally {
          try {
            const identity = JSON.parse(await readFile(pidFile, 'utf8'));
            receipt = JSON.parse(await readFile(join(home, 'process.log.process.json'), 'utf8'));
            report({ home, descendant: identity, leaderPid: receipt.pid, pgid: receipt.pgid,
              groupDrained: receipt.groupDrained, processOk: receipt.ok,
              processFailure: processFailure && { message: processFailure.message, code: processFailure.code } });
            assert.equal(identity.pgid, receipt.pgid);
            await assertProcessDrained(identity, report);
            assert.ok((await readdir(home)).includes('descendant.pid'));
            cleanupAllowed = receipt.groupDrained;
          } catch (error) {
            // A diagnostic or drain assertion must not make withHome erase recovery data.
            error.retainHome = true;
            error.cause ??= processFailure;
            cleanupAllowed = false;
            throw error;
          }
        }
      }).then(async result => {
        published = true;
        await writeFile(join(dir, 'result.json'), JSON.stringify({ result }));
      }), error => {
        outcome = groupRejection(error, exitMode === 'term' ?
          'oracle process timed out' : 'oracle leader exited with live descendants', home);
        report({ outcome, errorCode: error.code, retainedHome: error.retainHome === true, home });
        return true;
      });
      assert.equal(published, false);
      if (outcome === 'retained') {
        assert.equal(receipt.groupDrained, false);
        assert.deepEqual(await readdir(dir), [home.slice(dir.length + 1)]);
        assert.ok((await readdir(home)).includes('descendant.pid'));
        // EPERM remains unknown. Only ESRCH permits this test's later manual cleanup.
        cleanupAllowed = await awaitGroupAbsent(receipt.pgid, report);
        assert.ok(cleanupAllowed, 'owned test group remains unverified; retained recovery data: ' + dir);
      } else {
        assert.equal(receipt.groupDrained, true);
        assert.deepEqual(await readdir(dir), []);
      }
    } finally {
      if (cleanupAllowed) await rm(dir, { recursive: true, force: true });
      else report({ cleanup: 'retained', recoveryDirectory: dir });
    }
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
