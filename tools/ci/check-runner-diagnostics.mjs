import { execFileSync } from 'node:child_process';
import { appendFileSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { evaluate } from './bazel-diagnostics.mjs';
import { readEvidenceText } from './read-evidence.mjs';

const OBSERVER_NAME = 'Validation Check';
const MAX_PAGES = 100;
const MAX_REQUESTS = 500;
const MAX_JOBS = 5000;
const MAX_ANNOTATIONS = 10000;
const TIME_BUDGET_MS = 120000;
const isObject = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const positiveId = value => Number.isSafeInteger(value) && value > 0;
const sha = value => typeof value === 'string' && /^[a-f0-9]{40}$/.test(value);
const escape = value => String(value).replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;');

function validateContext(context) {
  if (!isObject(context) || !/^[A-Za-z0-9][A-Za-z0-9_.-]{0,99}\/[A-Za-z0-9][A-Za-z0-9_.-]{0,99}$/.test(context.repository)) throw new Error('Invalid GitHub repository');
  if (!/^[1-9][0-9]*$/.test(context.runId) || !positiveId(Number(context.runId))) throw new Error('Invalid GitHub run ID');
  if (!positiveId(context.runAttempt)) throw new Error('Invalid GitHub run attempt');
  if (!sha(context.headSha) || !sha(context.testedSha)) throw new Error('Invalid source/tested SHA');
}

function checkId(url, repository) {
  let parsed;
  try { parsed = new URL(url); } catch { throw new Error('Missing or invalid check run URL'); }
  const prefix = `/repos/${repository}/check-runs/`;
  if (parsed.origin !== 'https://api.github.com' || parsed.username || parsed.password || parsed.search || parsed.hash || !parsed.pathname.startsWith(prefix)) throw new Error('Untrusted check run URL');
  const value = parsed.pathname.slice(prefix.length);
  if (!/^[1-9][0-9]*$/.test(value) || !positiveId(Number(value))) throw new Error('Invalid check run ID');
  return Number(value);
}

function validateRun(run, context) {
  if (!isObject(run) || String(run.id) !== context.runId || run.run_attempt !== context.runAttempt || run.head_sha !== context.headSha || run.repository?.full_name !== context.repository) throw new Error('Workflow run identity or current attempt does not match context');
  if (!['queued', 'in_progress', 'completed', 'waiting', 'pending', 'requested'].includes(run.status)) throw new Error('Invalid workflow run status');
  return timestamp(run.run_started_at);
}

function timestamp(value) {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(value)) throw new Error('Invalid workflow/job timestamp');
  const parsed = new Date(value);
  // Date parsing alone normalizes impossible dates, such as February 30.
  if (!Number.isFinite(parsed.getTime()) || parsed.toISOString().slice(0, 19) !== value.slice(0, 19)) throw new Error('Invalid workflow/job timestamp');
  return value;
}

