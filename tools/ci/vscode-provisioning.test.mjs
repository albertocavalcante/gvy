// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict';
import { execFileSync, spawn, spawnSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { EventEmitter } from 'node:events';
import { existsSync, mkdtempSync, mkdirSync, readFileSync, realpathSync, renameSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import test from 'node:test';
import { createProvisioningReport, digest, downloadPlatform, emptyCapture, isResolutionTimeout, provisionWithBudget, provisioningLimits, resolutionTimeoutMessage, validatePhases, validateProvisioningReport } from './vscode-provisioning-policy.mjs';
import { probeVscodeVersion, runSdkProvisioning } from './vscode-provisioning-worker.mjs';
import { launchProvisioningAttempt } from './vscode-provisioning-process.mjs';
import { auditEditorProvisioning, invokeIntegrationOnce, provisioningContext } from './provision-vscode.mjs';
import { validateVscodeTestInputs, vscodeTestConfigurations, vscodeTestVersion } from '../../editors/code/tools/vscode-test-config.mjs';

const context = { repository: 'albertocavalcante/gvy', headSha: 'a'.repeat(40), testedSha: 'b'.repeat(40), runId: '123', runAttempt: 2, workflow: 'Extension CI', jobKey: 'validate', matrix: 'ubuntu-24.04', runnerOS: 'Linux', runnerArch: 'X64', invocationId: '11111111-1111-4111-8111-111111111111' };
const releaseMetadata = { version: vscodeTestVersion, platform: 'linux-x64', quality: 'stable', commit: 'c'.repeat(40), archiveSha256: 'e'.repeat(64) };
const delivered = { version: vscodeTestVersion, commit: 'c'.repeat(40), arch: 'x64', platform: 'linux-x64', expectedCommit: 'c'.repeat(40), advertisedArchiveSha256: 'e'.repeat(64), commitSource: 'vscode-update-api-exact-version' };
const verified = { expected: { ...releaseMetadata, arch: 'x64' }, observed: { version: vscodeTestVersion, commit: 'c'.repeat(40), arch: 'x64' }, metadataSource: 'vscode-update-api-exact-version', sdkProgressRevision: 'c'.repeat(40), matched: true };
const successfulPhases = ['configuration', 'version-resolution', 'version-resolved', 'archive-resolution', 'archive-transfer', 'provisioned', 'verification', 'release-metadata', 'runtime-probe', 'verified'];
const manifest = { engines: { vscode: `^${vscodeTestVersion}` }, devDependencies: { '@types/vscode': `^${vscodeTestVersion}` } };
class TimeoutError extends Error { constructor() { super(resolutionTimeoutMessage); } }
const reportAttempt = (number, outcome = 'success') => ({
  number, contextSha256: digest(JSON.stringify(context)), outcome, elapsedMs: 1,
  phases: (outcome === 'success' ? successfulPhases : ['configuration', 'version-resolution']).map((phase, elapsedMs) => ({ phase, elapsedMs })),
  errorLogs: outcome === 'success' ? [] : [{ phase: 'version-resolution', bytes: 1, sha256: digest('E') }],
  error: outcome === 'success' ? null : { code: outcome, messageSha256: outcome === 'version-resolution-timeout' ? digest(resolutionTimeoutMessage) : null },
  delivery: outcome === 'success' ? delivered : null,
  verification: outcome === 'success' ? verified : null,
  capture: outcome === 'success' ? emptyCapture() : { ...emptyCapture(), stderrBytes: 1, stderrSha256: digest('E') }, cleanupComplete: true,
});

test('one version-resolution timeout can recover within the shared deadline', async () => {
  const calls = [];
  let clock = 0;
  const result = await provisionWithBudget(context, async input => {
    calls.push(input);
    return reportAttempt(input.number, input.number === 1 ? 'version-resolution-timeout' : 'success');
  }, { now: () => clock, sleep: async ms => { clock += ms; } });
  assert.equal(result.ok, true);
  assert.equal(calls.length, 2);
  assert.deepEqual(result.attempts.map(attempt => attempt.outcome), ['version-resolution-timeout', 'success']);
  assert.equal(clock, provisioningLimits.backoffMs);
});

test('persistent version-resolution timeout has two attempts, one backoff and a final failure', async () => {
  let calls = 0, sleeps = 0;
  const result = await provisionWithBudget(context, async input => { calls++; return reportAttempt(input.number, 'version-resolution-timeout'); }, { sleep: async () => { sleeps++; } });
  assert.equal(result.ok, false); assert.equal(calls, 2); assert.equal(sleeps, 1);
  assert.equal(result.error, 'version-resolution-timeout');
});

test('malformed retry evidence preserves the first failed attempt and explicitly marks incomplete observation', async () => {
  const first = reportAttempt(1, 'version-resolution-timeout');
  for (const thrown of [false, true]) {
    const result = await provisionWithBudget(context, async ({ number }) => {
      if (number === 1) return first;
      if (thrown) throw new Error('PRIVATE controller error');
      return { raw: 'PRIVATE unapproved field' };
    }, { sleep: async () => {} });
    assert.equal(result.ok, false); assert.equal(result.error, 'invalid-evidence');
    assert.deepEqual(result.attempts[0], first); assert.equal(result.attempts.length, 2);
    assert.equal(result.attempts[1].capture.complete, false);
    assert.equal(result.attempts[1].cleanupComplete, false);
    assert.ok(!JSON.stringify(result).includes('PRIVATE'));
  }
});

test('unknown errors, cleanup failures, archive and verification errors are never retried', async () => {
  for (const outcome of ['configuration-error', 'provisioning-error', 'verification-error', 'invalid-evidence', 'deadline-exceeded', 'cleanup-incomplete', 'unexpected-output']) {
    let calls = 0;
    const result = await provisionWithBudget(context, async input => { calls++; return reportAttempt(input.number, outcome); });
    assert.equal(result.ok, false); assert.equal(calls, 1, outcome);
  }
  assert.equal(isResolutionTimeout(new Error(resolutionTimeoutMessage), ['configuration', 'version-resolution']), false);
  assert.equal(isResolutionTimeout(new TimeoutError(), ['configuration', 'version-resolution']), true);
  assert.equal(isResolutionTimeout(new TimeoutError(), successfulPhases.slice(0, 3)), false);
});

test('the total monotonic deadline includes backoff and does not reset on retry or late success', async () => {
  let clock = 0, calls = 0;
  const result = await provisionWithBudget(context, async input => { calls++; clock += 30; return reportAttempt(input.number, 'version-resolution-timeout'); },
    { now: () => clock, sleep: async ms => { clock += ms; }, limits: { ...provisioningLimits, totalMs: 40, attemptMs: 30, backoffMs: 10, killMs: 1 } });
  assert.equal(result.error, 'deadline-exceeded'); assert.equal(calls, 1);
  const late = await provisionWithBudget(context, async input => { clock += 100; return reportAttempt(input.number); },
    { now: () => clock, limits: { ...provisioningLimits, totalMs: 40, attemptMs: 30, killMs: 1 } });
  assert.equal(late.ok, false); assert.equal(late.error, 'deadline-exceeded');
});

test('canonical config preserves the floor and assertion timeout and rejects hidden dependency installation', () => {
  const config = vscodeTestConfigurations({});
  assert.equal(config[0].version, '1.144.0'); assert.equal(config[0].mocha.timeout, 30000);
  assert.doesNotThrow(() => validateVscodeTestInputs(manifest, config));
  assert.doesNotThrow(() => validateVscodeTestInputs(manifest, vscodeTestConfigurations({ VSCODE_TEST_EXECUTABLE: '/verified/code' }), '/verified/code'));
  for (const patch of [{ extensionDependencies: ['publisher.other'] }, { extensionDependencies: 'bad' }, { engines: { vscode: '^1.145.0' } }])
    assert.throws(() => validateVscodeTestInputs({ ...manifest, ...patch }, config));
  for (const patch of [{ installExtensions: ['other'] }, { skipExtensionDependencies: true }, { version: 'stable' }, { mocha: { timeout: 30000, retries: 1 } }, { download: { timeout: 30000 } }])
    assert.throws(() => validateVscodeTestInputs(manifest, [{ ...config[0], ...patch }]));
});

function successfulSdk() {
  return { async download(options) {
    assert.equal(options.version, vscodeTestVersion); assert.equal(options.timeout, 15000);
    options.reporter.report({ stage: 'resolvedVersion', version: vscodeTestVersion });
    options.reporter.report({ stage: 'resolvingCDNLocation', url: 'https://PRIVATE.invalid/?token=secret' });
    options.reporter.report({ stage: 'downloading', bytesSoFar: 0, totalBytes: 10 });
    options.reporter.report({ stage: 'downloading', bytesSoFar: 10, totalBytes: 10 });
    options.reporter.report({ stage: 'newInstallComplete', commitHash: delivered.commit });
    return resolve('verified-code');
  } };
}
async function workerCase(overrides = {}, probe = async () => ({ ...delivered, arch: 'x64' })) {
  const messages = [];
  const inputs = { context, sdkVersion: '3.1.0', cliVersion: '0.0.15', proxyAgentVersion: '7.0.6', fetchRelease: async () => releaseMetadata, manifest, configurations: vscodeTestConfigurations({}),
    platform: downloadPlatform(context), cachePath: resolve('PRIVATE-cache'), budgetMs: 80000, sdk: successfulSdk(), ...overrides };
  await runSdkProvisioning(async () => inputs, message => messages.push(message), probe);
  return { messages, result: messages.findLast(message => message.type === 'result') };
}

test('worker verifies delivered runtime against exact-release metadata without publishing private SDK fields', async () => {
  const value = await workerCase();
  assert.equal(value.result.outcome, 'success'); assert.deepEqual(value.result.delivery, delivered);
  assert.ok(!JSON.stringify(value.messages).includes('PRIVATE.invalid'));
  assert.deepEqual(value.messages.filter(message => message.type === 'phase').map(message => message.phase), successfulPhases);
});

test('exact-release metadata supplies expected runtime identity while a different SDK service revision remains informational', async () => {
  const sdk = successfulSdk();
  const download = sdk.download;
  sdk.download = options => download({ ...options, reporter: { ...options.reporter, report(event) {
    options.reporter.report(event.stage === 'newInstallComplete' ? { ...event, commitHash: 'd'.repeat(40) } : event);
  } } });
  const expected = { version: vscodeTestVersion, platform: 'linux-x64', quality: 'stable', commit: delivered.commit, archiveSha256: 'e'.repeat(64) };
  const value = await workerCase({ sdk, fetchRelease: async () => expected });
  assert.equal(value.result.outcome, 'success');
  assert.equal(value.result.verification.expected.commit, delivered.commit);
  assert.equal(value.result.verification.sdkProgressRevision, 'd'.repeat(40));
  assert.equal(value.result.verification.observed.commit, delivered.commit);
  assert.equal(value.result.verification.matched, true);
  const withoutHeader = successfulSdk();
  const downloadWithoutHeader = withoutHeader.download;
  withoutHeader.download = options => downloadWithoutHeader({ ...options, reporter: { ...options.reporter, report(event) {
    if (event.stage === 'newInstallComplete') { const { commitHash, ...rest } = event; event = rest; }
    options.reporter.report(event);
  } } });
  const noHeader = await workerCase({ sdk: withoutHeader });
  assert.equal(noHeader.result.outcome, 'success');
  assert.equal(noHeader.result.verification.sdkProgressRevision, null);
});

test('metadata failures never retry SDK provisioning and safe mismatched identities remain distinct from verified delivery', async () => {
  let probes = 0;
  for (const patch of [{ version: '1.145.0' }, { platform: 'darwin' }, { quality: 'insider' }, { commit: 'bad' }, { archiveSha256: 'bad' }, { raw: 'PRIVATE' }]) {
    const value = await workerCase({ fetchRelease: async () => ({ ...releaseMetadata, ...patch }) }, async () => { probes++; return { ...delivered }; });
    assert.equal(value.result.outcome, 'verification-error');
    assert.equal(value.result.verification, null);
    assert.equal(value.result.delivery, null);
  }
  assert.equal(probes, 0);
  const timeout = await workerCase({ fetchRelease: async () => { throw new TimeoutError(); } });
  assert.equal(timeout.result.outcome, 'verification-error');
  assert.equal(timeout.messages.filter(message => message.type === 'phase').at(-1).phase, 'release-metadata');
  for (const patch of [{ version: '1.145.0' }, { commit: 'd'.repeat(40) }, { arch: 'arm64' }]) {
    const value = await workerCase({}, async () => ({ version: vscodeTestVersion, commit: delivered.commit, arch: 'x64', ...patch }));
    assert.equal(value.result.outcome, 'verification-error'); assert.equal(value.result.delivery, null);
    assert.deepEqual(value.result.verification.observed, { version: vscodeTestVersion, commit: delivered.commit, arch: 'x64', ...patch });
    assert.equal(value.result.verification.matched, false);
    assert.ok(!JSON.stringify(value.result).includes('PRIVATE'));
  }
});

test('metadata and CLI share the remaining verification budget and an exhausted metadata request cannot invoke the probe', async () => {
  let metadataBudget, probeBudget;
  const value = await workerCase({ fetchRelease: async input => { metadataBudget = input.budgetMs; await new Promise(resolve => setTimeout(resolve, 10)); return releaseMetadata; } },
    async (sdk, executable, platform, budget) => { probeBudget = budget; return { ...delivered }; });
  assert.equal(value.result.outcome, 'success');
  assert.ok(probeBudget < metadataBudget && metadataBudget <= provisioningLimits.verifyMs);
  let probes = 0;
  const expired = await workerCase({ budgetMs: 20, fetchRelease: async () => { await new Promise(resolve => setTimeout(resolve, 30)); return releaseMetadata; } },
    async () => { probes++; return { ...delivered }; });
  assert.equal(expired.result.outcome, 'verification-error'); assert.equal(probes, 0);
  assert.equal(expired.result.verification.expected.commit, releaseMetadata.commit);
  assert.equal(expired.result.verification.observed, null); assert.equal(expired.result.verification.matched, null);
});

test('pinned SDK ZIP extraction starts before the remaining download events without regressing phase evidence', async () => {
  // SDK download.ts 316-378 reports extractingSynchonrously before it awaits
  // streamToBuffer/pipeline; Windows and macOS keep receiving downloading events.
  const value = await workerCase({ sdk: { download: async ({ reporter }) => {
    reporter.report({ stage: 'resolvedVersion', version: vscodeTestVersion });
    reporter.report({ stage: 'resolvingCDNLocation' });
    reporter.report({ stage: 'downloading', bytesSoFar: 0, totalBytes: 10 });
    reporter.report({ stage: 'extractingSynchonrously' });
    reporter.report({ stage: 'downloading', bytesSoFar: 10, totalBytes: 10 });
    reporter.report({ stage: 'newInstallComplete', commitHash: delivered.commit });
    return resolve('verified-code');
  } } });
  assert.equal(value.result.outcome, 'success');
  const events = value.messages.filter(message => message.type === 'phase').map(message => ({ phase: message.phase, elapsedMs: 0 }));
  assert.doesNotThrow(() => validatePhases(events));
  assert.deepEqual(events.map(event => event.phase), ['configuration', 'version-resolution', 'version-resolved', 'archive-resolution', 'archive-transfer', 'archive-extraction', 'provisioned', 'verification', 'release-metadata', 'runtime-probe', 'verified']);
});

test('only the exact SDK timeout before resolved-version evidence is classified as retryable', async () => {
  const resolution = await workerCase({ sdk: { download: async () => { throw new TimeoutError(); } } });
  assert.equal(resolution.result.outcome, 'version-resolution-timeout');
  for (const error of [new Error(resolutionTimeoutMessage), new Error('EAI_AGAIN'), new SyntaxError('invalid stable-release JSON'), new Error('unrecognized timeout')]) {
    const value = await workerCase({ sdk: { download: async () => { throw error; } } });
    assert.equal(value.result.outcome, 'provisioning-error');
  }
  const archive = await workerCase({ sdk: { download: async ({ reporter }) => {
    reporter.report({ stage: 'resolvedVersion', version: vscodeTestVersion }); throw new TimeoutError();
  } } });
  assert.equal(archive.result.outcome, 'provisioning-error');
  const verification = await workerCase({}, async () => { throw new TimeoutError(); });
  assert.equal(verification.result.outcome, 'verification-error');
});

test('SDK-owned archive retry stays inside one download call and preserves its original error', async () => {
  let calls = 0;
  const value = await workerCase({ sdk: { download: async ({ reporter }) => {
    calls++;
    reporter.report({ stage: 'resolvedVersion', version: vscodeTestVersion });
    reporter.report({ stage: 'resolvingCDNLocation' });
    reporter.report({ stage: 'retrying', attempt: 1, totalAttempts: 3, error: new Error('PRIVATE original archive failure') });
    reporter.report({ stage: 'resolvingCDNLocation' });
    reporter.report({ stage: 'downloading', bytesSoFar: 10, totalBytes: 10 });
    reporter.report({ stage: 'newInstallComplete', commitHash: delivered.commit });
    return resolve('verified-code');
  } } });
  assert.equal(calls, 1); assert.equal(value.result.outcome, 'success');
  const errors = value.messages.filter(message => message.type === 'raw-error');
  assert.equal(errors.length, 1); assert.ok(errors[0].bytes.includes('PRIVATE original archive failure'));
  assert.ok(!JSON.stringify(value.result).includes('PRIVATE'));
});

test('invalid install progress, unknown stages, cache hits, tool drift and wrong delivered version fail', async () => {
  const cases = [
    { sdkVersion: '3.2.0' }, { proxyAgentVersion: '7.0.7' }, { targetManifest: { ...manifest, extensionDependencies: ['other'] } },
    { sdk: { download: async ({ reporter }) => { reporter.report({ stage: 'unknown-stage' }); } } },
    { sdk: { download: async ({ reporter }) => { reporter.report({ stage: 'resolvedVersion', version: vscodeTestVersion }); reporter.report({ stage: 'foundMatchingInstall' }); } } },
    { sdk: { download: async ({ reporter }) => { reporter.report({ stage: 'resolvedVersion', version: vscodeTestVersion }); reporter.report({ stage: 'newInstallComplete' }); } } },
  ];
  for (const input of cases) assert.notEqual((await workerCase(input)).result.outcome, 'success');
  for (const patch of [{ version: '1.145.0' }, { commit: 'd'.repeat(40) }, { arch: 'arm64' }]) {
    const value = await workerCase({}, async () => ({ ...delivered, arch: 'x64', ...patch }));
    assert.equal(value.result.outcome, 'verification-error');
  }
});

test('public evidence rejects stale identities, unknown fields, malformed phases and policy drift', () => {
  const report = createProvisioningReport(context, { ok: true, attempts: [reportAttempt(1)], elapsedMs: 1, error: null });
  assert.equal(report.ok, false, 'provisioning alone is not integration acceptance');
  const changes = [
    value => { value.context.runAttempt = 1; }, value => { value.context.headSha = 'd'.repeat(40); },
    value => { value.context.matrix = 'other'; }, value => { value.rawDetails = 'PRIVATE'; },
    value => { value.provisioning.attempts[0].phases[0].phase = 'unknown'; },
    value => { value.provisioning.attempts[0].errorLogs.push({ phase: 'verification', bytes: 1, sha256: 'bad' }); },
    value => { value.provisioning.attempts[0].verification.rawDetails = 'PRIVATE'; },
    value => { value.provisioning.attempts[0].verification.observed.commit = 'd'.repeat(40); },
    value => { value.provisioning.attempts[0].verification.expected.archiveSha256 = 'bad'; },
    value => { value.tools.sdk = '3.2.0'; }, value => { value.limits.totalMs++; },
    value => { value.provisioning.attempts.push(reportAttempt(2)); }, value => { value.ok = true; },
  ];
  for (const change of changes) { const value = structuredClone(report); change(value); assert.throws(() => validateProvisioningReport(value, context)); }
});

function fixture(t) {
  const root = realpathSync(mkdtempSync(join(tmpdir(), 'gvy-vscode-provision-')));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  return root;
}

function fixtureChildrenCleanup(t, pids, siblingPid) {
  for (const pid of pids) assert.ok(Number.isSafeInteger(pid) && pid > 0 && pid !== process.pid && pid !== siblingPid);
  let pending;
  const cleanup = () => pending ??= (async () => {
    const results = await Promise.allSettled(pids.map(async pid => {
      if (process.platform === 'win32') spawnSync(join(process.env.SystemRoot, 'System32', 'taskkill.exe'), ['/PID', String(pid), '/T', '/F'], { timeout: 2000, stdio: 'ignore', windowsHide: true, shell: false });
      try { process.kill(pid, 'SIGKILL'); } catch (error) { if (error.code !== 'ESRCH') throw error; }
      for (let attempt = 0; attempt < 80; attempt++) {
        try { process.kill(pid, 0); } catch (error) { if (error.code === 'ESRCH') return; throw error; }
        await new Promise(resolve => setTimeout(resolve, 25));
      }
      assert.fail('owned fixture descendant must stop before its working directory is removed');
    }));
    const failed = results.filter(result => result.status === 'rejected');
    if (failed.length) throw new AggregateError(failed.map(result => result.reason), 'owned fixture cleanup failed');
  })();
  // The finally caller must await this before fixture(t)'s earlier after-hook;
  // the same promise is an idempotent fallback if an assertion throws.
  t.after(cleanup);
  return cleanup;
}

test('actual Windows matrix unit commands fail before compilation or provisioning after a native assertion failure', t => {
  const root = fixture(t);
  const workflow = readFileSync(new URL('../../.github/workflows/extension.yml', import.meta.url), 'utf8');
  const matrix = workflow.split('\n  test-matrix:\n')[1]?.split('\n  package:\n')[0];
  const step = matrix?.match(/      - name: Run unit tests and compile integration harness\n((?:        [^\n]*\n)*?)        run: \|\n((?:          [^\n]*\n)+)/);
  assert.ok(step, 'extract the actual unit/compile step, not a copied command');
  assert.match(step[1], /^        shell: bash$/m, 'Windows requires an explicit fail-fast shell');
  const commands = step[2].split('\n').filter(Boolean).map(line => line.slice(10)).join('\n');
  const bash = process.platform === 'win32' ? join(process.env.ProgramFiles, 'Git', 'bin', 'bash.exe') : '/bin/bash';
  const script = `pnpm() {\n  printf '%s\\n' "$*" >> calls.txt\n  if [ "$*" = 'run test' ]; then node -e 'process.exit(7)'; fi\n}\n${commands}\nprintf 'provisioned\\n' >> calls.txt\n`;
  const options = { cwd: root, env: process.env, encoding: 'utf8', timeout: 10000, maxBuffer: 4096, windowsHide: true, shell: false };
  const failed = spawnSync(bash, ['--noprofile', '--norc', '-e', '-o', 'pipefail', '-c', script], options);
  assert.equal(failed.error, undefined); assert.equal(failed.status, 7, failed.stderr);
  assert.equal(readFileSync(join(root, 'calls.txt'), 'utf8'), 'run test\n');
  rmSync(join(root, 'calls.txt'));
  // The same control without fail-fast reaches compilation and a success exit.
  const mutant = spawnSync(bash, ['--noprofile', '--norc', '-c', script], options);
  assert.equal(mutant.error, undefined); assert.equal(mutant.status, 0, mutant.stderr);
  assert.equal(readFileSync(join(root, 'calls.txt'), 'utf8'), 'run test\nrun compile:tests\nprovisioned\n');
  if (process.platform === 'win32') {
    const pwsh = spawnSync('pwsh', ['-NoLogo', '-NoProfile', '-NonInteractive', '-Command',
      `$ErrorActionPreference='stop'; & $env:GVY_FAILFAST_NODE -e 'process.exit(7)'; & $env:GVY_FAILFAST_NODE -e 'process.exit(0)'; exit $LASTEXITCODE`],
      { ...options, env: { ...process.env, GVY_FAILFAST_NODE: process.execPath } });
    assert.equal(pwsh.error, undefined); assert.equal(pwsh.status, 0, 'native first-command failure is masked by PowerShell final status');
  }
});
function workerScript(root, body) {
  const path = join(root, `${randomUUID()}.mjs`);
  writeFileSync(path, `import {createHash} from 'node:crypto'; import {spawn} from 'node:child_process'; import {writeFileSync} from 'node:fs';
process.once('message', async input => { let seq=0; const hash=bytes=>createHash('sha256').update(bytes).digest('hex');
const send=message=>process.send({token:input.token,attempt:input.number,contextSha256:hash(JSON.stringify(input.context)),seq:++seq,...message});
process.on('message',()=>{}); ${body}\n});\n`);
  return path;
}
const inputFor = root => ({ context, number: 1, budgetMs: 3000, editorRoot: root, cachePath: join(root, 'child-pid.txt'), platform: 'linux-x64', token: randomUUID() });
const successBody = `for(const phase of ${JSON.stringify(successfulPhases)}) send({type:'phase',phase});
send({type:'result',outcome:'success',captureComplete:true,probeScopeIntact:true,error:null,delivery:${JSON.stringify(delivered)},verification:${JSON.stringify(verified)},executable:input.cachePath});`;

test('actual owned worker protocol terminates its session and rejects malformed or unexpected output', async t => {
  const root = fixture(t);
  for (const [index, body] of [successBody, `send({type:'phase',phase:'unknown'});`, `send({type:'phase',phase:'configuration',raw:'PRIVATE'});`, `process.stdout.write('PRIVATE raw warning'); ${successBody}`].entries()) {
    const result = await launchProvisioningAttempt(inputFor(root), { script: workerScript(root, body), logDir: join(root, `logs-${index}`), consoleOutput: false });
    assert.equal(result.result.outcome, index === 0 ? 'success' : index === 3 ? 'unexpected-output' : 'invalid-evidence');
    assert.equal(result.result.cleanupComplete, true);
    assert.ok(!JSON.stringify(result.result).includes('PRIVATE'));
  }
});

test('worker probe-scope uncertainty cannot be upgraded by successful parent cleanup or confused with capture', async t => {
  const root = fixture(t);
  const body = `for(const phase of ${JSON.stringify(successfulPhases)}) send({type:'phase',phase});
send({type:'result',outcome:'success',captureComplete:true,probeScopeIntact:false,error:null,delivery:${JSON.stringify(delivered)},verification:${JSON.stringify(verified)},executable:input.cachePath});`;
  const value = await launchProvisioningAttempt(inputFor(root), { script: workerScript(root, body), logDir: join(root, 'scope-proof'), consoleOutput: false });
  assert.equal(value.result.outcome, 'cleanup-incomplete'); assert.equal(value.result.cleanupComplete, false);
  assert.equal(value.result.capture.complete, true); assert.equal(value.result.delivery, null);
});

function nestedProbeWorker(root, command) {
  return workerScript(root, `const {runSdkProvisioning,probeVscodeVersion}=await import(${JSON.stringify(new URL('./vscode-provisioning-worker.mjs', import.meta.url).href)});
const sdk={download:async({reporter})=>{reporter.report({stage:'resolvedVersion',version:${JSON.stringify(vscodeTestVersion)}});reporter.report({stage:'resolvingCDNLocation'});reporter.report({stage:'downloading',bytesSoFar:1,totalBytes:1});reporter.report({stage:'newInstallComplete',commitHash:${JSON.stringify(delivered.commit)}});return input.cachePath;},resolveCliArgsFromVSCodeExecutablePath:()=>[${JSON.stringify(command)}]};
let final;await runSdkProvisioning(async()=>({context:input.context,budgetMs:input.budgetMs,sdkVersion:'3.1.0',cliVersion:'0.0.15',proxyAgentVersion:'7.0.6',manifest:${JSON.stringify(manifest)},configurations:${JSON.stringify(vscodeTestConfigurations({}))},platform:'linux-x64',cachePath:input.cachePath,sdk,fetchRelease:async()=>(${JSON.stringify(releaseMetadata)})}),message=>{if(message.type==='raw-error')process.stderr.write(message.bytes);else if(message.type==='result')final=message;else send(message);},(sdk,executable,platform,budget)=>probeVscodeVersion(sdk,executable,platform,Math.min(budget,1500)));
await new Promise(resolve=>process.stderr.write('',resolve));send(final);`);
}

test('native nested-probe fixture parses before platform-specific execution', t => {
  const root = fixture(t);
  const script = nestedProbeWorker(root, join(root, 'fixture.cmd'));
  const checked = spawnSync(process.execPath, ['--check', script], { encoding: 'utf8', timeout: 5000, maxBuffer: 4096, windowsHide: true });
  assert.equal(checked.error, undefined); assert.equal(checked.status, 0, checked.stderr);
});

if (process.platform === 'win32') test('native Windows nested probe timeout and overflow preserve cleanup uncertainty and sibling safety', { timeout: 40000 }, async t => {
  const root = fixture(t);
  const sibling = spawn(process.execPath, ['-e', 'setInterval(()=>{},1000)'], { stdio: 'ignore' });
  t.after(() => sibling.kill('SIGKILL'));
  for (const mode of ['timeout', 'overflow']) {
    const ids = join(root, `${mode}-pids.json`), wrapper = join(root, `${mode}.cjs`), command = join(root, `${mode}.cmd`);
    for (const path of [process.execPath, wrapper]) assert.ok(!/["%!?&|<>^\r\n\0]/.test(path), 'fixture paths must be safe quoted cmd arguments');
    writeFileSync(wrapper, `const {spawn}=require('node:child_process');const {writeFileSync}=require('node:fs');
const child=spawn(process.execPath,['-e','setInterval(()=>{},1000)'],{stdio:'ignore'});
writeFileSync(${JSON.stringify(ids)},JSON.stringify({wrapper:process.pid,child:child.pid}));
process.stdout.on('error',()=>{});${mode === 'overflow' ? "process.stdout.write('X'.repeat(100000));" : ''}setInterval(()=>{},1000);`);
    writeFileSync(command, `@echo off\r\n"${process.execPath}" "${wrapper}"\r\n`);
    const script = nestedProbeWorker(root, command);
    const value = await launchProvisioningAttempt({ ...inputFor(root), cachePath: ids, budgetMs: 7000 }, { script, logDir: join(root, `${mode}-logs`), consoleOutput: false });
    let owned;
    for (let attempt = 0; attempt < 100; attempt++) {
      try { owned = JSON.parse(readFileSync(ids, 'utf8')); break; }
      catch (error) {
        if (error.code !== 'ENOENT' && !(error instanceof SyntaxError)) throw error;
        await new Promise(resolve => setTimeout(resolve, 25));
      }
    }
    assert.ok(owned, 'the actual wrapper must record its owned descendants');
    const cleanup = fixtureChildrenCleanup(t, [owned.wrapper, owned.child], sibling.pid);
    try {
      assert.equal(value.result.outcome, 'cleanup-incomplete'); assert.equal(value.result.cleanupComplete, false);
      assert.equal(value.result.delivery, null); assert.ok(value.result.phases.some(event => event.phase === 'runtime-probe')); assert.doesNotThrow(() => process.kill(sibling.pid, 0));
      if (value.result.errorLogs.length) assert.equal(value.result.capture.complete, mode === 'timeout', 'capture and descendant proof are separate');
    } finally { await cleanup(); }
  }
  // A watchdog during runtime-probe must not invent a missing final proof.
  const ids = join(root, 'no-final-pid');
  const script = workerScript(root, `for(const phase of ${JSON.stringify(successfulPhases.slice(0, -1))})send({type:'phase',phase});
const child=spawn(process.execPath,['-e','setInterval(()=>{},1000)'],{stdio:'ignore'});writeFileSync(input.cachePath,String(child.pid));setInterval(()=>{},1000);`);
  const noFinal = await launchProvisioningAttempt({ ...inputFor(root), cachePath: ids, budgetMs: 1000 }, { script, logDir: join(root, 'no-final-logs'), consoleOutput: false });
  const cleanup = fixtureChildrenCleanup(t, [Number(readFileSync(ids, 'utf8'))], sibling.pid);
  try {
    assert.equal(noFinal.result.cleanupComplete, false); assert.equal(noFinal.result.outcome, 'cleanup-incomplete');
    assert.doesNotThrow(() => process.kill(sibling.pid, 0));
  } finally { await cleanup(); }
});

test('actual child preserves SDK-recognized private proxy settings while excluding tokens and Node injection', async t => {
  const root = fixture(t);
  const ca = join(root, 'extra-cas.pem'); writeFileSync(ca, '');
  const values = { npm_config_proxy: 'http://PRIVATE-proxy.invalid:8080', npm_config_https_proxy: 'http://PRIVATE-secure-proxy.invalid:8080',
    NODE_EXTRA_CA_CERTS: ca, GITHUB_TOKEN: 'PRIVATE-token', GH_TOKEN: 'PRIVATE-other-token', NODE_OPTIONS: '--require PRIVATE-injection' };
  const script = workerScript(root, `if(process.env.npm_config_proxy!==${JSON.stringify(values.npm_config_proxy)} || process.env.npm_config_https_proxy!==${JSON.stringify(values.npm_config_https_proxy)} || process.env.NODE_EXTRA_CA_CERTS!==${JSON.stringify(ca)} || process.env.GITHUB_TOKEN!==undefined || process.env.GH_TOKEN!==undefined || process.env.NODE_OPTIONS!==undefined) throw new Error('private-child-environment-mismatch'); ${successBody}`);
  const value = await withEnvironment(values, () => launchProvisioningAttempt(inputFor(root), { script, logDir: join(root, 'proxy-control'), consoleOutput: false }));
  assert.equal(value.result.outcome, 'success');
  assert.ok(!JSON.stringify(value.result).includes('PRIVATE'));
  assert.equal(value.result.capture.stdoutBytes, 0); assert.equal(value.result.capture.stderrBytes, 0);
});

test('dripping output cannot extend the watchdog and only the owned child tree is terminated', async t => {
  const root = fixture(t);
  const sibling = spawn(process.execPath, ['-e', 'setInterval(()=>{},1000)'], { stdio: 'ignore' });
  t.after(() => sibling.kill('SIGKILL'));
  const script = workerScript(root, `send({type:'phase',phase:'configuration'});
const child=spawn(process.execPath,['-e','setInterval(()=>{},1000)'],{stdio:'ignore'}); writeFileSync(input.cachePath,String(child.pid));
process.on('SIGTERM',()=>{}); setInterval(()=>process.stdout.write('.'),20);`);
  const input = { ...inputFor(root), budgetMs: 1500 };
  const result = await launchProvisioningAttempt(input, { script, logDir: join(root, 'logs'), consoleOutput: false });
  assert.equal(result.result.outcome, 'deadline-exceeded');
  assert.equal(result.result.cleanupComplete, true);
  assert.ok(result.result.elapsedMs < 6000);
  assert.doesNotThrow(() => process.kill(sibling.pid, 0));
  const ownedPid = Number(readFileSync(input.cachePath, 'utf8'));
  let alive = true;
  for (let index = 0; index < 30 && alive; index++) {
    try { process.kill(ownedPid, 0); await new Promise(resolve => setTimeout(resolve, 25)); } catch { alive = false; }
  }
  assert.equal(alive, false, 'owned grandchild must stop');
});

test('unexpected worker exit never claims unproven descendant cleanup or stops an unrelated sibling', async t => {
  const root = fixture(t);
  const sibling = spawn(process.execPath, ['-e', 'setInterval(()=>{},1000)'], { stdio: 'ignore' });
  t.after(() => sibling.kill('SIGKILL'));
  const input = inputFor(root);
  const script = workerScript(root, `send({type:'phase',phase:'configuration'});
const child=spawn(process.execPath,['-e','setInterval(()=>{},1000)'],{stdio:'ignore'}); writeFileSync(input.cachePath,String(child.pid)); process.exit(3);`);
  const value = await launchProvisioningAttempt(input, { script, logDir: join(root, 'early-exit'), consoleOutput: false });
  const ownedPid = Number(readFileSync(input.cachePath, 'utf8'));
  const cleanup = fixtureChildrenCleanup(t, [ownedPid], sibling.pid);
  try {
    assert.doesNotThrow(() => process.kill(sibling.pid, 0));
    if (process.platform === 'win32') {
      assert.equal(value.result.cleanupComplete, false, 'a dead leader cannot prove Windows descendant cleanup');
      assert.equal(value.result.outcome, 'cleanup-incomplete');
      assert.doesNotThrow(() => process.kill(ownedPid, 0));
    } else {
      assert.equal(value.result.outcome, value.result.cleanupComplete ? 'invalid-evidence' : 'cleanup-incomplete', JSON.stringify(value.result));
      if (value.result.cleanupComplete) assert.throws(() => process.kill(ownedPid, 0), { code: 'ESRCH' });
    }
  } finally { await cleanup(); }
});

test('integration runner assertions are invoked once and their original failure is retained', async t => {
  const root = fixture(t);
  let calls = 0;
  const spawnChild = () => {
    calls++; const child = new EventEmitter(); child.kill = () => true;
    setImmediate(() => child.emit('close', 7, null)); return child;
  };
  const ok = await withEnvironment({ GITHUB_OUTPUT: undefined }, () => invokeIntegrationOnce(context, join(root, 'marker.json'), '/unused/test-cli.mjs', {}, spawnChild, join(root, 'logs')));
  assert.equal(ok, false); assert.equal(calls, 1);
  const marker = JSON.parse(readFileSync(join(root, 'marker.json'), 'utf8'));
  assert.equal(marker.state, 'invoked'); assert.equal(marker.outcome, 'failure'); assert.equal(marker.exitCode, 7);
  let actualArgs;
  const packageContext = { ...context, jobKey: 'package' };
  const invoked = await withEnvironment({ GITHUB_OUTPUT: undefined }, () => invokeIntegrationOnce(packageContext, join(root, 'package-marker.json'), '/unused/test-cli.mjs', {}, (command, args) => {
    actualArgs = args; const child = new EventEmitter(); child.kill = () => true;
    setImmediate(() => child.emit('close', 0, null)); return child;
  }, join(root, 'package-logs')));
  assert.equal(invoked, true);
  assert.deepEqual(actualArgs, ['/unused/test-cli.mjs', '--config', realpathSync(new URL('../../editors/code/.vscode-test.mjs', import.meta.url)), '--grep', 'Implementation navigation', '--fail-zero', '--forbid-pending']);
});

test('actual timeout recovery retains each failed attempt and its original private error hash', async t => {
  const root = fixture(t);
  const raw = `Error: ${resolutionTimeoutMessage}\nPRIVATE original SDK stack\n`;
  const timeoutBody = `for(const phase of ['configuration','version-resolution']) send({type:'phase',phase});
const raw=${JSON.stringify(raw)}; send({type:'error-log',phase:'version-resolution',bytes:Buffer.byteLength(raw),sha256:hash(raw)});
process.stderr.write(raw,()=>send({type:'result',outcome:'version-resolution-timeout',captureComplete:true,probeScopeIntact:true,error:{code:'version-resolution-timeout',messageSha256:hash(${JSON.stringify(resolutionTimeoutMessage)})},delivery:null,verification:null,executable:null}));`;
  for (const persistent of [false, true]) {
    const script = workerScript(root, persistent ? timeoutBody : `if(input.number===1){${timeoutBody}}else{${successBody}}`);
    const result = await provisionWithBudget(context, async ({ number, budgetMs }) => {
      const value = await launchProvisioningAttempt({ ...inputFor(root), number, budgetMs }, { script, logDir: join(root, `${persistent}-${number}`), consoleOutput: false });
      return value.result;
    }, { sleep: async () => {} });
    assert.equal(result.ok, !persistent); assert.equal(result.attempts.length, 2);
    assert.equal(result.attempts[0].capture.stderrSha256, digest(raw));
    assert.equal(result.attempts[0].errorLogs[0].sha256, digest(raw));
    assert.equal(readFileSync(join(root, `${persistent}-1`, 'stderr.log'), 'utf8'), raw);
    assert.ok(!JSON.stringify(createProvisioningReport(context, result)).includes('PRIVATE'));
  }
});

test('bounded supported CLI probe preserves original failures privately and verifies version, commit and arch', async t => {
  const root = fixture(t);
  const script = join(root, 'version-probe.cjs');
  const sdk = { ...successfulSdk(), resolveCliArgsFromVSCodeExecutablePath: () => [process.execPath, script] };
  writeFileSync(script, `console.log('${vscodeTestVersion}\\n${delivered.commit}\\nx64');`);
  assert.deepEqual(probeVscodeVersion(sdk, '/unused', 'unused', 2000), { version: vscodeTestVersion, commit: delivered.commit, arch: 'x64' });
  writeFileSync(script, `process.stderr.write('PRIVATE original probe warning'); process.exitCode=9;`);
  const value = await workerCase({ sdk }, probeVscodeVersion);
  assert.equal(value.result.outcome, 'verification-error');
  assert.ok(value.messages.some(message => message.type === 'raw-error' && message.bytes.includes('PRIVATE original probe warning')));
  assert.ok(!JSON.stringify(value.result).includes('PRIVATE'));
  writeFileSync(script, 'setInterval(()=>process.stdout.write("."),10);');
  const started = performance.now();
  assert.throws(() => probeVscodeVersion(sdk, '/unused', 'unused', 100), /version-probe-failed/);
  assert.ok(performance.now() - started < 2000);
  writeFileSync(script, `process.stdout.write('PRIVATE'.repeat(20000));`);
  const overflow = await workerCase({ sdk }, probeVscodeVersion);
  assert.equal(overflow.result.outcome, 'capture-limit');
  assert.equal(overflow.result.captureComplete, false);
  assert.ok(overflow.messages.some(message => message.type === 'raw-error' && message.bytes.includes('incomplete=true')));
});

test('capture overflow and stale IPC identity fail closed without a retry or public raw output', async t => {
  const root = fixture(t);
  for (const [index, body] of [
    `send({type:'phase',phase:'configuration'}); process.stdout.write('PRIVATE'.repeat(40000));`,
    `send({type:'phase',phase:'configuration',attempt:2});`,
    `send({type:'phase',phase:'configuration',contextSha256:'0'.repeat(64)});`,
    `send({type:'phase',phase:'configuration',token:'stale'});`,
  ].entries()) {
    const value = await launchProvisioningAttempt(inputFor(root), { script: workerScript(root, body), logDir: join(root, `capture-${index}`), consoleOutput: false });
    assert.equal(value.result.outcome, index === 0 ? 'capture-limit' : 'invalid-evidence');
    assert.equal(value.result.capture.complete, index !== 0);
    assert.equal(value.result.cleanupComplete, true);
    assert.ok(value.result.capture.stdoutBytes <= provisioningLimits.captureBytes);
    assert.ok(!JSON.stringify(value.result).includes('PRIVATE'));
  }
});

async function withEnvironment(values, action) {
  const before = Object.fromEntries(Object.keys(values).map(key => [key, process.env[key]]));
  for (const [key, value] of Object.entries(values)) value === undefined ? delete process.env[key] : process.env[key] = value;
  try { return await action(); }
  finally { for (const [key, value] of Object.entries(before)) value === undefined ? delete process.env[key] : process.env[key] = value; }
}

async function auditFixture(t, action, provisioned = true) {
  const root = fixture(t);
  const testedSha = execFileSync('git', ['rev-parse', 'HEAD'], { encoding: 'utf8' }).trim();
  const host = { linux: ['validate', 'ubuntu-24.04', 'Linux'], darwin: ['test-matrix', 'macos-latest', 'macOS'], win32: ['test-matrix', 'windows-latest', 'Windows'] }[process.platform];
  const eventPath = join(root, 'event.json');
  writeFileSync(eventPath, JSON.stringify({ pull_request: { head: { sha: context.headSha } } }));
  return withEnvironment({ RUNNER_TEMP: root, GITHUB_ACTIONS: 'true', GITHUB_SHA: testedSha, GITHUB_EVENT_PATH: eventPath,
    GITHUB_REPOSITORY: context.repository, GITHUB_RUN_ID: context.runId, GITHUB_RUN_ATTEMPT: '2', GITHUB_WORKFLOW: context.workflow, GITHUB_JOB: host[0],
    GVY_VSCODE_MATRIX: host[1], RUNNER_OS: host[2], RUNNER_ARCH: process.arch === 'arm64' ? 'ARM64' : 'X64', GITHUB_OUTPUT: join(root, 'output.txt') }, async () => {
    const actualContext = provisioningContext(randomUUID());
    const attempt = reportAttempt(1, provisioned ? 'success' : 'version-resolution-timeout');
    attempt.contextSha256 = digest(JSON.stringify(actualContext));
    if (attempt.delivery) attempt.delivery = { ...attempt.delivery, platform: downloadPlatform(actualContext), arch: actualContext.runnerArch === 'ARM64' ? 'arm64' : 'x64' };
    if (attempt.verification) attempt.verification = { ...verified, expected: { ...verified.expected, platform: downloadPlatform(actualContext), arch: actualContext.runnerArch === 'ARM64' ? 'arm64' : 'x64' },
      observed: { ...verified.observed, arch: actualContext.runnerArch === 'ARM64' ? 'arm64' : 'x64' } };
    const report = createProvisioningReport(actualContext, { ok: provisioned, attempts: [attempt], elapsedMs: 1, error: provisioned ? null : 'version-resolution-timeout' });
    const reportRoot = join(root, 'public');
    const privateRoot = join(root, 'gvy-vscode-private', actualContext.invocationId);
    const logs = join(root, 'editor-logs');
    mkdirSync(reportRoot); mkdirSync(privateRoot, { recursive: true });
    const executable = join(privateRoot, 'code'); writeFileSync(executable, '');
    const text = JSON.stringify(report, null, 2) + '\n';
    writeFileSync(join(reportRoot, 'provisioning.json'), text);
    const state = { context: actualContext, executable: provisioned ? executable : null, receiptSha256: digest(text) };
    writeFileSync(join(privateRoot, 'state.json'), JSON.stringify(state, null, 2) + '\n');
    const marker = { context: actualContext, state: 'invoked', outcome: 'success', exitCode: 0, logsPrepared: { fresh: true, priorFiles: 0 } };
    const writeMarker = () => writeFileSync(join(privateRoot, 'integration.json'), JSON.stringify(marker, null, 2) + '\n');
    await action({ root, reportRoot, privateRoot, logs, report, state, marker, writeMarker });
  });
}

test('post-test audit publishes only allowlisted identity and hashes, and keeps phase-specific missing logs fatal', async t => {
  for (const mode of ['provisioning-failed', 'uninvoked', 'missing-logs', 'assertion-failed', 'success']) {
    await auditFixture(t, data => {
      if (!['provisioning-failed', 'uninvoked'].includes(mode)) {
        if (mode === 'assertion-failed') { data.marker.outcome = 'failure'; data.marker.exitCode = 7; }
        data.writeMarker();
        if (mode !== 'missing-logs') { mkdirSync(data.logs); writeFileSync(join(data.logs, 'editor.log'), 'PRIVATE editor data'); }
      }
      assert.equal(auditEditorProvisioning(data.reportRoot, data.logs), mode === 'success');
      const text = readFileSync(join(data.reportRoot, 'result.json'), 'utf8');
      assert.ok(!text.includes('PRIVATE')); assert.ok(!text.includes(data.root));
      const report = JSON.parse(text);
      assert.equal(report.ok, mode === 'success');
      assert.equal(report.integration.state, ['provisioning-failed', 'uninvoked'].includes(mode) ? 'unexecuted' : 'invoked');
      const expectedOutput = ['success', 'assertion-failed'].includes(mode) ? 'safe=true\neditor_logs_valid=true\n' : 'safe=true\n';
      assert.equal(readFileSync(join(data.root, 'output.txt'), 'utf8'), expectedOutput);
    }, mode !== 'provisioning-failed');
  }
});

test('stale probe or earlier editor logs cannot satisfy a new integration invocation', async t => {
  for (const currentWritesLogs of [false, true]) await auditFixture(t, async data => {
    mkdirSync(data.logs); writeFileSync(join(data.logs, 'stale.log'), 'PRIVATE original probe log');
    let calls = 0;
    const spawnChild = () => {
      calls++; const child = new EventEmitter(); child.kill = () => true;
      setImmediate(() => {
        if (currentWritesLogs) writeFileSync(join(data.logs, 'current.log'), 'PRIVATE current editor log');
        child.emit('close', 0, null);
      });
      return child;
    };
    await invokeIntegrationOnce(data.report.context, join(data.privateRoot, 'integration.json'), '/unused/test-cli.mjs', {}, spawnChild, data.logs);
    assert.equal(calls, 1);
    assert.equal(auditEditorProvisioning(data.reportRoot, data.logs), currentWritesLogs, 'only logs created after this runner invocation count');
    assert.equal(readFileSync(join(data.privateRoot, 'previous-editor-logs', 'stale.log'), 'utf8'), 'PRIVATE original probe log');
    const result = JSON.parse(readFileSync(join(data.reportRoot, 'result.json'), 'utf8'));
    assert.deepEqual(result.integration.editorLogs, { fresh: true, priorFiles: 1, present: currentWritesLogs, files: currentWritesLogs ? 1 : 0 });
  });
});

test('unsafe prior log roots and quarantine collisions fail before any runner invocation or original-byte loss', async t => {
  for (const mode of ['linked-root', 'linked-parent', 'linked-file', 'nonregular-root', 'quarantine-collision']) await auditFixture(t, async data => {
    const target = join(data.root, 'original');
    let logs = data.logs;
    if (mode === 'linked-root') {
      mkdirSync(target); writeFileSync(join(target, 'original.log'), 'PRIVATE original');
      symlinkSync(target, logs, 'junction');
    } else if (mode === 'linked-parent') {
      mkdirSync(target); symlinkSync(target, join(data.root, 'linked-parent'), 'junction');
      logs = join(data.root, 'linked-parent', 'logs');
    } else if (mode === 'nonregular-root') writeFileSync(logs, 'PRIVATE original');
    else {
      mkdirSync(logs); writeFileSync(target, 'PRIVATE original');
      if (mode === 'linked-file') symlinkSync(target, join(logs, 'original.log'), 'file');
      else {
        writeFileSync(join(logs, 'original.log'), 'PRIVATE original');
        mkdirSync(join(data.privateRoot, 'previous-editor-logs'));
        writeFileSync(join(data.privateRoot, 'previous-editor-logs', 'existing.log'), 'PRIVATE previous');
      }
    }
    let calls = 0;
    await assert.rejects(() => invokeIntegrationOnce(data.report.context, join(data.privateRoot, 'integration.json'), '/unused/test-cli.mjs', {}, () => { calls++; throw new Error('must not invoke'); }, logs));
    assert.equal(calls, 0);
    const marker = JSON.parse(readFileSync(join(data.privateRoot, 'integration.json'), 'utf8'));
    assert.equal(marker.state, 'unexecuted'); assert.equal(marker.outcome, 'failure');
    if (mode === 'linked-root') assert.equal(readFileSync(join(target, 'original.log'), 'utf8'), 'PRIVATE original');
    if (mode === 'linked-file' || mode === 'quarantine-collision') assert.equal(readFileSync(target, 'utf8'), 'PRIVATE original');
  });
});

test('atomic claim precedes preparation and records an uninvoked preparation failure without authorizing logs', async t => {
  await auditFixture(t, async data => {
    writeFileSync(data.logs, 'PRIVATE original non-directory');
    let calls = 0;
    await assert.rejects(() => invokeIntegrationOnce(data.report.context, join(data.privateRoot, 'integration.json'), '/unused/test-cli.mjs', {}, () => { calls++; throw new Error('must not invoke'); }, data.logs));
    const marker = JSON.parse(readFileSync(join(data.privateRoot, 'integration.json'), 'utf8'));
    assert.equal(marker.state, 'unexecuted'); assert.equal(marker.outcome, 'failure');
    assert.equal(marker.exitCode, null); assert.equal(marker.logsPrepared, null); assert.equal(calls, 0);
    assert.equal(auditEditorProvisioning(data.reportRoot, data.logs), false);
    const result = JSON.parse(readFileSync(join(data.reportRoot, 'result.json'), 'utf8'));
    assert.equal(result.integration.preparation, 'failed'); assert.equal(result.integration.state, 'unexecuted');
    assert.equal(readFileSync(join(data.root, 'output.txt'), 'utf8'), 'safe=true\n');
    assert.equal(readFileSync(data.logs, 'utf8'), 'PRIVATE original non-directory');
  });
});

test('an existing atomic claim stops before any profile side effect or second invocation', async t => {
  const root = fixture(t), marker = join(root, 'integration.json'), logs = join(root, 'logs');
  writeFileSync(marker, 'PRIVATE existing claim'); mkdirSync(logs); writeFileSync(join(logs, 'current.log'), 'PRIVATE existing editor');
  let calls = 0;
  await assert.rejects(() => invokeIntegrationOnce(context, marker, '/unused/test-cli.mjs', {}, () => { calls++; }, logs));
  assert.equal(calls, 0); assert.equal(readFileSync(marker, 'utf8'), 'PRIVATE existing claim');
  assert.equal(readFileSync(join(logs, 'current.log'), 'utf8'), 'PRIVATE existing editor');
  assert.equal(existsSync(join(root, 'previous-editor-logs')), false);
});

test('post-invocation replacement of the log root or parent cannot pass audit or authorize raw upload', async t => {
  for (const mode of ['regular-file', 'linked-root', 'linked-parent']) await t.test(mode, async child => auditFixture(child, async data => {
    const profile = join(data.root, 'profile');
    const logs = join(profile, 'logs');
    const spawnChild = () => {
      const process = new EventEmitter(); process.kill = () => true;
      setImmediate(() => { writeFileSync(join(logs, 'current.log'), 'PRIVATE original editor bytes'); process.emit('close', 0, null); });
      return process;
    };
    await invokeIntegrationOnce(data.report.context, join(data.privateRoot, 'integration.json'), '/unused/test-cli.mjs', {}, spawnChild, logs);
    let preserved;
    if (mode === 'linked-parent') {
      const target = join(data.root, 'preserved-profile'); renameSync(profile, target); symlinkSync(target, profile, 'junction');
      preserved = join(target, 'logs', 'current.log');
    } else {
      const target = join(data.root, 'preserved-logs'); renameSync(logs, target); preserved = join(target, 'current.log');
      if (mode === 'regular-file') writeFileSync(logs, 'PRIVATE replacement file');
      else symlinkSync(target, logs, 'junction');
    }
    assert.throws(() => auditEditorProvisioning(data.reportRoot, logs));
    assert.equal(existsSync(join(data.reportRoot, 'result.json')), false);
    assert.ok(!readFileSync(join(data.root, 'output.txt'), 'utf8').includes('editor_logs_valid=true'));
    assert.equal(readFileSync(preserved, 'utf8'), 'PRIVATE original editor bytes');
  }));
});

test('actual raw-log upload predicates require positive audit evidence while valid assertion failures retain their logs', async t => {
  const workflow = readFileSync(new URL('../../.github/workflows/extension.yml', import.meta.url), 'utf8');
  const predicates = [...workflow.matchAll(/      - name: Upload failed editor logs\n        if: ([^\n]+)/g)].map(match => match[1]);
  assert.equal(predicates.length, 3);
  for (const predicate of predicates) {
    assert.match(predicate, /failure\(\)/);
    assert.match(predicate, /steps\.editor_integration\.outputs\.invoked == 'true'/);
    assert.match(predicate, /steps\.vscode_audit\.outputs\.editor_logs_valid == 'true'/);
  }
  await auditFixture(t, async data => {
    const spawnChild = () => {
      const process = new EventEmitter(); process.kill = () => true;
      setImmediate(() => { writeFileSync(join(data.logs, 'current.log'), 'PRIVATE failed assertion log'); process.emit('close', 7, null); });
      return process;
    };
    assert.equal(await invokeIntegrationOnce(data.report.context, join(data.privateRoot, 'integration.json'), '/unused/test-cli.mjs', {}, spawnChild, data.logs), false);
    assert.equal(auditEditorProvisioning(data.reportRoot, data.logs), false);
    assert.match(readFileSync(join(data.root, 'output.txt'), 'utf8'), /editor_logs_valid=true\n/);
    assert.equal(JSON.parse(readFileSync(join(data.reportRoot, 'result.json'), 'utf8')).integration.outcome, 'failure');
  });
});

test('post-test audit rejects malformed, stale, linked, oversized and unexpected public evidence before upload', async t => {
  const cases = [
    data => { data.report.context.runAttempt++; writeFileSync(join(data.reportRoot, 'provisioning.json'), JSON.stringify(data.report, null, 2) + '\n'); },
    data => { data.marker.context = { ...data.marker.context, testedSha: 'd'.repeat(40) }; data.writeMarker(); },
    data => { data.marker.outcome = 'pending'; data.marker.exitCode = null; data.writeMarker(); },
    data => { data.marker.logsPrepared.fresh = false; data.writeMarker(); },
    data => { delete data.marker.logsPrepared; data.writeMarker(); },
    data => { data.report.raw = 'PRIVATE'; writeFileSync(join(data.reportRoot, 'provisioning.json'), JSON.stringify(data.report)); },
    data => writeFileSync(join(data.reportRoot, 'provisioning.json'), '{"ok":false,"ok":true}'),
    data => writeFileSync(join(data.reportRoot, 'provisioning.json'), Buffer.from([0xff])),
    data => writeFileSync(join(data.reportRoot, 'provisioning.json'), ' '.repeat(64 * 1024 + 1)),
    data => writeFileSync(join(data.reportRoot, 'PRIVATE.log'), 'PRIVATE'),
    data => { data.state.receiptSha256 = '0'.repeat(64); writeFileSync(join(data.privateRoot, 'state.json'), JSON.stringify(data.state, null, 2) + '\n'); },
    data => { const canonical = JSON.stringify(data.state, null, 2); const text = '{"receiptSha256":"duplicate",' + canonical.slice(1); writeFileSync(join(data.privateRoot, 'state.json'), text + '\n'); },
    data => { rmSync(join(data.privateRoot, 'state.json')); symlinkSync(join(data.reportRoot, 'provisioning.json'), join(data.privateRoot, 'state.json'), 'file'); },
    data => { const target = join(data.root, 'target.json'); writeFileSync(target, JSON.stringify(data.marker)); symlinkSync(target, join(data.privateRoot, 'integration.json'), 'file'); },
    data => { data.writeMarker(); mkdirSync(data.logs); const target = join(data.root, 'private-log'); writeFileSync(target, 'PRIVATE'); symlinkSync(target, join(data.logs, 'linked.log'), 'file'); },
  ];
  for (const mutate of cases) await auditFixture(t, data => {
    mutate(data);
    assert.throws(() => auditEditorProvisioning(data.reportRoot, data.logs));
    assert.equal(existsSync(join(data.reportRoot, 'result.json')), false);
    assert.equal(existsSync(join(data.root, 'output.txt')), false);
  });
});
