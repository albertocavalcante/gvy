// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import assert from 'node:assert/strict';
import test from 'node:test';
import { combineReadiness, evaluateReadiness } from './check-readiness.mjs';

function successful(main = true, bazel = true, zed = true, dependency = false) {
  return {
    license: { result: 'success' },
    'check-paths': { result: 'success', outputs: { run_main_ci: String(main), run_bazel_ci: String(bazel), run_zed_ci: String(zed), run_dependency_check: String(dependency) } },
    'check-runner': { result: main ? 'success' : 'skipped', outputs: { provisioning_triggered: '' } },
    lint: { result: main ? 'success' : 'skipped' },
    build: { result: main ? 'success' : 'skipped' },
    launcher: { result: main ? 'success' : 'skipped' },
    'e2e-test': { result: main ? 'success' : 'skipped' },
    'smoke-test': { result: main ? 'success' : 'skipped' },
    bazel: { result: bazel ? 'success' : 'skipped' },
    zed: { result: zed ? 'success' : 'skipped' },
    'dependency-check': { result: dependency ? 'success' : 'skipped' },
  };
}

test('only successful applicable jobs and verified exclusions pass', () => {
  for (const main of [false, true]) {
    for (const bazel of [false, true]) {
      for (const zed of [false, true]) assert.equal(evaluateReadiness(successful(main, bazel, zed)).ok, true);
    }
  }
});

test('license compliance is required even for docs-only and fails closed', () => {
  for (const main of [false, true]) {
    for (const result of [undefined, 'failure', 'cancelled', 'skipped', 'unknown']) {
      const jobs = successful(main, false, false);
      jobs.license.result = result;
      const report = evaluateReadiness(jobs);
      assert.equal(report.ok, false, String(result));
      assert.ok(report.errors.some(error => error.startsWith('license: expected success')));
    }
    const jobs = successful(main, false, false);
    delete jobs.license;
    assert.equal(evaluateReadiness(jobs).ok, false, 'missing license job');
  }
});

test('missing, failed, cancelled and skipped applicability never mean docs only', () => {
  for (const result of [undefined, 'failure', 'cancelled', 'skipped']) {
    const jobs = successful(false, false, false);
    jobs['check-paths'].result = result;
    assert.equal(evaluateReadiness(jobs).ok, false, String(result));
  }
  for (const value of [undefined, '', 'True', '0', 'null', true, false, {}, []]) {
    for (const field of ['run_main_ci', 'run_bazel_ci', 'run_zed_ci', 'run_dependency_check']) {
      const jobs = successful(false, false, false);
      jobs['check-paths'].outputs[field] = value;
      assert.equal(evaluateReadiness(jobs).ok, false, `${field}=${value}`);
    }
  }
});

test('every applicable producer must succeed, including Bazel and the runner check', () => {
  for (const name of ['check-runner', 'lint', 'build', 'e2e-test', 'smoke-test', 'launcher', 'bazel', 'zed']) {
    for (const result of [undefined, 'failure', 'cancelled', 'skipped', 'unknown']) {
      const jobs = successful();
      jobs[name].result = result;
      assert.equal(evaluateReadiness(jobs).ok, false, `${name}: ${result}`);
    }
    const jobs = successful();
    delete jobs[name];
    assert.equal(evaluateReadiness(jobs).ok, false, `missing ${name}`);
  }
});

test('provisioning pending cannot satisfy required readiness', () => {
  const jobs = successful();
  jobs['check-runner'].outputs.provisioning_triggered = 'true';
  for (const name of ['lint', 'build', 'e2e-test']) jobs[name].result = 'skipped';
  const report = evaluateReadiness(jobs);
  assert.equal(report.ok, false);
  assert.match(report.errors.join('\n'), /provisioning/);
});

test('excluded jobs must be skipped; failures remain visible', () => {
  for (const name of ['check-runner', 'lint', 'build', 'e2e-test', 'smoke-test', 'launcher', 'bazel', 'zed']) {
    for (const result of ['success', 'failure', 'cancelled', undefined]) {
      const jobs = successful(false, false, false);
      jobs[name].result = result;
      assert.equal(evaluateReadiness(jobs).ok, false, `${name}: ${result}`);
    }
  }
});

