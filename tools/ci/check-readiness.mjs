import { appendFileSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const record = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);
const timestampMillis = value => typeof value === 'string' &&
  /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(value) ? Date.parse(value) : NaN;

/** Evaluate the current workflow's needs graph, never an earlier run's status. */
export function evaluateReadiness(needs) {
  const errors = [];
  const jobs = record(needs) ? needs : {};
  const paths = jobs['check-paths'];
  if (paths?.result !== 'success') errors.push('Applicability check did not succeed.');
  const applicability = {};
  for (const field of ['run_main_ci', 'run_bazel_ci']) {
    const value = paths?.outputs?.[field];
    if (value !== 'true' && value !== 'false') errors.push(`Missing or invalid applicability: ${field}.`);
    applicability[field] = value;
  }
  if (jobs['check-runner']?.outputs?.provisioning_triggered === 'true') {
    errors.push('Runner provisioning is pending; rerun this revision after the runner is available.');
  }
  const requirements = {
    'check-runner': applicability.run_main_ci,
    lint: applicability.run_main_ci,
    build: applicability.run_main_ci,
    'e2e-test': applicability.run_main_ci,
    'smoke-test': applicability.run_main_ci,
    bazel: applicability.run_bazel_ci,
  };
  for (const [name, applies] of Object.entries(requirements)) {
    const expected = applies === 'false' ? 'skipped' : 'success';
    const actual = jobs[name]?.result;
    if (actual !== expected) errors.push(`${name}: expected ${expected}, received ${actual ?? 'missing'}.`);
  }
  return { schemaVersion: 1, ok: errors.length === 0, applicability, jobs, errors };
}

export function combineReadiness(graph, runner, context) {
  const report = { ...graph, errors: [...graph.errors], graphOk: graph.ok, context, runnerDiagnostics: runner ?? null };
  if (runner?.schemaVersion !== 1 || runner?.ok !== true || runner?.observationComplete !== true) {
    report.errors.push('Actions diagnostic observation failed or is missing/incomplete.');
  }
  for (const field of ['headSha', 'testedSha', 'runId', 'runAttempt']) {
    if (!context[field] || String(runner?.context?.[field]) !== String(context[field])) report.errors.push(`Actions diagnostic ${field} does not match this workflow.`);
  }
  // Selective reruns relabel reused successes with new attempt IDs but can drop
  // their annotations. A fresh execution of this ancestor reruns its dependents.
  const anchors = Array.isArray(runner?.jobs) ? runner.jobs.filter(job => record(job) && job.name === 'check-paths') : [];
  const anchor = anchors[0];
  const attemptStart = timestampMillis(runner?.attemptStartedAt);
  const executionStart = timestampMillis(anchor?.startedAt);
  if (anchors.length !== 1 || anchor?.status !== 'completed' || anchor.conclusion !== 'success' ||
      anchor.annotationsObserved !== true || anchor.headSha !== context.headSha ||
      !Number.isFinite(attemptStart) || !Number.isFinite(executionStart) || executionStart < attemptStart) {
    report.errors.push('A fresh successful observed check-paths execution is required for this attempt; rerun all CI jobs.');
  }
  report.ok = report.errors.length === 0;
  return report;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  let report;
  try {
    report = evaluateReadiness(JSON.parse(process.env.NEEDS_JSON ?? 'null'));
  } catch (error) {
    report = { schemaVersion: 1, ok: false, errors: [`Invalid needs JSON: ${error.message}`] };
  }
  const context = {
    headSha: process.env.SOURCE_HEAD_SHA,
    testedSha: process.env.GITHUB_SHA,
    runId: process.env.GITHUB_RUN_ID,
    runAttempt: process.env.GITHUB_RUN_ATTEMPT,
  };
  let runner;
  try { runner = JSON.parse(readFileSync(process.argv[3], 'utf8')); }
  catch (error) { report.errors.push(`Cannot read Actions diagnostic receipt: ${error.message}`); }
  report = combineReadiness(report, runner, context);
  const output = process.argv[2];
  if (!output) throw new Error('Usage: NEEDS_JSON=... node check-readiness.mjs REPORT.json RUNNER_REPORT.json');
  mkdirSync(dirname(output), { recursive: true });
  writeFileSync(output, `${JSON.stringify(report, null, 2)}\n`);
  const summary = `## Required CI readiness: ${report.ok ? 'passed' : 'failed'}\n\n${
    report.errors.length ? report.errors.map((error) => `- ${error}`).join('\n') : 'All applicable producers succeeded; excluded producers were explicitly skipped; completed-job Actions diagnostics were accounted for.'
  }\n`;
  if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY, summary);
  console.log(summary);
  process.exitCode = report.ok ? 0 : 1;
}
