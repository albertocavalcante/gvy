import { appendFileSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

/** Bind both invocation receipts to the current workflow context. */
export function summarizeInvocations(receipts, expected) {
  const errors = [];
  for (const command of ['build', 'test']) {
    const matches = receipts.filter((receipt) => receipt?.producer === `bazel-${command}`);
    if (matches.length !== 1) {
      errors.push(`Expected one bazel-${command} receipt, found ${matches.length}.`);
      continue;
    }
    const receipt = matches[0];
    if (receipt.ok !== true || receipt.complete !== true) errors.push(`bazel-${command} evidence failed or is incomplete.`);
    if (receipt.context?.command !== command) errors.push(`bazel-${command} command does not match.`);
    for (const field of ['headSha', 'testedSha', 'runId', 'runAttempt']) {
      if (!expected[field] || String(receipt.context?.[field]) !== String(expected[field])) errors.push(`bazel-${command} ${field} does not match this workflow.`);
    }
  }
  for (const receipt of receipts) {
    if (!['bazel-build', 'bazel-test'].includes(receipt?.producer)) errors.push('Unexpected invocation producer.');
  }
  return { schemaVersion: 1, ok: errors.length === 0, context: expected, errors, invocations: receipts };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const root = process.argv[2];
  const output = process.argv[3];
  if (!root || !output) throw new Error('Usage: node summarize-bazel.mjs PUBLIC_REPORT_ROOT PRIVATE_REPORT.json');
  if (resolve(output) === resolve(root) || resolve(output).startsWith(`${resolve(root)}${sep}`)) {
    throw new Error('Aggregate report must be outside the public artifact root');
  }
  const event = JSON.parse(readFileSync(process.env.GITHUB_EVENT_PATH, 'utf8'));
  const expected = {
    headSha: event.pull_request?.head?.sha ?? process.env.GITHUB_SHA,
    testedSha: process.env.GITHUB_SHA,
    runId: process.env.GITHUB_RUN_ID,
    runAttempt: process.env.GITHUB_RUN_ATTEMPT,
  };
  const receipts = [];
  const errors = [];
  for (const entry of readdirSync(root, { withFileTypes: true })) {
    if (!entry.isDirectory() || entry.name === 'diagnostics') continue;
    try { receipts.push(JSON.parse(readFileSync(join(root, entry.name, 'summary.json'), 'utf8'))); }
    catch { errors.push(`Missing or invalid receipt: ${entry.name}`); }
  }
  const report = summarizeInvocations(receipts, expected);
  report.errors.push(...errors);
  report.ok &&= errors.length === 0;
  mkdirSync(dirname(output), { recursive: true });
  writeFileSync(output, `${JSON.stringify(report, null, 2)}\n`);
  const rows = receipts.map((receipt) => {
    const timing = receipt?.timing?.elapsedMillis;
    const seconds = typeof timing === 'number' ? (timing / 1000).toFixed(1) : 'unreported';
    const tests = receipt?.tests;
    return `| ${receipt?.context?.command ?? 'unknown'} | ${receipt?.ok === true ? 'passed' : 'failed'} | ${seconds} | ${receipt?.actions?.reported ?? 'unreported'} | ${receipt?.targets?.skipped ?? 'unreported'} | ${tests?.summaries?.length ?? 'unreported'} | ${tests?.locallyCachedAttempts ?? 'unreported'} / ${tests?.remotelyCachedAttempts ?? 'unreported'} |`;
  });
  const summary = [
    '## Bazel invocation evidence', '',
    `**${report.ok ? 'PASS' : 'FAIL'}** · remote uploads disabled · source \`${expected.headSha}\` · attempt ${expected.runAttempt}`, '',
    '| Command | Result | Seconds | Reported actions | Skipped targets | Test targets | Cached test attempts (local / remote) |',
    '| --- | --- | ---: | ---: | ---: | ---: | ---: |', ...rows, '',
    ...report.errors.map((error) => `- ${error}`), '',
    'Audited BEP/profile projections and numeric metrics are in the Bazel evidence artifact (14 days). Raw captures remain private; missing metrics are not inferred.', '',
  ].join('\n');
  if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY, summary);
  console.log(summary);
  process.exitCode = report.ok ? 0 : 1;
}