test('Zed-only readiness requires its typed producer while main and Bazel remain explicitly excluded', () => {
  const jobs = successful(false, false, true);
  assert.equal(evaluateReadiness(jobs).ok, true);
  for (const result of [undefined, 'failure', 'cancelled', 'skipped', 'unknown']) {
    const changed = structuredClone(jobs);
    changed.zed.result = result;
    const report = evaluateReadiness(changed);
    assert.equal(report.ok, false, String(result));
    assert.ok(report.errors.some(error => error.startsWith('zed: expected success')));
  }
  const missing = structuredClone(jobs);
  delete missing.zed;
  assert.equal(evaluateReadiness(missing).ok, false);
});

test('malformed needs fails closed with a report', () => {
  for (const value of [null, undefined, [], {}, 'success']) assert.equal(evaluateReadiness(value).ok, false);
});

test('an undeclared needs entry fails even when every known producer succeeds', () => {
  for (const result of ['success', 'failure', 'skipped', 'cancelled']) {
    assert.equal(evaluateReadiness({ ...successful(), unaccounted: { result } }).ok, false, result);
  }
});

test('applicable dependency updates must succeed and explicitly excluded updates must be skipped', () => {
  for (const applies of [false, true]) {
    const needs = successful();
    needs['check-paths'].outputs.run_dependency_check = String(applies);
    needs['dependency-check'] = { result: applies ? 'success' : 'skipped' };
    assert.equal(evaluateReadiness(needs).ok, true);
    for (const result of [undefined, 'failure', 'cancelled', 'timed_out', 'neutral', 'unknown', applies ? 'skipped' : 'success']) {
      needs['dependency-check'].result = result;
      assert.equal(evaluateReadiness(needs).ok, false, `${applies}: ${result}`);
    }
    delete needs['dependency-check'];
    assert.equal(evaluateReadiness(needs).ok, false, `${applies}: missing`);
  }
});

const context = { headSha: 'a'.repeat(40), testedSha: 'b'.repeat(40), runId: '123', runAttempt: '2' };
const attemptStartedAt = '2026-11-11T12:22:40Z';
const licenseJob = receipt => receipt.jobs.find(job => job.name === 'License compliance');
const observerJob = receipt => receipt.jobs.find(job => job.name === 'Validation Check');
function currentReceipt() {
  return {
    schemaVersion: 1, ok: true, observationComplete: true, context, attemptStartedAt,
    jobs: [{
      jobId: 2, checkId: 2, name: 'check-paths', headSha: context.headSha,
      status: 'completed', conclusion: 'success', annotationsObserved: true,
      startedAt: '2026-11-11T12:22:41Z',
    }, {
      jobId: 9, checkId: 9, name: 'License compliance', headSha: context.headSha,
      status: 'completed', conclusion: 'success', annotationsObserved: true,
      startedAt: '2026-11-11T12:22:41Z',
    }, {
      jobId: 1, checkId: 1, name: 'Validation Check', headSha: context.headSha,
      status: 'in_progress', conclusion: null, annotationsObserved: false,
      startedAt: '2026-11-11T12:22:41Z',
    }],
  };
}

test('a terminal producer graph cannot accept unfinished non-observer jobs', () => {
  for (const status of ['queued', 'in_progress', 'waiting', 'pending', 'requested']) {
    const receipt = currentReceipt();
    receipt.jobs.push({ ...receipt.jobs[0], jobId: 3, checkId: 3, name: 'Check Dependencies', status, conclusion: null, annotationsObserved: false });
    assert.equal(combineReadiness(evaluateReadiness(successful()), receipt, context).ok, false, status);
  }
});

test('a terminal producer graph requires exactly one current in-progress observer', () => {
  for (const alter of [
    receipt => { receipt.jobs = receipt.jobs.filter(job => job.name !== 'Validation Check'); },
    receipt => { observerJob(receipt).status = 'completed'; },
    receipt => { receipt.jobs.push({ ...observerJob(receipt), jobId: 3 }); },
    receipt => { observerJob(receipt).startedAt = '2026-11-11T12:00:00Z'; },
    receipt => { observerJob(receipt).headSha = 'c'.repeat(40); },
  ]) {
    const receipt = currentReceipt();
    alter(receipt);
    assert.equal(combineReadiness(evaluateReadiness(successful()), receipt, context).ok, false);
  }
});

