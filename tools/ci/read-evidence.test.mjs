import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { readEvidenceBytes, readEvidenceText } from './read-evidence.mjs';

test('reads exact original bytes at the limit without decoding valid or invalid UTF-8', (t) => {
  const dir = mkdtempSync(join(tmpdir(), 'gvy-read-evidence-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const file = join(dir, 'evidence');
  const bytes = Buffer.from([0xef, 0xbf, 0xbd, 0xff, 0x00]);
  writeFileSync(file, bytes);
  assert.deepEqual(readEvidenceBytes(file, bytes.length), bytes);
  assert.throws(() => readEvidenceText(file, bytes.length), /UTF-8/);
  writeFileSync(file, 'literal \ufffd');
  assert.equal(readEvidenceText(file, 1024), 'literal \ufffd');
  writeFileSync(file, bytes);
  assert.throws(() => readEvidenceBytes(file, bytes.length - 1), /exceeds/);
  writeFileSync(file, '');
  assert.equal(readEvidenceBytes(file, 1).length, 0);
});

test('refuses a symlink instead of reading a different private evidence target', t => {
  const dir = mkdtempSync(join(tmpdir(), 'gvy-read-evidence-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const target = join(dir, 'target');
  const link = join(dir, 'link');
  writeFileSync(target, 'private synthetic canary');
  symlinkSync(target, link);
  assert.throws(() => readEvidenceBytes(link, 1024));
});

test('rejects missing, nonregular and over-limit files before reading unbounded content', (t) => {
  const dir = mkdtempSync(join(tmpdir(), 'gvy-read-evidence-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  assert.throws(() => readEvidenceBytes(join(dir, 'missing'), 1), /ENOENT/);
  const folder = join(dir, 'folder');
  mkdirSync(folder);
  assert.throws(() => readEvidenceBytes(folder, 100), /regular file/);
  if (process.platform !== 'win32') {
    const fifo = join(dir, 'fifo');
    execFileSync('mkfifo', [fifo]);
    assert.throws(() => readEvidenceBytes(fifo, 100), /regular file/);
  }
  const large = join(dir, 'large');
  writeFileSync(large, Buffer.alloc(128 * 1024));
  assert.throws(() => readEvidenceBytes(large, 64 * 1024), /exceeds/);
  for (const limit of [0, -1, Infinity, 0.5, 1024 ** 3]) assert.throws(() => readEvidenceBytes(large, limit), /byte limit/);
});
