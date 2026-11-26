// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { loadArchive, runContext } from './archive.mjs';
import { compareGraph } from './graph.mjs';
import { historicalHash, json, policy, versions } from './io.mjs';
import { verifyRow } from './receipts.mjs';

// Requires a clean reviewed source checkout and GitHub read access. Verified
// archives are downloaded once and retained for independent replay.
const [root, resultFile, run, attempt] = process.argv.slice(2);
assert(root && resultFile && run && attempt, 'usage: compare.mjs ARCHIVE_DIR REPORT_JSON RUN_ID ATTEMPT');
await mkdir(root, { recursive: true });
const ctx = runContext(run, attempt);
const identity = { head: ctx.head, tree: ctx.tree, run, attempt, conclusion: ctx.conclusion };
const rows = new Map();
const problems = [];
const archives = {};
for (const os of ['Linux', 'macOS']) {
  for (const version of Object.keys(versions)) {
    for (const mode of Object.keys(policy)) {
      const key = [os, version, mode].join('/');
      const name = ['coursier', os, version, mode, run, attempt].join('-');
      try {
        const { files, id, digest } = await loadArchive(root, ctx, name);
        archives[key] = { id, digest };
        rows.set(key, { key, ...await verifyRow(files, ctx, os, version, mode) });
      } catch (error) {
        problems.push({ key, verdict: 'inconclusive-or-failed', reason: String(error) });
      }
    }
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
  const inputsMatch = JSON.stringify(left.inputs) === JSON.stringify(right.inputs);
  const equal = !(diff.added.length || diff.removed.length || diff.conflictsChanged) && targetsMatch && lockBytesMatch;
  if (!inputsMatch) problems.push({ leftKey, rightKey, verdict: 'inconclusive', reason: 'different effective resolver inputs' });
  comparisons.push({ leftKey, rightKey, verdict: !inputsMatch ? 'inconclusive' : equal ? 'pass' : 'different', inputsMatch, targetsMatch, lockBytesMatch, diff });
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
const verdict = problems.length || ctx.conclusion !== 'success' ? 'incomplete-or-failed-controls' : comparisons.some(c => c.verdict !== 'pass') ? 'different' :
  history.some(h => !h.matches) ? 'historical-drift' : 'pass';
await json(resultFile, { source: identity, archives, verdict, history, comparisons, problems });
console.log(JSON.stringify({ verdict, rows: rows.size, comparisons: comparisons.length, problems: problems.length }));
if (verdict !== 'pass') process.exitCode = 1;
