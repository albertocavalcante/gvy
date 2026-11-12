// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import { spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { dirname, isAbsolute, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { performance } from 'node:perf_hooks';
import { readEvidenceText } from './read-evidence.mjs';
import { digest, downloadPlatform, isResolutionTimeout, provisioningLimits, validatePhases, validateProvisioningContext, validateReleaseMetadata, validateVerification } from './vscode-provisioning-policy.mjs';
import { validateVscodeTestInputs, vscodeTestVersion, vscodeTestSdkVersion, vscodeTestCliVersion, vscodeTestProxyAgentVersion, vscodeTestIdleTimeout } from '../../editors/code/tools/vscode-test-config.mjs';

const sha = value => typeof value === 'string' && /^[a-f0-9]{40}$/.test(value);
const require = (condition, code) => { if (!condition) throw new Error(code); };
const probeEvidence = new WeakMap();

/** Exactly one supported CLI probe; it does not invoke runVSCodeCommand(), which downloads again. */
export function probeVscodeVersion(sdk, executable, platform, timeoutMs) {
  const [command, ...prefix] = sdk.resolveCliArgsFromVSCodeExecutablePath(executable, { platform });
  require(isAbsolute(command) && !/[\0\r\n]/.test(command), 'invalid-version-probe');
  let binary = command;
  let args = [...prefix, '--version'];
  const options = { encoding: 'buffer', timeout: timeoutMs, maxBuffer: 4096, windowsHide: true, shell: false };
  if (process.platform === 'win32') {
    const quote = value => {
      require(typeof value === 'string' && !/["%!?&|<>^\r\n\0]/.test(value), 'invalid-version-probe');
      return `"${value}"`;
    };
    binary = join(process.env.SystemRoot, 'System32', 'cmd.exe');
    args = ['/d', '/s', '/c', `"${[command, ...args].map(quote).join(' ')}"`];
    options.windowsVerbatimArguments = true;
  }
  const result = spawnSync(binary, args, options);
  const fail = code => {
    const error = new Error(code);
    // Preserve bounded original probe output privately, including unexpected
    // warnings. Only its byte counts and hashes enter the public projection.
    const stdout = result.stdout ?? Buffer.alloc(0), stderr = result.stderr ?? Buffer.alloc(0);
    const incomplete = result.error?.code === 'ENOBUFS' || stdout.length > 4096 || stderr.length > 4096;
    probeEvidence.set(error, { incomplete, bytes: Buffer.concat([
      Buffer.from(`${code}: status=${result.status}; signal=${result.signal}; incomplete=${incomplete}\n${result.error?.stack ?? ''}\nstdout bytes=${stdout.length} sha256=${digest(stdout)}:\n`),
      stdout.subarray(0, 4096), Buffer.from(`\nstderr bytes=${stderr.length} sha256=${digest(stderr)}:\n`), stderr.subarray(0, 4096), Buffer.from('\n'),
    ]) });
    throw error;
  };
  if (result.error || result.signal || result.status !== 0 || result.stderr.length) fail('version-probe-failed');
  const lines = result.stdout.toString('utf8').trim().split(/\r?\n/);
  if (!(lines.length === 3 && /^[0-9]{1,4}\.[0-9]{1,4}\.[0-9]{1,4}$/.test(lines[0]) && sha(lines[1]) && ['x64', 'arm64'].includes(lines[2]))) fail('delivered-version-mismatch');
  return { version: lines[0], commit: lines[1], arch: lines[2] };
}

/** Worker dependencies are injected in controls; production loads only the frozen local SDK/config. */
export async function runSdkProvisioning(load, output, probe = probeVscodeVersion) {
  const started = performance.now();
  const observed = [];
  let fatal;
  let executable;
  let sdkProgressRevision = null;
  let verification = null;
  let init;
  let captureComplete = true;
  const phase = value => {
    if (observed.at(-1) === value) return;
    try { validatePhases([...observed, value].map(phase => ({ phase, elapsedMs: 0 }))); }
    catch { fatal ??= new Error('invalid-sdk-phase'); throw fatal; }
    observed.push(value);
    output({ type: 'phase', phase: value });
  };
  const logError = error => {
    const probe = probeEvidence.get(error);
    if (probe?.incomplete) captureComplete = false;
    const originalHash = error?.code === 'VSCODE_RELEASE_METADATA_ERROR' && /^[a-f0-9]{64}$/.test(error.originalErrorSha256 ?? '') ? `originalErrorSha256=${error.originalErrorSha256}\n` : '';
    const bytes = probe?.bytes ?? Buffer.from(`${error instanceof Error ? error.stack ?? error.message : String(error)}\n${originalHash}`);
    require(bytes.length <= 16 * 1024, 'error-evidence-limit');
    output({ type: 'error-log', phase: observed.at(-1), bytes: bytes.length, sha256: digest(bytes) });
    output({ type: 'raw-error', bytes });
  };
  phase('configuration');
  try {
    init = await load();
    validateProvisioningContext(init.context);
    require(init.sdkVersion === vscodeTestSdkVersion && init.cliVersion === vscodeTestCliVersion && init.proxyAgentVersion === vscodeTestProxyAgentVersion, 'unsupported-sdk-version');
    require(Number.isSafeInteger(init.budgetMs) && init.budgetMs > 0 && init.budgetMs <= provisioningLimits.attemptMs, 'invalid-worker-budget');
    require(init.manifest.devDependencies?.['@types/vscode'] === `^${vscodeTestVersion}`, 'editor-floor-mismatch');
    validateVscodeTestInputs(init.manifest, init.configurations);
    if (init.targetManifest) validateVscodeTestInputs(init.targetManifest, init.configurations);
    require(init.platform === downloadPlatform(init.context), 'platform-mismatch');
    const invalid = () => { fatal ??= new Error('invalid-sdk-phase'); throw fatal; };
    const reporter = {
      report(event) {
        if (fatal) throw fatal;
        if (!event || typeof event !== 'object') invalid();
        switch (event.stage) {
          case 'resolvedVersion':
            if (event.version !== vscodeTestVersion || observed.at(-1) !== 'version-resolution') invalid();
            phase('version-resolved'); break;
          case 'resolvingCDNLocation': phase('archive-resolution'); break;
          case 'downloading':
            if (!Number.isSafeInteger(event.bytesSoFar) || !Number.isSafeInteger(event.totalBytes) || event.bytesSoFar < 0 || event.bytesSoFar > event.totalBytes || event.totalBytes > 1024 * 1024 * 1024) invalid();
            // ZIP extraction is reported before the SDK awaits its incoming
            // stream. Validate later download events without moving backwards.
            if (observed.at(-1) !== 'archive-extraction') phase('archive-transfer');
            break;
          case 'extractingSynchonrously': phase('archive-extraction'); break;
          case 'retrying': phase('sdk-archive-retry'); logError(event.error); break;
          case 'foundMatchingInstall': phase('cache-hit'); invalid(); break;
          case 'newInstallComplete':
            if (event.commitHash !== undefined && !sha(event.commitHash)) invalid();
            // SDK 3.1.0 reports the update-service response header here. It is
            // not the build identity returned by the official release API.
            sdkProgressRevision = event.commitHash ?? null; phase('provisioned'); break;
          default:
            // Keep the unexpected stage in private error evidence without
            // projecting arbitrary event fields or URLs into the artifact.
            fatal ??= new Error(`invalid-sdk-phase: ${typeof event.stage === 'string' ? event.stage : typeof event.stage}`);
            throw fatal;
        }
      },
      error(error) {
        if (fatal) throw fatal;
        phase('sdk-error'); logError(error);
      },
    };
    // The cache is fresh and owned by this attempt. With a custom reporter and
    // fixed stable version, pinned SDK 3.1.0 awaits release validation here,
    // before its first resolvedVersion event and outside archive retries.
    phase('version-resolution');
    executable = await init.sdk.download({ version: vscodeTestVersion, platform: init.platform, cachePath: init.cachePath, reporter, timeout: vscodeTestIdleTimeout });
    require(observed.at(-1) === 'provisioned' && isAbsolute(executable), 'missing-install-evidence');
    phase('verification');
    const deadline = Math.min(started + init.budgetMs, performance.now() + provisioningLimits.verifyMs);
    const remaining = () => {
      const milliseconds = Math.floor(deadline - performance.now());
      require(milliseconds > 0, 'verification-deadline');
      return milliseconds;
    };
    phase('release-metadata');
    const expected = validateReleaseMetadata(await init.fetchRelease({ version: vscodeTestVersion, platform: init.platform, budgetMs: remaining() }), init.context);
    phase('runtime-probe');
    const delivered = await probe(init.sdk, executable, init.platform, remaining());
    const expectedArch = init.context.runnerArch === 'ARM64' ? 'arm64' : 'x64';
    verification = validateVerification({ expected: { ...expected, arch: expectedArch }, observed: { version: delivered.version, commit: delivered.commit, arch: delivered.arch },
      metadataSource: 'vscode-update-api-exact-version', sdkProgressRevision,
      matched: delivered.version === expected.version && delivered.commit === expected.commit && delivered.arch === expectedArch }, init.context);
    require(verification.matched, `delivered-version-mismatch: expected version=${expected.version} commit=${expected.commit} arch=${expectedArch}; observed version=${delivered.version} commit=${delivered.commit} arch=${delivered.arch}`);
    phase('verified');
    output({ type: 'result', outcome: 'success', captureComplete, error: null, delivery: { version: delivered.version, commit: delivered.commit, arch: delivered.arch, platform: init.platform,
      expectedCommit: expected.commit, archiveSha256: expected.archiveSha256, commitSource: 'vscode-update-api-exact-version' }, verification, executable });
  } catch (error) {
    logError(error);
    const outcome = !captureComplete ? 'capture-limit' : isResolutionTimeout(error, observed) ? 'version-resolution-timeout' :
      fatal ? 'invalid-evidence' : observed.at(-1) === 'configuration' ? 'configuration-error' :
        ['verification', 'release-metadata', 'runtime-probe'].includes(observed.at(-1)) ? 'verification-error' : 'provisioning-error';
    output({ type: 'result', outcome, captureComplete, error: { code: outcome, messageSha256: error instanceof Error ? digest(error.message) : null }, delivery: null, verification, executable: null });
  }
}

async function loadProduction(input) {
  const editorRoot = resolve(fileURLToPath(new URL('../../editors/code/', import.meta.url)));
  require(input.editorRoot === editorRoot && isAbsolute(input.cachePath), 'invalid-worker-input');
  const requireEditor = createRequire(join(editorRoot, 'package.json'));
  const sdkPath = requireEditor.resolve('@vscode/test-electron');
  const cliPath = requireEditor.resolve('@vscode/test-cli');
  const json = path => JSON.parse(readEvidenceText(path, 512 * 1024));
  const sdkPackage = json(join(dirname(sdkPath), '..', 'package.json'));
  const cliPackage = json(join(dirname(cliPath), '..', 'package.json'));
  require(sdkPackage.name === '@vscode/test-electron' && cliPackage.name === '@vscode/test-cli', 'unsupported-sdk-version');
  const requireSdk = createRequire(sdkPath);
  const agentEntry = requireSdk.resolve('https-proxy-agent');
  const agentPackage = json(join(dirname(agentEntry), '..', 'package.json'));
  require(agentPackage.name === 'https-proxy-agent' && agentPackage.version === vscodeTestProxyAgentVersion, 'unsupported-sdk-proxy-agent');
  const { HttpsProxyAgent } = requireSdk('https-proxy-agent');
  require(typeof HttpsProxyAgent === 'function', 'unsupported-sdk-proxy-agent');
  const { fetchExactVscodeRelease } = await import('./vscode-release-metadata.mjs');
  const configurations = (await import(pathToFileURL(join(editorRoot, '.vscode-test.mjs')))).default;
  const targetRoot = process.env.GVY_TEST_EXTENSION_ROOT;
  require((input.context.jobKey === 'package') === Boolean(targetRoot), 'unsupported-extension-root');
  return {
    ...input, sdk: requireEditor('@vscode/test-electron'), sdkVersion: sdkPackage.version, cliVersion: cliPackage.version, proxyAgentVersion: agentPackage.version,
    fetchRelease: request => fetchExactVscodeRelease(request, { makeProxyAgent: proxy => new HttpsProxyAgent(proxy) }),
    configurations, manifest: json(join(editorRoot, 'package.json')),
    targetManifest: targetRoot ? json(join(targetRoot, 'package.json')) : null,
  };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  if (process.argv.length !== 3 || process.argv[2] !== '--worker' || !process.send) throw new Error('Worker requires an owned IPC invocation');
  process.once('message', async input => {
    let seq = 0;
    let final;
    // Remain alive until the parent stops this owned tree after collecting the
    // final, flushed protocol. A detached probe must not outlive its owner.
    process.on('message', () => {});
    const send = message => {
      if (message.type === 'raw-error') process.stderr.write(message.bytes);
      else if (message.type === 'result') final = message;
      else process.send({ token: input.token, attempt: input.number, contextSha256: digest(JSON.stringify(input.context)), seq: ++seq, ...message });
    };
    try {
      await runSdkProvisioning(() => loadProduction(input), send);
      require(final, 'missing-worker-result');
      await new Promise(resolve => process.stderr.write('', resolve));
      process.send({ token: input.token, attempt: input.number, contextSha256: digest(JSON.stringify(input.context)), seq: ++seq, ...final });
    } catch {
      process.exitCode = 2;
      process.disconnect();
    }
  });
}
