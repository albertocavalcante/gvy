import { statSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { readEvidenceText } from './read-evidence.mjs';

const MAX_BEP_BYTES = 256 * 1024 * 1024;
const METADATA_FIELDS = { headSha: 'HEAD_SHA', testedSha: 'TESTED_SHA', runId: 'RUN_ID', runAttempt: 'RUN_ATTEMPT' };

// Protobuf map/object member order is not part of an event ID's identity.
function canonical(value, depth = 0) {
  if (depth > 16) throw new Error('Event ID nesting exceeds limit');
  if (Array.isArray(value)) return value.map(item => canonical(item, depth + 1));
  if (value && typeof value === 'object') return Object.fromEntries(Object.keys(value).sort().map(key => [key, canonical(value[key], depth + 1)]));
  return value;
}
const eventKey = id => JSON.stringify(canonical(id));
const isObject = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const durationMillis = value => typeof value === 'string' && /^\d+(?:\.\d{1,9})?s$/.test(value) ? Number(value.slice(0, -1)) * 1000 : null;

function validateContext(context) {
  if (!isObject(context) || context.schemaVersion !== 1) throw new Error('Context requires schemaVersion 1');
  for (const field of ['headSha', 'testedSha']) {
    if (typeof context[field] !== 'string' || !/^[a-f0-9]{40}$/.test(context[field])) throw new Error(`Context requires full ${field}`);
  }
  if (typeof context.runId !== 'string' || !/^[A-Za-z0-9_.:-]{1,200}$/.test(context.runId)) throw new Error('Context requires runId');
  if (!['string', 'number'].includes(typeof context.runAttempt) || !/^[1-9][0-9]*$/.test(String(context.runAttempt)) || !Number.isSafeInteger(Number(context.runAttempt))) throw new Error('Context requires positive integer runAttempt');
  if (typeof context.invocationId !== 'string' || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(context.invocationId)) throw new Error('Context requires UUID invocationId');
  if (!['build', 'test'].includes(context.command)) throw new Error('Context requires build or test command');
}

// Only selected metrics are retained. Raw BEP may contain credentials/paths in
// command-line, environment, stdout and log URI fields; summaries never copy them.
export function evaluateEvidence(text, context, exitCode, captureExitCode = 0) {
  const result = {
    schemaVersion: 1, ok: false, complete: false, context: null, errors: [],
    commandExitCode: exitCode, captureExitCode, bazelExitCode: null, eventCount: 0,
    actions: { reported: 0, succeeded: 0, failed: 0, byMnemonic: {} },
    targets: { succeeded: 0, failed: 0, skipped: 0, aborted: 0 },
    tests: { attempts: 0, locallyCachedAttempts: 0, remotelyCachedAttempts: 0, summaries: [] },
    metrics: {}, timing: { started: null, finished: null, elapsedMillis: null },
  };
  const fail = message => result.errors.push(message);
  try {
    validateContext(context);
    result.context = Object.fromEntries(['schemaVersion', 'headSha', 'testedSha', 'runId', 'runAttempt', 'invocationId', 'command'].map(key => [key, context[key]]));
  } catch (error) { fail(error.message); }
  if (!Number.isInteger(exitCode) || exitCode < 0 || exitCode > 255) fail('Invalid command exit code');
  if (!Number.isInteger(captureExitCode) || captureExitCode < 0 || captureExitCode > 255) fail('Invalid capture exit code');
  else if (captureExitCode !== 0) fail('Log capture failed');
  if (typeof text !== 'string' || !text.trim()) { fail('Missing or empty BEP'); return result; }
  if (Buffer.byteLength(text, 'utf8') > MAX_BEP_BYTES) { fail('BEP exceeds 256 MiB limit'); return result; }
  const seen = new Set();
  const pending = new Set();
  let started = 0;
  let finished = 0;
  let metadata = 0;
  let terminal = 0;
  let malformed = false;
  for (const line of text.split(/\r?\n/)) {
    if (!line.trim()) continue;
    if (terminal) { fail('Event after terminal lastMessage'); malformed = true; }
    let event;
    try { event = JSON.parse(line); } catch { fail(`Malformed BEP JSON at event ${result.eventCount + 1}`); malformed = true; break; }
    result.eventCount++;
    if (!isObject(event) || !isObject(event.id) || Object.keys(event.id).length !== 1) { fail('Invalid event ID'); malformed = true; continue; }
    let key;
    try { key = eventKey(event.id); } catch { fail('Invalid or deeply nested event ID'); malformed = true; continue; }
    if (seen.has(key)) { fail('Duplicate event ID'); malformed = true; }
    seen.add(key);
    pending.delete(key);
    if (event.children !== undefined && !Array.isArray(event.children)) { fail('Invalid children list'); malformed = true; continue; }
    for (const child of event.children ?? []) {
      if (!isObject(child) || Object.keys(child).length !== 1) { fail('Invalid child event ID'); malformed = true; continue; }
      let childKey;
      try { childKey = eventKey(child); } catch { fail('Invalid or deeply nested child event ID'); malformed = true; continue; }
      if (!seen.has(childKey)) pending.add(childKey);
    }
    const payloadForId = { started: 'started', buildMetadata: 'buildMetadata', actionCompleted: 'action', targetCompleted: 'completed', testResult: 'testResult', testSummary: 'testSummary', buildFinished: 'finished', buildMetrics: 'buildMetrics' };
    const payload = payloadForId[Object.keys(event.id)[0]];
    if (payload && !isObject(event[payload]) && !isObject(event.aborted)) { fail('Missing tracked event payload'); malformed = true; }
    if (event.lastMessage === true) terminal++;
    if (event.started) {
      started++;
      if (event.started.uuid !== context?.invocationId) fail('BEP invocation does not match context');
      if (event.started.command !== context?.command) fail('BEP command does not match context');
      if (event.started.buildToolVersion !== '9.0.0') fail('BEP tool version does not match pinned Bazel 9.0.0');
      result.timing.started = event.started.startTime ?? null;
    }
    if (event.buildMetadata) {
      metadata++;
      for (const [field, name] of Object.entries(METADATA_FIELDS)) {
        if (event.buildMetadata.metadata?.[name] !== String(context?.[field])) fail(`BEP ${name} does not match context`);
      }
    }
    if (event.finished) {
      finished++;
      const code = event.finished.exitCode;
      // Protobuf JSON may omit scalar zero. The exitCode message must exist.
      result.bazelExitCode = isObject(code) ? code.code ?? 0 : null;
      if (!Number.isInteger(result.bazelExitCode)) fail('Missing or invalid BEP terminal exit code');
      if (result.bazelExitCode !== exitCode) fail('BEP terminal exit code disagrees with command exit code');
      if (result.bazelExitCode !== 0) fail('Bazel invocation failed');
      result.timing.finished = event.finished.finishTime ?? null;
    }
    if (event.action) {
      result.actions.reported++;
      if (event.action.success === true) result.actions.succeeded++;
      else { result.actions.failed++; fail('Failed action'); }
      const mnemonic = event.action.type ?? 'unknown';
      result.actions.byMnemonic = { ...result.actions.byMnemonic, [mnemonic]: (Object.hasOwn(result.actions.byMnemonic, mnemonic) ? result.actions.byMnemonic[mnemonic] : 0) + 1 };
    }
    if (event.completed) {
      if (event.completed.success === true) result.targets.succeeded++;
      else { result.targets.failed++; fail('Failed target'); }
    }
    if (event.aborted) {
      if (event.aborted.reason === 'SKIPPED') result.targets.skipped++;
      else { result.targets.aborted++; fail('Aborted build event'); }
    }
    if (event.testResult) {
      result.tests.attempts++;
      if (event.testResult.cachedLocally) result.tests.locallyCachedAttempts++;
      if (event.testResult.executionInfo?.cachedRemotely) result.tests.remotelyCachedAttempts++;
      if (event.testResult.status !== 'PASSED') fail('Non-passing test attempt');
      if ((event.id.testResult?.attempt ?? 1) > 1) fail('Retried test attempt');
    }
    if (event.testSummary) {
      const summary = event.testSummary;
      result.tests.summaries.push({
        label: event.id.testSummary?.label ?? 'unknown', status: summary.overallStatus ?? 'NO_STATUS',
        runs: summary.totalRunCount ?? 0, cachedRuns: summary.totalNumCached ?? 0,
        durationMillis: durationMillis(summary.totalRunDuration),
      });
      if (summary.overallStatus !== 'PASSED') fail('Non-passing test summary');
      if ((summary.attemptCount ?? 1) > 1) fail('Retried test summary');
    }
    if (event.buildMetrics) {
      const metrics = event.buildMetrics;
      // These protocol fields contain counts and timing, not command/env data.
      for (const field of ['actionSummary', 'timingMetrics', 'memoryMetrics', 'targetMetrics']) {
        if (metrics[field]) result.metrics[field] = metrics[field];
      }
    }
  }
  if (started !== 1) fail('Expected one build started event');
  if (finished !== 1) fail('Expected one build finished event');
  if (metadata !== 1) fail('Expected one build metadata event');
  if (terminal !== 1) fail('Missing final lastMessage');
  if (pending.size) fail(`${pending.size} announced events missing`);
  result.complete = !malformed && started === 1 && finished === 1 && terminal === 1 && pending.size === 0;
  if (context?.command === 'test' && result.bazelExitCode === 0 && !result.tests.summaries.length) fail('Successful test invocation has no test summaries');
  if (result.timing.started && result.timing.finished) {
    const elapsed = Date.parse(result.timing.finished) - Date.parse(result.timing.started);
    if (Number.isFinite(elapsed) && elapsed >= 0) result.timing.elapsedMillis = elapsed;
    else fail('Invalid invocation timestamps');
  }
  result.errors = [...new Set(result.errors)];
  result.ok = result.complete && result.errors.length === 0;
  return result;
}

function main(args) {
  const options = {};
  const allowed = new Set(['--bep', '--context', '--exit-code', '--capture-exit-code', '--profile', '--output']);
  for (let index = 0; index < args.length; index += 2) {
    const name = args[index];
    const value = args[index + 1];
    if (!allowed.has(name) || Object.hasOwn(options, name) || !value || value.startsWith('--')) throw new Error('Unknown, duplicate or missing option');
    options[name] = value;
  }
  for (const name of ['--bep', '--context', '--exit-code', '--output']) if (!options[name]) throw new Error(`Missing ${name}`);
  if (![options['--exit-code'], options['--capture-exit-code'] ?? '0'].every(value => /^(?:0|[1-9][0-9]{0,2})$/.test(value) && Number(value) <= 255)) throw new Error('Invalid exit code option');
  let text = '';
  let context;
  const inputErrors = [];
  try {
    text = readEvidenceText(options['--bep'], MAX_BEP_BYTES);
  } catch { inputErrors.push('Cannot read BEP or BEP exceeds 256 MiB limit'); }
  try { context = JSON.parse(readEvidenceText(options['--context'], 4 * 1024 * 1024)); } catch { inputErrors.push('Cannot read context JSON'); }
  if (options['--profile']) {
    try {
      const info = statSync(options['--profile']);
      if (!info.isFile() || info.size === 0) throw new Error('Empty profile');
    } catch { inputErrors.push('Missing or empty trace profile'); }
  }
  const result = evaluateEvidence(text, context, Number(options['--exit-code']), Number(options['--capture-exit-code'] ?? '0'));
  result.errors.push(...inputErrors);
  result.ok &&= inputErrors.length === 0;
  writeFileSync(options['--output'], `${JSON.stringify(result, null, 2)}\n`);
  console.log(`Bazel evidence: ${result.ok ? 'passed' : 'failed'} (${result.errors.length} errors; ${result.eventCount} events)`);
  process.exitCode = result.ok ? 0 : 1;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try { main(process.argv.slice(2)); } catch (error) { console.error(error.message); process.exitCode = 1; }
}
