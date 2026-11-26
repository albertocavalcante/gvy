// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import { spawnSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';
import { isDeepStrictEqual } from 'node:util';

const HELP = 'Usage: node tools/ci/pr-snapshot.mjs --repo owner/name PR [--json]';
const MAX_PAGES = 100;
const escapeControls = value => JSON.stringify(String(value)).slice(1, -1);
const QUERY = {
  threads: `query($owner:String!,$name:String!,$number:Int!,$cursor:String) {
    repository(owner:$owner,name:$name) { pullRequest(number:$number) { headRefOid
      reviewThreads(first:100,after:$cursor) { nodes { id path line isOutdated isResolved
        comments(first:1) { nodes { url } } } pageInfo { hasNextPage endCursor } } } } }`,
  checks: `query($owner:String!,$name:String!,$number:Int!,$sha:GitObjectID!,$cursor:String) {
    repository(owner:$owner,name:$name) { pullRequest(number:$number) { headRefOid }
      object(oid:$sha) { ... on Commit { statusCheckRollup { contexts(first:100,after:$cursor) {
        nodes { __typename ... on CheckRun { id name status conclusion detailsUrl }
          ... on StatusContext { id context state targetUrl } } pageInfo { hasNextPage endCursor } } } } } } }`,
};

export function parseArgs(args) {
  if (args.length === 1 && args[0] === '--help') return { help: true };
  const json = args.includes('--json');
  const rest = args.filter(x => x !== '--json');
  if (args.filter(x => x === '--json').length > 1 || rest.length !== 3 || rest[0] !== '--repo' ||
      !/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(rest[1]) || !/^[1-9]\d*$/.test(rest[2]) || !Number.isSafeInteger(Number(rest[2]))) throw new Error(HELP);
  return { repo: rest[1], number: Number(rest[2]), json };
}

function runGh(command, args, options) {
  const result = spawnSync(command, args, { ...options, encoding: 'utf8' });
  if (result.error || result.status !== 0) {
    const detail = result.error?.message || result.stderr?.trim() || result.status;
    throw new Error(`gh request failed: ${escapeControls(detail).slice(0, 500)}`);
  }
  return result.stdout;
}
export async function ghRequest(request, run = runGh) {
  const options = { timeout: 30_000, maxBuffer: 8 * 1024 * 1024 };
  let args;
  if (request.kind === 'rest') args = ['api', '--method', 'GET', `repos/${request.repo}/pulls/${request.number}`];
  else {
    const [owner, name] = request.repo.split('/');
    args = ['api', 'graphql', '--input', '-'];
    options.input = JSON.stringify({ query: QUERY[request.kind], variables: { owner, name, number: request.number, cursor: request.cursor, ...(request.kind === 'checks' ? { sha: request.sha } : {}) } });
  }
  return JSON.parse(await run('gh', args, options));
}

function required(condition, message) { if (!condition) throw new Error(message); }
function metadata(pr, number) {
  required(pr && pr.number === number && typeof pr.title === 'string' && typeof pr.draft === 'boolean' &&
    ['open', 'closed'].includes(pr.state) && typeof pr.merged === 'boolean' && !(pr.state === 'open' && pr.merged) &&
    typeof pr.created_at === 'string' && ['additions', 'deletions', 'changed_files'].every(k => Number.isSafeInteger(pr[k]) && pr[k] >= 0) &&
    /^[a-f0-9]{40}$/i.test(pr.head?.sha ?? '') && typeof pr.head?.ref === 'string' &&
    /^[a-f0-9]{40}$/i.test(pr.base?.sha ?? '') && typeof pr.base?.ref === 'string', 'Missing PR metadata');
  return { number, title: pr.title, state: pr.merged ? 'merged' : pr.state, draft: pr.draft, openedAt: pr.created_at, head: { ref: pr.head.ref, sha: pr.head.sha }, base: { ref: pr.base.ref, sha: pr.base.sha }, additions: pr.additions, deletions: pr.deletions, fileCount: pr.changed_files };
}

async function collect(kind, repo, number, sha, read) {
  let cursor = null;
  const cursors = new Set(), nodes = new Map();
  for (let i = 0; i < MAX_PAGES; i++) {
    const response = await read({ kind, repo, number, sha, cursor });
    required(response && (response.errors === undefined || (Array.isArray(response.errors) && response.errors.length === 0)) && response.data?.repository, 'GraphQL response unavailable or contains errors');
    const repository = response.data.repository;
    required(repository.pullRequest?.headRefOid === sha, 'PR head changed during scan');
    if (kind === 'checks') {
      required(repository.object, 'Missing commit');
      if (repository.object.statusCheckRollup === null) { required(i === 0, 'Rollup disappeared during scan'); return null; }
    }
    const connection = kind === 'threads' ? repository.pullRequest.reviewThreads : repository.object.statusCheckRollup?.contexts;
    required(connection && Array.isArray(connection.nodes) && typeof connection.pageInfo?.hasNextPage === 'boolean', 'Missing connection or page info');
    for (const node of connection.nodes) {
      required(node && typeof node.id === 'string' && node.id.length > 0, 'Missing node ID');
      if (nodes.has(node.id)) {
        required(isDeepStrictEqual(nodes.get(node.id), node), 'Conflicting duplicate node');
      } else nodes.set(node.id, node);
    }
    if (!connection.pageInfo.hasNextPage) return [...nodes.values()];
    const next = connection.pageInfo.endCursor;
    required(typeof next === 'string' && next.length > 0 && !cursors.has(next), 'Missing or repeated pagination cursor');
    cursors.add(next); cursor = next;
  }
  throw new Error('Pagination page limit reached');
}

