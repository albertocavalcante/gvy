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
    assert.equal(result.ready, false);
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
  assert.equal(result.ready, false);
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
    assert.equal(JSON.parse(result.stdout).ready, true);
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
