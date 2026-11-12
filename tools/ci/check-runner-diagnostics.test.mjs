// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict';
import { chmodSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import test from 'node:test';
import { observeRunnerDiagnostics } from './check-runner-diagnostics.mjs';
import { combineReadiness } from './check-readiness.mjs';

const sourceSha = 'a'.repeat(40);
const testedSha = 'b'.repeat(40);
const context = { repository: 'albertocavalcante/gvy', runId: '123', runAttempt: 2, headSha: sourceSha, testedSha };
const base = '/repos/albertocavalcante/gvy';
const runPath = `${base}/actions/runs/123`;
const jobsPath = `${runPath}/attempts/2/jobs`;
const attemptStartedAt = '2026-11-11T12:22:40Z';
const jobStartedAt = '2026-11-11T12:22:41Z';
const run = { id: 123, run_attempt: 2, head_sha: sourceSha, status: 'in_progress', run_started_at: attemptStartedAt, repository: { full_name: context.repository } };
const job = (id, name = 'Build', status = 'completed', conclusion = 'success') => ({
  id, name, run_id: 123, run_attempt: 2, head_sha: sourceSha, status, conclusion,
  started_at: status === 'queued' ? null : jobStartedAt,
  check_run_url: `https://api.github.com${base}/check-runs/${id + 1000}`,
});
const annotation = { annotation_level: 'warning', path: '.github/workflows/ci.yml', start_line: 1, end_line: 1, title: 'Runner', message: 'Deprecated action runtime' };
const notice = { ...annotation, annotation_level: 'notice', message: 'Hosted image migration notice' };
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
  assert.equal(result.attemptStartedAt, attemptStartedAt);
  assert.equal(result.jobs[0].startedAt, jobStartedAt);
  assert.equal(result.unfinishedJobs[0].startedAt, null);
  assert.equal(result.context.testedSha, testedSha);
  assert.ok(!f.calls.some(value => /check-runs\/(1002|1004)/.test(value)));
  assert.equal(f.calls.filter(value => value === runPath).length, 2);
});

test('retains actual cloned-job start provenance without claiming the job executed in the current attempt', async () => {
  const previousAttemptStart = '2026-11-11T12:14:11Z';
  const f = fixture([{ ...job(21, 'Check Paths'), started_at: previousAttemptStart }]);
  const result = await observeRunnerDiagnostics(context, f.request, []);
  assert.equal(result.ok, true, result.errors.join('\n'));
  assert.equal(result.observationComplete, true);
  assert.equal(result.schemaVersion, 1);
  assert.equal(result.attemptStartedAt, attemptStartedAt);
  assert.equal(result.jobs[0].startedAt, previousAttemptStart);
  assert.ok(Date.parse(result.jobs[0].startedAt) < Date.parse(result.attemptStartedAt));
  assert.equal(result.jobs[0].annotationsObserved, true);
  assert.equal(result.annotations.length, 0);
});

test('accepts valid UTC fractional timestamps and keeps absent job starts explicitly unknown', async () => {
  const started = '2026-11-11T12:22:41.123456789Z';
  const absentStart = job(2, 'Optional', 'queued', null);
  delete absentStart.started_at;
  const f = fixture([{ ...job(1), started_at: started }, absentStart, { ...job(3), started_at: null }]);
  f.responses.set(runPath, { ...run, run_started_at: '2026-11-11T12:22:40.1Z' });
  const result = await observeRunnerDiagnostics(context, f.request, []);
  assert.equal(result.ok, true, result.errors.join('\n'));
  assert.equal(result.attemptStartedAt, '2026-11-11T12:22:40.1Z');
  assert.deepEqual(result.jobs.map(value => value.startedAt), [started, null, null]);
});