// This is a bounded completed-job snapshot, not a claim about unfinished jobs or
// the observer's own future annotations. API links are never followed blindly.
export async function observeRunnerDiagnostics(context, request, exceptions, now = new Date()) {
  validateContext(context);
  evaluate([], exceptions, now); // Reuse the exact, owned, expiring debt policy.
  const result = {
    schemaVersion: 1, ok: false, observationComplete: false, runWideComplete: false,
    context, observedAt: now.toISOString(), attemptStartedAt: null,
    boundary: `Completed jobs in this exact run attempt, excluding ${OBSERVER_NAME}; unfinished jobs and observer annotations are unobserved.`,
    errors: [], jobs: [], skippedJobs: [], unfinishedJobs: [], excludedJobs: [], annotations: [], diagnostics: [],
    unexpected: [], accepted: [], exceptions,
  };
  const root = `/repos/${context.repository}`;
  const runPath = `${root}/actions/runs/${context.runId}`;
  const jobsPath = `${runPath}/attempts/${context.runAttempt}/jobs`;
  const deadline = Date.now() + TIME_BUDGET_MS;
  let requests = 0;
  const get = async path => {
    if (requests >= MAX_REQUESTS || Date.now() >= deadline) throw new Error('Observer request/time budget exceeded');
    requests++;
    const response = await request(path, Math.min(15000, deadline - Date.now()));
    if (Date.now() >= deadline) throw new Error('Observer time budget exceeded');
    return response;
  };
  try {
    result.attemptStartedAt = validateRun(await get(runPath), context);
    const allJobs = [];
    let expected;
    let jobsFinished = false;
    for (let page = 1; page <= MAX_PAGES; page++) {
      const response = await get(`${jobsPath}?per_page=100&page=${page}`);
      if (!isObject(response) || !Number.isSafeInteger(response.total_count) || response.total_count < 1 || response.total_count > MAX_JOBS || !Array.isArray(response.jobs) || response.jobs.length > 100) throw new Error('Invalid or over-limit jobs response');
      expected ??= response.total_count;
      if (response.total_count !== expected) throw new Error('Job count changed during pagination');
      allJobs.push(...response.jobs);
      if (allJobs.length > expected) throw new Error('Jobs pagination exceeds declared count');
      if (response.jobs.length < 100) {
        if (allJobs.length !== expected) throw new Error('Truncated jobs pagination');
        jobsFinished = true;
        break;
      }
    }
    if (!jobsFinished) throw new Error('Jobs pagination limit exceeded');
    const ids = new Set();
    for (const job of allJobs) {
      if (!isObject(job) || !positiveId(job.id) || ids.has(job.id) || String(job.run_id) !== context.runId || job.run_attempt !== context.runAttempt || job.head_sha !== context.headSha || typeof job.name !== 'string' || !job.name || job.name.length > 500 || !['queued', 'in_progress', 'completed', 'waiting', 'pending', 'requested'].includes(job.status)) throw new Error('Invalid, duplicate or stale job identity');
      if (job.status === 'completed' && !['success', 'failure', 'cancelled', 'skipped', 'neutral', 'timed_out', 'action_required', 'stale', 'startup_failure'].includes(job.conclusion)) throw new Error('Invalid completed job conclusion');
      ids.add(job.id);
      result.jobs.push({ jobId: job.id, name: job.name, checkId: checkId(job.check_run_url, context.repository), headSha: job.head_sha, status: job.status, conclusion: job.conclusion ?? null, startedAt: job.started_at == null ? null : timestamp(job.started_at), annotationsObserved: false });
    }
    result.excludedJobs = result.jobs.filter(job => job.name === OBSERVER_NAME);
    if (result.excludedJobs.length > 1) throw new Error('Ambiguous observer job name');
    result.unfinishedJobs = result.jobs.filter(job => job.name !== OBSERVER_NAME && job.status !== 'completed');
    result.skippedJobs = result.jobs.filter(job => job.conclusion === 'skipped');
    let annotationCount = 0;
    for (const job of result.jobs) {
      if (job.name === OBSERVER_NAME || job.status !== 'completed') continue;
      const path = `${root}/check-runs/${job.checkId}`;
      const check = await get(path);
      const expectedCount = check?.output?.annotations_count;
      if (!isObject(check) || check.id !== job.checkId || check.head_sha !== context.headSha || check.status !== 'completed' || !Number.isSafeInteger(expectedCount) || expectedCount < 0 || expectedCount > MAX_ANNOTATIONS) throw new Error(`Invalid or stale check metadata for job ${job.jobId}`);
      const annotations = [];
      let finished = false;
      for (let page = 1; page <= MAX_PAGES; page++) {
        const values = await get(`${path}/annotations?per_page=100&page=${page}`);
        if (!Array.isArray(values) || values.length > 100) throw new Error(`Invalid annotation page for job ${job.jobId}`);
        annotations.push(...values);
        annotationCount += values.length;
        if (annotationCount > MAX_ANNOTATIONS || annotations.length > expectedCount) throw new Error('Annotation count exceeds declared count or observer limit');
        if (values.length < 100) {
          if (annotations.length !== expectedCount) throw new Error(`Truncated annotations for job ${job.jobId}`);
          finished = true;
          break;
        }
      }
      if (!finished) throw new Error('Annotation pagination limit exceeded');
      for (const item of annotations) {
        if (!isObject(item) || !['notice', 'warning', 'failure'].includes(item.annotation_level) || typeof item.path !== 'string' || typeof item.message !== 'string' || !item.message || !positiveId(item.start_line) || !positiveId(item.end_line) || item.end_line < item.start_line || (item.title !== undefined && item.title !== null && typeof item.title !== 'string') || (item.raw_details !== undefined && item.raw_details !== null && typeof item.raw_details !== 'string')) throw new Error(`Invalid annotation schema for job ${job.jobId}`);
        if (item.annotation_level === 'notice') continue;
        const details = { level: item.annotation_level, path: item.path, startLine: item.start_line, endLine: item.end_line, title: item.title ?? '', message: item.message, rawDetails: item.raw_details ?? '' };
        const diagnostic = JSON.stringify({ job: job.name, ...details });
        result.annotations.push({ jobId: job.jobId, jobName: job.name, checkId: job.checkId, headSha: job.headSha, ...details, diagnostic });
      }
      job.annotationsObserved = true;
      if (!['success', 'skipped', 'neutral'].includes(job.conclusion)) result.errors.push(`Completed job ${job.jobId} (${job.name}) concluded ${job.conclusion}`);
    }
    // Selective reruns can clone old job execution times into the new attempt.
    // Retain that provenance and invalidate a rerun starting mid-snapshot.
    if (validateRun(await get(runPath), context) !== result.attemptStartedAt) throw new Error('Workflow run attempt start changed during observation');
    result.observationComplete = true;
  } catch (error) { result.errors.push(error.message); }
  result.diagnostics = [...new Set(result.annotations.map(item => item.diagnostic))].sort();
  const policy = evaluate(result.diagnostics, exceptions, now);
  Object.assign(result, { unexpected: policy.unexpected, accepted: policy.accepted });
  result.ok = result.observationComplete && result.errors.length === 0 && policy.ok;
  result.requestCount = requests;
  return result;
}

