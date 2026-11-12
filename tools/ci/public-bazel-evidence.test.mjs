import assert from 'node:assert/strict';
import test from 'node:test';
import { bazelChildEnvironment } from './public-bazel-evidence.mjs';

const canary = 'synthetic-canary-must-not-be-published';

test('Bazel child inherits only explicit tool/platform variables and no parent credentials/context', () => {
  const env = bazelChildEnvironment({
    PATH: '/tools', HOME: '/home/build', USER: 'build', JAVA_HOME: '/jdk', TMPDIR: '/scratch',
    BAZELISK_HOME: '/bazelisk-cache', BAZELISK_GITHUB_TOKEN: canary, GH_TOKEN: canary,
    GITHUB_TOKEN: canary, SECRET_NOT_NAMED_TOKEN: canary, GITHUB_RUN_ID: '123',
    GITHUB_EVENT_PATH: '/private/event.json', JDK_JAVA_OPTIONS: canary, JAVA_TOOL_OPTIONS: canary,
  });
  assert.equal(env.PATH, '/tools');
  assert.equal(env.HOME, '/home/build');
  assert.equal(env.JAVA_HOME, '/jdk');
  assert.equal(env.BAZELISK_HOME, '/bazelisk-cache');
  assert.equal(JSON.stringify(env).includes(canary), false);
  assert.equal(Object.keys(env).some(key => key.startsWith('GITHUB_')), false);
  assert.equal(Object.hasOwn(env, 'SECRET_NOT_NAMED_TOKEN'), false);
});

import { gzipSync } from 'node:zlib';
import { evaluateEvidence } from './check-bazel-evidence.mjs';
import { projectBep, projectProfile } from './public-bazel-evidence.mjs';

const context = { schemaVersion: 1, headSha: 'a'.repeat(40), testedSha: 'b'.repeat(40), runId: '123', runAttempt: 1, invocationId: '12345678-1234-4234-8234-123456789abc', command: 'test' };
function rawEvents(code = 0) {
  const events = [
    { id: { started: {} }, started: { uuid: context.invocationId, command: 'test', buildToolVersion: '9.0.0', optionsDescription: canary, workingDirectory: canary, startTime: '2026-11-11T00:00:00Z' } },
    { id: { buildMetadata: {} }, buildMetadata: { metadata: { HEAD_SHA: context.headSha, TESTED_SHA: context.testedSha, RUN_ID: '123', RUN_ATTEMPT: '1', EXTRA: canary } } },
    { id: { unstructuredCommandLine: {} }, unstructuredCommandLine: { args: [canary] } },
    { id: { structuredCommandLine: { commandLineLabel: 'original' } }, structuredCommandLine: { sections: [{ optionList: { option: [{ optionName: 'client_env', optionValue: canary }] } }] } },
    { id: { optionsParsed: {} }, optionsParsed: { startupOptions: [canary], cmdLine: [canary] } },
    { id: { fetch: { url: `https://example.invalid/?token=${canary}` } }, fetch: { success: true } },
    { id: { actionCompleted: { primaryOutput: canary } }, action: { success: !code, type: canary, commandLine: [canary], stdout: { contents: canary }, failureDetail: { message: canary } } },
    { id: { testResult: { label: '//common:test', attempt: 1, run: 1, shard: 0 } }, testResult: { status: code ? 'FAILED' : 'PASSED', cachedLocally: true, testAttemptDuration: '0.1s', warning: [canary], testActionOutput: [{ uri: canary }] } },
    { id: { testSummary: { label: '//common:test' } }, testSummary: { overallStatus: code ? 'FAILED' : 'PASSED', totalRunCount: 1, totalNumCached: 1, totalRunDuration: '0.1s', failed: [{ uri: canary }] } },
    { id: { buildMetrics: {} }, buildMetrics: { actionSummary: { actionsExecuted: '2', actionData: [{ mnemonic: canary, actionsExecuted: '2' }], extraCommand: canary }, timingMetrics: { wallTimeInMs: '1000' }, arbitrary: canary } },
    { id: { buildFinished: {} }, finished: { exitCode: { code, name: canary }, finishTime: '2026-11-11T00:00:01Z', failureDetail: { message: canary } } },
    { id: { buildToolLogs: {} }, buildToolLogs: { log: [{ contents: canary, uri: canary }] }, lastMessage: true },
  ];
  events[0].children = events.slice(1).map(event => event.id);
  return events;
}
const lines = events => events.map(event => JSON.stringify(event)).join('\n') + '\n';

