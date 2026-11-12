import assert from 'node:assert/strict';
import test from 'node:test';
import { effectiveGradleJvmArgs, gradleMemoryPlan } from './gradle-memory-options.mjs';

const context = { headSha: 'a'.repeat(40), testedSha: 'b'.repeat(40), runId: '123', runAttempt: 1,
  invocationId: '11111111-1111-4111-8111-111111111111' };
const jvmArgs = '-Xms512m -Xmx3g -XX:MaxMetaspaceSize=2g';
const privateDirectory = '/runner/temp/gvy-gradle-memory-private';

test('memory observation preserves configured JVM budgets and the last explicit override', () => {
  assert.equal(effectiveGradleJvmArgs(['test'], 'org.gradle.jvmargs=' + jvmArgs + '\n'), jvmArgs);
  assert.equal(effectiveGradleJvmArgs(['-Dorg.gradle.jvmargs=-Xmx1g', '-Dorg.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=1g'], ''), '-Xmx2g -XX:MaxMetaspaceSize=1g');
  for (const properties of ['', 'org.gradle.jvmargs=\n', 'org.gradle.jvmargs=-Xmx3g\\\n -XX:MaxMetaspaceSize=2g\n', 'org.gradle.jvmargs=-Xmx3g\u0000\n']) {
    assert.throws(() => effectiveGradleJvmArgs(['test'], properties), /explicit single-line/);
  }
  for (const option of ['-XX:ErrorFile=/tmp/old.log', '-XX:+ErrorFileToStdout', '-XX:+ErrorFileToStderr']) {
    assert.throws(() => effectiveGradleJvmArgs([], 'org.gradle.jvmargs=-Xmx3g ' + option), /private ErrorFile/);
  }
});

test('one job marker reuses its daemon across producers but rejects other identities', () => {
  const first = gradleMemoryPlan({ context, producer: 'proto-generation', job: 'build', jvmArgs, privateDirectory });
  const second = gradleMemoryPlan({ context: { ...context, invocationId: '22222222-2222-4222-8222-222222222222' }, producer: 'unit', job: 'build', jvmArgs, privateDirectory });
  assert.equal(first.ownerArgument, second.ownerArgument);
  assert.match(first.ownerArgument, /^-XX:ErrorFile=\/runner\/temp\/gvy-gradle-memory-private\/gvy-memory-[a-f0-9]{64}-hs_err_pid%p\.log$/);
  assert.equal(first.jvmArgs, jvmArgs + ' ' + first.ownerArgument);
  for (const changed of [{ runId: '124' }, { runAttempt: 2 }, { testedSha: 'c'.repeat(40) }, { headSha: 'd'.repeat(40) }]) {
    assert.notEqual(gradleMemoryPlan({ context: { ...context, ...changed }, producer: 'unit', job: 'build', jvmArgs, privateDirectory }).ownerArgument, first.ownerArgument);
  }
  assert.notEqual(gradleMemoryPlan({ context, producer: 'unit', job: 'other_job', jvmArgs, privateDirectory }).ownerArgument, first.ownerArgument);
});

test('memory identity excludes JVM argument contents and fails closed for invalid identity', () => {
  const secret = 'PRIVATE-JVM-VALUE';
  const plan = gradleMemoryPlan({ context, producer: 'unit', job: 'build', jvmArgs: jvmArgs + ' -Dexample=' + secret, privateDirectory });
  assert.doesNotMatch(JSON.stringify(plan.identity), /PRIVATE-JVM-VALUE/);
  for (const job of ['', 'build/other', '1invalid', undefined]) {
    assert.throws(() => gradleMemoryPlan({ context, producer: 'unit', job, jvmArgs, privateDirectory }), /GitHub job identity/);
  }
  for (const changed of [{ headSha: 'bad' }, { testedSha: null }, { runId: '' }, { runAttempt: 0 }, { invocationId: 'bad' }]) {
    assert.throws(() => gradleMemoryPlan({ context: { ...context, ...changed }, producer: 'unit', job: 'build', jvmArgs, privateDirectory }), /complete invocation identity/);
  }
  for (const unsafe of ['relative', '/runner/temp/has space', '/runner/temp/%p', '/runner/temp/quote"', '/runner/temp/back\\slash', undefined]) {
    assert.throws(() => gradleMemoryPlan({ context, producer: 'unit', job: 'build', jvmArgs, privateDirectory: unsafe }), /absolute private directory/);
  }
});
