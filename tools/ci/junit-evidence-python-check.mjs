// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, isAbsolute, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { compareJunitEvidence, prepareJunitBatch, runJunitPython } from './junit-evidence.mjs';

// Explicit targeted suite: the generic *.test.mjs gate does not need Python.
const python = process.env.GVY_JUNIT_PYTHON;
assert(typeof python === 'string' && isAbsolute(python), 'Set GVY_JUNIT_PYTHON to the reviewed absolute Python executable');

function fixture(t, name = 'one') {
  const dir = mkdtempSync(join(tmpdir(), 'gvy-junit-python-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const path = join(dir, 'private-report.xml');
  writeFileSync(path, `<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase classname="C" name="${name}"/></testsuite>`);
  return { path, reportId: 'private-report', expected: [['C', name, 'passed', 1]] };
}

test('one actual isolated Python invocation compares a batch including Unicode identities', async t => {
  const plans = [fixture(t), fixture(t, '雪😀\x7f')];
  const compared = await compareJunitEvidence(plans, { python });
  assert.equal(compared.kind, 'compared');
  assert.equal(compared.result.ok, true);
  assert.equal(compared.result.reports.length, 2);
  assert.equal(compared.executionAttestation, 'not_provided');
  assert.equal(compared.processTreeCleanup, 'not_assessed');
  assert(!JSON.stringify(compared).includes('private-report'));
});

test('actual mismatch and malformed XML never become a successful comparison', async t => {
  const plan = fixture(t);
  const mismatch = await compareJunitEvidence([{ ...plan, expected: [['C', 'missing', 'passed', 1]] }], { python });
  assert.equal(mismatch.kind, 'compared'); assert.equal(mismatch.result.ok, false);
  assert.equal(mismatch.result.reports[0].reason, 'case_mismatch');
  writeFileSync(plan.path, '<broken>');
  const malformed = await compareJunitEvidence([plan], { python });
  assert.equal(malformed.kind, 'compared'); assert.equal(malformed.result.ok, false);
  assert.equal(malformed.result.reports[0].reason, 'invalid_xml');
});

test('parser spawn failure and pre-abort are fixed failures without private diagnostics', async t => {
  const plan = fixture(t);
  const missing = await compareJunitEvidence([plan], { python: join(tmpdir(), 'missing-private-python-canary') });
  assert.equal(missing.kind, 'start_failed');
  assert(!JSON.stringify(missing).includes('canary'));
  const controller = new AbortController(); controller.abort();
  assert.deepEqual(await runJunitPython(prepareJunitBatch([plan]).input, { python, signal: controller.signal }), { kind: 'aborted' });
});

test('actual CLI preserves comparison exits and rejects ambiguous input plans privately', t => {
  const plan = fixture(t);
  const file = join(dirname(plan.path), 'private-plan.json');
  const cli = fileURLToPath(new URL('./junit-evidence.mjs', import.meta.url));
  const run = () => spawnSync(process.execPath, [cli, '--python', python, '--plan', file],
    { encoding: 'utf8', timeout: 20_000, killSignal: 'SIGKILL', maxBuffer: 512 * 1024 });
  writeFileSync(file, JSON.stringify([plan]) + '\n');
  let output = run(); assert.equal(output.status, 0, output.stderr);
  assert.equal(JSON.parse(output.stdout).result.ok, true); assert.equal(output.stderr, '');
  assert(!output.stdout.includes('private-report'));
  writeFileSync(file, JSON.stringify([{ ...plan, expected: [['C', 'missing', 'passed', 1]] }]) + '\n');
  output = run(); assert.equal(output.status, 1); assert.equal(JSON.parse(output.stdout).result.ok, false);
  writeFileSync(file, (JSON.stringify([plan]) + '\n').replace('"reportId":', '"reportId":"private-duplicate","reportId":'));
  output = run(); assert.equal(output.status, 2); assert.deepEqual(JSON.parse(output.stdout), { kind: 'input_failed' });
  assert(!output.stdout.includes('private'));
});
