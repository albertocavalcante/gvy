// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, posix } from 'node:path';
import { performance } from 'node:perf_hooks';
import test from 'node:test';

const prefix = 'tools/perf/bench/corpus/';
const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');

test('real autocrlf checkout preserves frozen bytes without changing neighboring text rules', { timeout: 65000 }, async t => {
  // This is an ephemeral Git plumbing fixture, not a publication clone/worktree.
  // Raw objects model the already committed bytes; checkout-index performs the conversion under test.
  const directory = await fs.mkdtemp(join(tmpdir(), 'gvy-corpus-checkout-'));
  t.after(() => fs.rm(directory, { recursive: true, force: true }));
  const emptyConfig = join(directory, 'empty-config');
  await fs.writeFile(emptyConfig, '');
  const emptyTemplate = join(directory, 'empty-template');
  await fs.mkdir(emptyTemplate);
  const environment = {
    ...Object.fromEntries(Object.entries(process.env).filter(([name]) => !/^GIT_/i.test(name))),
    GIT_CONFIG_NOSYSTEM: '1', GIT_ATTR_NOSYSTEM: '1', GIT_CONFIG_GLOBAL: emptyConfig, GIT_TEMPLATE_DIR: emptyTemplate,
  };
  const deadline = performance.now() + 60000;
  function git(args, input) {
    const remaining = Math.floor(deadline - performance.now());
    assert.ok(remaining > 0, 'checkout fixture exceeded its total command budget');
    const result = spawnSync('git', ['-c', 'core.autocrlf=true', '-c', `core.attributesFile=${emptyConfig}`, ...args], {
      cwd: directory, env: environment, input, timeout: Math.min(5000, remaining), maxBuffer: 1024 * 1024, shell: false, windowsHide: true,
    });
    assert.ifError(result.error);
    assert.equal(result.signal, null);
    assert.equal(result.status, 0, result.stderr?.toString('utf8'));
    return result.stdout;
  }
  git(['init', '--quiet', '--object-format=sha1']);
  const manifestBytes = await fs.readFile(new URL('./manifest.json', import.meta.url));
  const manifest = JSON.parse(manifestBytes.toString('utf8'));
  const imported = [...manifest.fixtures, ...manifest.sources.flatMap(source => source.license.files)];
  assert.equal(imported.length, 21);
  assert.equal(new Set(imported.map(entry => entry.path)).size, 21);
  const pinned = await Promise.all(imported.map(async entry => ({
    path: prefix + entry.path, bytes: await fs.readFile(new URL(entry.path, import.meta.url)), sha256: entry.sha256,
  })));
  pinned.push({ path: 'LICENSES/MIT.txt', bytes: await fs.readFile(new URL('../../../../LICENSES/MIT.txt', import.meta.url)),
    sha256: 'b05785f9f18e6716bab63424b11454513b9943a222595b70411009202fc592b5' });
  pinned.push({ path: prefix + 'manifest.json', bytes: manifestBytes,
    sha256: 'cf83cf5b600d73517332d4b45ac206b1f40158c0ccbb7109daaddb1115a63d07' });
  const neighbors = ['LICENSES/Neighbor.txt', 'LICENSES/unlisted/MIT.txt', prefix + 'neighbor.json', prefix + 'unlisted/manifest.json', ...imported.map(entry =>
    prefix + posix.join(posix.dirname(entry.path), 'unlisted', posix.basename(entry.path)))];
  assert.equal(new Set(neighbors).size, 25);
  const ordinary = Buffer.from('ordinary text\nsecond line\n');
  const attributes = await fs.readFile(new URL('../../../../.gitattributes', import.meta.url));
  await fs.writeFile(join(directory, '.gitattributes'), attributes);
  const entries = [{ path: '.gitattributes', bytes: attributes }, ...pinned,
    ...neighbors.map(path => ({ path, bytes: ordinary }))];
  for (const entry of pinned) assert.equal(sha256(entry.bytes), entry.sha256, entry.path);
  function indexEntry(path, bytes) {
    const object = git(['hash-object', '-w', '--no-filters', '--stdin'], bytes).toString('ascii').trim();
    assert.match(object, /^[a-f0-9]{40}$/);
    return `100644 ${object}\t${path}\n`;
  }
  git(['update-index', '--index-info'], Buffer.from(entries.map(entry => indexEntry(entry.path, entry.bytes)).join('')));
  git(['checkout-index', '--all', '--force']);

  await t.test('all 21 imports, canonical MIT and the manifest retain their frozen hashes', async () => {
    const mismatches = [];
    for (const entry of pinned) {
      const actual = sha256(await fs.readFile(join(directory, entry.path)));
      if (actual !== entry.sha256) mismatches.push({ path: entry.path, expected: entry.sha256, actual });
    }
    assert.deepEqual(mismatches, []);
  });
  await t.test('unlisted neighbors of every imported path and canonical MIT still become CRLF', async () => {
    for (const path of neighbors) assert.deepEqual(await fs.readFile(join(directory, path)), Buffer.from('ordinary text\r\nsecond line\r\n'), path);
  });
  await t.test('an exact imported path also preserves deliberately mixed CRLF and LF bytes', async () => {
    const path = pinned[0].path;
    const mixed = Buffer.from('first CRLF\r\nsecond LF\nthird CRLF\r\n');
    git(['update-index', '--index-info'], Buffer.from(indexEntry(path, mixed)));
    await fs.unlink(join(directory, path));
    git(['checkout-index', '--force', '--', path]);
    assert.deepEqual(await fs.readFile(join(directory, path)), mixed);
  });
});
