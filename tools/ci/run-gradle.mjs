import { isUtf8 } from 'node:buffer';
import { spawn, spawnSync } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { accessSync, appendFileSync, constants as fsConstants, createWriteStream, lstatSync, mkdirSync, readFileSync, realpathSync, writeFileSync } from 'node:fs';
import { constants } from 'node:os';
import { isAbsolute, join, relative, sep } from 'node:path';
import { finished } from 'node:stream/promises';
import { isDeepStrictEqual } from 'node:util';
import { checkGradleDiagnostics, loadNativeDiagnosticPolicy, renderSummary } from './check-gradle-diagnostics.mjs';
import { prepareNativeProvisioning, verifyNativeProvisioning } from './native-provisioning.mjs';
import { effectiveGradleJvmArgs, gradleMemoryPlan, gradleMemoryObservationProfile } from './gradle-memory-options.mjs';
import { startGradleMemoryProbe } from './gradle-memory-probe.mjs';
import { readEvidenceBytes, readEvidenceText } from './read-evidence.mjs';

function parseArgs(args) {
  const options = {};
  let index = 0;
  while (['--report-root', '--producer', '--native-provisioning'].includes(args[index])) {
    const key = args[index];
    if (Object.hasOwn(options, key) || !args[index + 1] || args[index + 1].startsWith('--')) throw new Error('Missing or duplicate Gradle wrapper option');
    options[key] = args[index + 1];
    index += 2;
  }
  if (!options['--report-root'] || !/^[a-z0-9][a-z0-9-]{0,79}$/.test(options['--producer'] ?? '') || index === args.length) {
    throw new Error('Usage: run-gradle.mjs --report-root DIR --producer NAME GRADLE_ARGS...');
  }
  if (args.slice(index).includes('--native-provisioning')) throw new Error('Native provisioning option must precede Gradle arguments');
  if (options['--native-provisioning'] && args.slice(index).some(arg => /^-P(?:konan\.data\.dir|kotlin\.native\.home)(?:=|$)/.test(arg))) {
    throw new Error('Native provisioning cannot override its data directory or home');
  }
  return { reportRoot: options['--report-root'], producer: options['--producer'], nativeTarget: options['--native-provisioning'], gradleArgs: args.slice(index) };
}

function git(args) {
  const result = spawnSync('git', args, { encoding: 'utf8' });
  if (result.status !== 0) throw new Error('Cannot determine checkout identity');
  return result.stdout.trim();
}

function createContext(invocationId) {
  const testedSha = git(['rev-parse', 'HEAD']);
  if (process.env.GITHUB_SHA && process.env.GITHUB_SHA !== testedSha) throw new Error('GitHub tested SHA does not match checkout HEAD');
  if (process.env.GITHUB_ACTIONS === 'true') {
    for (const field of ['GITHUB_SHA', 'GITHUB_EVENT_PATH', 'GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT']) {
      if (!process.env[field]) throw new Error(`Missing ${field} for GitHub invocation`);
    }
  }
  let headSha = testedSha;
  if (process.env.GITHUB_EVENT_PATH) {
    let event;
    try {
      event = JSON.parse(readEvidenceText(process.env.GITHUB_EVENT_PATH, 4 * 1024 * 1024));
    } catch { throw new Error('Cannot read GitHub event JSON'); }
    if (event.pull_request) headSha = event.pull_request.head?.sha;
  }
  const runId = process.env.GITHUB_RUN_ID ?? `local-${invocationId}`;
  const attempt = process.env.GITHUB_RUN_ATTEMPT ?? '1';
  if (![headSha, testedSha].every(value => typeof value === 'string' && /^[a-f0-9]{40}$/.test(value))) {
    throw new Error('Context requires full source and tested SHAs');
  }
  if (!/^[A-Za-z0-9_.:-]{1,200}$/.test(runId)) throw new Error('Context requires valid runId');
  if (!/^[1-9][0-9]*$/.test(attempt) || !Number.isSafeInteger(Number(attempt))) throw new Error('Context requires positive integer runAttempt');
  return { schemaVersion: 1, headSha, testedSha, runId, runAttempt: Number(attempt), invocationId, command: 'gradle' };
}

