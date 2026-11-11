import { isUtf8 } from 'node:buffer';
import { spawn, spawnSync } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { appendFileSync, createWriteStream, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { constants } from 'node:os';
import { join } from 'node:path';
import { finished } from 'node:stream/promises';
import { checkGradleDiagnostics, renderSummary } from './check-gradle-diagnostics.mjs';
import { readEvidenceBytes, readEvidenceText } from './read-evidence.mjs';

function parseArgs(args) {
  const options = {};
  let index = 0;
  while (['--report-root', '--producer'].includes(args[index])) {
    const key = args[index];
    if (Object.hasOwn(options, key) || !args[index + 1]) throw new Error('Missing or duplicate Gradle wrapper option');
    options[key] = args[index + 1];
    index += 2;
  }
  if (!options['--report-root'] || !/^[a-z0-9][a-z0-9-]{0,79}$/.test(options['--producer'] ?? '') || index === args.length) {
    throw new Error('Usage: run-gradle.mjs --report-root DIR --producer NAME GRADLE_ARGS...');
  }
  return { reportRoot: options['--report-root'], producer: options['--producer'], gradleArgs: args.slice(index) };
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

async function capture(args, dir) {
  let captureExitCode = 0;
  const streams = ['stdout.log', 'stderr.log', 'console.log'].map(name => createWriteStream(join(dir, name), { flags: 'wx' }));
  const settled = streams.map(stream => finished(stream).catch(() => { captureExitCode = 1; }));
  const child = spawn('./gradlew', args, { stdio: ['ignore', 'pipe', 'pipe'] });
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
  const errors = [];
  try {
    exceptions = JSON.parse(readFileSync(new URL('./gradle-diagnostics.json', import.meta.url), 'utf8'));
    context = createContext(invocationId);
    writeFileSync(join(dir, 'context.json'), `${JSON.stringify(context, null, 2)}\n`, { flag: 'wx' });
    worktreeDirty = Boolean(git(['status', '--porcelain', '--untracked-files=normal']));
    status = await capture([...options.gradleArgs, '--console=plain', '--warning-mode=all', '-Dorg.gradle.deprecation.trace=true'], dir);
    // Oversize logs stay intact as artifacts; evidence fails instead of truncating.
    bytes = readEvidenceBytes(join(dir, 'console.log'), 128 * 1024 * 1024);
  } catch (error) { errors.push(error.message); }
  const result = checkGradleDiagnostics({
    log: bytes?.toString('utf8'), exceptions,
    commandExitCode: status.commandExitCode, captureExitCode: status.captureExitCode,
    encodingValid: bytes ? isUtf8(bytes) : false,
  });
  Object.assign(result, {
    producer: options.producer, context, worktreeDirty, commandSignal: status.commandSignal,
    log: bytes ? { bytes: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex') } : null,
  });
  result.errors.push(...errors);
  result.ok &&= errors.length === 0 && context !== null;
  result.complete &&= errors.length === 0 && context !== null;
  writeFileSync(join(dir, 'raw.log'), bytes ?? '');
  const summary = renderSummary(result);
  writeFileSync(join(dir, 'summary.md'), summary);
  writeFileSync(join(dir, 'diagnostics.json'), `${JSON.stringify(result, null, 2)}\n`);
  writeFileSync(join(dir, 'summary.json'), `${JSON.stringify(result, null, 2)}\n`);
  if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY, summary);
  console.log(`Gradle evidence ${result.ok ? 'passed' : 'failed'}: ${dir}`);
  process.exitCode = status.commandExitCode !== 0 ? status.commandExitCode : result.ok ? 0 : 1;
}

try { await main(process.argv.slice(2)); }
catch (error) { console.error(error.message); process.exitCode = 2; }