function contextFromEnvironment() {
  // GitHub's Actions/check metadata uses the PR source head. GITHUB_SHA may
  // identify the synthetic merge checkout, which is recorded separately.
  let headSha = process.env.GITHUB_SHA;
  if (process.env.GITHUB_EVENT_PATH) {
    const event = JSON.parse(readEvidenceText(process.env.GITHUB_EVENT_PATH, 4 * 1024 * 1024));
    if (event.pull_request) headSha = event.pull_request.head?.sha;
  } else if (process.env.GITHUB_EVENT_NAME?.startsWith('pull_request')) throw new Error('PR observation requires GitHub event context');
  const attempt = process.env.GITHUB_RUN_ATTEMPT;
  if (!/^[1-9][0-9]*$/.test(attempt ?? '')) throw new Error('Invalid GitHub run attempt');
  return { repository: process.env.GITHUB_REPOSITORY, runId: process.env.GITHUB_RUN_ID, runAttempt: Number(attempt), headSha, testedSha: process.env.GITHUB_SHA };
}

async function main(args) {
  if (args.length !== 2 || args[0] !== '--report' || !args[1]) throw new Error('Usage: check-runner-diagnostics.mjs --report DIR');
  const report = resolve(args[1]);
  mkdirSync(report, { recursive: true });
  let result;
  try {
    if (!process.env.GH_TOKEN?.trim()) throw new Error('GH_TOKEN is required with Actions/Checks read permissions');
    const context = contextFromEnvironment();
    const exceptions = JSON.parse(readFileSync(join(dirname(fileURLToPath(import.meta.url)), 'runner-diagnostics.json'), 'utf8'));
    const request = (endpoint, timeout) => {
      try {
        const response = execFileSync('gh', ['api', '--hostname', 'github.com', '--method', 'GET', '-H', 'Accept: application/vnd.github+json', '-H', 'X-GitHub-Api-Version: 2022-11-28', endpoint], { encoding: 'utf8', timeout, maxBuffer: 8 * 1024 * 1024, stdio: ['ignore', 'pipe', 'pipe'] });
        return JSON.parse(response);
      } catch { throw new Error('GitHub API request failed, timed out, exceeded 8 MiB or returned invalid JSON'); }
    };
    result = await observeRunnerDiagnostics(context, request, exceptions);
  } catch (error) {
    result = { schemaVersion: 1, ok: false, observationComplete: false, runWideComplete: false, attemptStartedAt: null, errors: [error.message], annotations: [], unexpected: [], accepted: [], exceptions: [], jobs: [], skippedJobs: [], unfinishedJobs: [], excludedJobs: [] };
  }
  const summary = [
    '## Actions and runner diagnostic snapshot', '',
    `**${result.ok ? 'PASS' : 'FAIL'}**: ${result.unexpected.length} new annotations; ${result.accepted.length} tracked annotations.`, '',
    result.boundary ?? 'Observation failed before a completed-job boundary could be established.',
    `Observation complete: ${result.observationComplete}. Run-wide annotation completeness: false.`, '',
    ...result.errors.map(value => `- ${escape(value)}`),
    ...result.unexpected.map(value => `<pre>${escape(value)}</pre>`), '',
    `Unfinished jobs: ${result.unfinishedJobs.map(job => escape(job.name)).join(', ') || 'none observed'}.`,
    `Skipped jobs: ${result.skippedJobs.map(job => escape(job.name)).join(', ') || 'none observed'}.`,
    `Excluded observer jobs: ${result.excludedJobs.map(job => escape(job.name)).join(', ') || 'none observed'}.`, '',
    '### Tracked debt (including absent annotations)', '',
    ...result.exceptions.map(entry => `- ${escape(entry.owner)}: ${entry.issue}, expires ${entry.expires}. ${escape(entry.reason)} Next: ${escape(entry.nextAction)} Acceptance: ${escape(entry.acceptance)}`), '',
    'Only completed jobs in this attempt were inspected. Producer/readiness checks separately establish required validation coverage. GitHub may cap emitted step annotations; this observer cannot detect messages that were never published as annotations.', '',
  ].join('\n');
  writeFileSync(join(report, 'diagnostics.json'), `${JSON.stringify(result, null, 2)}\n`);
  writeFileSync(join(report, 'summary.md'), summary);
  if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY, summary);
  console.log(`Runner diagnostics ${result.ok ? 'passed' : 'failed'}: ${result.unexpected.length} new annotations; snapshot complete=${result.observationComplete}`);
  process.exitCode = result.ok ? 0 : 1;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try { await main(process.argv.slice(2)); } catch (error) { console.error(error.message); process.exitCode = 1; }
}