function memoryPrivateDirectory(reportRoot) {
  try {
    if (!isAbsolute(process.env.RUNNER_TEMP ?? '')) throw new Error();
    const temp = realpathSync(process.env.RUNNER_TEMP);
    const dir = join(temp, 'gvy-gradle-memory-private');
    const inside = (parent, child) => {
      const suffix = relative(parent, child);
      return suffix === '' || (suffix !== '..' && !suffix.startsWith('..' + sep) && !isAbsolute(suffix));
    };
    const report = realpathSync(reportRoot);
    if (inside(realpathSync(process.cwd()), dir) || inside(report, dir) || inside(dir, report)) throw new Error();
    try { mkdirSync(dir, { mode: 0o700 }); }
    catch (error) { if (error.code !== 'EEXIST') throw error; }
    const stat = lstatSync(dir);
    if (!stat.isDirectory() || stat.isSymbolicLink() || stat.uid !== process.getuid() || (stat.mode & 0o777) !== 0o700 || realpathSync(dir) !== dir) throw new Error();
    accessSync(dir, fsConstants.W_OK | fsConstants.X_OK);
    return dir;
  } catch {
    // Filesystem errors may contain private paths. Keep them out of artifacts.
    throw new Error('Cannot prepare private owner-only daemon crash routing outside workspace and artifacts');
  }
}

async function capture(args, dir, childEnv = {}) {
  let captureExitCode = 0;
  const streams = ['stdout.log', 'stderr.log', 'console.log'].map(name => createWriteStream(join(dir, name), { flags: 'wx' }));
  const settled = streams.map(stream => finished(stream).catch(() => { captureExitCode = 1; }));
  const child = spawn('./gradlew', args, { stdio: ['ignore', 'pipe', 'pipe'], env: { ...process.env, ...childEnv } });
  // Multiple pipe sinks preserve backpressure without retaining output in memory.
  child.stdout.pipe(streams[0]);
  child.stdout.pipe(streams[2], { end: false });
  child.stdout.pipe(process.stdout, { end: false });
  child.stderr.pipe(streams[1]);
  child.stderr.pipe(streams[2], { end: false });
  child.stderr.pipe(process.stderr, { end: false });
  let spawnFailed = false;
  child.on('error', () => { spawnFailed = true; });
  const interrupt = () => child.kill('SIGINT');
  const terminate = () => child.kill('SIGTERM');
  process.on('SIGINT', interrupt);
  process.on('SIGTERM', terminate);
  const status = await new Promise(resolve => child.on('close', (code, signal) => resolve({ code, signal })));
  process.off('SIGINT', interrupt);
  process.off('SIGTERM', terminate);
  streams[2].end();
  await Promise.all(settled);
  return {
    commandExitCode: spawnFailed ? 127 : status.code ?? 128 + (constants.signals[status.signal] ?? 0),
    captureExitCode, commandSignal: status.signal ?? null,
  };
}

