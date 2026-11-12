// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, readFileSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import test from 'node:test';

const workflow = readFileSync(new URL('../../.github/workflows/ci.yml', import.meta.url), 'utf8');

for (const [name, task] of [['Require unit test reports', 'test'], ['Require E2E test reports', 'e2eTest']]) {
  const step = workflow.slice(workflow.indexOf(`      - name: ${name}\n`));
  const match = step.match(/        run: \|\n((?:          [^\n]*\n)+)/);
  assert.ok(match, `Missing executable workflow block: ${name}`);
  const command = match[1].replace(/^ {10}/gm, '');

  function runFixture(t, files) {
    const cwd = mkdtempSync(join(tmpdir(), 'gvy-junit-presence-'));
    t.after(() => rmSync(cwd, { recursive: true, force: true }));
    for (const file of files) {
      const path = join(cwd, file);
      mkdirSync(join(path, '..'), { recursive: true });
      writeFileSync(path, '<testsuite tests="1"/>\n');
    }
    return { cwd, run: () => spawnSync('/bin/bash', ['--noprofile', '--norc', '-e', '-o', 'pipefail', '-c', command], { cwd, encoding: 'utf8', timeout: 5000 }) };
  }

  test(`${name}: accepts nested module reports under the runner's Bash`, t => {
    const fixture = runFixture(t, [`nested module/child/build/test-results/${task}/TEST-example.xml`]);
    const result = fixture.run();
    assert.equal(result.status, 0, result.stderr || result.stdout);
  });

  test(`${name}: rejects missing reports`, t => {
    const result = runFixture(t, []).run();
    assert.equal(result.status, 1);
    assert.match(result.stdout, /::error::.*produced no JUnit reports/);
  });

  test(`${name}: ignores other task results and arbitrary XML`, t => {
    const other = task === 'test' ? 'e2eTest' : 'test';
    const result = runFixture(t, [`module/build/test-results/${other}/TEST-other.xml`, 'unrelated.xml']).run();
    assert.equal(result.status, 1);
    assert.match(result.stdout, /::error::.*produced no JUnit reports/);
  });

  test(`${name}: rejects a symlink standing in for a report`, t => {
    const fixture = runFixture(t, ['unrelated.xml']);
    const dir = join(fixture.cwd, `module/build/test-results/${task}`);
    mkdirSync(dir, { recursive: true });
    symlinkSync(join(fixture.cwd, 'unrelated.xml'), join(dir, 'TEST-link.xml'));
    const result = fixture.run();
    assert.equal(result.status, 1);
    assert.match(result.stdout, /::error::.*produced no JUnit reports/);
  });

  test(`${name}: preserves discovery failure`, t => {
    const fixture = runFixture(t, [`module/build/test-results/${task}/TEST-example.xml`]);
    const bin = join(fixture.cwd, 'bin');
    mkdirSync(bin);
    writeFileSync(join(bin, 'find'), '#!/bin/sh\nexit 73\n', { mode: 0o700 });
    const result = spawnSync('/bin/bash', ['--noprofile', '--norc', '-e', '-o', 'pipefail', '-c', command], {
      cwd: fixture.cwd, env: { ...process.env, PATH: `${bin}:${process.env.PATH}` }, encoding: 'utf8', timeout: 5000,
    });
    assert.equal(result.status, 73);
  });
}