test('final policy verdict requires the current successful Actions diagnostic receipt', () => {
  const graph = evaluateReadiness(successful());
  const runner = currentReceipt();
  assert.equal(combineReadiness(graph, runner, context).ok, true);
  for (const bad of [undefined, { ...runner, ok: false }, { ...runner, observationComplete: false }]) {
    assert.equal(combineReadiness(graph, bad, context).ok, false);
  }
  for (const field of Object.keys(context)) {
    assert.equal(combineReadiness(graph, { ...runner, context: { ...context, [field]: 'stale' } }, context).ok, false, field);
  }
  assert.equal(combineReadiness(evaluateReadiness({}), runner, context).ok, false);
});

const partialSnapshots = [
  ['missing attempt start', value => { delete value.attemptStartedAt; }],
  ['invalid attempt start', value => { value.attemptStartedAt = 'not-a-date'; }],
  ['missing job list', value => { delete value.jobs; }],
  ['empty snapshot', value => { value.jobs = []; }],
  ['observer only', value => { value.jobs[0].name = 'Validation Check'; }],
  ['unrelated producer only', value => { value.jobs[0].name = 'Lint'; }],
  ['reused current-attempt clone with old execution', value => { value.jobs[0].startedAt = '2026-11-11T12:14:11Z'; }],
  ['older sub-millisecond execution', value => {
    value.attemptStartedAt = '2026-11-11T12:22:40.123456789Z';
    value.jobs[0].startedAt = '2026-11-11T12:22:40.123456788Z';
  }],
  ['missing execution start', value => { delete value.jobs[0].startedAt; }],
  ['invalid execution start', value => { value.jobs[0].startedAt = 'invalid'; }],
  ['unfinished anchor', value => { value.jobs[0].status = 'in_progress'; }],
  ['failed anchor', value => { value.jobs[0].conclusion = 'failure'; }],
  ['skipped anchor', value => { value.jobs[0].conclusion = 'skipped'; }],
  ['unobserved anchor', value => { value.jobs[0].annotationsObserved = false; }],
  ['different source anchor', value => { value.jobs[0].headSha = 'c'.repeat(40); }],
  ['ambiguous anchor', value => { value.jobs.push({ ...value.jobs[0], jobId: 3, checkId: 3 }); }],
];
for (const [name, alter] of partialSnapshots) {
  test(`partial rerun cannot satisfy readiness: ${name}`, () => {
    for (const applies of [false, true]) {
      const graph = evaluateReadiness(successful(applies, applies, applies));
      const runner = currentReceipt();
      assert.equal(combineReadiness(graph, runner, context).ok, true);
      alter(runner);
      const result = combineReadiness(graph, runner, context);
      assert.equal(result.ok, false);
      assert.match(result.errors.join('\n'), /fresh.*check-paths.*rerun all/i);
    }
  });
}

test('a complete rerun passes with an anchor starting in the attempt first second', () => {
  const runner = currentReceipt();
  runner.jobs[0].startedAt = attemptStartedAt;
  for (const main of [false, true]) {
    for (const bazel of [false, true]) {
      for (const zed of [false, true]) {
        assert.equal(combineReadiness(evaluateReadiness(successful(main, bazel, zed)), runner, context).ok, true);
      }
    }
  }
});

test('timestamp precision preserves equality and a genuinely later execution', () => {
  for (const [attempt, execution] of [
    ['.123456789', '.123456789'], ['.123456789', '.123456790'],
    ['.123', '.123000001'], ['.123000000', '.123'],
  ]) {
    const runner = currentReceipt();
    runner.attemptStartedAt = `2026-11-11T12:22:40${attempt}Z`;
    runner.jobs[0].startedAt = `2026-11-11T12:22:40${execution}Z`;
    assert.equal(combineReadiness(evaluateReadiness(successful()), runner, context).ok, true);
  }
});