async function main(args) {
  const options = parseArgs(args);
  const invocationId = randomUUID();
  const dir = join(options.reportRoot, `${options.producer}-${invocationId}`);
  mkdirSync(dir, { recursive: true });
  let context = null;
  let worktreeDirty = null;
  let status = { commandExitCode: 2, captureExitCode: 0, commandSignal: null };
  let bytes;
  let exceptions;
  let conditional;
  let nativePlan;
  const memoryRequired = process.env.GVY_GRADLE_MEMORY_PROBE === 'true';
  const memoryPath = join(dir, 'memory.json');
  let memoryProbe = null;
  let memoryPlan = null;
  let memoryOutcome = null;
  let memoryReport = null;
  let memoryComplete = false;
  const errors = [];
  try {
    conditional = loadNativeDiagnosticPolicy();
    exceptions = [...JSON.parse(readFileSync(new URL('./gradle-diagnostics.json', import.meta.url), 'utf8')), ...conditional];
    context = createContext(invocationId);
    writeFileSync(join(dir, 'context.json'), `${JSON.stringify(context, null, 2)}\n`, { flag: 'wx' });
    worktreeDirty = Boolean(git(['status', '--porcelain', '--untracked-files=normal']));
    const gradleArgs = [...options.gradleArgs];
    if (options.nativeTarget) {
      if (memoryRequired || process.env.GVY_GRADLE_MEMORY_PROFILE !== undefined) {
        throw new Error('Native provisioning does not support numeric memory observation');
      }
      nativePlan = prepareNativeProvisioning({ context, producer: options.producer, target: options.nativeTarget,
        sourceRoot: process.cwd(), runnerTemp: process.env.RUNNER_TEMP, producerDir: dir, invocationId });
      gradleArgs.push(...nativePlan.gradleArgs);
    }
    if (!['true', 'false', undefined].includes(process.env.GVY_GRADLE_MEMORY_PROBE)) throw new Error('Invalid memory observation opt-in');
    const memoryProfile = gradleMemoryObservationProfile(process.env.GVY_GRADLE_MEMORY_PROFILE);
    if (process.env.GVY_GRADLE_MEMORY_PROFILE !== undefined && !memoryRequired) {
      throw new Error('Memory observation profile requires probe opt-in');
    }
    if (memoryRequired) {
      if (process.platform !== 'linux' || process.env.GITHUB_ACTIONS !== 'true' || process.env.RUNNER_OS !== 'Linux' || process.env.RUNNER_ENVIRONMENT !== 'github-hosted') {
        throw new Error('Required memory observation needs a Linux GitHub-hosted runner');
      }
      if (['JDK_JAVA_OPTIONS', 'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS'].some(key => /-XX:[+-]?ErrorFile/.test(process.env[key] ?? ''))) {
        throw new Error('Memory observation cannot use an inherited ErrorFile override');
      }
      const properties = readEvidenceText(join(process.cwd(), 'gradle.properties'), 64 * 1024);
      memoryPlan = gradleMemoryPlan({ context, producer: options.producer, job: process.env.GITHUB_JOB,
        jvmArgs: effectiveGradleJvmArgs(gradleArgs, properties), privateDirectory: memoryPrivateDirectory(options.reportRoot) });
      // Private crash-file routing proves ownership without changing memory/GC limits.
      gradleArgs.push('-Dorg.gradle.jvmargs=' + memoryPlan.jvmArgs);
      memoryProbe = startGradleMemoryProbe({ ...memoryProfile, ownerArgument: memoryPlan.ownerArgument, identity: memoryPlan.identity,
        reportPath: memoryPath });
    }
    status = await capture([...gradleArgs, '--console=plain', '--warning-mode=all', '-Dorg.gradle.deprecation.trace=true'], dir, nativePlan?.childEnv);
    // Oversize logs stay intact as artifacts; evidence fails instead of truncating.
    bytes = readEvidenceBytes(join(dir, 'console.log'), 128 * 1024 * 1024);
  } catch (error) { errors.push(error.message); }
  finally {
    if (memoryProbe) {
      try { memoryOutcome = await memoryProbe.stop(); }
      catch { errors.push('Gradle memory observation failed'); }
    }
  }
  if (memoryRequired && memoryPlan && memoryOutcome) {
    try {
      const reportBytes = readEvidenceBytes(memoryPath, 256 * 1024);
      if (!isUtf8(reportBytes)) throw new Error('Invalid memory report encoding');
      const report = JSON.parse(reportBytes.toString('utf8'));
      if (report.schemaVersion !== 1 || !isDeepStrictEqual(report.identity, memoryPlan.identity) ||
          typeof report.complete !== 'boolean' || report.complete !== memoryOutcome.complete) throw new Error('Invalid memory report identity');
      memoryReport = { bytes: reportBytes.length, sha256: createHash('sha256').update(reportBytes).digest('hex') };
      memoryComplete = report.complete;
    } catch { errors.push('Gradle memory evidence is missing, malformed or mismatched'); }
  }
  if (memoryRequired && !memoryComplete) errors.push('Required Gradle memory observation is incomplete');
  const result = checkGradleDiagnostics({
    log: bytes?.toString('utf8'), exceptions,
    commandExitCode: status.commandExitCode, captureExitCode: status.captureExitCode,
    encodingValid: bytes ? isUtf8(bytes) : false,
  });
  Object.assign(result, {
    producer: options.producer, context, worktreeDirty, commandSignal: status.commandSignal,
    log: bytes ? { bytes: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex') } : null,
  });
  if (memoryRequired) result.memoryProbe = { schemaVersion: 1, required: true, complete: memoryComplete, report: memoryReport };
  result.errors.push(...errors);
  result.ok &&= errors.length === 0 && context !== null;
  result.complete &&= errors.length === 0 && context !== null;
  if (options.nativeTarget) {
    try {
      if (!nativePlan) throw new Error('Native provisioning preparation unavailable');
      const proof = verifyNativeProvisioning({ plan: nativePlan, context, producer: options.producer, rawBytes: bytes, preliminaryDiagnostics: result });
      const admitted = proof.eligibleDiagnostics.map(diagnostic => {
        const entry = conditional.find(item => item.diagnostic === diagnostic);
        if (!entry || !result.unexpected.includes(diagnostic)) throw new Error('Native diagnostic policy mismatch');
        return entry;
      });
      const proofBytes = readEvidenceBytes(join(dir, 'native-provisioning-verification.json'), 2 * 1024 * 1024);
      const unexpected = result.unexpected.filter(value => !proof.eligibleDiagnostics.includes(value));
      const nativeProvisioning = { schemaVersion: 1, required: true, complete: true,
        preflightSha256: nativePlan.preflightSha256, preliminaryDiagnosticsSha256: proof.preliminaryDiagnosticsSha256,
        proofSha256: createHash('sha256').update(proofBytes).digest('hex') };
      const candidate = { ...result, unexpected, accepted: [...result.accepted, ...admitted], nativeProvisioning,
        ok: result.complete && result.commandExitCode === 0 && result.captureExitCode === 0 && result.errors.length === 0 && unexpected.length === 0 };
      const candidateBytes = Buffer.from(`${JSON.stringify(candidate, null, 2)}\n`);
      writeFileSync(join(dir, 'native-provisioning-final-verdict.json'), `${JSON.stringify({ schemaVersion: 1,
        diagnosticsSha256: createHash('sha256').update(candidateBytes).digest('hex'), proofSha256: nativeProvisioning.proofSha256 }, null, 2)}\n`, { flag: 'wx', mode: 0o400 });
      Object.assign(result, candidate);
    } catch (error) {
      result.errors.push(`Native provisioning proof failed: ${error.message}`);
      result.ok = false;
      result.complete = false;
      result.nativeProvisioning = { schemaVersion: 1, required: true, complete: false,
        preflightSha256: nativePlan?.preflightSha256 ?? null, preliminaryDiagnosticsSha256: null, proofSha256: null };
    }
  }
  writeFileSync(join(dir, 'raw.log'), bytes ?? '');
  const summary = renderSummary(result);
  const resultBytes = Buffer.from(`${JSON.stringify(result, null, 2)}\n`);
  writeFileSync(join(dir, 'diagnostics.json'), resultBytes);
  writeFileSync(join(dir, 'summary.json'), resultBytes);
  writeFileSync(join(dir, 'summary.md'), summary);
  if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY, summary);
  console.log(`Gradle evidence ${result.ok ? 'passed' : 'failed'}: ${dir}`);
  process.exitCode = status.commandExitCode !== 0 ? status.commandExitCode : result.ok ? 0 : 1;
}

try { await main(process.argv.slice(2)); }
catch (error) { console.error(error.message); process.exitCode = 2; }
