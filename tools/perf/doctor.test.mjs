import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { inspectPrerequisites } from './doctor.mjs';

test('reports bounded capabilities without exposing private inputs', async () => {
  const root = await mkdtemp(join(tmpdir(), 'perf-doctor-'));
  try {
    const artifact = join(root, 'secret-artifact.jar');
    await writeFile(artifact, 'artifact');
    const result = await inspectPrerequisites({ artifact, outputRoot: root, probe: async (tool) => ({ status: tool === 'java' ? 'supported' : 'unavailable' }) });
    assert.equal(result.artifact.status, 'supported');
    assert.match(result.artifact.sha256, /^[0-9a-f]{64}$/);
    assert.equal(result.runtime.status, 'supported');
    assert.equal(result.jfr.status, 'unavailable');
    assert.equal(result.output.writable, 'supported');
    assert.equal(JSON.stringify(result).includes(root), false);
    assert.equal(JSON.stringify(result).includes('secret-artifact'), false);
  } finally { await rm(root, { recursive: true, force: true }); }
});

test('missing artifact and failed probe are explicit and do not throw raw errors', async () => {
  const result = await inspectPrerequisites({ artifact: '/private/missing-secret', outputRoot: '/private/missing-secret', probe: async () => { throw new Error('secret'); } });
  assert.equal(result.artifact.status, 'unavailable');
  assert.equal(result.runtime.status, 'unknown');
  assert.equal(result.output.writable, 'unavailable');
  assert.equal('ready' in result, false);
  assert.equal(JSON.stringify(result).includes('secret'), false);
});

test('CLI probes fake tools and emits no tool output or private paths', async () => {
  const { chmod } = await import('node:fs/promises');
  const { spawnSync } = await import('node:child_process');
  const root = await mkdtemp(join(tmpdir(), 'perf-doctor-cli-'));
  try {
    const artifact = join(root, 'private.jar');
    await writeFile(artifact, 'test');
    for (const tool of ['java', 'jfr']) {
      const executable = join(root, tool);
      await writeFile(executable, '#!/bin/sh\nprintf "secret-stdout"\nprintf "secret-stderr" >&2\n');
      await chmod(executable, 0o700);
    }
    const result = spawnSync(process.execPath, ['tools/perf/doctor.mjs', '--json', '--artifact', artifact, '--output-root', root], { env: { PATH: root }, encoding: 'utf8', timeout: 10000 });
    assert.equal(result.status, 0);
    assert.equal(JSON.parse(result.stdout).runtime.status, 'supported');
    assert.equal(result.stdout.includes('secret'), false);
    assert.equal(result.stdout.includes(root), false);
    assert.equal(result.stderr, '');
  } finally { await rm(root, { recursive: true, force: true }); }
});

test('CLI reports unavailable tools and exits nonzero without disclosing inputs', async () => {
  const { spawnSync } = await import('node:child_process');
  const root = await mkdtemp(join(tmpdir(), 'perf-doctor-missing-'));
  try {
    const artifact = join(root, 'private.jar');
    await writeFile(artifact, 'test');
    const result = spawnSync(process.execPath, ['tools/perf/doctor.mjs', '--json', '--artifact', artifact, '--output-root', root], { env: { PATH: root }, encoding: 'utf8', timeout: 10000 });
    const report = JSON.parse(result.stdout);
    assert.equal(result.status, 1);
    assert.equal(report.runtime.status, 'unavailable');
    assert.equal(report.jfr.status, 'unavailable');
    assert.equal(result.stdout.includes(root), false);
    assert.equal(result.stderr, '');
  } finally { await rm(root, { recursive: true, force: true }); }
});

test('artifact probe rejects symlinks and named pipes without blocking', async () => {
  const { symlink } = await import('node:fs/promises');
  const { execFileSync } = await import('node:child_process');
  const root = await mkdtemp(join(tmpdir(), 'perf-doctor-special-'));
  try {
    const artifact = join(root, 'artifact');
    await writeFile(artifact, 'data');
    await symlink(artifact, join(root, 'link'));
    execFileSync('mkfifo', [join(root, 'fifo')]);
    for (const name of ['link', 'fifo']) {
      const result = await inspectPrerequisites({ artifact: join(root, name), outputRoot: root, probe: async () => ({ status: 'supported' }) });
      assert.notEqual(result.artifact.status, 'supported');
    }
  } finally { await rm(root, { recursive: true, force: true }); }
});

test('availability does not claim artifact compatibility or operational readiness', async () => {
  const root = await mkdtemp(join(tmpdir(), 'perf-doctor-claims-'));
  try {
    const artifact = join(root, 'not-a-jar');
    await writeFile(artifact, 'not a jar');
    const result = await inspectPrerequisites({ artifact, outputRoot: root, probe: async () => ({ status: 'supported' }) });
    assert.equal('ready' in result, false);
    assert.equal(result.artifact.compatibility, 'unknown');
    assert.equal(result.jfr.recording, 'unknown');
    assert.equal(result.output.capacity, 'unknown');
    assert.equal(result.buildSlot, 'unknown');
  } finally { await rm(root, { recursive: true, force: true }); }
});

