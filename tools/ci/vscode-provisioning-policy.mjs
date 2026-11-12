// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import { performance } from 'node:perf_hooks';
import { createHash } from 'node:crypto';
import { isDeepStrictEqual } from 'node:util';
import { vscodeTestVersion, vscodeTestSdkVersion, vscodeTestCliVersion, vscodeTestIdleTimeout } from '../../editors/code/tools/vscode-test-config.mjs';

export const provisioningLimits = Object.freeze({ totalMs: 180000, attemptMs: 80000, backoffMs: 5000, killMs: 2000, verifyMs: 10000, captureBytes: 128 * 1024 });
export const digest = bytes => createHash('sha256').update(bytes).digest('hex');
export const emptyDigest = digest('');
export const resolutionTimeoutMessage = `@vscode/test-electron request timeout out after ${vscodeTestIdleTimeout}ms`;
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const hash = value => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value);
const commit = value => typeof value === 'string' && /^[a-f0-9]{40}$/.test(value);
const integer = (value, max = Number.MAX_SAFE_INTEGER) => Number.isSafeInteger(value) && value >= 0 && value <= max;
const require = (condition, message) => { if (!condition) throw new Error(message); };
function keys(value, expected) {
  require(object(value) && isDeepStrictEqual(Object.keys(value).sort(), expected.slice().sort()), 'invalid-evidence');
}

export function validateProvisioningContext(context) {
  keys(context, ['repository', 'headSha', 'testedSha', 'runId', 'runAttempt', 'workflow', 'jobKey', 'matrix', 'runnerOS', 'runnerArch', 'invocationId']);
  require(/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(context.repository) && commit(context.headSha) && commit(context.testedSha), 'invalid-context');
  require(/^[1-9][0-9]*$/.test(context.runId) && Number.isSafeInteger(Number(context.runId)) && integer(context.runAttempt) && context.runAttempt > 0, 'invalid-context');
  require(/^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/.test(context.invocationId), 'invalid-context');
  const hosts = { 'ubuntu-24.04': 'Linux', 'windows-latest': 'Windows', 'macos-latest': 'macOS' };
  require(hosts[context.matrix] === context.runnerOS && ['X64', 'ARM64'].includes(context.runnerArch), 'invalid-context');
  require(context.workflow === 'Extension CI' ? ['validate', 'test-matrix', 'package'].includes(context.jobKey) :
    context.workflow === 'Nightly Build' && context.jobKey === 'build', 'invalid-context');
  require(context.jobKey === 'test-matrix' ? context.matrix !== 'ubuntu-24.04' : context.matrix === 'ubuntu-24.04', 'invalid-context');
  return context;
}

export function downloadPlatform(context) {
  validateProvisioningContext(context);
  const arch = context.runnerArch === 'ARM64' ? 'arm64' : 'x64';
  return context.runnerOS === 'Windows' ? `win32-${arch}-archive` : context.runnerOS === 'macOS' ? (arch === 'arm64' ? 'darwin-arm64' : 'darwin') : `linux-${arch}`;
}

export const phases = Object.freeze(['configuration', 'version-resolution', 'version-resolved', 'archive-resolution', 'archive-transfer', 'archive-extraction', 'sdk-archive-retry', 'sdk-error', 'cache-hit', 'provisioned', 'verification', 'verified']);
const transitions = {
  configuration: ['version-resolution'], 'version-resolution': ['version-resolved'],
  'version-resolved': ['archive-resolution', 'cache-hit', 'sdk-archive-retry', 'sdk-error'],
  'archive-resolution': ['archive-transfer', 'sdk-archive-retry', 'sdk-error'],
  'archive-transfer': ['archive-extraction', 'provisioned', 'sdk-archive-retry', 'sdk-error'],
  'archive-extraction': ['provisioned', 'sdk-archive-retry', 'sdk-error'],
  'sdk-archive-retry': ['archive-resolution'], provisioned: ['verification'], verification: ['verified'],
};
const outcomes = ['success', 'version-resolution-timeout', 'configuration-error', 'provisioning-error', 'verification-error', 'invalid-evidence', 'deadline-exceeded', 'capture-limit', 'cleanup-incomplete', 'unexpected-output'];

export function validatePhases(events, maxMs = provisioningLimits.totalMs) {
  require(Array.isArray(events) && events.length > 0 && events.length <= 32, 'invalid-phase-evidence');
  let previous;
  for (const event of events) {
    keys(event, ['phase', 'elapsedMs']);
    require(phases.includes(event.phase) && integer(event.elapsedMs, maxMs), 'invalid-phase-evidence');
    if (!previous) require(event.phase === 'configuration', 'invalid-phase-evidence');
    else require(event.elapsedMs >= previous.elapsedMs && transitions[previous.phase]?.includes(event.phase), 'invalid-phase-evidence');
    previous = event;
  }
}

