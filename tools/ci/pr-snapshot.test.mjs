// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict';
import test from 'node:test';
import { parseArgs, snapshot, formatSnapshot, ghRequest } from './pr-snapshot.mjs';

const sha = 'a'.repeat(40);
const pr = { number: 42, title: 'Title', draft: false, created_at: '2026-01-01T00:00:00Z', additions: 3, deletions: 2, changed_files: 1, head: { sha, ref: 'feature' }, base: { ref: 'main', sha: 'b'.repeat(40) } };
const page = (nodes = [], next = null) => ({ nodes, pageInfo: { hasNextPage: next !== null, endCursor: next } });
const thread = id => ({ id, path: 'a', line: null, url: 'https://example.test/t', isOutdated: true, isResolved: false, body: 'SECRET' });
const check = id => ({ __typename: 'CheckRun', id, name: id, status: 'COMPLETED', conclusion: 'SUCCESS', detailsUrl: null });
function reader({ threads = [page()], checks = [page()], final = sha, rollup = true } = {}) {
  let ti = 0, ci = 0, rest = 0;
  return async request => {
    if (request.kind === 'rest') return { ...pr, head: { ...pr.head, sha: rest++ ? final : sha } };
    const connection = request.kind === 'threads' ? threads[ti++] : checks[ci++];
    return { data: { repository: { pullRequest: { headRefOid: sha, ...(request.kind === 'threads' ? { reviewThreads: connection } : {}) }, object: { statusCheckRollup: rollup ? { contexts: connection } : null } } } };
  };
}

test('projects all pages, deduplicates, and omits bodies', async () => {
  const first = Array.from({ length: 100 }, (_, i) => thread(`t${i}`));
  const result = await snapshot('owner/repo', 42, reader({ threads: [page(first, 'a'), page([first[0], thread('t100')])], checks: [page(Array.from({ length: 100 }, (_, i) => check(`c${i}`)), 'b'), page([check('c0'), { __typename: 'StatusContext', id: 'two', context: 'ci', state: 'PENDING', targetUrl: null }])] }));
  assert.equal(result.unresolvedThreads.length, 101);
  assert.deepEqual(result.ci.counts, { passed: 100, failed: 0, pending: 1, skipped: 0, unknown: 0 });
  assert.ok(!JSON.stringify(result).includes('SECRET'));
  assert.ok(!formatSnapshot(result).includes('SECRET'));
});

test('unavailable rollup is explicit', async () => {
  const result = await snapshot('owner/repo', 42, reader({ rollup: false }));
  assert.equal(result.ci.available, false);
  assert.equal(result.ci.counts, null);
});

test('rejects moving heads, GraphQL errors and incomplete pagination', async () => {
  await assert.rejects(snapshot('owner/repo', 42, reader({ final: 'c'.repeat(40) })), /head/i);
  const base = reader();
  await assert.rejects(snapshot('owner/repo', 42, async r => r.kind === 'rest' ? base(r) : { errors: [{ message: 'bad' }] }), /GraphQL/);
  await assert.rejects(snapshot('owner/repo', 42, reader({ threads: [page([], 'same'), page([], 'same')] })), /cursor/i);
  await assert.rejects(snapshot('owner/repo', 42, reader({ threads: [null] })), /connection/i);
  const moving = reader();
  await assert.rejects(snapshot('owner/repo', 42, async r => { const value = await moving(r); if (r.kind !== 'rest') value.data.repository.pullRequest.headRefOid = 'c'.repeat(40); return value; }), /head/i);
});

test('validates arguments and confines gh to read requests', async () => {
  for (const args of [[], ['--repo', 'bad', '4'], ['--repo', 'o/r', '0'], ['--repo', 'o/r', '4', '--bad']]) assert.throws(() => parseArgs(args));
  assert.deepEqual(parseArgs(['--repo', 'o/r', '4', '--json']), { repo: 'o/r', number: 4, json: true });
  let call;
  await ghRequest({ kind: 'rest', repo: 'o/r', number: 4 }, async (command, args, options) => { call = { command, args, options }; return JSON.stringify(pr); });
  assert.equal(call.command, 'gh');
  assert.deepEqual(call.args, ['api', '--method', 'GET', 'repos/o/r/pulls/4']);
  assert.ok(call.options.timeout > 0 && call.options.maxBuffer > 0);
});

test('check outcomes are conservative and unknowns stay visible', async () => {
  const nodes = [
    { ...check('neutral'), conclusion: 'NEUTRAL' },
    { ...check('cancelled'), conclusion: 'CANCELLED' },
    { ...check('skipped'), conclusion: 'SKIPPED' },
    { ...check('queued'), status: 'QUEUED', conclusion: null },
    { ...check('new-status'), status: 'FUTURE', conclusion: null },
    { __typename: 'StatusContext', id: 'error', context: 'ci', state: 'ERROR', targetUrl: null },
  ];
  const result = await snapshot('owner/repo', 42, reader({ checks: [page(nodes)] }));
  assert.deepEqual(result.ci.counts, { passed: 0, failed: 2, pending: 1, skipped: 1, unknown: 2 });
  assert.equal(result.ci.contexts.length, 6);
  assert.equal(formatSnapshot(result).split('\n').length, 8);
});

test('rejects missing metadata and page limit exhaustion', async () => {
  const base = reader();
  await assert.rejects(snapshot('owner/repo', 42, async r => r.kind === 'rest' ? { ...pr, title: null } : base(r)), /metadata/i);
  const pages = Array.from({ length: 100 }, (_, i) => page([], `cursor-${i}`));
  await assert.rejects(snapshot('owner/repo', 42, reader({ threads: pages })), /limit/i);
  await assert.rejects(snapshot('owner/repo', 42, reader({ checks: [page([null])] })), /node/i);
});

test('GraphQL uses JSON stdin without shell interpolation', async () => {
  let call;
  await ghRequest({ kind: 'checks', repo: 'o/r', number: 4, sha, cursor: 'cursor' }, async (command, args, options) => { call = { command, args, options }; return '{}'; });
  assert.deepEqual(call.args, ['api', 'graphql', '--input', '-']);
  assert.equal(JSON.parse(call.options.input).variables.sha, sha);
  assert.equal(call.options.shell, undefined);
  assert.ok(!call.options.input.includes('body'));
});

test('rejects malformed GraphQL errors and escapes untrusted display text', async () => {
  const base = reader();
  await assert.rejects(snapshot('owner/repo', 42, async r => {
    const result = await base(r);
    if (r.kind !== 'rest') result.errors = {};
    return result;
  }), /GraphQL/);
  const result = await snapshot('owner/repo', 42, reader({ threads: [page([thread('t')])] }));
  result.title = 'title\nforged line';
  result.unresolvedThreads[0].path = 'a\nforged path';
  const formatted = formatSnapshot(result);
  assert.equal(formatted.split('\n').length, 8);
  assert.ok(formatted.includes('title\\nforged line'));
  assert.ok(formatted.includes('a\\nforged path'));
});
