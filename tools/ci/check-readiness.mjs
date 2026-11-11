import { appendFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const record = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);

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

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  let report;
  try {
    report = evaluateReadiness(JSON.parse(process.env.NEEDS_JSON ?? 'null'));
  } catch (error) {
    report = { schemaVersion: 1, ok: false, errors: [`Invalid needs JSON: ${error.message}`] };
  }
  report.context = {
    headSha: process.env.SOURCE_HEAD_SHA,
    testedSha: process.env.GITHUB_SHA,
    runId: process.env.GITHUB_RUN_ID,
    runAttempt: process.env.GITHUB_RUN_ATTEMPT,
  };
  const output = process.argv[2];
  if (!output) throw new Error('Usage: NEEDS_JSON=... node check-readiness.mjs REPORT.json');
  mkdirSync(dirname(output), { recursive: true });
  writeFileSync(output, `${JSON.stringify(report, null, 2)}\n`);
  const summary = `## Required CI readiness: ${report.ok ? 'passed' : 'failed'}\n\n${
    report.errors.length ? report.errors.map((error) => `- ${error}`).join('\n') : 'All applicable producers succeeded; excluded producers were explicitly skipped.'
  }\n`;
  if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY, summary);
  console.log(summary);
  process.exitCode = report.ok ? 0 : 1;
}
