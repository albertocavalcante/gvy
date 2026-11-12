// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import test from 'node:test';
import { evaluateEvidence } from './check-bazel-evidence.mjs';
import { projectBep } from './public-bazel-evidence.mjs';

const context = {
  schemaVersion: 1,
  headSha: 'a'.repeat(40),
  testedSha: 'b'.repeat(40),
  runId: 'local-123',
  runAttempt: '1',
  invocationId: '12345678-1234-4234-8234-123456789abc',
  command: 'test',
};

function events() {
  const payloads = [
    { id: { started: {} }, started: { uuid: context.invocationId, command: 'test', buildToolVersion: '9.0.0', startTime: '2026-11-11T00:00:00Z' } },
    { id: { buildMetadata: {} }, buildMetadata: { metadata: { HEAD_SHA: context.headSha, TESTED_SHA: context.testedSha, RUN_ID: context.runId, RUN_ATTEMPT: context.runAttempt } } },
    { id: { actionCompleted: { primaryOutput: 'out' } }, action: { success: true, type: 'KotlinCompile' } },
    { id: { targetCompleted: { label: '//common:test', configuration: { id: 'c' } } }, completed: { success: true } },
    { id: { testResult: { label: '//common:test', run: 1, shard: 1, attempt: 1 } }, testResult: { status: 'PASSED', cachedLocally: true, testAttemptDuration: '0.125s' } },
    { id: { testSummary: { label: '//common:test' } }, testSummary: { overallStatus: 'PASSED', totalRunCount: 1, totalNumCached: 1, totalRunDuration: '0.125s' } },
    { id: { buildFinished: {} }, finished: { exitCode: { name: 'SUCCESS', code: 0 }, finishTime: '2026-11-11T00:00:01Z' } },
    { id: { buildMetrics: {} }, buildMetrics: { actionSummary: { actionsCreated: '2', actionsExecuted: '1', runnerCount: [{ name: 'worker', count: 1 }], actionCacheStatistics: { hits: 1 } }, timingMetrics: { wallTimeInMs: '1000', executionPhaseTimeInMs: '300' } } },
    { id: { buildToolLogs: {} }, buildToolLogs: {}, lastMessage: true },
  ];
  payloads[0].children = payloads.slice(1).map(event => event.id);
  return payloads;
}
const serialize = values => values.map(value => JSON.stringify(value)).join('\n') + '\n';
const evaluate = (values = events(), settings = context, exitCode = 0) => evaluateEvidence(serialize(values), settings, exitCode);

test('binds complete evidence to source, run, invocation and command and reports cache/timing', () => {
  const result = evaluate();
  assert.equal(result.ok, true, result.errors.join('\n'));
  assert.equal(result.complete, true);
  assert.equal(result.context.testedSha, context.testedSha);
  assert.deepEqual(result.actions, { reported: 1, succeeded: 1, failed: 0, byMnemonic: { KotlinCompile: 1 } });
  assert.equal(result.tests.summaries[0].cachedRuns, 1);
  assert.equal(result.tests.locallyCachedAttempts, 1);
  assert.equal(result.metrics.actionSummary.actionsExecuted, '1');
  assert.equal(result.timing.elapsedMillis, 1000);
});

test('requires positive test execution evidence even for cached passing summaries', () => {
  const coherent = values => {
    values[0].children = values.slice(1).map(event => event.id);
    return values;
  };
  for (const projected of [false, true]) {
    const check = values => {
      let text = serialize(coherent(values));
      if (projected) {
        try { text = projectBep(text, context); }
        catch (error) {
          assert.match(error.message, /Invalid numeric evidence field|Invalid typed test identity|Invalid BEP string identifier/);
          return { ok: false };
        }
      }
      return evaluateEvidence(text, context, 0);
    };
    const absent = events().filter(event => !event.testResult);
    assert.equal(check(absent).ok, false, 'a cached summary alone cannot prove an attempt');
    for (const count of [undefined, 0, -1, 0.5, '1', 2]) {
      const values = events();
      values[5].testSummary.totalRunCount = count;
      assert.equal(check(values).ok, false, `unobserved or invalid run count ${count}`);
    }
    for (const count of [-1, 0, 0.5, '1', 2]) {
      const values = events();
      values[5].testSummary.totalNumCached = count;
      assert.equal(check(values).ok, false, `invalid cache count ${count}`);
    }
    const otherTarget = events();
    otherTarget[4].id.testResult.label = '//other:test';
    assert.equal(check(otherTarget).ok, false, 'unrelated results cannot satisfy a summary');
    const otherConfiguration = events();
    otherConfiguration[4].id.testResult.configuration = { id: 'other' };
    assert.equal(check(otherConfiguration).ok, false, 'a different configuration cannot satisfy a summary');
    const wrongKinds = events();
    wrongKinds[4].id = { progress: { opaqueCount: 1 } };
    wrongKinds[5].id = { progress: { opaqueCount: 2 } };
    assert.equal(check(wrongKinds).ok, false, 'untyped payloads cannot establish matching test identities');
    for (const invalid of [{}, { label: '' }, { label: 1 }, { label: '//common:test', configuration: {} }, { label: '//common:test', configuration: { id: 1 } }]) {
      const values = events();
      values[4].id.testResult = invalid;
      values[5].id.testSummary = invalid;
      assert.equal(check(values).ok, false, 'malformed test identities cannot establish execution');
    }
    for (const cached of ['fresh', 'local', 'remote']) {
      const values = events();
      delete values[4].testResult.cachedLocally;
      values[5].testSummary.totalNumCached = cached === 'fresh' ? 0 : 1;
      if (cached === 'local') values[4].testResult.cachedLocally = true;
      if (cached === 'remote') values[4].testResult.executionInfo = { cachedRemotely: true };
      assert.equal(check(values).ok, true, `${cached} attempt remains valid`);
    }
  }
});

