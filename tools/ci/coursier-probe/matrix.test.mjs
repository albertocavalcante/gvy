// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdir, mkdtemp, readFile, rm, symlink, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { inventory } from './artifacts.mjs';
import { base, historicalHash, json, policy, versions } from './io.mjs';

test('matrix requires all rows, content receipts, controls and both OSes', async t => {
  const scratch = await mkdtemp(join(tmpdir(), 'coursier-matrix-'));
  t.after(() => rm(scratch, { recursive: true, force: true }));
  const root = join(scratch, 'rows');
  const report = join(scratch, 'report.json');
  const graph = { dependencies: [{ coord: 'demo:a:1', file: null, direct: [], transitive: [], exclusions: [] }], conflicts: {} };
  for (const os of ['Linux', 'macOS']) {
    for (const version of Object.keys(versions)) {
      for (const mode of Object.keys(policy)) {
        const dir = join(root, [os, version, mode].join('-'));
        await mkdir(dir, { recursive: true });
        await json(join(dir, 'source.json'), { base, os, version, mode, head: 'synthetic', run: '1', attempt: '1', sourceTreeWithoutProbeSha256: 'fixture' });
        await json(join(dir, 'graph.json'), graph);
        await json(join(dir, 'graph-result.json'), {
          historicalReferenceSha256: historicalHash, historicalAlgorithmSha256: historicalHash, historicalMatch: true,
          resolverSha256: versions[version], checksum: policy[mode], targetsSha256: 'targets', lockSha256: 'lock',
        });
        if (mode === 'permissive') {
          await mkdir(join(dir, 'fixtures'));
          await json(join(dir, 'fixtures/results.json'), ['valid', 'missing', 'malformed', 'mismatch', 'permissive-missing'].map(kind => ({ kind, verdict: 'pass' })));
        }
        await json(join(dir, 'manifest.json'), await inventory(dir));
      }
    }
  }
  async function compare(expectedCode) {
    const result = spawnSync(process.execPath, [fileURLToPath(new URL('./compare.mjs', import.meta.url)), root, report], { encoding: 'utf8' });
    assert.equal(result.status, expectedCode, result.stderr);
    return JSON.parse(await readFile(report));
  }
  const pass = await compare(0);
  assert.equal(pass.verdict, 'pass');
  assert.equal(pass.comparisons.length, 20);
  const changed = join(root, 'macOS-2.1.27-strict');
  await json(join(changed, 'graph.json'), { ...graph, dependencies: [] });
  assert.equal((await compare(1)).verdict, 'incomplete-or-failed-controls');
  await json(join(changed, 'manifest.json'), await inventory(changed));
  assert.equal((await compare(1)).verdict, 'different');
  await rm(changed, { recursive: true });
  assert.equal((await compare(1)).verdict, 'incomplete-or-failed-controls');
});
test('artifact admission rejects binary, symbolic links and signed access URLs', async t => {
  const dir = await mkdtemp(join(tmpdir(), 'coursier-audit-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const file = join(dir, 'log.txt');
  await writeFile(file, Buffer.from([0]));
  await assert.rejects(inventory(dir), /binary/);
  await writeFile(file, 'https://example.test/path?sig=test');
  await assert.rejects(inventory(dir), /credential/);
  await writeFile(file, 'ordinary evidence');
  assert.equal((await inventory(dir)).length, 1);
  await symlink(file, join(dir, 'copy.txt'));
  await assert.rejects(inventory(dir), /unexpected link/);
});
