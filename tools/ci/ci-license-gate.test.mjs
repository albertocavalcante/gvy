// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const workflow = readFileSync(new URL('../../.github/workflows/ci.yml', import.meta.url), 'utf8');
const mainJobs = ['lint', 'build', 'e2e-test'];
const jobs = ['bazel', 'check-runner', ...mainJobs, 'launcher', 'zed', 'dependency-check'];

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
    return rest === "needs.check-paths.outputs.run_main_ci == 'true' && needs.check-runner.result == 'success' && needs.check-runner.outputs.provisioning_triggered != 'true'";
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