test('rejects missing or malformed attempt starts and malformed nonnull job starts before trusting the snapshot', async t => {
  const invalid = [undefined, null, '', 1794400000, 'not-a-timestamp', '2026-11-11',
    '2026-11-11 12:22:40Z', '2026-11-11T12:22:40', '2026-11-11T12:22:40+00:00',
    '2026-02-30T12:22:40Z', '2026-11-11T24:22:40Z', '2026-11-11T12:22:60Z',
    '2026-11-11T12:22:40.1234567890Z', '99999-11-11T12:22:40Z'];
  for (const [index, timestamp] of invalid.entries()) {
    await t.test(`attempt timestamp case ${index}`, async () => {
      const f = fixture();
      f.responses.set(runPath, { ...run, run_started_at: timestamp });
      const result = await observeRunnerDiagnostics(context, f.request, []);
      assert.equal(result.ok, false);
      assert.equal(result.observationComplete, false);
      assert.equal(result.attemptStartedAt, null);
      assert.match(result.errors.join('\n'), /timestamp/i);
      assert.deepEqual(f.calls, [runPath]);
    });
    if (timestamp == null) continue;
    await t.test(`job timestamp case ${index}`, async () => {
      const f = fixture([{ ...job(1), started_at: timestamp }]);
      const result = await observeRunnerDiagnostics(context, f.request, []);
      assert.equal(result.ok, false);
      assert.equal(result.observationComplete, false);
      assert.equal(result.attemptStartedAt, attemptStartedAt);
      assert.match(result.errors.join('\n'), /timestamp/i);
      assert.ok(!f.calls.some(value => value.startsWith(`${base}/check-runs/`)));
    });
  }
});

test('rejects changed or malformed final attempt starts while retaining the initial trusted timestamp', async t => {
  for (const [index, finalStart] of ['2026-11-11T12:22:41Z', null, undefined, 'invalid'].entries()) {
    await t.test(`final timestamp case ${index}`, async () => {
      const f = fixture([job(1)]);
      let reads = 0;
      const request = endpoint => endpoint === runPath && ++reads === 2
        ? { ...run, run_started_at: finalStart } : f.request(endpoint);
      const result = await observeRunnerDiagnostics(context, request, []);
      assert.equal(reads, 2);
      assert.equal(result.ok, false);
      assert.equal(result.observationComplete, false);
      assert.equal(result.attemptStartedAt, attemptStartedAt);
      assert.match(result.errors.join('\n'), /timestamp|attempt start/i);
      assert.equal(result.jobs[0].annotationsObserved, true);
    });
  }
});

test('notice annotations retain original severity and exact run, attempt, job, check and source provenance', async () => {
  const f = fixture([job(1)]);
  const original = { ...notice, message: 'Image <migration> & UTF-8 café', raw_details: 'Unneeded raw payload', extra: 'not a public report field' };
  f.responses.set(`${base}/check-runs/1001`, check(1, 1));
  f.responses.set(`${base}/check-runs/1001/annotations?per_page=100&page=1`, [original]);
  const result = await observeRunnerDiagnostics(context, f.request, []);
  assert.equal(result.ok, true, result.errors.join('\n'));
  assert.equal(result.observationComplete, true);
  assert.deepEqual(result.notices, [{
    repository: context.repository, runId: context.runId, runAttempt: context.runAttempt,
    jobId: 1, jobName: 'Build', checkId: 1001, headSha: sourceSha, testedSha,
    level: 'notice', path: notice.path, startLine: 1, endLine: 1, title: notice.title, message: original.message,
  }]);
  assert.equal(result.noticeBytes, Buffer.byteLength(JSON.stringify(result.notices)));
  assert.equal(result.noticeLimitBytes, 256 * 1024);
  assert.deepEqual(result.annotations, []);
  assert.deepEqual(result.diagnostics, []);
  assert.deepEqual(result.unexpected, []);
  assert.deepEqual(result.accepted, []);
  assert.ok(!JSON.stringify(result).includes(original.raw_details));
});

test('malformed notices fail observation with stable errors instead of disappearing', async () => {
  for (const patch of [{ message: '' }, { message: null }, { path: null }, { start_line: 0 },
    { end_line: 0 }, { title: {} }, { raw_details: {} }]) {
    const f = fixture([job(1)]);
    f.responses.set(`${base}/check-runs/1001`, check(1, 1));
    f.responses.set(`${base}/check-runs/1001/annotations?per_page=100&page=1`, [{ ...notice, ...patch }]);
    const result = await observeRunnerDiagnostics(context, f.request, []);
    assert.equal(result.ok, false);
    assert.equal(result.observationComplete, false);
    assert.deepEqual(result.notices, []);
    assert.deepEqual(result.errors, ['Invalid annotation schema for job 1']);
  }
});

