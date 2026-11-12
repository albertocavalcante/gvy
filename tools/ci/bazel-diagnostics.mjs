// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import { appendFileSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

// Exact diagnostics, never regex allowlists: another file/target is new debt.
export function collectDiagnostics(log) {
  const diagnostics = new Set();
  let dependency = '';
  let action = 'unknown target';
  for (const raw of log.split(/\r?\n/)) {
    const line = raw.replace(/\x1b\[[0-9;]*m/g, '').replace(/^\[35m\s*/, '')
      .replace(/\S*\/_srcjars\//g, 'generated/')
      .replaceAll(`${process.cwd()}/`, '')
      .replace(/bazel-out\/[^/]+\/bin\//g, 'bazel-out/bin/')
      .replace(/Test execution time \([0-9.]+s excluding execution overhead\)/g, 'Test execution time (<elapsed>s excluding execution overhead)')
      .trim().replace(/\s+/g, ' ');
    const actionHeader = line.match(/^INFO: From \S+ (@?@?\/\/\S+) /);
    if (actionHeader) action = actionHeader[1].replace(/^@+/, '');
    if (/Please add the following dependencies:/.test(line)) {
      dependency = line.replace(/^.*?Please add/, 'Please add');
      continue;
    }
    if (dependency && !/^(?:INFO:|DEBUG:|\[|buildozer)/.test(line) && line) {
      dependency += ` ${line}`;
      if (/ to @?@?\/\//.test(line)) {
        diagnostics.add(dependency);
        dependency = '';
      }
      continue;
    }
    if (/\bWARNING\b|\bwarning:|\bWARN\b|Found duplicate artifact versions|canonical reproducible form|Please remove the following dependencies:|There were tests whose specified size is too big/.test(line)) {
      diagnostics.add(line.startsWith('generated/') ? `[${action}] ${line}` : line);
    }
  }
  if (dependency) diagnostics.add(dependency);
  return [...diagnostics].sort();
}

export function evaluate(diagnostics, exceptions, now = new Date()) {
  if (!Array.isArray(exceptions)) throw new Error('Exceptions must be an array');
  const known = new Map();
  for (const entry of exceptions) {
    for (const field of ['diagnostic', 'owner', 'issue', 'reason', 'nextAction', 'acceptance', 'expires']) {
      if (typeof entry?.[field] !== 'string' || !entry[field].trim()) {
        throw new Error(`Exception requires ${field}`);
      }
    }
    if (!/^https:\/\/github\.com\/albertocavalcante\/gvy\/issues\/[1-9][0-9]*$/.test(entry.issue)) {
      throw new Error('Exception requires a gvy issue URL');
    }
    const expiry = new Date(`${entry.expires}T00:00:00Z`);
    if (!/^\d{4}-\d{2}-\d{2}$/.test(entry.expires) || !Number.isFinite(expiry.getTime()) ||
        expiry.toISOString().slice(0, 10) !== entry.expires) throw new Error('Exception has invalid expiry date');
    if (entry.expires < now.toISOString().slice(0, 10)) throw new Error(`Expired exception: ${entry.issue}`);
    if (known.has(entry.diagnostic)) throw new Error('Duplicate exception diagnostic');
    known.set(entry.diagnostic, entry);
  }
  const unexpected = diagnostics.filter(value => !known.has(value));
  const accepted = diagnostics.filter(value => known.has(value)).map(value => known.get(value));
  return { ok: unexpected.length === 0, unexpected, accepted, exceptions };
}

function main(args) {
  let baseline;
  let report;
  const logs = [];
  for (let index = 0; index < args.length; index++) {
    if (args[index] === '--baseline') baseline = args[++index];
    else if (args[index] === '--report') report = args[++index];
    else logs.push(args[index]);
  }
  if (!baseline || !report || !logs.length) throw new Error('Usage: bazel-diagnostics.mjs --baseline FILE --report DIR LOG...');
  mkdirSync(report, { recursive: true });
  const errors = [];
  let result = { ok: false, unexpected: [], accepted: [], exceptions: [] };
  const contents = [];
  for (const path of logs) {
    try {
      const log = readFileSync(path, 'utf8');
      if (!/INFO: Build completed successfully,/.test(log)) errors.push(`Missing successful completion in ${path}`);
      contents.push(log);
    } catch (error) {
      errors.push(error.message);
    }
  }
  try {
    result = evaluate(collectDiagnostics(contents.join('\n')), JSON.parse(readFileSync(baseline, 'utf8')));
  } catch (error) {
    errors.push(error.message);
  }
  result.ok &&= errors.length === 0;
  const escape = value => String(value).replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;');
  const debts = new Map();
  for (const entry of result.exceptions) {
    const key = JSON.stringify([entry.owner, entry.issue, entry.reason, entry.nextAction, entry.acceptance, entry.expires]);
    const debt = debts.get(key) ?? { ...entry, count: 0 };
    debt.count++;
    debts.set(key, debt);
  }
  const summary = [
    '## Bazel diagnostic accountability',
    '',
    `**${result.ok ? 'PASS' : 'FAIL'}**: ${result.unexpected.length} new diagnostics; ${result.accepted.length} observed tracked exceptions.`,
    '',
    ...errors.map(value => `- ${escape(value)}`),
    ...result.unexpected.map(value => `<pre>${escape(value)}</pre>`),
    '',
    '### Tracked debt (including exceptions not emitted by this cache state)',
    '',
    ...[...debts.values()].map(entry => `- ${entry.count} exact exception${entry.count === 1 ? '' : 's'}: [${escape(entry.owner)}](${entry.issue}), expires ${entry.expires}: ${escape(entry.reason)} Next: ${escape(entry.nextAction)} Acceptance: ${escape(entry.acceptance)}`),
    '',
    'Fix new diagnostics. Any unavoidable exception requires independent review, an exact diagnostic, an owner, an issue, a reason, a next action, acceptance criteria and an expiry. See docs/ci-diagnostics.md.',
    '',
  ].join('\n');
  writeFileSync(resolve(report, 'summary.md'), summary);
  writeFileSync(resolve(report, 'diagnostics.json'), `${JSON.stringify({ ...result, errors }, null, 2)}\n`);
  if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY, summary);
  console.log(summary);
  if (!result.ok) {
    console.error('::error::Bazel diagnostics require action; see the job summary and diagnostics artifact.');
    process.exitCode = 1;
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try { main(process.argv.slice(2)); }
  catch (error) { console.error(error.message); process.exitCode = 1; }
}
