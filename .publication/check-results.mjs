// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import fs from 'node:fs';
import { execFileSync } from 'node:child_process';

const [mode, path] = process.argv.slice(2);
if (mode === 'scope') {
  const allowed = new Set(fs.readFileSync(path, 'utf8').split('\0').filter(Boolean));
  const entries = execFileSync('git', ['status', '--porcelain=v1', '-z', '--untracked-files=all'], { encoding: 'utf8' })
    .split('\0').filter(Boolean);
  if (entries.some(entry => entry.length < 4 || /[RC]/.test(entry.slice(0, 2)) || !allowed.has(entry.slice(3)))) {
    throw new Error('candidate changed outside the seven-file allowlist');
  }
} else if (mode === 'red' || mode === 'green') {
  const expected = new Set([
    'private unsupported modes are rejected before acquisition()',
    'remove policy strips each authored JVM options variable()',
    'reject policy blocks each authored JVM options variable()',
    'reject policy starts process when all authored JVM options are empty()',
  ]);
  const reportNames = ['PackagedLaunchTest', 'PackagedSessionFactoryTest'].map(
    name => `TEST-com.github.albertocavalcante.gvy.gls.e2e.${name}.xml`,
  );
  const actualReports = fs.readdirSync(path).filter(name => name.startsWith('TEST-') && name.endsWith('.xml'));
  const command = JSON.parse(fs.readFileSync(`${path}/command.json`, 'utf8'));
  let tests = 0, failures = 0, errors = 0, skipped = 0;
  const cases = [], failedCases = [];
  for (const name of reportNames) {
    const xml = fs.readFileSync(`${path}/${name}`, 'utf8');
    const header = xml.match(/<testsuite\b[^>]*>/)?.[0] ?? '';
    const count = key => Number(header.match(new RegExp(`\\b${key}="(\\d+)"`))?.[1] ?? NaN);
    tests += count('tests'); failures += count('failures'); errors += count('errors'); skipped += count('skipped');
    for (const match of xml.matchAll(/<testcase\b([^>]*?)(?:\/>|>([\s\S]*?)<\/testcase>)/g)) {
      const name = match[1].match(/\bname="([^"]*)"/)?.[1] ?? '';
      const type = (match[2] ?? '').match(/<failure\b[^>]*\btype="([^"]*)"/)?.[1];
      cases.push(name);
      if (type) failedCases.push({ name, type });
    }
  }
  const expectedFailures = mode === 'red' ? 4 : 0;
  const passed = actualReports.length === 2 && tests === 40 && cases.length === 40 && new Set(cases).size === 40 &&
    failures === expectedFailures && errors === 0 && skipped === 0 && failedCases.length === expectedFailures &&
    command.testExit === (mode === 'red' ? 1 : 0) && command.captureExit === 0 &&
    new Set(failedCases.map(item => item.name)).size === expectedFailures &&
    failedCases.every(item => expected.has(item.name) &&
      ['java.lang.AssertionError', 'org.opentest4j.AssertionFailedError'].includes(item.type));
  // Deliberately retain only names/types/counts; assertion messages may contain
  // values inherited from a runtime environment and are not safe summaries.
  fs.writeFileSync(`${path}/validation.json`, JSON.stringify({ mode, passed, tests, failures, errors, skipped, failedCases }) + '\n');
  if (!passed) throw new Error(`${mode} control gate rejected the result`);
} else {
  throw new Error('unsupported control gate');
}