export function emptyCapture() {
  return { stdoutBytes: 0, stdoutSha256: emptyDigest, stderrBytes: 0, stderrSha256: emptyDigest, complete: true };
}

export function validateAttempt(attempt, context, expectedNumber) {
  keys(attempt, ['number', 'contextSha256', 'outcome', 'elapsedMs', 'phases', 'errorLogs', 'error', 'delivery', 'capture', 'cleanupComplete']);
  require(attempt.number === expectedNumber && attempt.contextSha256 === digest(JSON.stringify(context)), 'stale-attempt-evidence');
  require(outcomes.includes(attempt.outcome) && integer(attempt.elapsedMs, provisioningLimits.totalMs) && typeof attempt.cleanupComplete === 'boolean', 'invalid-evidence');
  validatePhases(attempt.phases);
  keys(attempt.capture, ['stdoutBytes', 'stdoutSha256', 'stderrBytes', 'stderrSha256', 'complete']);
  require(integer(attempt.capture.stdoutBytes, provisioningLimits.captureBytes) && integer(attempt.capture.stderrBytes, provisioningLimits.captureBytes) &&
    attempt.capture.stdoutBytes + attempt.capture.stderrBytes <= provisioningLimits.captureBytes && hash(attempt.capture.stdoutSha256) && hash(attempt.capture.stderrSha256) && typeof attempt.capture.complete === 'boolean', 'invalid-capture-evidence');
  require(Array.isArray(attempt.errorLogs) && attempt.errorLogs.length <= 8, 'invalid-error-evidence');
  for (const error of attempt.errorLogs) {
    keys(error, ['phase', 'bytes', 'sha256']);
    require(phases.includes(error.phase) && integer(error.bytes, 16 * 1024) && error.bytes > 0 && hash(error.sha256), 'invalid-error-evidence');
  }
  if (attempt.error !== null) {
    keys(attempt.error, ['code', 'messageSha256']);
    require(outcomes.includes(attempt.error.code) && attempt.error.code !== 'success' && (attempt.error.messageSha256 === null || hash(attempt.error.messageSha256)), 'invalid-error-evidence');
  }
  if (attempt.delivery !== null) {
    keys(attempt.delivery, ['version', 'commit', 'arch', 'platform', 'expectedCommit', 'commitSource']);
    require(attempt.delivery.version === vscodeTestVersion && commit(attempt.delivery.commit) && attempt.delivery.commit === attempt.delivery.expectedCommit &&
      attempt.delivery.arch === (context.runnerArch === 'ARM64' ? 'arm64' : 'x64') && attempt.delivery.platform === downloadPlatform(context) &&
      attempt.delivery.commitSource === 'sdk-new-install-progress', 'invalid-delivery-evidence');
  }
  if (attempt.outcome === 'success') require(attempt.phases.at(-1).phase === 'verified' && attempt.delivery !== null && attempt.error === null && attempt.cleanupComplete && attempt.capture.complete, 'incomplete-success-evidence');
  else require(attempt.error?.code === attempt.outcome && attempt.delivery === null, 'invalid-error-evidence');
  if (attempt.outcome === 'capture-limit') require(!attempt.capture.complete, 'invalid-capture-evidence');
  if (attempt.outcome === 'version-resolution-timeout') {
    require(isDeepStrictEqual(attempt.phases.map(event => event.phase), ['configuration', 'version-resolution']) &&
      attempt.error.messageSha256 === digest(resolutionTimeoutMessage) && attempt.errorLogs.length === 1 && attempt.capture.complete && attempt.cleanupComplete, 'invalid-timeout-evidence');
  }
  return attempt;
}

export function isResolutionTimeout(error, observedPhases) {
  return error instanceof Error && error.constructor?.name === 'TimeoutError' && error.message === resolutionTimeoutMessage &&
    isDeepStrictEqual(observedPhases, ['configuration', 'version-resolution']);
}