test('a warning alongside a retained notice still fails the unchanged exact diagnostic policy', async () => {
  const f = fixture([job(1)]);
  f.responses.set(`${base}/check-runs/1001`, check(1, 2));
  f.responses.set(`${base}/check-runs/1001/annotations?per_page=100&page=1`, [notice, annotation]);
  const result = await observeRunnerDiagnostics(context, f.request, []);
  const diagnostic = JSON.stringify({ job: 'Build', level: 'warning', path: annotation.path,
    startLine: 1, endLine: 1, title: annotation.title, message: annotation.message, rawDetails: '' });
  assert.equal(result.observationComplete, true);
  assert.equal(result.ok, false);
  assert.equal(result.notices.length, 1);
  assert.deepEqual(result.diagnostics, [diagnostic]);
  assert.deepEqual(result.unexpected, [diagnostic]);
});

test('notice retention enforces the total serialized UTF-8 byte budget without truncating a notice', async () => {
  const f = fixture([job(1)]);
  const endpoint = `${base}/check-runs/1001/annotations?per_page=100&page=1`;
  f.responses.set(`${base}/check-runs/1001`, check(1, 1));
  f.responses.set(endpoint, [notice]);
  const probe = await observeRunnerDiagnostics(context, f.request, []);
  const overhead = probe.noticeBytes - Buffer.byteLength(notice.message);
  const payloadBytes = 256 * 1024 - overhead;
  const exact = 'é'.repeat(Math.floor(payloadBytes / 2)) + (payloadBytes % 2 ? 'a' : '');
  f.responses.set(endpoint, [{ ...notice, message: exact }]);
  const complete = await observeRunnerDiagnostics(context, f.request, []);
  assert.equal(complete.ok, true);
  assert.equal(complete.noticeBytes, 256 * 1024);
  assert.equal(complete.notices[0].message, exact);
  f.responses.set(endpoint, [{ ...notice, message: exact + 'é' }]);
  const exceeded = await observeRunnerDiagnostics(context, f.request, []);
  assert.equal(exceeded.ok, false);
  assert.equal(exceeded.observationComplete, false);
  assert.deepEqual(exceeded.notices, []);
  assert.equal(exceeded.noticeBytes, 2);
  assert.deepEqual(exceeded.errors, ['Retained notice JSON exceeds 256 KiB observer limit']);
});

test('notice byte budget is shared across jobs and partial retained evidence remains explicitly incomplete', async () => {
  const f = fixture([job(1), job(2)]);
  for (const id of [1, 2]) {
    f.responses.set(`${base}/check-runs/${id + 1000}`, check(id, 1));
    f.responses.set(`${base}/check-runs/${id + 1000}/annotations?per_page=100&page=1`, [{ ...notice, message: 'x'.repeat(128 * 1024) }]);
  }
  const result = await observeRunnerDiagnostics(context, f.request, []);
  assert.equal(result.ok, false);
  assert.equal(result.observationComplete, false);
  assert.equal(result.notices.length, 1);
  assert.equal(result.notices[0].jobId, 1);
  assert.equal(result.noticeBytes, Buffer.byteLength(JSON.stringify(result.notices)));
  assert.equal(result.jobs[0].annotationsObserved, true);
  assert.equal(result.jobs[1].annotationsObserved, false);
});

