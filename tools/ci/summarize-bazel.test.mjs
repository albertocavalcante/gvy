// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import assert from 'node:assert/strict';
import test from 'node:test';
import { spawnSync } from 'node:child_process';
import { existsSync, mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { summarizeInvocations } from './summarize-bazel.mjs';

const context = { headSha: 'a'.repeat(40), testedSha: 'b'.repeat(40), runId: '123', runAttempt: '2' };
const receipt = (command) => ({ ok: true, complete: true, producer: `bazel-${command}`, context: { ...context, command }, timing: {}, tests: { summaries: [] } });

test('requires one complete build and test from this source, checkout, run and attempt', () => {
  const receipts = [receipt('build'), receipt('test')];
  assert.equal(summarizeInvocations(receipts, context).ok, true);
  for (const field of Object.keys(context)) {
    const bad = structuredClone(receipts);
    bad[1].context[field] = 'stale';
    assert.equal(summarizeInvocations(bad, context).ok, false, field);
  }
  for (const values of [[], [receipts[0]], [...receipts, receipts[0]]]) assert.equal(summarizeInvocations(values, context).ok, false);
});

test('failed or incomplete evidence cannot pass', () => {
  for (const field of ['ok', 'complete']) {
    const receipts = [receipt('build'), receipt('test')];
    receipts[1][field] = false;
    assert.equal(summarizeInvocations(receipts, context).ok, false);
  }
  assert.equal(summarizeInvocations([null], context).ok, false);
});

test('CLI keeps aggregate output outside the audited public directory', (t) => {
  const dir = mkdtempSync(join(tmpdir(), 'gvy-bazel-summary-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const root = join(dir, 'public');
  for (const command of ['build', 'test']) {
    const folder = join(root, command);
    mkdirSync(folder, { recursive: true });
    writeFileSync(join(folder, 'summary.json'), JSON.stringify(receipt(command)));
  }
  const event = join(dir, 'event.json');
  writeFileSync(event, JSON.stringify({ pull_request: { head: { sha: context.headSha } } }));
  const env = { ...process.env, GITHUB_EVENT_PATH: event, GITHUB_SHA: context.testedSha,
    GITHUB_RUN_ID: context.runId, GITHUB_RUN_ATTEMPT: context.runAttempt };
  delete env.GITHUB_STEP_SUMMARY;
  const output = join(dir, 'private-summary', 'invocations.json');
  const run = (path) => spawnSync(process.execPath, [resolve('tools/ci/summarize-bazel.mjs'), root, path], { env, encoding: 'utf8' });
  const result = run(output);
  assert.equal(result.status, 0, result.stderr);
  assert.equal(JSON.parse(readFileSync(output, 'utf8')).ok, true);
  assert.equal(existsSync(join(root, 'invocations.json')), false);
  assert.notEqual(run(join(root, 'invocations.json')).status, 0);
  assert.equal(existsSync(join(root, 'invocations.json')), false);
});