export function classifyCheck(node) {
  if (node.__typename === 'CheckRun') {
    required(typeof node.name === 'string' && typeof node.status === 'string' && (node.conclusion === null || typeof node.conclusion === 'string') && (node.detailsUrl === null || typeof node.detailsUrl === 'string'), 'Missing check metadata');
    if (['QUEUED', 'IN_PROGRESS', 'WAITING', 'PENDING', 'REQUESTED'].includes(node.status)) return 'pending';
    if (node.status !== 'COMPLETED') return 'unknown';
    if (node.conclusion === 'SUCCESS') return 'passed';
    if (['FAILURE', 'CANCELLED', 'TIMED_OUT', 'ACTION_REQUIRED', 'STARTUP_FAILURE', 'STALE'].includes(node.conclusion)) return 'failed';
    if (node.conclusion === 'SKIPPED') return 'skipped';
    return 'unknown';
  }
  if (node.__typename === 'StatusContext') {
    required(typeof node.context === 'string' && typeof node.state === 'string' && (node.targetUrl === null || typeof node.targetUrl === 'string'), 'Missing status metadata');
    return ({ SUCCESS: 'passed', FAILURE: 'failed', ERROR: 'failed', PENDING: 'pending' })[node.state] ?? 'unknown';
  }
  throw new Error('Unknown check type');
}

export async function snapshot(repo, number, read = ghRequest) {
  const first = metadata(await read({ kind: 'rest', repo, number }), number);
  const sha = first.head.sha;
  const threads = await collect('threads', repo, number, sha, read);
  const checks = await collect('checks', repo, number, sha, read);
  const unresolvedThreads = threads.filter(t => { required(typeof t.isResolved === 'boolean', 'Missing thread resolution'); return !t.isResolved; }).map(t => {
    const url = t.url ?? t.comments?.nodes?.[0]?.url;
    required(typeof t.path === 'string' && (t.line === null || Number.isInteger(t.line)) && typeof t.isOutdated === 'boolean' && typeof url === 'string', 'Missing thread metadata');
    return { id: t.id, path: t.path, line: t.line, url, isOutdated: t.isOutdated };
  });
  const counts = checks === null ? null : { passed: 0, failed: 0, pending: 0, skipped: 0, unknown: 0 };
  if (checks) for (const check of checks) counts[classifyCheck(check)]++;
  const last = metadata(await read({ kind: 'rest', repo, number }), number);
  required(last.head.sha === sha, 'PR head changed before output');
  required(last.state === first.state, 'PR state changed before output');
  const contexts = checks?.map(check => check.__typename === 'CheckRun'
    ? { type: check.__typename, id: check.id, name: check.name, status: check.status, conclusion: check.conclusion, url: check.detailsUrl }
    : { type: check.__typename, id: check.id, context: check.context, state: check.state, url: check.targetUrl }) ?? null;
  return { repo, ...first, ci: { available: checks !== null, counts, contexts }, unresolvedThreads };
}

export function formatSnapshot(value) {
  const ci = value.ci.available ? Object.entries(value.ci.counts).map(([k, v]) => `${k}=${v}`).join(' ') : 'unavailable';
  return [`${value.repo} #${value.number}: ${escapeControls(value.title)}`, `Head: ${escapeControls(value.head.ref)} ${value.head.sha}`, `Base: ${escapeControls(value.base.ref)} ${value.base.sha}`, `State: ${value.state}; draft: ${value.draft}; opened: ${escapeControls(value.openedAt)}`, `Changes: +${value.additions} -${value.deletions}, ${value.fileCount} files`, `CI observed: ${ci}`, `Unresolved threads: ${value.unresolvedThreads.length}`, `Thread metadata (first 3; use --json for all): ${JSON.stringify(value.unresolvedThreads.slice(0, 3))}`].join('\n');
}

async function main() {
  try {
    const args = parseArgs(process.argv.slice(2));
    if (args.help) { console.log(HELP); return; }
    const result = await snapshot(args.repo, args.number);
    console.log(args.json ? JSON.stringify(result) : formatSnapshot(result));
  } catch (error) { console.error(error.message); process.exitCode = 1; }
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) await main();