test('fails closed for missing, malformed, truncated and incomplete event streams', () => {
  for (const text of ['', '{', serialize(events()).slice(0, -12), serialize(events().slice(0, -1))]) {
    assert.equal(evaluateEvidence(text, context, 0).ok, false);
  }
  const missing = events();
  missing.splice(2, 1);
  assert.match(evaluate(missing).errors.join('\n'), /announced.*missing/i);
  const duplicate = events();
  duplicate.splice(2, 0, duplicate[1]);
  assert.match(evaluate(duplicate).errors.join('\n'), /duplicate/i);
  const afterTerminal = events();
  afterTerminal.push({ id: { progress: { opaqueCount: 1 } }, progress: {} });
  assert.equal(evaluate(afterTerminal).ok, false);
});

test('canonicalizes graph IDs independently of protobuf JSON key order', () => {
  const reordered = events();
  reordered[0].children[2] = { targetCompleted: { configuration: { id: 'c' }, label: '//common:test' } };
  assert.equal(evaluate(reordered).ok, true);
});

test('rejects stale SHA, invocation, run, version, command and absent metadata', () => {
  for (const field of ['headSha', 'testedSha', 'runId', 'runAttempt', 'invocationId', 'command']) {
    const replacement = field === 'command' ? 'build' : field === 'runAttempt' ? '2' : field === 'invocationId' ? '87654321-1234-4234-8234-123456789abc' : field.endsWith('Sha') ? 'c'.repeat(40) : 'other-run';
    assert.equal(evaluate(events(), { ...context, [field]: replacement }).ok, false, field);
  }
  const wrongVersion = events();
  wrongVersion[0].started.buildToolVersion = '8.6.0';
  assert.equal(evaluate(wrongVersion).ok, false);
  const noMetadata = events();
  delete noMetadata[1].buildMetadata;
  assert.equal(evaluate(noMetadata).ok, false);
});

test('rejects invalid context and exit codes without trusting artifacts', () => {
  for (const field of Object.keys(context)) {
    const invalid = { ...context };
    delete invalid[field];
    assert.equal(evaluate(events(), invalid).ok, false, field);
  }
  for (const invalid of [{ runAttempt: '0' }, { headSha: 'short' }, { invocationId: 'bad' }, { schemaVersion: 2 }, { command: 'query' }]) {
    assert.equal(evaluate(events(), { ...context, ...invalid }).ok, false);
  }
  for (const exit of [-1, 1.5, undefined, NaN, '0']) assert.equal(evaluateEvidence(serialize(events()), context, exit).ok, false);
});

test('keeps build, action and test failures and catches flaky or retried tests', () => {
  const failed = events();
  failed[2].action = { type: 'KotlinCompile', exitCode: 1 };
  failed[3].completed.success = false;
  failed[4].testResult.status = 'FAILED';
  failed[5].testSummary.overallStatus = 'FAILED';
  failed[6].finished.exitCode = { name: 'TESTS_FAILED', code: 3 };
  const result = evaluate(failed, context, 3);
  assert.equal(result.ok, false);
  assert.equal(result.complete, true);
  assert.equal(result.actions.failed, 1);
  assert.equal(result.tests.summaries[0].status, 'FAILED');
  assert.equal(result.bazelExitCode, 3);
  assert.equal(evaluate(failed, context, 0).ok, false);
  for (const status of ['FLAKY', 'TIMEOUT', 'NO_STATUS']) {
    const flaky = events();
    flaky[5].testSummary.overallStatus = status;
    assert.equal(evaluate(flaky).ok, false, status);
  }
  const retry = events();
  retry[4].id.testResult.attempt = 2;
  retry[0].children[3] = retry[4].id;
  assert.match(evaluate(retry).errors.join('\n'), /retr/i);
});

