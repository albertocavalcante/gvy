import assert from 'node:assert/strict';
import test from 'node:test';
import { combineReadiness, evaluateReadiness } from './check-readiness.mjs';

function successful(main = true, bazel = true) {
  return {
    'check-paths': { result: 'success', outputs: { run_main_ci: String(main), run_bazel_ci: String(bazel) } },
    'check-runner': { result: main ? 'success' : 'skipped', outputs: { provisioning_triggered: '' } },
    lint: { result: main ? 'success' : 'skipped' },
    build: { result: main ? 'success' : 'skipped' },
    'e2e-test': { result: main ? 'success' : 'skipped' },
    'smoke-test': { result: main ? 'success' : 'skipped' },
    bazel: { result: bazel ? 'success' : 'skipped' },
  };
}

test('only successful applicable jobs and verified exclusions pass', () => {
  for (const main of [false, true]) {
    for (const bazel of [false, true]) assert.equal(evaluateReadiness(successful(main, bazel)).ok, true);
  }
});

test('missing, failed, cancelled and skipped applicability never mean docs only', () => {
  for (const result of [undefined, 'failure', 'cancelled', 'skipped']) {
    const jobs = successful(false, false);
    jobs['check-paths'].result = result;
    assert.equal(evaluateReadiness(jobs).ok, false, String(result));
  }
  for (const value of [undefined, '', 'True', '0', 'null']) {
    for (const field of ['run_main_ci', 'run_bazel_ci']) {
      const jobs = successful(false, false);
      jobs['check-paths'].outputs[field] = value;
      assert.equal(evaluateReadiness(jobs).ok, false, `${field}=${value}`);
    }
  }
});

test('every applicable producer must succeed, including Bazel and the runner check', () => {
  for (const name of ['check-runner', 'lint', 'build', 'e2e-test', 'smoke-test', 'bazel']) {
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
  for (const name of ['check-runner', 'lint', 'build', 'e2e-test', 'smoke-test', 'bazel']) {
    for (const result of ['success', 'failure', 'cancelled', undefined]) {
      const jobs = successful(false, false);
      jobs[name].result = result;
      assert.equal(evaluateReadiness(jobs).ok, false, `${name}: ${result}`);
    }
  }
});

test('malformed needs fails closed with a report', () => {
  for (const value of [null, undefined, [], {}, 'success']) assert.equal(evaluateReadiness(value).ok, false);
});

const context = { headSha: 'a'.repeat(40), testedSha: 'b'.repeat(40), runId: '123', runAttempt: '2' };
const attemptStartedAt = '2026-11-11T12:22:40Z';
function currentReceipt() {
  return {
    schemaVersion: 1, ok: true, observationComplete: true, context, attemptStartedAt,
    jobs: [{
      jobId: 2, checkId: 2, name: 'check-paths', headSha: context.headSha,
      status: 'completed', conclusion: 'success', annotationsObserved: true,
      startedAt: '2026-11-11T12:22:41Z',
    }],
  };
}

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
      const graph = evaluateReadiness(successful(applies, applies));
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
      assert.equal(combineReadiness(evaluateReadiness(successful(main, bazel)), runner, context).ok, true);
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

test('skipped and unfinished optional jobs retain their explicit snapshot boundary', () => {
  const runner = currentReceipt();
  runner.jobs.push(
    { ...runner.jobs[0], jobId: 3, name: 'Skipped advisory', conclusion: 'skipped', startedAt: '2026-11-11T12:14:11Z' },
    { ...runner.jobs[0], jobId: 4, name: 'Running advisory', status: 'in_progress', conclusion: null, startedAt: null, annotationsObserved: false },
    { ...runner.jobs[0], jobId: 5, name: 'Validation Check', status: 'in_progress', conclusion: null, startedAt: null, annotationsObserved: false },
  );
  assert.equal(combineReadiness(evaluateReadiness(successful()), runner, context).ok, true);
});
