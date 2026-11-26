// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict';
import { readFile, readdir } from 'node:fs/promises';
import { join } from 'node:path';
import { inventory } from './artifacts.mjs';
import { compareGraph } from './graph.mjs';
import { base, historicalHash, json, policy, versions } from './io.mjs';

// Usage: node compare.mjs <directory containing unpacked row artifact directories> <new result.json>
const [root, resultFile] = process.argv.slice(2);
const read = async (dir, name) => JSON.parse(await readFile(join(dir, name)));
const rows = new Map();
const problems = [];
let identity;
for (const item of await readdir(root, { withFileTypes: true })) {
  if (!item.isDirectory()) continue;
  const dir = join(root, item.name);
  try {
    const source = await read(dir, 'source.json');
    assert.equal(source.base, base);
    assert(Object.hasOwn(versions, source.version) && Object.hasOwn(policy, source.mode));
    assert(['Linux', 'macOS'].includes(source.os));
    const run = [source.head, source.run, source.attempt, source.sourceTreeWithoutProbeSha256];
    if (!identity) identity = run;
    assert.deepEqual(run, identity, 'mixed run, attempt or source');
    assert.deepEqual(await read(dir, 'manifest.json'), await inventory(dir), 'receipt mismatch');
    const key = [source.os, source.version, source.mode].join('/');
    assert(!rows.has(key), 'duplicate row');
    const graph = await read(dir, 'graph.json');
    const result = await read(dir, 'graph-result.json');
    assert.equal(result.historicalReferenceSha256, historicalHash);
    assert.equal(result.resolverSha256, versions[source.version]);
    assert.equal(result.checksum, policy[source.mode]);
    const row = { graph, result, key };
    rows.set(key, row);
    if (source.mode === 'permissive') {
      const cases = await read(dir, 'fixtures/results.json');
      assert.deepEqual(cases.map(c => c.kind), ['valid', 'missing', 'malformed', 'mismatch', 'permissive-missing']);
      for (const c of cases) if (c.verdict !== 'pass') problems.push({ key, case: c.kind, verdict: c.verdict });
    }
  } catch (error) {
    problems.push({ directory: item.name, verdict: 'inconclusive', reason: String(error) });
  }
}
const comparisons = [];
function compare(leftKey, rightKey) {
  const left = rows.get(leftKey);
  const right = rows.get(rightKey);
  if (!left || !right) {
    problems.push({ leftKey, rightKey, verdict: 'inconclusive', reason: 'missing complete full graph' });
    return;
  }
  const diff = compareGraph(left.graph, right.graph);
  const targetsMatch = left.result.targetsSha256 === right.result.targetsSha256;
  const lockBytesMatch = left.result.lockSha256 === right.result.lockSha256;
  const equal = !(diff.added.length || diff.removed.length || diff.conflictsChanged) && targetsMatch && lockBytesMatch;
  comparisons.push({ leftKey, rightKey, verdict: equal ? 'pass' : 'different', targetsMatch, lockBytesMatch, diff });
}
for (const os of ['Linux', 'macOS']) {
  for (const version of Object.keys(versions)) {
    compare([os, version, 'permissive'].join('/'), [os, version, 'strict'].join('/'));
    for (const mode of Object.keys(policy)) {
      if (version !== '2.1.25-M23') compare([os, '2.1.25-M23', mode].join('/'), [os, version, mode].join('/'));
      if (os === 'Linux') compare([os, version, mode].join('/'), ['macOS', version, mode].join('/'));
    }
  }
}
const history = [...rows.values()].map(({ key, result }) => ({
  key, historicalReference: historicalHash, observed: result.historicalAlgorithmSha256, matches: result.historicalMatch,
}));
const verdict = problems.length ? 'incomplete-or-failed-controls' : comparisons.some(c => c.verdict !== 'pass') ? 'different' :
  history.some(h => !h.matches) ? 'historical-drift' : 'pass';
await json(resultFile, { source: identity, verdict, history, comparisons, problems });
console.log(JSON.stringify({ verdict, rows: rows.size, comparisons: comparisons.length, problems: problems.length }));
if (verdict !== 'pass') process.exitCode = 1;