test('notice evidence cannot turn stale source or attempt identities into a complete snapshot', async () => {
  for (const patch of [{ run_attempt: 1 }, { head_sha: testedSha }]) {
    const f = fixture([{ ...job(1), ...patch }]);
    const result = await observeRunnerDiagnostics(context, f.request, []);
    assert.equal(result.ok, false);
    assert.equal(result.observationComplete, false);
    assert.deepEqual(result.notices, []);
  }
  const f = fixture([job(1)]);
  f.responses.set(`${base}/check-runs/1001`, check(1, 1));
  f.responses.set(`${base}/check-runs/1001/annotations?per_page=100&page=1`, [notice]);
  f.responses.set(runPath, { ...run, run_attempt: 1 });
  const staleRun = await observeRunnerDiagnostics(context, f.request, []);
  assert.equal(staleRun.observationComplete, false);
  assert.deepEqual(staleRun.notices, []);
  f.responses.set(runPath, run);
  f.responses.set(`${base}/check-runs/1001`, { ...check(1, 1), head_sha: testedSha });
  const staleCheck = await observeRunnerDiagnostics(context, f.request, []);
  assert.equal(staleCheck.observationComplete, false);
  assert.deepEqual(staleCheck.notices, []);
  f.responses.set(`${base}/check-runs/1001`, check(1, 1));
  let reads = 0;
  const changed = endpoint => endpoint === runPath && ++reads === 2 ? { ...run, run_attempt: 3 } : f.request(endpoint);
  const result = await observeRunnerDiagnostics(context, changed, []);
  assert.equal(result.ok, false);
  assert.equal(result.observationComplete, false);
  assert.equal(result.notices[0].runAttempt, 2);
  assert.equal(result.notices[0].headSha, sourceSha);
});

test('readiness accepts observed informational notices and rejects incomplete or warning-bearing evidence', async () => {
  const f = fixture([job(1, 'check-paths'), job(2, 'License compliance'), job(3, 'Validation Check', 'in_progress', null)]);
  const endpoint = `${base}/check-runs/1001/annotations?per_page=100&page=1`;
  const graph = { schemaVersion: 1, ok: true, errors: [], jobs: { license: { result: 'success' } } };
  f.responses.set(`${base}/check-runs/1001`, check(1, 1));
  f.responses.set(endpoint, [notice]);
  const complete = await observeRunnerDiagnostics(context, f.request, []);
  assert.equal(combineReadiness(graph, complete, context).ok, true);
  f.responses.set(`${base}/check-runs/1001`, check(1, 2));
  const incomplete = await observeRunnerDiagnostics(context, f.request, []);
  assert.equal(incomplete.notices.length, 1);
  assert.equal(combineReadiness(graph, incomplete, context).ok, false);
  f.responses.set(endpoint, [notice, annotation]);
  const warned = await observeRunnerDiagnostics(context, f.request, []);
  assert.equal(warned.observationComplete, true);
  assert.equal(combineReadiness(graph, warned, context).ok, false);
});