export async function provisionWithBudget(context, attempt, { now = () => performance.now(), sleep = ms => new Promise(resolve => setTimeout(resolve, ms)), limits = provisioningLimits } = {}) {
  validateProvisioningContext(context);
  const started = now();
  const deadline = started + limits.totalMs;
  const attempts = [];
  for (let number = 1; number <= 2; number++) {
    const budgetMs = Math.min(limits.attemptMs, Math.floor(deadline - now() - limits.killMs));
    if (budgetMs <= 0) return { ok: false, attempts, elapsedMs: Math.ceil(now() - started), error: 'deadline-exceeded' };
    let result;
    try { result = validateAttempt(await attempt({ context, number, budgetMs }), context, number); }
    catch {
      // Preserve preceding attempts when an invocation cannot supply valid
      // evidence. This row explicitly claims neither capture nor cleanup.
      result = { number, contextSha256: digest(JSON.stringify(context)), outcome: 'invalid-evidence', elapsedMs: 0,
        phases: [{ phase: 'configuration', elapsedMs: 0 }], errorLogs: [], error: { code: 'invalid-evidence', messageSha256: null },
        delivery: null, capture: { ...emptyCapture(), complete: false }, cleanupComplete: false };
    }
    attempts.push(result);
    if (now() >= deadline) return { ok: false, attempts, elapsedMs: Math.ceil(now() - started), error: 'deadline-exceeded' };
    if (result.outcome === 'success') return { ok: true, attempts, elapsedMs: Math.ceil(now() - started), error: null };
    if (result.outcome !== 'version-resolution-timeout' || number === 2) return { ok: false, attempts, elapsedMs: Math.ceil(now() - started), error: result.outcome };
    if (deadline - now() <= limits.backoffMs + limits.killMs) return { ok: false, attempts, elapsedMs: Math.ceil(now() - started), error: 'deadline-exceeded' };
    await sleep(limits.backoffMs);
  }
}

export function createProvisioningReport(context, result) {
  const report = {
    schemaVersion: 1, context, requested: { version: vscodeTestVersion, platform: downloadPlatform(context) },
    tools: { sdk: vscodeTestSdkVersion, cli: vscodeTestCliVersion }, limits: provisioningLimits,
    provisioning: result, integration: { state: 'unexecuted', outcome: null, editorLogs: null }, ok: false,
  };
  validateProvisioningReport(report, context);
  return report;
}

export function validateProvisioningReport(report, expectedContext) {
  keys(report, ['schemaVersion', 'context', 'requested', 'tools', 'limits', 'provisioning', 'integration', 'ok']);
  validateProvisioningContext(report.context);
  require(report.schemaVersion === 1 && isDeepStrictEqual(report.context, expectedContext), 'stale-provisioning-evidence');
  require(isDeepStrictEqual(report.requested, { version: vscodeTestVersion, platform: downloadPlatform(expectedContext) }) &&
    isDeepStrictEqual(report.tools, { sdk: vscodeTestSdkVersion, cli: vscodeTestCliVersion }) && isDeepStrictEqual(report.limits, provisioningLimits), 'invalid-provisioning-policy');
  const result = report.provisioning;
  keys(result, ['ok', 'attempts', 'elapsedMs', 'error']);
  require(Array.isArray(result.attempts) && result.attempts.length <= 2 && integer(result.elapsedMs, provisioningLimits.totalMs + provisioningLimits.killMs) && typeof result.ok === 'boolean', 'invalid-provisioning-evidence');
  result.attempts.forEach((attempt, index) => validateAttempt(attempt, expectedContext, index + 1));
  if (result.attempts.length === 2) require(result.attempts[0].outcome === 'version-resolution-timeout', 'unapproved-retry');
  require(result.error === null || outcomes.includes(result.error), 'invalid-provisioning-evidence');
  require(result.ok === (result.error === null && result.attempts.at(-1)?.outcome === 'success' && result.elapsedMs < provisioningLimits.totalMs), 'inconsistent-provisioning-verdict');
  keys(report.integration, ['state', 'outcome', 'editorLogs']);
  require(['unexecuted', 'invoked'].includes(report.integration.state), 'invalid-integration-evidence');
  if (report.integration.state === 'unexecuted') require(report.integration.outcome === null && report.integration.editorLogs === null, 'invalid-integration-evidence');
  else {
    require(result.ok && ['success', 'failure'].includes(report.integration.outcome), 'invalid-integration-evidence');
    keys(report.integration.editorLogs, ['present', 'files']);
    require(typeof report.integration.editorLogs.present === 'boolean' && integer(report.integration.editorLogs.files, 5000) && report.integration.editorLogs.present === (report.integration.editorLogs.files > 0), 'invalid-integration-evidence');
  }
  require(report.ok === (result.ok && report.integration.state === 'invoked' && report.integration.outcome === 'success' && report.integration.editorLogs.present), 'inconsistent-editor-verdict');
  const text = JSON.stringify(report);
  require(Buffer.byteLength(text) <= 32 * 1024, 'provisioning-evidence-limit');
  return report;
}
