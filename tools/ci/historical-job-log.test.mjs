// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';

const platform = { skip: process.platform === 'win32' ? 'POSIX Bash/jq capture fixture' : false };

// Exercise the documented command itself. A local executable replaces gh, so no account,
// network request, repository credential or historical Actions retention is needed.
const guide = readFileSync(new URL('../../docs/ci-diagnostics.md', import.meta.url), 'utf8');
const section = guide.split('## Preserve the requested CI attempt\n')[1].split('\n## Required readiness')[0];
const blocks = [...section.matchAll(/```bash\n([\s\S]*?)```/g)].map(match => match[1]);
assert.equal(blocks.length, 3, 'Review the regression fixture when the documented command structure changes');
const declarations = blocks[0].split('\ngh api ')[0];
const capture = blocks[2];
const request = { repository: 'albertocavalcante/gvy', run: 43237436692, attempt: 1, job: 131249849696,
  head: '1d1b0d72aeafcdbd276ca530219f15e4f83c3b9d' };
const original = Buffer.from([0x54, 0x69, 0x6d, 0x65, 0x6f, 0x75, 0x74, 13, 10, 0, 27, 91, 51, 49, 109, 255, 0, 10]);
const later = Buffer.from('Later retry passed\n');
const run = () => ({ repository: { full_name: request.repository }, id: request.run, run_attempt: 1,
  head_sha: request.head, status: 'completed', conclusion: 'failure' });
const job = () => ({ id: request.job, run_id: request.run, run_attempt: 1, head_sha: request.head,
  status: 'completed', conclusion: 'failure', name: 'Package VSIX' });

const fakeGh = `#!${process.execPath}
import { appendFileSync, readFileSync } from 'node:fs';
const state = JSON.parse(readFileSync(process.env.GVY_LOG_FIXTURE, 'utf8'));
const args = process.argv.slice(2);
appendFileSync(process.env.GVY_LOG_CALLS, JSON.stringify(args) + '\\n');
const response = state.responses[args[1]];
if (args[0] !== 'api' || !response ||
    (response.log ? args.length !== 3 || args[2] !== '--allow-escape-sequences' : args.length !== 2)) {
  process.exitCode = 79;
} else {
  process.stdout.write(Buffer.from(response.base64, 'base64'));
  process.exitCode = response.exit ?? 0;
}
`;

function exercise({ runMetadata = run(), jobMetadata = job(), missing = null, logExit = 0 } = {}) {
  const directory = mkdtempSync(join(tmpdir(), 'gvy-job-log-test-'));
  try {
    mkdirSync(join(directory, 'bin'));
    symlinkSync(process.execPath, join(directory, 'bin', 'node'));
    const executable = join(directory, 'bin', 'gh');
    // Node treats this extensionless ESM executable according to this private fixture package.
    writeFileSync(join(directory, 'bin', 'package.json'), '{"type":"module"}\n');
    writeFileSync(executable, fakeGh); chmodSync(executable, 0o700);
    const prefix = `repos/${request.repository}/actions/`;
    const response = value => ({ base64: Buffer.from(JSON.stringify(value)).toString('base64') });
    const responses = {
      [`${prefix}runs/${request.run}/attempts/1`]: response(runMetadata),
      [`${prefix}jobs/${request.job}`]: response(jobMetadata),
      [`${prefix}jobs/${request.job}/logs`]: { log: true, base64: original.toString('base64'), exit: logExit },
      // A same-name successful retry exists. Selecting it would produce decisively different bytes.
      [`${prefix}jobs/131252364808/logs`]: { log: true, base64: later.toString('base64') },
    };
    if (missing) delete responses[`${prefix}${missing}`];
    const state = join(directory, 'fixture.json'); writeFileSync(state, JSON.stringify({ responses }));
    const calls = join(directory, 'calls.jsonl');
    const child = spawnSync('/bin/bash', ['--noprofile', '--norc', '-c', `${declarations}\n${capture}`], {
      cwd: directory, timeout: 10_000, killSignal: 'SIGKILL', maxBuffer: 1024 * 1024,
      env: { PATH: `${join(directory, 'bin')}:${process.env.PATH}`, LANG: 'C', NODE_OPTIONS: '--max-old-space-size=128',
        GVY_LOG_FIXTURE: state, GVY_LOG_CALLS: calls },
    });
    assert.ifError(child.error);
    assert.equal(child.signal, null, 'The bounded fixture must finish without being killed');
    assert.ok(existsSync(calls), `Fixture did not run: ${child.stderr.toString('utf8')}`);
    const evidence = join(directory, '.agent/out', `ci-${request.run}-attempt-1-job-${request.job}`);
    const log = name => existsSync(join(evidence, name)) ? readFileSync(join(evidence, name)) : null;
    return { status: child.status, stdout: child.stdout.toString('utf8'), log: log('job.log'),
      partial: log('job.log.partial'), calls: readFileSync(calls, 'utf8').trimEnd().split('\n').map(line => JSON.parse(line)) };
  } finally { rmSync(directory, { recursive: true, force: true }); }
}

test('historical capture preserves failed-attempt original bytes instead of the later retry', platform, () => {
  const result = exercise();
  assert.equal(result.status, 0);
  assert.deepEqual(result.log, original);
  assert.equal(result.partial, null);
  assert.deepEqual(JSON.parse(result.stdout), { sha256: createHash('sha256').update(original).digest('hex'),
    bytes: original.length, nulBytes: 2 });
  assert.deepEqual(result.calls.map(args => args[1]), [
    `repos/${request.repository}/actions/runs/${request.run}/attempts/1`,
    `repos/${request.repository}/actions/jobs/${request.job}`,
    `repos/${request.repository}/actions/jobs/${request.job}/logs`,
  ]);
  assert.ok(result.calls.every(args => args[0] === 'api'));
});

for (const [kind, fields] of [['run', ['repository', 'id', 'run_attempt', 'head_sha', 'status']],
  ['job', ['id', 'run_id', 'run_attempt', 'head_sha', 'status']]]) {
  for (const field of fields) for (const mutation of ['missing', 'wrong']) {
    test(`historical capture rejects ${mutation} ${kind}.${field} before requesting logs`, platform, () => {
      const metadata = kind === 'run' ? run() : job();
      if (mutation === 'missing') delete metadata[field];
      else if (field === 'repository') metadata.repository.full_name = 'another/repository';
      else if (field === 'status') metadata.status = 'in_progress';
      else if (field === 'head_sha') metadata.head_sha = 'f'.repeat(40);
      else metadata[field] += 1;
      const result = exercise(kind === 'run' ? { runMetadata: metadata } : { jobMetadata: metadata });
      assert.notEqual(result.status, 0);
      assert.equal(result.log, null);
      assert.equal(result.partial, null);
      assert.ok(result.calls.every(args => !args[1].endsWith('/logs')));
    });
  }
}

for (const endpoint of [`runs/${request.run}/attempts/1`, `jobs/${request.job}`]) {
  test(`missing historical metadata ${endpoint} cannot fall back to the latest run`, platform, () => {
    const result = exercise({ missing: endpoint });
    assert.notEqual(result.status, 0);
    assert.equal(result.log, null);
    assert.ok(result.calls.every(args => !args[1].endsWith('/logs')));
  });
}

test('failed retrieval retains incomplete original bytes without publishing a completed log', platform, () => {
  const result = exercise({ logExit: 22 });
  assert.notEqual(result.status, 0);
  assert.deepEqual(result.partial, original);
  assert.equal(result.log, null);
  assert.equal(result.stdout, '');
});
