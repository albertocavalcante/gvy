// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const workflow = readFileSync(new URL('../../.github/workflows/ci.yml', import.meta.url), 'utf8');
const mainJobs = ['lint', 'build', 'e2e-test'];
const jobs = ['bazel', ...mainJobs, 'launcher', 'zed', 'dependency-check'];

function jobBlock(source, name) {
  return source.match(new RegExp(`^  ${name}:\\n([\\s\\S]*?)(?=^  [a-z][a-z-]*:|$(?![\\s\\S]))`, 'm'))?.[1];
}

// Check the small set of conditions used by these jobs, not arbitrary Actions expressions.
function hasLicenseGate(block, name) {
  if (!block) return false;
  const needs = block.match(/^    needs: \[([^\]]+)\]/m)?.[1].split(',').map(value => value.trim());
  if (!needs?.includes('license')) return false;
  const expression = (block.match(/^    if: \|\n((?:      .*\n)+)/m)?.[1]
    ?? block.match(/^    if: (.+)$/m)?.[1] ?? '').replace(/\s+/g, ' ').trim();
  const guard = "needs.license.result == 'success' && ";
  const prefix = mainJobs.includes(name) ? `!cancelled() && ${guard}` : guard;
  if (!expression.startsWith(prefix)) return false;
  const rest = expression.slice(prefix.length);
  if (mainJobs.includes(name)) {
    const main = "needs.check-paths.result == 'success' && needs.check-paths.outputs.run_main_ci == 'true' && needs.check-paths.outputs.provisioning_triggered != 'true'";
    // Lint alone may be skipped after check-paths ran it as the pull-request fast gate.
    return rest === (name === 'lint' ? `${main} && needs.check-paths.outputs.lint_verified != 'true'` : main);
  }
  if (name === 'launcher' || name === 'zed') {
    const output = name === 'launcher' ? 'run_main_ci' : 'run_zed_ci';
    return rest === `(needs.check-paths.outputs.${output} == 'true' || github.event_name == 'workflow_dispatch')`;
  }
  if (name === 'dependency-check') return rest === "github.ref == 'refs/heads/main'";
  const output = name === 'bazel' ? 'run_bazel_ci' : 'run_main_ci';
  return rest === `needs.check-paths.outputs.${output} == 'true'`;
}

test('expensive jobs depend on and require successful license compliance', () => {
  for (const name of jobs) assert.equal(hasLicenseGate(jobBlock(workflow, name), name), true, name);
});

test('contract rejects removed dependencies and guards that permit a bypass', () => {
  for (const name of jobs) {
    const block = jobBlock(workflow, name);
    assert.ok(block, name);
    const withoutNeed = block.replace(/\blicense\b(?=\])/, 'other-job');
    assert.notEqual(withoutNeed, block, name);
    assert.equal(hasLicenseGate(withoutNeed, name), false, `${name}: missing dependency`);
    const bypass = block.replace("needs.license.result == 'success' &&", "needs.license.result == 'success' ||");
    assert.notEqual(bypass, block, name);
    assert.equal(hasLicenseGate(bypass, name), false, `${name}: OR bypass`);
  }
  for (const name of ['launcher', 'zed']) {
    const block = jobBlock(workflow, name);
    const ungrouped = block.replace(' && (needs.check-paths', ' && needs.check-paths')
      .replace("github.event_name == 'workflow_dispatch')", "github.event_name == 'workflow_dispatch'");
    assert.notEqual(ungrouped, block, name);
    assert.equal(hasLicenseGate(ungrouped, name), false, `${name}: ungrouped dispatch`);
  }
});

test('check-paths declares the provisioning output that the main jobs and readiness read', () => {
  const block = jobBlock(workflow, 'check-paths');
  assert.match(block, /^      provisioning_triggered: \$\{\{ steps\.exit-early\.outputs\.provisioning_triggered \}\}$/m);
  assert.match(block, /^        id: exit-early$/m);
});

test('the pull-request lint gate runs the Lint job setup and invocation, then reports it verified', () => {
  const paths = jobBlock(workflow, 'check-paths');
  const lint = jobBlock(workflow, 'lint');
  const step = (block, name) => block.match(new RegExp(`^      - name: ${name}\\n([\\s\\S]*?)(?=^      - |(?![\\s\\S]))`, 'm'))?.[1];
  const gateIf = "github.event_name == 'pull_request' && steps.filter.outputs.run_main_ci == 'true'";
  const invocation = lint.match(/^        run: (node tools\/ci\/run-gradle\.mjs .*--producer lint lint .*)$/m)?.[1];
  assert.ok(invocation, 'Lint job invocation');
  assert.match(paths, /^      lint_verified: \$\{\{ steps\.lint-gate\.outputs\.verified \}\}$/m);
  // Pull requests only: pushes and manual runs keep the parallel Lint job.
  const run = step(paths, 'Run Lint \\(pull-request lint gate\\)');
  assert.ok(run, 'lint gate step');
  assert.match(run, /^        id: lint-run$/m);
  assert.ok(run.includes(`        if: ${gateIf}\n`));
  assert.ok(run.includes(`        run: ${invocation}\n`));
  assert.doesNotMatch(run, /continue-on-error|^        shell:/m);
  const record = step(paths, 'Record verified pull-request lint gate');
  assert.ok(record, 'verification step');
  assert.match(record, /^        id: lint-gate$/m);
  assert.ok(record.includes(`        if: ${gateIf} && steps.lint-run.outcome == 'success'\n`));
  assert.match(record, /^        run: echo "verified=true" >> "\$GITHUB_OUTPUT"$/m);
  // Same toolchain and cache as the Lint job.
  const uses = block => [...block.matchAll(/^\s+uses: (\S+)/gm)].map(match => match[1]);
  const lintSetup = uses(lint).filter(action => !action.startsWith('actions/checkout@') && !action.startsWith('actions/upload-artifact@'));
  for (const action of lintSetup) assert.ok(uses(paths).includes(action), action);
  assert.match(lint, /java-version: 17$/m);
  assert.match(step(paths, 'Set up JDK 17 \\(pull-request lint gate\\)') ?? '', /java-version: 17$/m);
  assert.match(step(paths, 'Setup Gradle \\(pull-request lint gate\\)') ?? '', /^          GRADLE_BUILD_ACTION_CACHE_KEY_JOB: lint$/m);
});
