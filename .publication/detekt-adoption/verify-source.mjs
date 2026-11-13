// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import * as fs from 'node:fs';
import { execFileSync } from 'node:child_process';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { readBounded, sha256, exclusive } from './io.mjs';
export const HERE = dirname(fileURLToPath(import.meta.url));
export const ROOT = resolve(HERE, '../..');
export const readJSON = file => JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(readBounded(file)));
export const check = (condition, message) => { if (!condition) throw Error(message); };
export function git(args, env = {}, input) {
  const clean = Object.fromEntries(Object.entries(process.env).filter(([key]) => !key.startsWith('GIT_')));
  return execFileSync('/usr/bin/git', args, { cwd: ROOT, env: { ...clean, GIT_CONFIG_NOSYSTEM: '1', GIT_CONFIG_GLOBAL: '/dev/null', ...env }, input, maxBuffer: 8 * 1024 * 1024, timeout: 20_000 });
}
export function verifyBytes(bytes, row) {
  check(bytes.length === row.bytes && sha256(bytes) === row.sha256, 'Wrong bound bytes: ' + row.path); return bytes;
}
export function verifyReceipt(receipt) {
  check(receipt.schemaVersion === 1 && receipt.kind === 'detekt-fixture-only-dirty-overlay' && receipt.productCommitAllowed === false && receipt.files.length === 22 && receipt.baselines.length === 16 && receipt.baselineEntries === 231, 'Wrong fixture-only receipt');
  check(receipt.patch.bytes <= 256 * 1024 && receipt.patch.bytes === 177936, 'Wrong closed patch bound');
  check(new Set(receipt.support.map(row => row.path)).size === receipt.support.length && receipt.support.every(row => /^\.publication\/detekt-adoption\/[A-Za-z0-9.-]+$/.test(row.path)), 'Support path escaped exact directory');
  for (const row of [...receipt.files, ...receipt.baselines, ...receipt.support]) check(typeof row.path === 'string' && !row.path.includes('..') && row.mode === '100644' && /^[a-f0-9]{64}$/.test(row.sha256) && Number.isSafeInteger(row.bytes) && row.bytes <= 1024 * 1024, 'Invalid closed file record');
  check(receipt.files[0].path === '.github/workflows/ci.yml', 'Missing sole workflow substitution');
  check(new Set([...receipt.files, ...receipt.baselines].map(row => row.path)).size === 38, 'Duplicated product/baseline identity');
}
export function verifySupport(receipt) {
  verifyReceipt(receipt);
  for (const row of receipt.support) verifyBytes(readBounded(join(ROOT, row.path)), row);
  verifyBytes(readBounded(join(HERE, 'source.patch'), 256 * 1024), receipt.patch);
  verifyBytes(readBounded(join(HERE, 'product-ci.yml')), receipt.files[0]);
  const head = git(['rev-parse', 'HEAD']).toString().trim();
  check(process.env.GITHUB_ACTIONS === 'true' && process.env.GITHUB_SHA === head && /^[a-f0-9]{40}$/.test(head), 'Actual support HEAD must equal real GitHub tested SHA');
  check(git(['rev-list', '--parents', '-n', '1', head]).toString().trim() === `${head} ${receipt.base}`, 'Support commit must be one metadata-only child of exact base');
  for (const path of ['.github/workflows/ci.yml', '.publication/detekt-adoption/receipt.json']) check(sha256(readBounded(join(ROOT, path))) === sha256(git(['show', `HEAD:${path}`])), 'Runtime changed support bootstrap: ' + path);
  const allowed = new Set(['.github/workflows/ci.yml', '.publication/detekt-adoption/receipt.json', ...receipt.support.map(row => row.path)]);
  const changed = git(['diff', '--name-only', receipt.base, head]).toString().trim().split('\n').filter(Boolean);
  check(changed.length === allowed.size && changed.every(path => allowed.has(path)), 'Support commit contains undeclared implementation paths');
  return { supportHead: head, supportParent: receipt.base, githubSha: process.env.GITHUB_SHA, runId: process.env.GITHUB_RUN_ID, runAttempt: process.env.GITHUB_RUN_ATTEMPT,
    executedWorkflowSha256: sha256(readBounded(join(ROOT, '.github/workflows/ci.yml'))), supportPaths: [...allowed].sort() };
}
export function sourceState(receipt, directory, label) {
  const files = [...receipt.files.slice(1), ...receipt.baselines].map(row => { const bytes = readBounded(join(ROOT, row.path)); return { path: row.path, bytes: bytes.length, sha256: sha256(bytes) }; });
  const state = { label, supportHead: git(['rev-parse', 'HEAD']).toString().trim(), files,
    staged: git(['diff', '--cached', '--name-status', 'HEAD']).toString(), worktree: git(['diff', '--name-status']).toString(),
    changedPaths: [...new Set([git(['diff', '--name-only', 'HEAD']).toString(), git(['diff', '--cached', '--name-only', 'HEAD']).toString()].join('\n').split('\n').filter(Boolean))].sort(),
    untracked: git(['ls-files', '--others', '--exclude-standard']).toString() };
  exclusive(join(directory, `source-${label}.json`), state); return state;
}
export function requirePreserved(before, after, receipt, allowFormatting) {
  const allowed = new Set(allowFormatting ? receipt.allowedFormattingPaths : []), product = new Set(receipt.files.slice(1).map(row => row.path));
  check(after.supportHead === before.supportHead && after.untracked === '', 'Support identity changed or untracked source remains');
  check(after.changedPaths.every(path => product.has(path)), 'Tracked source outside declared overlay changed');
  for (const previous of before.files) {
    const current = after.files.find(row => row.path === previous.path);
    check(current && (current.sha256 === previous.sha256 || allowed.has(previous.path)), 'Unexpected source/baseline delta: ' + previous.path);
  }
}
export function projectTree(receipt, directory, indexName) {
  const index = join(directory, indexName), env = { GIT_INDEX_FILE: index };
  check(!fs.existsSync(index), 'Fresh alternate index required');
  git(['read-tree', receipt.base], env);
  for (const row of receipt.files) {
    const bytes = row.path === '.github/workflows/ci.yml' ? readBounded(join(HERE, 'product-ci.yml')) : readBounded(join(ROOT, row.path));
    const blob = git(['hash-object', '-w', '--stdin'], env, bytes).toString().trim();
    git(['update-index', '--add', '--cacheinfo', row.mode, blob, row.path], env);
  }
  return git(['write-tree'], env).toString().trim();
}
export function applyOverlay(receipt) {
  check(git(['status', '--porcelain=v1', '--untracked-files=all']).length === 0, 'Fresh clean support checkout required');
  git(['apply', '--check', '--index', '--exclude=.github/workflows/ci.yml', join(HERE, 'source.patch')]);
  git(['apply', '--index', '--exclude=.github/workflows/ci.yml', join(HERE, 'source.patch')]);
  for (const row of receipt.files.slice(1)) verifyBytes(readBounded(join(ROOT, row.path)), row);
  for (const row of receipt.baselines) verifyBytes(readBounded(join(ROOT, row.path)), row);
}
