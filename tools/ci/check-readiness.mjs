// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import { appendFileSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const record = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);
function timestampKey(value) {
  if (typeof value !== 'string') return null;
  const parts = value.match(/^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?Z$/);
  const parsed = new Date(value);
  if (!parts || !Number.isFinite(parsed.getTime()) || parsed.toISOString().slice(0, 19) !== parts[1]) return null;
  // Fixed-width UTC fractions compare without losing sub-millisecond precision.
  return `${parts[1]}.${(parts[2] ?? '').padEnd(9, '0')}`;
}

/** Evaluate the current workflow's needs graph, never an earlier run's status. */
export function evaluateReadiness(needs) {
  const errors = [];
  const jobs = record(needs) ? needs : {};
  const paths = jobs['check-paths'];
  if (paths?.result !== 'success') errors.push('Applicability check did not succeed.');
  const applicability = {};
  for (const field of ['run_main_ci', 'run_bazel_ci', 'run_zed_ci']) {
    const value = paths?.outputs?.[field];
    if (value !== 'true' && value !== 'false') errors.push(`Missing or invalid applicability: ${field}.`);
    applicability[field] = value;
  }
  if (jobs['check-runner']?.outputs?.provisioning_triggered === 'true') {
    errors.push('Runner provisioning is pending; rerun this revision after the runner is available.');
  }
  const requirements = {
    license: 'true',
    'check-runner': applicability.run_main_ci,
    lint: applicability.run_main_ci,
    build: applicability.run_main_ci,
    launcher: applicability.run_main_ci,
    'e2e-test': applicability.run_main_ci,
    'smoke-test': applicability.run_main_ci,
    bazel: applicability.run_bazel_ci,
    zed: applicability.run_zed_ci,
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
  const jobs = Array.isArray(runner?.jobs) ? runner.jobs.filter(record) : [];
  const anchors = jobs.filter(job => job.name === 'check-paths');
  const anchor = anchors[0];
  const attemptStart = timestampKey(runner?.attemptStartedAt);
  const freshObserved = job => {
    const executionStart = timestampKey(job.startedAt);
    return job.annotationsObserved === true && job.headSha === context.headSha &&
      attemptStart !== null && executionStart !== null && executionStart >= attemptStart;
  };
  if (anchors.length !== 1 || anchor?.status !== 'completed' || anchor.conclusion !== 'success' ||
      !freshObserved(anchor)) {
    report.errors.push('A fresh successful observed check-paths execution is required for this attempt; rerun all CI jobs.');
  }
  // License compliance is independent of check-paths. A successful needs result
  // alone may be reused from an earlier attempt or absent from this receipt.
  const licenseJobs = jobs.filter(job => job.name === 'License compliance');
  const licenseJob = licenseJobs[0];
  if (licenseJobs.length !== 1 || licenseJob?.status !== 'completed' ||
      licenseJob.conclusion !== 'success' || graph.jobs?.license?.result !== licenseJob.conclusion ||
      !freshObserved(licenseJob)) {
    report.errors.push('A fresh successful observed License compliance execution matching needs is required for this attempt; rerun all CI jobs.');
  }
  // Independent advisory jobs are not descendants of check-paths. Their reused
  // completed records can lose annotations too, so the ancestor alone is insufficient.
  const completed = jobs.filter(job => job.name !== 'Validation Check' && job.status === 'completed' && job.conclusion !== 'skipped');
  if (completed.some(job => !freshObserved(job))) {
    report.errors.push('A completed job has reused or unobserved execution evidence; rerun all CI jobs.');
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
