// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, rename, symlink, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { readAsset, staticServer } from './serve.mjs';

test('an opened file stays bound when its path is replaced and leaf symlinks are refused', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'gvy-static-test-'));
  try {
    const file = join(dir, 'page.html');
    const secret = join(dir, 'secret');
    await writeFile(file, 'original');
    await writeFile(secret, 'not served');
    const result = await readAsset(file, async () => {
      await rename(file, join(dir, 'old'));
      await symlink(secret, file);
    });
    assert.equal(result.bytes.toString(), 'original');
    await assert.rejects(readAsset(file), { code: 'ELOOP' });
  } finally { await rm(dir, { recursive: true, force: true }); }
});

test('serves a directory index and returns the static page with HTTP 404', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'gvy-static-test-'));
  const server = staticServer(dir);
  try {
    await mkdir(join(dir, 'guide'));
    await writeFile(join(dir, 'guide', 'index.html'), 'guide');
    await writeFile(join(dir, '404.html'), 'not found');
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    const base = `http://127.0.0.1:${server.address().port}`;
    const ok = await fetch(`${base}/gvy/guide/`);
    assert.equal(ok.status, 200);
    assert.equal(await ok.text(), 'guide');
    const missing = await fetch(`${base}/gvy/missing/`);
    assert.equal(missing.status, 404);
    assert.equal(await missing.text(), 'not found');
  } finally {
    await new Promise((resolve, reject) => server.close(error => error ? reject(error) : resolve()));
    await rm(dir, { recursive: true, force: true });
  }
});
