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
  const { chmod } = await import('node:fs/promises');
  const root = await mkdtemp(join(tmpdir(), 'perf-doctor-pipe-'));
  try {
    const program = join(root, 'java');
    const pidFile = join(root, 'descendant.pid');
    await writeFile(program, `#!/bin/sh\n/bin/sleep 10 &\nprintf '%s' "$!" > '${pidFile}'\nexit 0\n`);
    await chmod(program, 0o700);
    const start = Date.now();
    const result = await probeTool('java', { executable: program, timeoutMs: 300, environment: { PATH: '/bin' } });
    assert.equal(result.status, 'unknown');
    assert.ok(Date.now() - start < 1500);
    const { readFile } = await import('node:fs/promises');
    const pid = Number(await readFile(pidFile, 'utf8'));
    assert.ok(pid > 1);
    let alive = true;
    for (let attempt = 0; attempt < 50 && alive; attempt++) {
      try { process.kill(pid, 0); } catch (error) { if (error.code === 'ESRCH') alive = false; else throw error; }
      if (alive) await new Promise((resolve) => setTimeout(resolve, 20));
    }
    if (alive) process.kill(pid, 'SIGKILL'); // Test owns this exact PID.
    assert.equal(alive, false, 'owned descendant survived the probe timeout');
  } finally { await rm(root, { recursive: true, force: true }); }
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
