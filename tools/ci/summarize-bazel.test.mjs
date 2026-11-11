import assert from 'node:assert/strict';
import test from 'node:test';
import { summarizeInvocations } from './summarize-bazel.mjs';

const context = { headSha: 'a'.repeat(40), testedSha: 'b'.repeat(40), runId: '123', runAttempt: '2' };
const receipt = (command) => ({ ok: true, complete: true, producer: `bazel-${command}`, context: { ...context, command }, timing: {}, tests: { summaries: [] } });

test('requires one complete build and test from this source, checkout, run and attempt', () => {
  const receipts = [receipt('build'), receipt('test')];
  assert.equal(summarizeInvocations(receipts, context).ok, true);
  for (const field of Object.keys(context)) {
    const bad = structuredClone(receipts);
    bad[1].context[field] = 'stale';
    assert.equal(summarizeInvocations(bad, context).ok, false, field);
  }
  for (const values of [[], [receipts[0]], [...receipts, receipts[0]]]) assert.equal(summarizeInvocations(values, context).ok, false);
});

test('failed or incomplete evidence cannot pass', () => {
  for (const field of ['ok', 'complete']) {
    const receipts = [receipt('build'), receipt('test')];
    receipts[1][field] = false;
    assert.equal(summarizeInvocations(receipts, context).ok, false);
  }
  assert.equal(summarizeInvocations([null], context).ok, false);
});