test('rejects aborted targets and successful test commands with no test summaries', () => {
  const aborted = events();
  aborted[3] = { id: aborted[3].id, aborted: { reason: 'ANALYSIS_FAILURE' } };
  assert.equal(evaluate(aborted).ok, false);
  const noTests = events();
  delete noTests[5].testSummary;
  assert.equal(evaluate(noTests).ok, false);
});

for (const command of ['build', 'test']) {
  for (const projected of [false, true]) {
    test(`${command} rejects skipped target coverage in ${projected ? 'projected' : 'raw'} complete evidence`, () => {
      const settings = { ...context, command };
      const values = events().filter(event => command === 'test' || (!event.testResult && !event.testSummary));
      values[0].started.command = command;
      values[0].children = values.slice(1).map(event => event.id);
      const encode = () => projected ? projectBep(serialize(values), settings) : serialize(values);
      assert.equal(evaluateEvidence(encode(), settings, 0).ok, true);
      values[3] = { id: values[3].id, aborted: { reason: 'SKIPPED' } };
      const result = evaluateEvidence(encode(), settings, 0);
      assert.equal(result.ok, false);
      assert.equal(result.complete, true);
      assert.equal(result.targets.skipped, 1);
      assert.equal(result.bazelExitCode, 0);
      assert.ok(result.errors.includes('Skipped target requires reviewed disposition'));
    });
  }
}

test('CLI writes a failure summary for missing input and enforces option syntax', t => {
  const dir = mkdtempSync(join(tmpdir(), 'gvy-evidence-test-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const contextPath = join(dir, 'context.json');
  const bep = join(dir, 'bep.json');
  const output = join(dir, 'summary.json');
  writeFileSync(contextPath, JSON.stringify(context));
  const args = ['tools/ci/check-bazel-evidence.mjs', '--bep', bep, '--context', contextPath, '--exit-code', '0', '--output', output];
  const run = extra => spawnSync(process.execPath, [...args, ...extra], { encoding: 'utf8' });
  assert.notEqual(run([]).status, 0);
  assert.equal(JSON.parse(readFileSync(output, 'utf8')).ok, false);
  writeFileSync(bep, serialize(events()));
  assert.equal(run([]).status, 0);
  assert.equal(JSON.parse(readFileSync(output, 'utf8')).ok, true);
  assert.notEqual(run(['--unknown']).status, 0);
  assert.notEqual(run(['--exit-code', '0']).status, 0);
  assert.notEqual(run(['--exit-code', '0junk']).status, 0);
});

test('log capture failure remains failure even with successful Bazel evidence', () => {
  const result = evaluateEvidence(serialize(events()), context, 0, 1);
  assert.equal(result.ok, false);
  assert.equal(result.bazelExitCode, 0);
  assert.equal(result.captureExitCode, 1);
  assert.match(result.errors.join('\n'), /capture/i);
});

test('CLI requires a nonempty profile when requested and preserves valid BEP status', t => {
  const dir = mkdtempSync(join(tmpdir(), 'gvy-evidence-profile-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const bep = join(dir, 'bep.json');
  const contextPath = join(dir, 'context.json');
  const profile = join(dir, 'profile.json.gz');
  const output = join(dir, 'summary.json');
  writeFileSync(bep, serialize(events()));
  writeFileSync(contextPath, JSON.stringify({ ...context, runAttempt: 1 }));
  const run = () => spawnSync(process.execPath, ['tools/ci/check-bazel-evidence.mjs', '--bep', bep, '--context', contextPath,
    '--exit-code', '0', '--capture-exit-code', '0', '--profile', profile, '--output', output]);
  assert.equal(run().status, 1);
  assert.equal(JSON.parse(readFileSync(output, 'utf8')).bazelExitCode, 0);
  writeFileSync(profile, '');
  assert.equal(run().status, 1);
  writeFileSync(profile, 'nonempty-profile');
  assert.equal(run().status, 0);
});

test('rejects missing tracked payloads, deeply nested IDs and invalid stream records', () => {
  const missing = events();
  delete missing[2].action;
  assert.equal(evaluate(missing).ok, false);
  const nested = events();
  let id = {};
  for (let i = 0; i < 100; i++) id = { nested: id };
  nested[2].id = id;
  assert.equal(evaluate(nested).ok, false);
  for (const value of [null, [], 123]) {
    assert.equal(evaluateEvidence(`${JSON.stringify(value)}\n`, context, 0).ok, false);
  }
});

test('summary does not copy arbitrary context, command, stdout or environment fields', () => {
  const secret = 'should-never-appear';
  const noisy = events();
  noisy[0].started.optionsDescription = secret;
  noisy[2].action.commandLine = [secret];
  noisy[2].action.stdout = { contents: secret };
  const result = evaluate(noisy, { ...context, secret });
  assert.equal(result.ok, true);
  assert.equal(JSON.stringify(result).includes(secret), false);
});