for (const [name, alter] of [
  ['reused execution', value => { value.startedAt = '2026-11-11T12:14:11Z'; }],
  ['unobserved annotations', value => { value.annotationsObserved = false; }],
  ['different source', value => { value.headSha = 'c'.repeat(40); }],
]) {
  test(`a fresh ancestor cannot hide an independent completed job: ${name}`, () => {
    const runner = currentReceipt();
    const advisory = { ...runner.jobs[0], jobId: 3, checkId: 3, name: 'Check Dependencies' };
    runner.jobs.push(advisory);
    const graph = evaluateReadiness(successful());
    assert.equal(combineReadiness(graph, runner, context).ok, true);
    alter(advisory);
    const result = combineReadiness(graph, runner, context);
    assert.equal(result.ok, false);
    assert.match(result.errors.join('\n'), /rerun all CI jobs/);
  });
}

test('license job success needs fresh observed execution evidence', () => {
  const runner = currentReceipt();
  const graph = evaluateReadiness(successful(false, false, false));
  assert.equal(combineReadiness(graph, runner, context).ok, true);
  for (const patch of [
    { startedAt: '2026-11-11T12:14:11Z' },
    { annotationsObserved: false },
    { headSha: 'c'.repeat(40) },
  ]) {
    const changed = structuredClone(runner);
    Object.assign(licenseJob(changed), patch);
    assert.equal(combineReadiness(graph, changed, context).ok, false);
  }
});

test('explicitly skipped jobs and the running observer retain the snapshot boundary', () => {
  const runner = currentReceipt();
  runner.jobs.push(
    { ...runner.jobs[0], jobId: 3, name: 'Skipped advisory', conclusion: 'skipped', startedAt: '2026-11-11T12:14:11Z' },
  );
  assert.equal(combineReadiness(evaluateReadiness(successful()), runner, context).ok, true);
});

test('license receipt must contain exactly one fresh successful execution matching needs', () => {
  const graph = evaluateReadiness(successful(false, false, false));
  const receipt = currentReceipt();
  assert.equal(combineReadiness(graph, receipt, context).ok, true);
  for (const [name, alter] of [
    ['missing', value => { value.jobs = value.jobs.filter(job => job.name !== 'License compliance'); }],
    ['skipped separately', value => { value.skippedJobs = [licenseJob(value)]; value.jobs = value.jobs.filter(job => job.name !== 'License compliance'); }],
    ['duplicate', value => { value.jobs.push({ ...licenseJob(value), jobId: 10, checkId: 10 }); }],
    ['duplicate skipped', value => { value.jobs.push({ ...licenseJob(value), jobId: 10, checkId: 10, conclusion: 'skipped' }); }],
    ['unfinished', value => { licenseJob(value).status = 'in_progress'; }],
    ['missing status', value => { delete licenseJob(value).status; }],
    ['skipped', value => { licenseJob(value).conclusion = 'skipped'; }],
    ['failed', value => { licenseJob(value).conclusion = 'failure'; }],
    ['cancelled', value => { licenseJob(value).conclusion = 'cancelled'; }],
    ['unexpected conclusion', value => { licenseJob(value).conclusion = 'timed_out'; }],
    ['missing conclusion', value => { delete licenseJob(value).conclusion; }],
    ['stale', value => { licenseJob(value).startedAt = '2026-11-11T12:14:11Z'; }],
    ['missing start', value => { delete licenseJob(value).startedAt; }],
    ['invalid start', value => { licenseJob(value).startedAt = 'invalid'; }],
    ['unobserved', value => { licenseJob(value).annotationsObserved = false; }],
    ['different head', value => { licenseJob(value).headSha = 'c'.repeat(40); }],
  ]) {
    const changed = structuredClone(receipt);
    alter(changed);
    const report = combineReadiness(graph, changed, context);
    assert.equal(report.ok, false, name);
    assert.ok(report.errors.some(error => /fresh.*License compliance/.test(error)), name);
  }
  const inconsistent = structuredClone(graph);
  inconsistent.jobs.license.result = 'failure';
  const report = combineReadiness(inconsistent, receipt, context);
  assert.equal(report.ok, false, 'needs and receipt disagree');
  assert.ok(report.errors.some(error => /License compliance/.test(error)));
});
