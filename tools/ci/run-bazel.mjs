import { spawn, spawnSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { createWriteStream, mkdirSync, statSync, writeFileSync } from 'node:fs';
import { constants } from 'node:os';
import { isAbsolute, join } from 'node:path';
import { finished } from 'node:stream/promises';
import { evaluateEvidence } from './check-bazel-evidence.mjs';
import { readEvidenceText } from './read-evidence.mjs';

function parseArgs(args) {
  const options = { startup: [] };
  let index = 0;
  for (; index < args.length && !['build', 'test'].includes(args[index]); index += 2) {
    const name = args[index];
    const value = args[index + 1];
    if (!['--report-root', '--producer', '--startup-arg'].includes(name) || !value) throw new Error('Usage: run-bazel.mjs --report-root DIR --producer NAME [--startup-arg FLAG] build|test [BAZEL_ARGS...]');
    if (name === '--startup-arg') options.startup.push(value);
    else {
      if (Object.hasOwn(options, name)) throw new Error('Duplicate wrapper option');
      options[name] = value;
    }
  }
  if (!options['--report-root'] || !/^[a-z0-9][a-z0-9-]{0,79}$/.test(options['--producer'] ?? '') || index === args.length) throw new Error('Report root, producer name and build/test command are required');
  return { ...options, command: args[index], bazelArgs: args.slice(index + 1) };
}

function validateStartup(args) {
  for (const value of args) {
    if (value === '--batch') continue;
    if (/^--host_jvm_args=-Xmx[1-9][0-9]*[mMgG]$/.test(value)) {
      const match = value.match(/([0-9]+)([mMgG])$/);
      const mib = Number(match[1]) * (match[2].toLowerCase() === 'g' ? 1024 : 1);
      if (mib <= 4096) continue;
    }
    const path = value.match(/^--(?:output_user_root|output_base)=(.+)$/)?.[1];
    if (path && isAbsolute(path) && !/[\r\n\0]/.test(path)) continue;
    throw new Error('Unsupported startup argument (allowed: --batch, bounded -Xmx, absolute output root/base)');
  }
}

function git(args) {
  const result = spawnSync('git', args, { encoding: 'utf8' });
  if (result.status !== 0) throw new Error('Cannot determine checkout identity');
  return result.stdout.trim();
}

function createContext(command, invocationId) {
  const testedSha = git(['rev-parse', 'HEAD']);
  if (process.env.GITHUB_SHA && process.env.GITHUB_SHA !== testedSha) throw new Error('GitHub tested SHA does not match checkout HEAD');
  let headSha = testedSha;
  if (process.env.GITHUB_EVENT_PATH) {
    let event;
    try {
      event = JSON.parse(readEvidenceText(process.env.GITHUB_EVENT_PATH, 4 * 1024 * 1024));
    } catch { throw new Error('Cannot read GitHub event JSON'); }
    if (event.pull_request) headSha = event.pull_request.head?.sha;
  }
  return {
    schemaVersion: 1, headSha, testedSha, command, invocationId,
    runId: process.env.GITHUB_RUN_ID ?? `local-${invocationId}`,
    runAttempt: Number(process.env.GITHUB_RUN_ATTEMPT ?? '1'),
  };
}

async function capture(args, dir) {
  let captureExitCode = 0;
  const streams = ['stdout.log', 'stderr.log', 'console.log'].map(name => createWriteStream(join(dir, name), { flags: 'wx' }));
  const settled = streams.map(stream => finished(stream).catch(() => { captureExitCode = 1; }));
  const child = spawn('bazel', args, { stdio: ['ignore', 'pipe', 'pipe'] });
  // pipe() applies backpressure across all sinks, keeping large output bounded.
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
  const dir = join(options['--report-root'], `${options['--producer']}-${options.command}-${invocationId}`);
  mkdirSync(dir, { recursive: true });
  let result;
  let status = { commandExitCode: 2, captureExitCode: 0, commandSignal: null };
  let dirty = null;
  let context;
  try {
    validateStartup(options.startup);
    context = createContext(options.command, invocationId);
    // Reuse the checker's context contract before starting any build. A missing
    // BEP is expected here; all other context errors must abort invocation.
    const preflight = evaluateEvidence('', context, 0);
    if (!preflight.context) throw new Error(preflight.errors[0]);
    writeFileSync(join(dir, 'context.json'), `${JSON.stringify(context, null, 2)}\n`, { flag: 'wx' });
    dirty = Boolean(git(['status', '--porcelain', '--untracked-files=normal']));
    const separator = options.bazelArgs.indexOf('--');
    const userFlags = separator < 0 ? options.bazelArgs : options.bazelArgs.slice(0, separator);
    const targetSuffix = separator < 0 ? [] : options.bazelArgs.slice(separator);
    const telemetry = [
      '--config=bep', '--config=profile', '--bes_backend=', '--remote_cache=', '--remote_executor=', '--noremote_upload_local_results',
      `--invocation_id=${invocationId}`, `--build_event_json_file=${join(dir, 'bep.json')}`, `--profile=${join(dir, 'profile.json.gz')}`,
      ...Object.entries({ HEAD_SHA: context.headSha, TESTED_SHA: context.testedSha, RUN_ID: context.runId, RUN_ATTEMPT: String(context.runAttempt) }).map(([key, value]) => `--build_metadata=${key}=${value}`),
    ];
    status = await capture(['--nohome_rc', ...options.startup, options.command, '--config=ci', ...userFlags, ...telemetry, ...targetSuffix], dir);
    let bep = '';
    const errors = [];
    try {
      bep = readEvidenceText(join(dir, 'bep.json'), 256 * 1024 * 1024);
    } catch { errors.push('Cannot read BEP or BEP exceeds 256 MiB limit'); }
    try {
      const profile = statSync(join(dir, 'profile.json.gz'));
      if (!profile.isFile() || !profile.size) throw new Error('Empty profile');
    } catch { errors.push('Missing or empty trace profile'); }
    result = evaluateEvidence(bep, context, status.commandExitCode, status.captureExitCode);
    result.errors.push(...errors);
    result.ok &&= errors.length === 0;
  } catch (error) {
    result = evaluateEvidence('', context, status.commandExitCode, status.captureExitCode);
    result.errors.push(error.message);
  }
  result.producer = options['--producer'];
  result.remoteTelemetry = 'disabled-by-owner';
  result.worktreeDirty = dirty;
  result.commandSignal = status.commandSignal;
  writeFileSync(join(dir, 'summary.json'), `${JSON.stringify(result, null, 2)}\n`);
  console.log(`Bazel evidence ${result.ok ? 'passed' : 'failed'}: ${dir}`);
  process.exitCode = status.commandExitCode !== 0 ? status.commandExitCode : result.ok ? 0 : 1;
}

try { await main(process.argv.slice(2)); } catch (error) { console.error(error.message); process.exitCode = 2; }
