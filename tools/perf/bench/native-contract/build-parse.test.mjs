// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { verifyBuildInputs } from './build-parse.mjs';

const recipe = path.join(path.dirname(fileURLToPath(import.meta.url)), 'build-parse.mjs');

test('build source identities are bounded SHA-256 values', () => {
  const hashes = verifyBuildInputs();
  assert.deepEqual(Object.keys(hashes), ['manifest', 'lock', 'sourceLock', 'binarySource', 'inputs', 'recipe']);
  for (const hash of Object.values(hashes)) assert.match(hash, /^[a-f0-9]{64}$/);
});

test('compiler overrides fail before creating a target directory', () => {
  const result = spawnSync(process.execPath, [recipe, '/unused-native-build-target'], {
    encoding: 'utf8', env: { ...process.env, RUSTFLAGS: '-C opt-level=0' },
  });
  assert.equal(result.status, 1);
  assert.match(result.stderr, /NATIVE_BUILD_ENVIRONMENT_OVERRIDE/);
});