test('completed dependency post-job warnings fail the terminal verdict and pending dependency jobs cannot pass it', async () => {
  const graph = { schemaVersion: 1, ok: true, errors: [], jobs: { license: { result: 'success' } } };
  const completed = fixture([job(1, 'check-paths'), job(2, 'Check Dependencies'), job(3, 'Validation Check', 'in_progress', null), job(4, 'License compliance')]);
  const clean = await observeRunnerDiagnostics(context, completed.request, []);
  assert.equal(combineReadiness(graph, clean, context).ok, true);
  const postJob = { ...annotation, title: 'Artifact upload', message: 'New dependency evidence post-job warning' };
  completed.responses.set(`${base}/check-runs/1002`, check(2, 1));
  completed.responses.set(`${base}/check-runs/1002/annotations?per_page=100&page=1`, [postJob]);
  const warned = await observeRunnerDiagnostics(context, completed.request, []);
  assert.equal(warned.observationComplete, true);
  assert.equal(warned.annotations[0].jobName, 'Check Dependencies');
  assert.equal(combineReadiness(graph, warned, context).ok, false);
  assert.equal(warned.runWideComplete, false, 'the observer still cannot inspect its own future post-job output');
  const pending = fixture([job(1, 'check-paths'), job(2, 'Check Dependencies', 'queued', null), job(3, 'Validation Check', 'in_progress', null), job(4, 'License compliance')]);
  const unfinished = await observeRunnerDiagnostics(context, pending.request, []);
  assert.equal(unfinished.ok, true, 'the annotation snapshot retains its narrower completed-job boundary');
  assert.equal(combineReadiness(graph, unfinished, context).ok, false);
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
  assert.equal(result.notices.length, 1);
  assert.equal(result.notices[0].level, 'notice');
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
  const complete = await observeRunnerDiagnostics(context, f.request, []);
  assert.equal(complete.ok, true);
  assert.deepEqual(complete.notices.map(value => value.message), annotations.map(value => value.message));
  f.responses.set(`${base}/check-runs/1001/annotations?per_page=100&page=2`, []);
  const truncated = await observeRunnerDiagnostics(context, f.request, []);
  assert.equal(truncated.ok, false);
  assert.equal(truncated.observationComplete, false);
  assert.equal(truncated.notices.length, 100);
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
  const visibleNotice = { ...notice, title: '<img src=x>', message: '</pre><script>image & migration</script>', raw_details: 'unneeded-private-details' };
  f.responses.set(`${base}/check-runs/1001`, check(1, 1));
  f.responses.set(`${base}/check-runs/1001/annotations?per_page=100&page=1`, [visibleNotice]);
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
  const success = execute({});
  assert.equal(success.status, 0);
  assert.match(success.stdout, /0 new warning\/failure annotations; 1 retained notices \(informational\)/);
  const result = JSON.parse(readFileSync(join(report, 'diagnostics.json'), 'utf8'));
  assert.equal(result.ok, true);
  assert.equal(result.attemptStartedAt, attemptStartedAt);
  assert.equal(result.jobs[0].startedAt, jobStartedAt);
  assert.equal(result.notices[0].message, visibleNotice.message);
  const summary = readFileSync(join(report, 'summary.md'), 'utf8');
  assert.match(summary, /unfinished|observer/i);
  assert.match(summary, /0 new warning\/failure annotations; 0 tracked warning\/failure annotations; 1 retained notices \(informational\)/);
  assert.match(summary, /Compact notice-array payload: .*this limit excludes the rest of the report/);
  assert.ok(summary.includes('&lt;img src=x&gt;'));
  assert.ok(summary.includes('&lt;/pre&gt;&lt;script&gt;image &amp; migration&lt;/script&gt;'));
  assert.ok(summary.includes('"runId":"123","runAttempt":2'));
  assert.ok(!summary.includes('<script>') && !summary.includes('unneeded-private-details'));
  assert.ok(!JSON.stringify(result).includes('unneeded-private-details'));
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
  const summaryFile = join(dir, 'github-step-summary.md');
  const publish = () => execute({ GITHUB_STEP_SUMMARY: summaryFile });
  const captured = () => JSON.parse(readFileSync(join(report, 'diagnostics.json'), 'utf8'));
  const readSummary = () => readFileSync(join(report, 'summary.md'), 'utf8');
  const graph = { schemaVersion: 1, ok: true, errors: [], jobs: { license: { result: 'success' } } };
  const summaryLimit = 512 * 1024;
  const budgetFixture = fixture([job(1, 'check-paths'), job(2, 'License compliance'), job(3, 'Validation Check', 'in_progress', null)]);
  const setNotices = values => {
    budgetFixture.responses.set(`${base}/check-runs/1001`, check(1, values.length));
    budgetFixture.responses.set(`${base}/check-runs/1001/annotations?per_page=100&page=1`, values);
    writeFileSync(data, JSON.stringify(Object.fromEntries(budgetFixture.responses)));
  };
  const amplified = Array.from({ length: 4 }, () => ({ ...notice, message: '&'.repeat(60 * 1024) }));
  setNotices(amplified);
  writeFileSync(summaryFile, '');
  assert.equal(publish().status, 1, 'escaped notice expansion must fail before GitHub drops an oversized summary');
  const renderedFailure = captured();
  assert.equal(renderedFailure.observationComplete, true);
  assert.equal(renderedFailure.ok, false);
  assert.ok(renderedFailure.noticeBytes < 256 * 1024);
  assert.deepEqual(renderedFailure.notices.map(value => value.message), amplified.map(value => value.message));
  assert.deepEqual(renderedFailure.errors, ['Rendered diagnostic summary exceeds 512 KiB observer limit']);
  assert.equal(combineReadiness(graph, renderedFailure, context).ok, false);
  assert.equal(combineReadiness(graph, { ...renderedFailure, ok: true }, context).ok, true);
  assert.ok(Buffer.byteLength(readSummary()) < summaryLimit);
  assert.match(readSummary(), /\*\*FAIL\*\*.*rendered summary/i);
  assert.match(readSummary(), /diagnostics\.json/);
  assert.equal(readFileSync(summaryFile, 'utf8'), readSummary());

  // The whole summary budget includes warning text and measures UTF-8 bytes, not string length.
  const largeWarning = { ...annotation, message: '界'.repeat(180000) };
  setNotices([notice, largeWarning]);
  writeFileSync(summaryFile, '');
  assert.equal(publish().status, 1);
  assert.equal(captured().notices[0].message, notice.message);
  assert.ok(captured().diagnostics[0].includes(largeWarning.message));
  assert.deepEqual(captured().errors, ['Rendered diagnostic summary exceeds 512 KiB observer limit']);
  assert.ok(Buffer.byteLength(readSummary()) < summaryLimit);
  assert.equal(readFileSync(summaryFile, 'utf8'), readSummary());

  setNotices([notice]);
  writeFileSync(summaryFile, '');
  assert.equal(publish().status, 0);
  const ordinarySummary = readSummary();
  const prefixBytes = summaryLimit - Buffer.byteLength(ordinarySummary);
  const exactPrefix = 'é'.repeat(Math.floor(prefixBytes / 2)) + (prefixBytes % 2 ? 'a' : '');
  writeFileSync(summaryFile, exactPrefix);
  assert.equal(publish().status, 0);
  assert.equal(Buffer.byteLength(readFileSync(summaryFile)), summaryLimit);
  assert.equal(readFileSync(summaryFile, 'utf8'), exactPrefix + ordinarySummary);

  writeFileSync(summaryFile, exactPrefix + 'x');
  assert.equal(publish().status, 1);
  assert.deepEqual(captured().errors, ['GitHub step summary exceeds 512 KiB observer limit']);
  assert.ok(Buffer.byteLength(readFileSync(summaryFile)) <= summaryLimit);
  assert.equal(readFileSync(summaryFile, 'utf8'), exactPrefix + 'x' + readSummary());

  const fullPrefix = 'é'.repeat(summaryLimit / 2);
  writeFileSync(summaryFile, fullPrefix);
  const noSpace = publish();
  assert.equal(noSpace.status, 1);
  assert.match(noSpace.stderr, /GitHub step summary exceeds 512 KiB observer limit/);
  assert.equal(captured().ok, false);
  assert.equal(readFileSync(summaryFile, 'utf8'), fullPrefix);
  assert.ok(Buffer.byteLength(readSummary()) < summaryLimit);
  assert.match(readSummary(), /diagnostics\.json/);
  const cannotWrite = execute({ GITHUB_STEP_SUMMARY: dir });
  assert.equal(cannotWrite.status, 1);
  assert.deepEqual(captured().errors, ['GitHub step summary could not be written']);
  assert.equal(captured().ok, false);
  assert.equal(captured().notices[0].message, notice.message);
  assert.match(cannotWrite.stderr, /GitHub step summary could not be written/);
  assert.ok(!cannotWrite.stderr.includes(dir));
  assert.ok(Buffer.byteLength(readSummary()) < summaryLimit);
  writeFileSync(data, JSON.stringify(Object.fromEntries(f.responses)));
  const failed = execute({ FAKE_FAIL: '1' });
  assert.equal(failed.status, 1);
  assert.ok(!failed.stdout.includes('secret-token-value') && !failed.stderr.includes('secret-token-value'));
  assert.equal(JSON.parse(readFileSync(join(report, 'diagnostics.json'), 'utf8')).observationComplete, false);
  assert.deepEqual(JSON.parse(readFileSync(join(report, 'diagnostics.json'), 'utf8')).notices, []);
  assert.equal(execute({ GH_TOKEN: '' }).status, 1);
  const failedContext = JSON.parse(readFileSync(join(report, 'diagnostics.json'), 'utf8'));
  assert.deepEqual(failedContext.notices, []);
  assert.equal(failedContext.noticeBytes, 2);
  assert.equal(failedContext.noticeLimitBytes, 256 * 1024);
  assert.deepEqual(failedContext.errors, ['GH_TOKEN is required with Actions/Checks read permissions']);
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