test('BEP projection removes every command/env/URI surface and keeps graph, status, timing and numeric metrics', () => {
  const projected = projectBep(lines(rawEvents()), context);
  assert.equal(projected.includes(canary), false);
  assert.equal(projectBep(projected, context, { publicInput: true }), projected);
  const result = evaluateEvidence(projected, context, 0);
  assert.equal(result.ok, true, result.errors.join('\n'));
  assert.equal(result.eventCount, rawEvents().length);
  assert.equal(result.actions.succeeded, 1);
  assert.equal(result.tests.summaries[0].cachedRuns, 1);
  assert.equal(result.timing.elapsedMillis, 1000);
  assert.equal(result.metrics.actionSummary.actionsExecuted, '2');
  assert.match(result.tests.summaries[0].label, /^opaque:[a-f0-9]{64}$/);
  const failed = evaluateEvidence(projectBep(lines(rawEvents(3)), context), context, 3);
  assert.equal(failed.ok, false);
  assert.equal(failed.complete, true);
  assert.equal(failed.bazelExitCode, 3);
  assert.equal(failed.tests.summaries[0].status, 'FAILED');
});

test('privacy projection rejects malformed/schema/depth/limit inputs without echoing raw snippets', () => {
  for (const input of ['{ "' + canary, lines([{ id: { unsupported: { secret: canary } } }]), lines([{ id: { started: { unexpected: canary } } }])]) {
    assert.throws(() => projectBep(input, context), error => !error.message.includes(canary));
  }
  const bad = rawEvents();
  bad[9].buildMetrics.actionSummary.actionsExecuted = canary;
  assert.throws(() => projectBep(lines(bad), context), /numeric/);
  assert.throws(() => projectProfile(Buffer.from(canary)), error => !error.message.includes(canary));
});

test('profile projection keeps numeric timeline/counters and drops metadata, names and all arbitrary args', () => {
  const raw = { otherData: { command: canary }, traceEvents: [
    { ph: 'M', name: 'thread_name', pid: 1, tid: 1, args: { name: canary } },
    { ph: 'X', name: canary, cat: canary, pid: 1, tid: 1, ts: 1, dur: 2, args: { command: canary, nested: { token: canary } } },
    { ph: 'C', name: canary, pid: 1, tid: 1, ts: 2, args: { [canary]: 3, ignored: canary } },
  ] };
  const publicBytes = projectProfile(gzipSync(JSON.stringify(raw)));
  const decoded = publicBytes.toString('utf8');
  assert.equal(decoded.includes(canary), false);
  const profile = JSON.parse(decoded);
  assert.equal(profile.traceEvents.length, 2);
  assert.equal(profile.traceEvents[0].dur, 2);
  assert.deepEqual(profile.traceEvents[1].args, { counter_1: 3 });
  assert.deepEqual(projectProfile(publicBytes, { publicInput: true }), publicBytes);
});

test('legitimate wide event streams remain complete and retry fields survive opaque graph projection', () => {
  const events = rawEvents();
  const progress = Array.from({ length: 500 }, (_, opaqueCount) => ({ id: { progress: { opaqueCount } }, progress: { stdout: canary } }));
  events[0].children.push(...progress.map(event => event.id));
  events.splice(2, 0, ...progress);
  const projected = projectBep(lines(events), context);
  assert.equal(evaluateEvidence(projected, context, 0).ok, true);
  assert.equal(projected.includes(canary), false);
  const retried = rawEvents();
  retried[7].id.testResult.attempt = 2;
  assert.match(evaluateEvidence(projectBep(lines(retried), context), context, 0).errors.join('\n'), /retried/i);
});

test('trace clock offsets may be negative while finite timeline information stays intact', () => {
  const profile = { traceEvents: [{ ph: 'X', name: 'phase', pid: 1, tid: 1, ts: -10, dur: 1 }] };
  const projected = projectProfile(gzipSync(JSON.stringify(profile)));
  assert.equal(JSON.parse(projected).traceEvents[0].ts, -10);
});

test('invalid UTF-8 profiles and decompression bombs fail closed without a raw fallback', () => {
  const invalid = Buffer.concat([Buffer.from('{"traceEvents":[{"ph":"X","name":"'), Buffer.from([0xff]), Buffer.from('","ts":0,"dur":1}]}')]);
  assert.throws(() => projectProfile(gzipSync(invalid)), /profile/);
  assert.throws(() => projectProfile(Buffer.alloc(32 * 1024 * 1024 + 1)), /oversized/);
  const bomb = gzipSync(Buffer.alloc(128 * 1024 * 1024 + 1, 0x20));
  assert.throws(() => projectProfile(bomb), /oversized/);
});

test('deep unknown ID values fail with one constant diagnostic at different magnitudes', () => {
  const errors = [];
  for (const depth of [64, 4096]) {
    let nested = canary;
    for (let index = 0; index < depth; index++) nested = { nested };
    try { projectBep(lines([{ id: { configuration: { id: nested } } }]), context); }
    catch (error) { errors.push(error.message); }
  }
  assert.deepEqual(errors, ['Invalid BEP string identifier', 'Invalid BEP string identifier']);
});

