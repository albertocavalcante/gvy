// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, readFileSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import test from 'node:test';

const workflow = readFileSync(new URL('../../.github/workflows/ci.yml', import.meta.url), 'utf8');
const step = workflow.slice(workflow.indexOf('      - name: Require plugin and test evidence\n'));
const match = step.match(/        run: \|\n((?:          [^\n]*\n)+)/);
assert.ok(match, 'Missing executable JetBrains evidence gate');
const command = match[1].replace(/^ {10}/gm, '');
const requirements = [
  ['test', 'org.gvy.jetbrains.GvyRealServerTest', 'test pinned server initializes and shuts down through IDE manager'],
  ['testIde20262', 'org.gvy.jetbrains.GvyRealServerTest', 'test pinned server initializes and shuts down through IDE manager'],
  ['testNativeUnload253', 'org.gvy.jetbrains.qa.GvyDynamicUnloadTest', 'test dynamic unload stops real server and reload restarts already open file'],
  ['testNativeUnload262', 'org.gvy.jetbrains.qa.GvyDynamicUnloadTest', 'test dynamic unload stops real server and reload restarts already open file'],
];
const passed = (suite, method) => `<testsuite name="${suite}" tests="1" skipped="0" failures="0" errors="0"><testcase name="${method}" classname="${suite}"/></testsuite>`;

function fixture(t) {
  const cwd = mkdtempSync(join(tmpdir(), 'gvy-jetbrains-evidence-'));
  t.after(() => rmSync(cwd, { recursive: true, force: true }));
  const write = (path, data) => {
    const absolute = join(cwd, path);
    mkdirSync(dirname(absolute), { recursive: true });
    writeFileSync(absolute, data);
  };
  const report = task => {
    const [, suite] = requirements.find(([name]) => name === task);
    return `editors/jetbrains/build/test-results/${task}/TEST-${suite}.xml`;
  };
  write('editors/jetbrains/build/distributions/plugin.zip', 'fixture');
  write('editors/jetbrains/build/reports/pluginVerifier/report.txt', 'fixture');
  for (const [task, suite, method] of requirements) write(report(task), passed(suite, method));
  return {
    cwd, write, report,
    run: () => spawnSync('/bin/bash', ['--noprofile', '--norc', '-e', '-o', 'pipefail', '-c', command], {
      cwd, encoding: 'utf8', timeout: 5000,
    }),
  };
}

test('requires actual real-server initialization and unload tests on both IDEs', t => {
  const result = fixture(t).run();
  assert.equal(result.status, 0, result.stderr || result.stdout);
});

for (const [task, suite, method] of requirements) {
  const valid = passed(suite, method);
  for (const [name, invalid] of [
    ['skipped suite', valid.replace('skipped="0"', 'skipped="1"')],
    ['assumption skip hidden by counters', valid.replace('/></testsuite>', '><skipped/></testcase></testsuite>')],
    ['failed test hidden by counters', valid.replace('/></testsuite>', '><failure/></testcase></testsuite>')],
    ['errored test hidden by counters', valid.replace('/></testsuite>', '><error/></testcase></testsuite>')],
    ['zero discovered tests', valid.replace('tests="1"', 'tests="0"')],
    ['missing test hidden by counters', `<testsuite name="${suite}" tests="1" skipped="0" failures="0" errors="0"/>`],
    ['extra test hidden by counters', valid.replace('</testsuite>', `<testcase name="another" classname="${suite}"/></testsuite>`)],
    ['invalid UTF-8', Buffer.from([0xff, 0xfe, 0x00])],
    ['renamed suite', valid.replaceAll(suite, 'OtherSuite')],
    ['renamed test', valid.replace(method, 'another test')],
    ['malformed XML', '<testsuite>'],
    ['document type', `<!DOCTYPE testsuite [<!ENTITY name "unused">]>${valid}`],
  ]) {
    test(`${task}: rejects ${name}`, t => {
      const f = fixture(t);
      f.write(f.report(task), invalid);
      assert.equal(f.run().status, 1);
    });
  }

  test(`${task}: other XML cannot substitute for the required report`, t => {
    const f = fixture(t);
    rmSync(join(f.cwd, f.report(task)));
    f.write(`editors/jetbrains/build/test-results/${task}/TEST-other.xml`, '<testsuite tests="1"/>');
    assert.equal(f.run().status, 1);
  });

  test(`${task}: rejects a symbolic real-server report`, t => {
    const f = fixture(t);
    const path = join(f.cwd, f.report(task));
    rmSync(path);
    f.write('unrelated.xml', valid);
    symlinkSync(join(f.cwd, 'unrelated.xml'), path);
    assert.equal(f.run().status, 1);
  });
}