test('probe deadline settles even if a descendant retains output pipes', async () => {
  const { probeTool } = await import('./doctor.mjs');
  const { chmod, readFile } = await import('node:fs/promises');
  const { createRequire, syncBuiltinESMExports } = await import('node:module');
  const { once } = await import('node:events');
  const childProcess = createRequire(import.meta.url)('node:child_process');
  const root = await mkdtemp(join(tmpdir(), 'perf-doctor-pipe-'));
  const realTimeout = globalThis.setTimeout;
  const realSpawn = childProcess.spawn;
  let leader;
  let deadline;
  let watchdog;
  let controlledTimer;
  let descendant;
  try {
    const program = join(root, 'java');
    const pidFile = join(root, 'descendant.pid');
    const release = join(root, 'release');
    await writeFile(program, `#!/bin/sh\nwhile [ ! -f '${release}' ]; do /bin/sleep 0.02; done\n/bin/sleep 30 &\nprintf '%s' "$!" >'${pidFile}'\nexit 0\n`);
    await chmod(program, 0o700);
    let exited;
    childProcess.spawn = (...args) => {
      leader = realSpawn(...args);
      exited = once(leader, 'exit').then((value) => ({ value }), (error) => ({ error }));
      return leader;
    };
    syncBuiltinESMExports();
    globalThis.setTimeout = (callback, delay, ...args) => {
      if (delay !== 300) return realTimeout(callback, delay, ...args);
      deadline = () => callback(...args);
      controlledTimer = realTimeout(() => {}, 60_000);
      return controlledTimer;
    };
    let resultPromise;
    try {
      resultPromise = probeTool('java', { executable: program, timeoutMs: 300, environment: { PATH: '/bin' } });
    } finally {
      globalThis.setTimeout = realTimeout;
      childProcess.spawn = realSpawn;
      syncBuiltinESMExports();
    }
    let resultSettled = false;
    resultPromise.then(() => { resultSettled = true; }, () => { resultSettled = true; });
    assert.ok(deadline, 'probe deadline was not registered');
    assert.ok(exited, 'probe child was not spawned');
    const readiness = (async () => {
      // Exceed the old 300 ms deadline before permitting the fixture to start its descendant.
      await new Promise((resolve) => realTimeout(resolve, 400));
      await writeFile(release, 'ready');
      const exit = await exited;
      if (exit.error) throw exit.error;
      assert.deepEqual(exit.value, [0, null], 'probe leader did not exit successfully');
      descendant = Number(await readFile(pidFile, 'utf8'));
      assert.ok(descendant > 1);
    })();
    await Promise.race([
      readiness,
      new Promise((_, reject) => { watchdog = realTimeout(() => reject(new Error('fixture readiness timed out')), 5000); }),
    ]);
    clearTimeout(watchdog);
    assert.doesNotThrow(() => process.kill(descendant, 0), 'descendant exited before deadline');
    assert.equal(resultSettled, false, 'probe settled before deadline');
    deadline();
    const result = await Promise.race([
      resultPromise,
      new Promise((_, reject) => { watchdog = realTimeout(() => reject(new Error('probe did not settle')), 5000); }),
    ]);
    clearTimeout(watchdog);
    assert.equal(result.status, 'unknown');
    let alive = true;
    for (let attempt = 0; attempt < 50 && alive; attempt++) {
      try { process.kill(descendant, 0); } catch (error) { if (error.code === 'ESRCH') alive = false; else throw error; }
      if (alive) await new Promise((resolve) => realTimeout(resolve, 20));
    }
    assert.equal(alive, false, 'owned descendant survived the probe timeout');
  } finally {
    clearTimeout(watchdog);
    clearTimeout(controlledTimer);
    globalThis.setTimeout = realTimeout;
    childProcess.spawn = realSpawn;
    syncBuiltinESMExports();
    if (leader?.pid) { try { process.kill(-leader.pid, 'SIGKILL'); } catch (error) { if (error.code !== 'ESRCH') throw error; } }
    await rm(root, { recursive: true, force: true });
  }
});

test('injected JVM options are not inherited by tool probes', async () => {
  const { probeTool } = await import('./doctor.mjs');
  const { chmod } = await import('node:fs/promises');
  const root = await mkdtemp(join(tmpdir(), 'perf-doctor-env-'));
  try {
    const program = join(root, 'java');
    await writeFile(program, '#!/bin/sh\ntest -z "$JAVA_TOOL_OPTIONS$JDK_JAVA_OPTIONS$_JAVA_OPTIONS"\n');
    await chmod(program, 0o700);
    const result = await probeTool('java', { executable: program, environment: { PATH: '/bin', JAVA_TOOL_OPTIONS: 'secret' } });
    assert.equal(result.status, 'unknown');
    assert.equal(result.reason, 'inherited_jvm_options');
  } finally { await rm(root, { recursive: true, force: true }); }
});