test('opaque-looking raw IDs cannot collapse a missing graph child into a different delivered event', async () => {
  const { createHash } = await import('node:crypto');
  const rawId = 'raw-X';
  const opaqueLookingRawId = 'opaque:' + createHash('sha256').update(JSON.stringify(rawId)).digest('hex');
  const events = rawEvents();
  events[0].children.push({ namedSet: { id: opaqueLookingRawId } });
  events.splice(2, 0, { id: { namedSet: { id: rawId } }, namedSetOfFiles: {} });
  assert.equal(evaluateEvidence(lines(events), context, 0).complete, false);
  const projected = evaluateEvidence(projectBep(lines(events), context), context, 0);
  assert.equal(projected.complete, false);
  assert.equal(projected.ok, false);
});

test('opaque-looking strings remain distinct in nested IDs, metric names and trace flow identities', async () => {
  const { createHash } = await import('node:crypto');
  const raw = 'raw-name';
  const disguised = 'opaque:' + createHash('sha256').update(JSON.stringify(raw)).digest('hex');
  const events = rawEvents();
  events[9].buildMetrics.actionSummary.actionData = [{ mnemonic: raw, actionsExecuted: '1' }, { mnemonic: disguised, actionsExecuted: '1' }];
  events[9].buildMetrics.actionSummary.runnerCount = [{ name: raw, count: 1 }, { name: disguised, count: 1 }];
  events[0].children.push({ configuration: { id: raw } }, { configuration: { id: disguised } });
  events.splice(2, 0, { id: { configuration: { id: raw } }, configuration: {} }, { id: { configuration: { id: disguised } }, configuration: {} });
  events[0].publicEvidenceContract = 'gvy-public-bazel-v1';
  const projected = projectBep(lines(events), context);
  const result = evaluateEvidence(projected, context, 0);
  assert.equal(result.complete, true);
  const metrics = result.metrics.actionSummary;
  assert.notEqual(metrics.actionData[0].mnemonic, metrics.actionData[1].mnemonic);
  assert.notEqual(metrics.runnerCount[0].name, metrics.runnerCount[1].name);
  const profile = { traceEvents: [raw, disguised].map((id, index) => ({ ph: index ? 'f' : 's', name: 'flow', id, pid: 1, tid: 1, ts: index })) };
  const bytes = projectProfile(gzipSync(JSON.stringify(profile)));
  const flows = JSON.parse(bytes).traceEvents;
  assert.notEqual(flows[0].id, flows[1].id);
  assert.deepEqual(projectProfile(bytes, { publicInput: true }), bytes);
});

test('unknown nested identifier fields cannot collapse a missing child into another configuration', () => {
  const events = rawEvents();
  const announced = { targetCompleted: { label: '//target:t', configuration: { id: 'C', unexpected: 'A' } } };
  const delivered = { targetCompleted: { label: '//target:t', configuration: { id: 'C', unexpected: 'B' } } };
  events[0].children.push(announced);
  events.splice(2, 0, { id: delivered, completed: { success: true } });
  assert.equal(evaluateEvidence(lines(events), context, 0).complete, false);
  assert.throws(() => projectBep(lines(events), context), /identifier/);
});

test('distinct lone-surrogate strings retain distinct opaque graph identities', () => {
  const events = rawEvents();
  events[0].children.push({ namedSet: { id: '\ud800' } });
  events.splice(2, 0, { id: { namedSet: { id: '\ud801' } }, namedSetOfFiles: {} });
  assert.equal(evaluateEvidence(lines(events), context, 0).complete, false);
  const projected = evaluateEvidence(projectBep(lines(events), context), context, 0);
  assert.equal(projected.complete, false);
  assert.equal(projected.ok, false);
});

test('overflowing and fractional numeric trace flow IDs are rejected while safe integers remain distinct', () => {
  for (const id of ['1e400', '-1e400', '1.5', '9007199254740992']) {
    const trace = '{"traceEvents":[{"ph":"s","name":"flow","id":' + id + ',"ts":0}]}';
    assert.throws(() => projectProfile(gzipSync(trace)), /flow ID/);
  }
  const profile = { traceEvents: [-Number.MAX_SAFE_INTEGER, Number.MAX_SAFE_INTEGER].map((id, index) => ({ ph: index ? 'f' : 's', name: 'flow', id, ts: index })) };
  const events = JSON.parse(projectProfile(gzipSync(JSON.stringify(profile)))).traceEvents;
  assert.notEqual(events[0].id, events[1].id);
});
