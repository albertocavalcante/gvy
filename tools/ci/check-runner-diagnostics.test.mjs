import assert from 'node:assert/strict';
import { chmodSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import test from 'node:test';
import { observeRunnerDiagnostics } from './check-runner-diagnostics.mjs';

const sourceSha = 'a'.repeat(40);
const testedSha = 'b'.repeat(40);
const context = { repository: 'albertocavalcante/gvy', runId: '123', runAttempt: 2, headSha: sourceSha, testedSha };
const base = '/repos/albertocavalcante/gvy';
const runPath = `${base}/actions/runs/123`;
const jobsPath = `${runPath}/attempts/2/jobs`;
const run = { id: 123, run_attempt: 2, head_sha: sourceSha, status: 'in_progress', repository: { full_name: context.repository } };
const job = (id, name = 'Build', status = 'completed', conclusion = 'success') => ({
  id, name, run_id: 123, run_attempt: 2, head_sha: sourceSha, status, conclusion,
  check_run_url: `https://api.github.com${base}/check-runs/${id + 1000}`,
});
const annotation = { annotation_level: 'warning', path: '.github/workflows/ci.yml', start_line: 1, end_line: 1, title: 'Runner', message: 'Deprecated action runtime' };
const check = (id, count = 0) => ({ id: id + 1000, head_sha: sourceSha, status: 'completed', output: { annotations_count: count } });

function fixture(jobs = [job(1), job(2, 'Optional', 'queued', null), job(3, 'Skipped', 'completed', 'skipped'), job(4, 'Validation Check', 'in_progress', null)]) {
  const calls = [];
  const responses = new Map([
    [runPath, run], [`${jobsPath}?per_page=100&page=1`, { total_count: jobs.length, jobs }],
  ]);
  for (const entry of jobs) {
    responses.set(`${base}/check-runs/${entry.id + 1000}`, check(entry.id));
    responses.set(`${base}/check-runs/${entry.id + 1000}/annotations?per_page=100&page=1`, []);
  }
  const request = async endpoint => {
    calls.push(endpoint);
    if (!responses.has(endpoint)) throw new Error(`Unexpected endpoint: ${endpoint}`);
    return structuredClone(responses.get(endpoint));
  };
  return { responses, request, calls };
}

test('observes exact attempt completed jobs, records boundaries and never claims unfinished/own annotations complete', async () => {
  const f = fixture();
  const result = await observeRunnerDiagnostics(context, f.request, []);
  assert.equal(result.ok, true, result.errors.join('\n'));
  assert.equal(result.observationComplete, true);
  assert.equal(result.runWideComplete, false);
  assert.deepEqual(result.skippedJobs.map(value => value.name), ['Skipped']);
  assert.deepEqual(result.unfinishedJobs.map(value => value.name), ['Optional']);
  assert.deepEqual(result.excludedJobs.map(value => value.name), ['Validation Check']);
  assert.equal(result.jobs[0].headSha, sourceSha);
  assert.equal(result.context.testedSha, testedSha);
  assert.ok(!f.calls.some(value => /check-runs\/(1002|1004)/.test(value)));
  assert.equal(f.calls.filter(value => value === runPath).length, 2);
});

test('warning/failure annotations have stable exact keys and full job/check/source provenance', async () => {
  const f = fixture([job(1)]);
  f.responses.set(`${base}/check-runs/1001`, check(1, 3));
  f.responses.set(`${base}/check-runs/1001/annotations?per_page=100&page=1`, [annotation,
    { ...annotation, annotation_level: 'failure', message: 'Test reporter failed' },
    { ...annotation, annotation_level: 'notice', message: 'Informational' }]);
  const result = await observeRunnerDiagnostics(context, f.request, []);
  assert.equal(result.ok, false);
  assert.equal(result.diagnostics.length, 2);
  assert.equal(result.annotations[0].jobId, 1);
  assert.equal(result.annotations[0].jobName, 'Build');
  assert.equal(result.annotations[0].checkId, 1001);
  assert.equal(result.annotations[0].headSha, sourceSha);
  assert.equal(result.unexpected.length, 2);
  const accepted = { diagnostic: result.diagnostics[0], owner: 'CI maintainers', issue: 'https://github.com/albertocavalcante/gvy/issues/1', reason: 'Upstream limitation.', nextAction: 'Upgrade fixed action.', acceptance: 'Warning disappears.', expires: '2099-01-01' };
  const tracked = await observeRunnerDiagnostics(context, f.request, [accepted]);
  assert.equal(tracked.accepted.length, 1);
  assert.equal(tracked.ok, false);
  await assert.rejects(() => observeRunnerDiagnostics(context, f.request, [{ ...accepted, expires: '2000-01-01' }]), /expired/i);
});

test('paginates jobs and annotations and fails declared-count truncation, duplicate jobs and stale identity', async () => {
  const jobs = Array.from({ length: 101 }, (_, i) => job(i + 1));
  const f = fixture(jobs);
  f.responses.set(`${jobsPath}?per_page=100&page=1`, { total_count: 101, jobs: jobs.slice(0, 100) });
  f.responses.set(`${jobsPath}?per_page=100&page=2`, { total_count: 101, jobs: jobs.slice(100) });
  const annotations = Array.from({ length: 101 }, (_, i) => ({ ...annotation, annotation_level: 'notice', message: `Notice ${i}` }));
  f.responses.set(`${base}/check-runs/1001`, check(1, 101));
  f.responses.set(`${base}/check-runs/1001/annotations?per_page=100&page=1`, annotations.slice(0, 100));
  f.responses.set(`${base}/check-runs/1001/annotations?per_page=100&page=2`, annotations.slice(100));
  assert.equal((await observeRunnerDiagnostics(context, f.request, [])).ok, true);
  f.responses.set(`${base}/check-runs/1001/annotations?per_page=100&page=2`, []);
  assert.equal((await observeRunnerDiagnostics(context, f.request, [])).ok, false);
  for (const patch of [{ run_id: 124 }, { run_attempt: 1 }, { head_sha: testedSha }, { id: -1 }, { status: 'mystery' }]) {
    const invalid = fixture([{ ...job(1), ...patch }]);
    assert.equal((await observeRunnerDiagnostics(context, invalid.request, [])).ok, false);
  }
  const duplicate = fixture([job(1), job(1)]);
  assert.equal((await observeRunnerDiagnostics(context, duplicate.request, [])).ok, false);
});

test('rejects foreign check URLs without calling them, API/schema failures and stale attempts before/after observation', async () => {
  for (const url of ['https://evil.invalid/steal', 'https://api.github.com/repos/other/private/check-runs/1001',
    'https://api.github.com@evil.invalid/repos/albertocavalcante/gvy/check-runs/1001',
    `https://api.github.com${base}/check-runs/1001?token=secret`, `http://api.github.com${base}/check-runs/1001`]) {
    const f = fixture([{ ...job(1), check_run_url: url }]);
    assert.equal((await observeRunnerDiagnostics(context, f.request, [])).ok, false);
    assert.ok(f.calls.every(value => value === runPath || value.startsWith(jobsPath)));
  }
  const failed = fixture();
  failed.responses.set(`${jobsPath}?per_page=100&page=1`, { jobs: [] });
  assert.equal((await observeRunnerDiagnostics(context, failed.request, [])).ok, false);
  const stale = fixture();
  stale.responses.set(runPath, { ...run, run_attempt: 3 });
  assert.equal((await observeRunnerDiagnostics(context, stale.request, [])).ok, false);
  assert.equal(stale.calls.length, 1);
  let reads = 0;
  const changed = fixture();
  const changedRequest = endpoint => endpoint === runPath && ++reads === 2 ? { ...run, run_attempt: 3 } : changed.request(endpoint);
  assert.equal((await observeRunnerDiagnostics(context, changedRequest, [])).ok, false);
  assert.equal((await observeRunnerDiagnostics(context, async () => { throw new Error('API unavailable'); }, [])).ok, false);
});

test('bounds page/request counts and validates annotation/check schema and exact exception policy', async () => {
  const f = fixture([job(1)]);
  for (const invalid of [{ ...annotation, annotation_level: 'unknown' }, { ...annotation, message: null }, { ...annotation, start_line: 0 }]) {
    f.responses.set(`${base}/check-runs/1001`, check(1, 1));
    f.responses.set(`${base}/check-runs/1001/annotations?per_page=100&page=1`, [invalid]);
    assert.equal((await observeRunnerDiagnostics(context, f.request, [])).ok, false);
  }
  f.responses.set(`${base}/check-runs/1001`, { ...check(1), head_sha: testedSha });
  assert.equal((await observeRunnerDiagnostics(context, f.request, [])).ok, false);
  const endless = fixture(Array.from({ length: 100 }, (_, i) => job(i + 1)));
  const repeating = async endpoint => endpoint.startsWith(jobsPath) ? { total_count: 1000000, jobs: Array.from({ length: 100 }, (_, i) => job(i + 1)) } : endless.request(endpoint);
  assert.equal((await observeRunnerDiagnostics(context, repeating, [])).ok, false);
});

test('CLI uses only isolated fake gh GET endpoints, writes reports and redacts API failure output', t => {
  const dir = mkdtempSync(join(tmpdir(), 'gvy-runner-observer-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const f = fixture();
  const data = join(dir, 'responses.json');
  writeFileSync(data, JSON.stringify(Object.fromEntries(f.responses)));
  const gh = join(dir, 'gh');
  writeFileSync(gh, `#!${process.execPath}\nimport { appendFileSync, readFileSync } from 'node:fs';
const args = process.argv.slice(2); appendFileSync(process.env.FAKE_CALLS, JSON.stringify(args) + '\\n');
if (process.env.FAKE_FAIL) { console.error('secret-token-value'); process.exit(1); }
const endpoint = args.at(-1); const data = JSON.parse(readFileSync(process.env.FAKE_DATA, 'utf8'));
if (!(endpoint in data)) process.exit(2); console.log(JSON.stringify(data[endpoint]));\n`);
  chmodSync(gh, 0o755);
  const event = join(dir, 'event.json');
  writeFileSync(event, JSON.stringify({ pull_request: { head: { sha: sourceSha } } }));
  const report = join(dir, 'report');
  const env = { ...process.env, PATH: dir, GH_TOKEN: 'test-no-network', GITHUB_REPOSITORY: context.repository,
    GITHUB_RUN_ID: '123', GITHUB_RUN_ATTEMPT: '2', GITHUB_SHA: testedSha, GITHUB_EVENT_PATH: event,
    FAKE_DATA: data, FAKE_CALLS: join(dir, 'calls.jsonl') };
  delete env.GITHUB_STEP_SUMMARY;
  const script = resolve('tools/ci/check-runner-diagnostics.mjs');
  const execute = extra => spawnSync(process.execPath, [script, '--report', report], { encoding: 'utf8', env: { ...env, ...extra } });
  assert.equal(execute({}).status, 0);
  const result = JSON.parse(readFileSync(join(report, 'diagnostics.json'), 'utf8'));
  assert.equal(result.ok, true);
  assert.match(readFileSync(join(report, 'summary.md'), 'utf8'), /unfinished|observer/i);
  const calls = readFileSync(env.FAKE_CALLS, 'utf8').trim().split('\n').map(line => JSON.parse(line));
  for (const args of calls) {
    const hostnameIndex = args.indexOf('--hostname');
    const methodIndex = args.indexOf('--method');
    assert.notEqual(hostnameIndex, -1);
    assert.notEqual(methodIndex, -1);
    assert.equal(args[hostnameIndex + 1], 'github.com');
    assert.equal(args[methodIndex + 1], 'GET');
    assert.match(args.at(-1), /^\/repos\/albertocavalcante\/gvy\/(?:actions|check-runs)\//);
  }
  const failed = execute({ FAKE_FAIL: '1' });
  assert.equal(failed.status, 1);
  assert.ok(!failed.stdout.includes('secret-token-value') && !failed.stderr.includes('secret-token-value'));
  assert.equal(JSON.parse(readFileSync(join(report, 'diagnostics.json'), 'utf8')).observationComplete, false);
  assert.notEqual(execute({ GITHUB_RUN_ATTEMPT: '0' }).status, 0);
  assert.notEqual(execute({ GITHUB_REPOSITORY: 'other/../../secret' }).status, 0);
});

test('a count mismatch or unsuccessful conclusion cannot become a successful annotation snapshot', async () => {
  const missing = fixture([job(1)]);
  missing.responses.set(`${jobsPath}?per_page=100&page=1`, { total_count: 2, jobs: [job(1)] });
  const truncated = await observeRunnerDiagnostics(context, missing.request, []);
  assert.equal(truncated.observationComplete, false);
  assert.match(truncated.errors.join('\n'), /truncat/i);
  const failed = fixture([job(1, 'Build', 'completed', 'failure')]);
  const result = await observeRunnerDiagnostics(context, failed.request, []);
  assert.equal(result.observationComplete, true);
  assert.equal(result.ok, false);
  assert.match(result.errors.join('\n'), /concluded failure/);
});

test('more than 500 requests returns partial failed evidence without making additional API calls', async () => {
  const jobs = Array.from({ length: 250 }, (_, i) => job(i + 1));
  const f = fixture(jobs);
  for (let page = 1; page <= 3; page++) f.responses.set(`${jobsPath}?per_page=100&page=${page}`, { total_count: jobs.length, jobs: jobs.slice((page - 1) * 100, page * 100) });
  const result = await observeRunnerDiagnostics(context, f.request, []);
  assert.equal(result.ok, false);
  assert.equal(result.observationComplete, false);
  assert.match(result.errors.join('\n'), /budget/);
  assert.equal(f.calls.length, 500);
  assert.ok(result.jobs.some(job => !job.annotationsObserved));
});

test('diagnostic identity includes raw details and never accepts wildcard or expired debt entries', async () => {
  const f = fixture([job(1)]);
  f.responses.set(`${base}/check-runs/1001`, check(1, 1));
  f.responses.set(`${base}/check-runs/1001/annotations?per_page=100&page=1`, [{ ...annotation, raw_details: 'First detail' }]);
  const result = await observeRunnerDiagnostics(context, f.request, []);
  const wildcard = { diagnostic: '.*', owner: 'CI maintainers', issue: 'https://github.com/albertocavalcante/gvy/issues/1', reason: 'Tracked upstream issue.', nextAction: 'Upgrade action.', acceptance: 'Warning absent.', expires: '2099-01-01' };
  const tracked = await observeRunnerDiagnostics(context, f.request, [wildcard]);
  assert.equal(tracked.ok, false);
  assert.equal(tracked.accepted.length, 0);
  f.responses.set(`${base}/check-runs/1001/annotations?per_page=100&page=1`, [{ ...annotation, raw_details: 'Changed detail' }]);
  assert.notDeepEqual((await observeRunnerDiagnostics(context, f.request, [])).diagnostics, result.diagnostics);
});
