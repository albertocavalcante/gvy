// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { readRegularBytes, verifyFiles, verifyFixtureBytes } from './source-files.mjs';

const sha256 = text => createHash('sha256').update(text).digest('hex');
function fixture(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'gvy-native-pin-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  fs.mkdirSync(path.join(root, 'src'));
  fs.writeFileSync(path.join(root, 'src/parser.c'), 'known generated parser\n');
  return root;
}

test('source pin accepts exact bytes and returns their relative identity', t => {
  const actual = verifyFiles(fixture(t), { 'src/parser.c': sha256('known generated parser\n') });
  assert.deepEqual(actual, [{ path: 'src/parser.c', sha256: sha256('known generated parser\n'), bytes: 23 }]);
});

test('source pin rejects changed generated-parser bytes', t => {
  assert.throws(() => verifyFiles(fixture(t), { 'src/parser.c': '0'.repeat(64) }), /SOURCE_DIGEST_MISMATCH/);
});

test('source pin rejects missing files and traversal', t => {
  const root = fixture(t);
  assert.throws(() => verifyFiles(root, { 'src/scanner.c': '0'.repeat(64) }), /SOURCE_FILE_INVALID/);
  assert.throws(() => verifyFiles(root, { '../parser.c': '0'.repeat(64) }), /SOURCE_PIN_INVALID/);
});

test('source pin rejects symbolic links rather than hashing their target', t => {
  const root = fixture(t);
  fs.symlinkSync('parser.c', path.join(root, 'src/scanner.c'));
  assert.throws(() => verifyFiles(root, { 'src/scanner.c': sha256('known generated parser\n') }), /SOURCE_FILE_INVALID/);
});

test('bounded read rejects an input larger than its explicit limit', t => {
  const root = fixture(t);
  assert.throws(() => readRegularBytes(path.join(root, 'src/parser.c'), 8), /SOURCE_FILE_INVALID/);
});

test('embedded fixture identity rejects stale same-length bytes', () => {
  verifyFixtureBytes({ valid: [97, 98] }, { valid: Buffer.from('ab') });
  assert.throws(() => verifyFixtureBytes({ valid: [98, 97] }, { valid: Buffer.from('ab') }), /FIXTURE_IDENTITY_MISMATCH/);
});
