// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { EventEmitter } from 'node:events';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { PassThrough, Writable } from 'node:stream';
import test from 'node:test';
import { expectedContractDigest, prepareJunitBatch, runJunitPython, validateJunitResponse } from './junit-evidence.mjs';

const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const xml = '<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase classname="C" name="one"/></testsuite>';
const expected = [['C', 'one', 'passed', 1]];
const identity = { artifactSha256: sha(xml), expectedContractSha256: expectedContractDigest('report', expected),
  counts: [1, 0, 0, 0], duplicateCaseRows: 0 };
const row = { index: 0, reason: 'ok', ...identity, counts: [1, 0, 0, 0], duplicateCaseRows: 0 };
const encode = value => Buffer.from(JSON.stringify(value) + '\n');
const result = reports => ({ schemaVersion: 1, status: 'compared', ok: true, reports });

test('matches independent Python ASCII serialization for DEL, BMP, astral and lone surrogate units', () => {
  const cases = [
    ['\x7f', '["\\u007f",[["C","one","passed",1]]]'],
    ['雪', '["\\u96ea",[["C","one","passed",1]]]'],
    ['😀', '["\\ud83d\\ude00",[["C","one","passed",1]]]'],
    ['\ud800', '["\\ud800",[["C","one","passed",1]]]'],
  ];
  for (const [id, canonical] of cases) assert.equal(expectedContractDigest(id, expected), sha(canonical));
});

test('uses original bytes and a copied expectation inventory without echoing paths', t => {
  const dir = mkdtempSync(join(tmpdir(), 'gvy-junit-intake-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const path = join(dir, 'private-canary.xml');
  const bytes = Buffer.from([0xff, 0x00, 0xef, 0xbf, 0xbd]);
  writeFileSync(path, bytes);
  const rows = [['C', 'one', 'passed', 1]];
  const request = prepareJunitBatch([{ path, reportId: 'report', expected: rows }]);
  rows[0][1] = 'changed-later';
  const wire = JSON.parse(request.input);
  assert.deepEqual(Buffer.from(wire.reports[0].xmlBase64, 'base64'), bytes);
  assert.equal(wire.reports[0].expected[0][1], 'one');
  assert.equal(request.identities[0].artifactSha256, sha(bytes));
  assert(!request.input.includes('private-canary'));
});

test('rejects invalid and aggregate plans before producing a partial batch', t => {
  const dir = mkdtempSync(join(tmpdir(), 'gvy-junit-plan-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const path = join(dir, 'one.xml'); writeFileSync(path, xml);
  const plan = { path, reportId: 'report', expected };
  for (const plans of [[], [{ ...plan, extra: true }], [{ ...plan, expected: [['C', 'one', 'passed', true]] }],
    [{ ...plan, reportId: '\ud800' }], [plan, { ...plan, path: join(dir, 'private-missing-canary') }]]) {
    assert.throws(() => prepareJunitBatch(plans), error => !String(error).includes('private-missing-canary'));
  }
  const large = { ...plan, expected: [['C', 'one', 'passed', 6000]] };
  assert.throws(() => prepareJunitBatch([large, large]), /input_limit/);
});

test('accepts a matching report and preserves a genuine comparison mismatch', () => {
  assert.deepEqual(validateJunitResponse(encode(result([row])), 0, [identity]), result([row]));
  const mismatch = { schemaVersion: 1, status: 'compared', ok: false, reports: [{ ...row, reason: 'case_mismatch' }] };
  assert.deepEqual(validateJunitResponse(encode(mismatch), 1, [identity]), mismatch);
});

test('rejects missing, duplicate, foreign and reordered report identities', () => {
  for (const reports of [[], [row, row], [{ ...row, index: 1 }],
    [{ ...row, artifactSha256: '0'.repeat(64) }], [{ ...row, expectedContractSha256: '0'.repeat(64) }]]) {
    assert.throws(() => validateJunitResponse(encode(result(reports)), 0, [identity]), /invalid_parser_result/);
  }
});

test('rejects result status, outcome and numeric contradictions', () => {
  const badRows = [{ ...row, reason: 'unknown' }, { ...row, counts: [1, 1, 1, 0] },
    { ...row, counts: [true, 0, 0, 0] }, { ...row, counts: [0, 0, 0, 0] },
    { ...row, duplicateCaseRows: 1 }, { ...row, duplicateCaseRows: 2 }, { ...row, extra: 'private' }];
  for (const invalid of badRows) assert.throws(() => validateJunitResponse(encode(result([invalid])), 0, [identity]));
  assert.throws(() => validateJunitResponse(encode(result([row])), 1, [identity]));
  assert.throws(() => validateJunitResponse(encode({ ...result([row]), ok: false }), 1, [identity]));
  for (const status of ['invalid_input', 'limit_exceeded']) {
    const invalid = { schemaVersion: 1, status, ok: false, reports: [] };
    assert.deepEqual(validateJunitResponse(encode(invalid), 2, [identity]), invalid);
    assert.throws(() => validateJunitResponse(encode(invalid), 0, [identity]));
  }
});

test('rejects duplicate JSON keys, trailing documents, noncanonical output and invalid UTF-8', () => {
  const valid = encode(result([row]));
  for (const bytes of [Buffer.from(valid.toString().replace('"ok":true', '"ok":false,"ok":true')),
    Buffer.concat([valid, valid]), Buffer.concat([valid, Buffer.from(' ')]), Buffer.from([0xff]),
    Buffer.alloc(512 * 1024 + 1)]) assert.throws(() => validateJunitResponse(bytes, 0, [identity]));
});

function controlledChild(action) {
  const child = new EventEmitter();
  const writes = [];
  child.stdout = new PassThrough(); child.stderr = new PassThrough();
  child.stdin = new Writable({ write(chunk, _encoding, callback) { writes.push(Buffer.from(chunk)); callback(); } });
  child.kills = 0; child.unreferenced = false;
  child.kill = signal => { assert.equal(signal, 'SIGKILL'); child.kills++; return true; };
  child.unref = () => { child.unreferenced = true; };
  let calls = 0;
  const spawnProcess = (executable, args, options) => {
    calls++; assert.equal(executable, process.execPath);
    assert.deepEqual(args.slice(0, 2), ['-I', '-S']);
    assert(args[2].endsWith('junit_contract_batch.py'));
    assert.deepEqual(options.stdio, ['pipe', 'pipe', 'pipe']);
    assert(!options.shell);
    queueMicrotask(() => child.emit('spawn'));
    setImmediate(() => action(child));
    return child;
  };
  return { child, writes, spawnProcess, calls: () => calls };
}

test('direct-child seam invokes one isolated parser and preserves exact stdin/stdout and exit', async () => {
  const fixture = controlledChild(child => { child.stdout.write('fixed-result'); child.emit('close', 1, null); });
  const input = Buffer.from('original private input');
  const result = await runJunitPython(input, { python: process.execPath }, fixture.spawnProcess);
  assert.equal(fixture.calls(), 1); assert.deepEqual(Buffer.concat(fixture.writes), input);
  assert.equal(result.kind, 'result'); assert.equal(result.exitCode, 1);
  assert.equal(result.stdout.toString(), 'fixed-result'); assert.equal(result.directChildAndPipesSettled, true);
});

test('stderr, output overflow, signal and unexpected exit discard partial parser output', async () => {
  for (const [action, reason] of [
    [child => { child.stdout.write('partial-private'); child.stderr.write('stderr-private'); child.emit('close', 0, null); }, 'unexpected_stderr'],
    [child => { child.stdout.write(Buffer.alloc(512 * 1024 + 1)); child.emit('close', 0, null); }, 'output_limit'],
    [child => child.emit('close', null, 'SIGTERM'), 'parser_exit'],
    [child => child.emit('close', 7, null), 'parser_exit'],
    [child => { child.emit('error', new Error('private-after-spawn')); child.emit('close', 0, null); }, 'process_error'],
  ]) {
    const fixture = controlledChild(action);
    const result = await runJunitPython(Buffer.from('{}'), { python: process.execPath }, fixture.spawnProcess);
    assert.equal(result.kind, reason); assert(!JSON.stringify(result).includes('private'));
  }
});

test('deadline bounds settlement even when direct child or pipe closure is not observed', { timeout: 5000 }, async () => {
  const fixture = controlledChild(() => {});
  const result = await runJunitPython(Buffer.from('{}'), { python: process.execPath, timeoutMs: 1 }, fixture.spawnProcess);
  assert.equal(result.kind, 'timeout'); assert.equal(result.directChildAndPipesSettled, false);
  assert.equal(fixture.child.kills, 1); assert.equal(fixture.child.unreferenced, true);
  assert(fixture.child.stdout.destroyed && fixture.child.stderr.destroyed && fixture.child.stdin.destroyed);
});

test('abort and spawn exception remain fixed outcomes without arbitrary tool diagnostics', async () => {
  const controller = new AbortController();
  const fixture = controlledChild(child => { controller.abort(); child.emit('close', 0, null); });
  const aborted = await runJunitPython(Buffer.from('{}'), { python: process.execPath, signal: controller.signal }, fixture.spawnProcess);
  assert.equal(aborted.kind, 'aborted');
  const failed = await runJunitPython(Buffer.from('{}'), { python: process.execPath }, () => { throw new Error('private-start'); });
  assert.deepEqual(failed, { kind: 'start_failed' });
});
