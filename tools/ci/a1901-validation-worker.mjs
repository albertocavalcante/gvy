// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import assert from 'node:assert/strict';
import { spawn, spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { closeSync, constants, copyFileSync, existsSync, lstatSync, mkdirSync, openSync, readdirSync, realpathSync, renameSync, statfsSync, unlinkSync, writeFileSync } from 'node:fs';
import { isAbsolute, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { readEvidenceBytes, readEvidenceText } from './read-evidence.mjs';

export const SOURCE = '70c639403588b74d556eb42591c9a7aa2941df2a';
export const WORKER_REF = 'refs/heads/ci/a1901-validation-worker';
export const SOURCE_PATHS = Object.freeze(['.reuse/check.py', '.reuse/tests/test_check.py', 'build.gradle.kts',
  'docs/ci-diagnostics.md', 'tools/ci/gradle-memory-probe.mjs', 'tools/ci/gradle-memory-probe.test.mjs',
  'tools/ci/verify-license-task-ordering.mjs', 'tools/ci/verify-license-task-ordering.test.mjs'].sort());
export const PHASES = Object.freeze(['node-controls', 'python-controls', 'root-format-apply', 'root-format-check',
  'fixtures', 'prerequisites', 'probe-order', 'lintfix', 'lint', 'focused-compile', 'root-format-final-check']);
const repository = fileURLToPath(new URL('../../', import.meta.url));
const assets = join(repository, '.github/a1901-validation');
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const fileHash = path => sha(readEvidenceBytes(path, 1024 * 1024));
const json = path => JSON.parse(readEvidenceText(path, 256 * 1024));
const sleep = ms => new Promise(done => setTimeout(done, ms));
const GiB = 1024 ** 3;
const LIMITS = Object.freeze({ jobMinutes: 45, phaseSeconds: 900, phaseLogBytes: 8388608,
  totalStreamBytes: 29360128, evidenceBytes: 134217728, maxFiles: 4096,
  minMemTotalGiB: 14, minMemAvailableGiB: 12, minDiskFreeGiB: 12, minRemainingDiskGiB: 4,
  fullDaemonHeapMiB: 2048, fullDaemonMetaspaceMiB: 1024, maxWorkers: 2,
  clientHeapMiB: 256, clientMetaspaceMiB: 256,
  additionalWorkerHeapBudgetMiB: 4096, additionalWorkerMetaspaceBudgetMiB: 512 });
const PAYLOAD_PATHS = Object.freeze(['.github/workflows/dependency-maintenance.yml',
  '.github/a1901-validation/profile.json', '.github/a1901-validation/source.patch',
  '.github/a1901-validation/payload.json.license',
  '.github/a1901-validation/source.patch.license', '.github/a1901-validation/profile.json.license',
  '.github/a1901-validation/ordering-graph.init.gradle', '.github/a1901-validation/root-kotlin-format.init.gradle',
  'tools/ci/a1901-validation-worker.mjs', 'tools/ci/a1901-validation-worker.test.mjs'].sort());

export function safeRelative(path) {
  assert.equal(typeof path, 'string');
  assert.ok(path.length > 0 && path.length <= 512 && !isAbsolute(path) && !/^[A-Za-z]:/.test(path));
  assert.ok(!/[\\\x00-\x20\x7f]/.test(path));
  assert.ok(path.split('/').every(part => part && part !== '.' && part !== '..' && !part.startsWith('-')));
  return path;
}

export function validateContext(env, runtime = { platform: process.platform, arch: process.arch, node: process.versions.node }) {
  assert.equal(runtime.platform, 'linux'); assert.equal(runtime.arch, 'x64');
  assert.equal(runtime.node.split('.')[0], '24');
  assert.equal(env.GITHUB_ACTIONS, 'true'); assert.equal(env.RUNNER_ENVIRONMENT, 'github-hosted');
  assert.equal(env.GITHUB_REPOSITORY, 'albertocavalcante/gvy');
  assert.equal(env.GITHUB_EVENT_NAME, 'workflow_dispatch'); assert.equal(env.GITHUB_REF, WORKER_REF);
  assert.match(env.GITHUB_SHA ?? '', /^[a-f0-9]{40}$/);
  assert.match(env.GITHUB_RUN_ID ?? '', /^[1-9][0-9]{0,17}$/);
  assert.equal(env.GITHUB_RUN_ATTEMPT, '1', 'A rerun is not admitted');
  assert.ok(isAbsolute(env.RUNNER_TEMP ?? ''));
  return { repository: env.GITHUB_REPOSITORY, helperSha: env.GITHUB_SHA, runId: env.GITHUB_RUN_ID,
    attempt: 1, sourceBase: SOURCE, kind: 'precommit-source-plus-patch-proof' };
}

export function validateProfile(profile) {
  assert.equal(profile.schemaVersion, 1); assert.equal(profile.revision, 'v2');
  assert.equal(profile.sourceBase, SOURCE); assert.equal(profile.workerRef, WORKER_REF);
  assert.equal(profile.repository, 'albertocavalcante/gvy');
  assert.match(profile.sourceTree, /^[a-f0-9]{40}$/);
  assert.match(profile.patchedTree, /^[a-f0-9]{40}$/);
  assert.equal(profile.purpose, 'fixed A1901 precommit proof, not publication-head CI acceptance');
  assert.deepEqual(profile.sourceFiles.map(item => item.path).sort(), SOURCE_PATHS);
  assert.deepEqual(profile.phases, PHASES);
  assert.match(profile.patchSha256, /^[a-f0-9]{64}$/);
  for (const entry of [...profile.sourceFiles, ...profile.toolHashes]) {
    safeRelative(entry.path);
    assert.match(entry.sha256, /^[a-f0-9]{64}$/);
  }
  const all = [...profile.sourceFiles, ...profile.toolHashes];
  assert.equal(new Set(all.map(entry => entry.path)).size, all.length);
  for (const entry of profile.sourceFiles) assert.match(entry.gitBlob, /^[a-f0-9]{40}$/);
  assert.deepEqual(profile.limits, LIMITS);
  assert.deepEqual(profile.scope, { remoteBuildScans: false, remoteCache: false, remoteBes: false,
    remoteExecutor: false, numericSampler: false, rssClaim: false, sourcePublication: false, factory1897Edits: false });
  assert.ok(!/\/Users\/|\/Volumes\/|Library\/Application Support/.test(JSON.stringify(profile)), 'Private profile paths are forbidden');
  return profile;
}

function paths(env = process.env) {
  const context = validateContext(env);
  const root = join(realpathSync(env.RUNNER_TEMP), `a1901-validation-${context.runId}-${context.attempt}`);
  const target = join(repository, 'build/a1901-target');
  assert.ok(relative(repository, root).startsWith('..' + sep));
  return { context, root, target, publication: join(root, 'publication'), stateFile: join(root, 'state.json') };
}
function save(path, value) {
  const temporary = path + '.new';
  writeFileSync(temporary, JSON.stringify(value, null, 2) + '\n', { flag: 'wx', mode: 0o600 });
  renameSync(temporary, path);
}
function load() {
  const p = paths(), state = json(p.stateFile);
  assert.deepEqual(state.context, p.context);
  const profile = validateProfile(json(join(assets, 'profile.json')));
  assert.equal(state.profileSha256, fileHash(join(assets, 'profile.json')));
  return { ...p, state, profile };
}
function fixed(command, args, cwd = repository, timeout = 20000, trimOutput = true) {
  const result = spawnSync(command, args, { cwd, encoding: 'utf8', timeout, killSignal: 'SIGKILL', maxBuffer: 1024 * 1024,
    env: { ...process.env, GIT_CONFIG_GLOBAL: '/dev/null', GIT_CONFIG_NOSYSTEM: '1',
      JDK_JAVA_OPTIONS: '-Xmx256m -XX:MaxMetaspaceSize=256m' } });
  assert.ifError(result.error); assert.equal(result.signal, null); assert.equal(result.status, 0, 'A fixed preflight command failed');
  return trimOutput ? result.stdout.trim() : result.stdout;
}
function diskFree(path) {
  const stat = statfsSync(path, { bigint: true });
  const bytes = Number(stat.bavail * stat.bsize);
  assert.ok(Number.isSafeInteger(bytes)); return bytes;
}
export function parseCapacity(text, freeDisk) {
  assert.ok(Number.isSafeInteger(freeDisk) && freeDisk >= 0);
  const read = name => {
    const rows = [...text.matchAll(new RegExp(`^${name}:\\s+(\\d+) kB$`, 'gm'))];
    assert.equal(rows.length, 1); const value = Number(rows[0][1]) * 1024;
    assert.ok(Number.isSafeInteger(value)); return value;
  };
  const result = { memTotalBytes: read('MemTotal'), memAvailableBytes: read('MemAvailable'), diskFreeBytes: freeDisk };
  assert.ok(result.memAvailableBytes <= result.memTotalBytes);
  assert.ok(result.memTotalBytes >= 14 * GiB && result.memAvailableBytes >= 12 * GiB && freeDisk >= 12 * GiB,
    'Actual runner capacity is below the fixed admission floor');
  return result;
}
function proc(pid) {
  const text = readEvidenceText(`/proc/${pid}/stat`, 32768), end = text.lastIndexOf(') ');
  assert.ok(text.startsWith(`${pid} (`) && end > 0);
  const fields = text.slice(end + 2).trim().split(/\s+/);
  const result = { pid, ppid: Number(fields[1]), group: Number(fields[2]), session: Number(fields[3]), start: Number(fields[19]), state: fields[0] };
  assert.ok([result.ppid, result.group, result.session, result.start].every(Number.isSafeInteger));
  assert.ok(result.start > 0); return result;
}
function javaProcesses() {
  const entries = readdirSync('/proc').filter(name => /^[1-9][0-9]*$/.test(name));
  assert.ok(entries.length <= 4096, 'Process inventory exceeds its bound');
  const rows = [];
  for (const name of entries) {
    try {
      if (readEvidenceText(`/proc/${name}/comm`, 256).trim() !== 'java') continue;
      const identity = proc(Number(name));
      if (identity.state === 'Z') continue;
      const argv = readEvidenceBytes(`/proc/${name}/cmdline`, 32768).toString('utf8').split('\0').filter(Boolean);
      rows.push({ ...identity, argv });
    } catch (error) { if (!['ENOENT', 'ESRCH'].includes(error.code)) throw error; }
  }
  return rows;
}
export function heapMiB(argv) {
  const value = argv.filter(arg => /^-Xmx/.test(arg)).at(-1);
  const match = value?.match(/^-Xmx([1-9][0-9]*)([kKmMgG])$/);
  assert.ok(match, 'A running JVM has no explicit recognizable heap cap');
  const bytes = Number(match[1]) * ({ k: 1024, m: 1024 ** 2, g: 1024 ** 3 })[match[2].toLowerCase()];
  assert.ok(Number.isSafeInteger(bytes) && bytes <= 2 * GiB, 'A running JVM exceeds the admitted heap');
  return bytes / 1024 ** 2;
}
function verifyAssets() {
  const manifest = json(join(assets, 'payload.json'));
  assert.equal(manifest.schemaVersion, 1);
  assert.equal(manifest.status, 'fixed-v2');
  assert.deepEqual(manifest.files.map(entry => entry.path).sort(), PAYLOAD_PATHS);
  for (const entry of manifest.files) {
    safeRelative(entry.path); assert.match(entry.sha256, /^[a-f0-9]{64}$/);
    assert.equal(fileHash(join(repository, entry.path)), entry.sha256, 'Helper payload or tool bytes changed');
  }
  return manifest;
}

function prepare() {
  const p = paths();
  assert.equal(fixed('git', ['rev-parse', 'HEAD']), p.context.helperSha);
  const profile = validateProfile(json(join(assets, 'profile.json')));
  verifyAssets(); assert.equal(fileHash(join(assets, 'source.patch')), profile.patchSha256);
  mkdirSync(p.root, { mode: 0o700 }); mkdirSync(p.publication, { mode: 0o700 });
  const state = { schemaVersion: 1, context: p.context, profileSha256: fileHash(join(assets, 'profile.json')),
    patchSha256: profile.patchSha256, status: 'preparing', phases: [], startedAt: new Date().toISOString(),
    deadline: Date.now() + 38 * 60 * 1000, cleanup: null };
  save(p.stateFile, state);
  try {
    const run = JSON.parse(fixed('gh', ['api', `repos/albertocavalcante/gvy/actions/runs/${p.context.runId}/attempts/1`]));
    assert.equal(run.head_sha, p.context.helperSha); assert.equal(run.event, 'workflow_dispatch');
    assert.equal(run.run_attempt, 1); assert.equal(String(run.id), p.context.runId);
    assert.equal(run.path, '.github/workflows/dependency-maintenance.yml');
    assert.equal(run.head_branch, WORKER_REF.slice('refs/heads/'.length));
    assert.equal(run.repository?.full_name, p.context.repository);
    assert.equal(run.head_repository?.full_name, p.context.repository);
    const runs = JSON.parse(fixed('gh', ['api', `repos/albertocavalcante/gvy/actions/workflows/dependency-maintenance.yml/runs?head_sha=${p.context.helperSha}&event=workflow_dispatch&per_page=100`]));
    assert.equal(runs.total_count, 1, 'Only one fresh dispatch at this helper head is admitted');
    assert.equal(String(runs.workflow_runs[0].id), p.context.runId);
    state.capacity = parseCapacity(readEvidenceText('/proc/meminfo', 32768), diskFree(p.root));
    assert.deepEqual(javaProcesses(), [], 'Runner already has an unrelated JVM; no source JVM is started');
    state.status = 'prepared';
  } catch { state.status = 'failed'; state.error = 'preflight_failed'; throw new Error('Worker preflight failed'); }
  finally { save(p.stateFile, state); }
}
function stage() {
  const p = load(); assert.equal(p.state.status, 'prepared'); verifyAssets();
  assert.equal(fixed('git', ['rev-parse', 'HEAD'], p.target), SOURCE);
  assert.equal(fixed('git', ['rev-parse', 'HEAD^{tree}'], p.target), p.profile.sourceTree);
  assert.equal(fixed('git', ['status', '--porcelain'], p.target), '');
  fixed('git', ['apply', '--check', join(assets, 'source.patch')], p.target);
  fixed('git', ['apply', '--index', join(assets, 'source.patch')], p.target);
  assert.deepEqual(fixed('git', ['diff', '--cached', '--name-only'], p.target).split('\n').sort(), SOURCE_PATHS);
  for (const item of [...p.profile.sourceFiles, ...p.profile.toolHashes]) assert.equal(fileHash(join(p.target, item.path)), item.sha256);
  for (const item of p.profile.sourceFiles) assert.equal(fixed('git', ['hash-object', '--', item.path], p.target), item.gitBlob);
  p.state.stagedTree = fixed('git', ['write-tree'], p.target);
  assert.equal(p.state.stagedTree, p.profile.patchedTree);
  p.state.status = 'staged'; save(p.stateFile, p.state);
}

export function validateSummary(summary, log, producer, context) {
  assert.equal(summary.schemaVersion, 1); assert.equal(summary.producer, producer);
  assert.equal(summary.complete, true); assert.equal(summary.ok, true);
  assert.equal(summary.commandExitCode, 0); assert.equal(summary.captureExitCode, 0);
  assert.deepEqual(summary.errors, []); assert.deepEqual(summary.unexpected, []);
  assert.equal(summary.log?.bytes, log.length); assert.equal(summary.log?.sha256, sha(log));
  assert.equal(summary.context?.headSha, SOURCE); assert.equal(summary.context?.testedSha, SOURCE);
  assert.equal(summary.context?.runId, context.runId); assert.equal(summary.context?.runAttempt, context.attempt);
  return summary;
}
export function verifyProbeLog(log) {
  const lines = log.split(/\r?\n/);
  const markers = [/^> Task :licenseCheck$/, /^REUSE and first-party header checks passed$/,
    /^> Task :verifyKotlinFormattingTargets$/,
    /^Verified Kotlin formatter source and generated-output targets for [1-9][0-9]* layouts$/];
  const positions = markers.map(pattern => {
    const matches = lines.flatMap((line, index) => pattern.test(line) ? [index] : []);
    assert.equal(matches.length, 1, 'Missing or repeated actual licensing/probe witness'); return matches[0];
  });
  assert.ok(positions.every((position, index) => index === 0 || position > positions[index - 1]),
    'Actual licensing must pass before the formatter probe runs');
}

function childEnvironment(p) {
  // Source capture context is deliberately distinct from the actual helper run.
  // Neither this dirty precommit target nor its patched tree is final-head CI.
  const env = { ...process.env, GITHUB_SHA: SOURCE, GVY_A1901_OWNER: p.context.runId + '-' + p.context.helperSha,
    JDK_JAVA_OPTIONS: '-Xmx256m -XX:MaxMetaspaceSize=256m', GVY_GRADLE_MEMORY_PROBE: 'false',
    UV_NO_SYNC: '1', PYTHONDONTWRITEBYTECODE: '1', UV_PROJECT_ENVIRONMENT: join(p.target, '.reuse/.venv'),
    GRADLE_USER_HOME: join(p.root, 'gradle-home'), UV_CACHE_DIR: join(p.root, 'uv-cache'),
    GIT_CONFIG_GLOBAL: '/dev/null', GIT_CONFIG_NOSYSTEM: '1', CI: 'true' };
  // Commands under test have no API or repository credential requirement.
  delete env.GH_TOKEN; delete env.GITHUB_TOKEN; delete env.ACTIONS_RUNTIME_TOKEN;
  delete env.JAVA_TOOL_OPTIONS; delete env._JAVA_OPTIONS; delete env.GRADLE_OPTS; delete env.JAVA_OPTS;
  delete env.GVY_MEMORY_PROBE_NATIVE_TEST;
  return env;
}
function jvmInventory(p) {
  const owner = p.context.runId + '-' + p.context.helperSha;
  return javaProcesses().flatMap(row => {
    try {
    const env = readEvidenceBytes(`/proc/${row.pid}/environ`, 128 * 1024).toString('utf8').split('\0');
    const again = proc(row.pid); assert.equal(again.start, row.start, 'JVM identity changed during inspection');
    const owned = env.includes('GVY_A1901_OWNER=' + owner);
    const options = env.find(item => item.startsWith('JDK_JAVA_OPTIONS='))?.slice('JDK_JAVA_OPTIONS='.length) ?? '';
    const args = [...options.split(/\s+/), ...row.argv];
      return [{ pid: row.pid, start: row.start, owned, args }];
    } catch (error) { if (['ENOENT', 'ESRCH'].includes(error.code)) return []; throw error; }
  });
}
function inspectJvms(p) {
  const rows = jvmInventory(p);
  assert.ok(rows.every(row => row.owned), 'An unrelated JVM appeared; it will not be signalled');
  assert.ok(rows.length <= 4, 'JVM process count exceeds the admitted profile');
  let heaps = 0, metaspace = 0;
  for (const row of rows) {
    heaps += heapMiB(row.args);
    const value = row.args.filter(arg => arg.startsWith('-XX:MaxMetaspaceSize=')).at(-1);
    const match = value?.match(/^-XX:MaxMetaspaceSize=([1-9][0-9]*)([mMgG])$/);
    assert.ok(match, 'JVM has no explicit metaspace limit');
    const amount = Number(match[1]) * (match[2].toLowerCase() === 'g' ? 1024 : 1);
    assert.ok(Number.isSafeInteger(amount) && amount <= 1024); metaspace += amount;
  }
  assert.ok(heaps <= 6400 && metaspace <= 1792, 'Aggregate configured JVM caps exceed admission');
  p.state.maxObservedJvmCount = Math.max(p.state.maxObservedJvmCount ?? 0, rows.length);
}
function ownedProcesses(p) {
  const entries = readdirSync('/proc').filter(name => /^[1-9][0-9]*$/.test(name));
  assert.ok(entries.length <= 4096);
  const owner = 'GVY_A1901_OWNER=' + p.context.runId + '-' + p.context.helperSha;
  const rows = []; let bytes = 0;
  for (const entry of entries) {
    try {
      if (lstatSync('/proc/' + entry).uid !== process.getuid()) continue;
      const identity = proc(Number(entry)); if (identity.state === 'Z') continue;
      const environment = readEvidenceBytes(`/proc/${entry}/environ`, 128 * 1024);
      bytes += environment.length; assert.ok(bytes <= 4 * 1024 * 1024, 'Process environment scan exceeds its byte bound');
      if (!environment.toString('utf8').split('\0').includes(owner)) continue;
      assert.equal(proc(Number(entry)).start, identity.start);
      rows.push({ pid: identity.pid, start: identity.start });
    } catch (error) { if (!['ENOENT', 'ESRCH'].includes(error.code)) throw error; }
  }
  return rows;
}
function treeSize(directory) {
  if (!existsSync(directory)) return 0;
  let files = 0, bytes = 0;
  const walk = (path, depth) => {
    assert.ok(depth <= 16, 'Evidence nesting exceeds its bound');
    for (const item of readdirSync(path, { withFileTypes: true })) {
      assert.ok(++files <= LIMITS.maxFiles, 'Evidence file count exceeds its bound');
      const child = join(path, item.name), info = lstatSync(child);
      assert.equal(info.isSymbolicLink(), false, 'Evidence must not contain symbolic links');
      if (info.isDirectory()) walk(child, depth + 1);
      else { assert.equal(info.isFile(), true); bytes += info.size; assert.ok(bytes <= LIMITS.evidenceBytes); }
    }
  };
  walk(directory, 0); return bytes;
}
function resourceCheck(p) {
  assert.ok(Date.now() < p.state.deadline, 'Worker deadline exceeded');
  assert.ok(diskFree(p.root) >= LIMITS.minRemainingDiskGiB * GiB, 'Remaining disk floor violated');
  treeSize(join(p.root, 'evidence')); inspectJvms(p);
}

// The controller owns this detached child group. Once its leader disappears,
// daemon teardown uses the independent owner-tag + start-time check below.
export async function capture(p, phase, command, args, deadline, observations = {}) {
  assert.ok(PHASES.includes(phase));
  // Native Node-only controls replace Linux observation, never workflow inputs.
  const identityOf = observations.identityOf ?? proc;
  const check = observations.check ?? resourceCheck;
  const file = join(p.root, 'evidence', phase + '.log');
  const previous = p.state.commands.some(item => item.phase === phase);
  const descriptor = openSync(file, constants.O_WRONLY | constants.O_APPEND | constants.O_NOFOLLOW |
    constants.O_CREAT | (previous ? 0 : constants.O_EXCL), 0o600);
  let child, identity, cause = null, bytes = 0, completed = false;
  let timeout, monitor, grace, hard;
  const startedAt = Date.now();
  return await new Promise((resolveCapture, rejectCapture) => {
    const signalGroup = signal => {
      if (!identity) return;
      try { if (identityOf(identity.pid).start === identity.start) process.kill(-identity.pid, signal); }
      catch (error) { if (!['ESRCH', 'ENOENT'].includes(error.code)) throw error; }
    };
    const finish = (code, signal) => {
      if (completed) return; completed = true;
      for (const timer of [timeout, monitor, grace, hard]) clearTimeout(timer);
      process.off('SIGTERM', interrupted); process.off('SIGINT', interrupted);
      child.stdout?.destroy(); child.stderr?.destroy(); closeSync(descriptor);
      const result = { command, args, code, signal, cause, bytes, elapsedMs: Date.now() - startedAt };
      p.state.commands.push({ phase, ...result });
      try { save(p.stateFile, p.state); } catch { rejectCapture(new Error('capture_state_failed')); return; }
      if (cause || code !== 0 || signal) rejectCapture(new Error(cause ?? 'command_exit'));
      else resolveCapture(result);
    };
    const stop = reason => {
      if (cause || completed) return; cause = reason;
      try { signalGroup('SIGTERM'); } catch { p.state.teardownUncertain = true; }
      grace = setTimeout(() => {
        try { signalGroup('SIGKILL'); } catch { p.state.teardownUncertain = true; }
        hard = setTimeout(() => { p.state.teardownUncertain = true; child.unref(); finish(null, 'hard_bound'); }, 2000);
      }, 1000);
    };
    const interrupted = () => stop('interrupted');
    try {
      child = spawn(command, args, { cwd: p.target, env: childEnvironment(p), detached: true, stdio: ['ignore', 'pipe', 'pipe'] });
      if (child.pid) identity = identityOf(child.pid);
    } catch { if (child?.pid) child.kill('SIGKILL'); closeSync(descriptor); rejectCapture(new Error('start_failed')); return; }
    const output = chunk => {
      if (completed) return;
      bytes += chunk.length; p.state.streamBytes += chunk.length;
      const priorBytes = p.state.commands.filter(item => item.phase === phase).reduce((total, item) => total + item.bytes, 0);
      if (priorBytes + bytes > LIMITS.phaseLogBytes || p.state.streamBytes > LIMITS.totalStreamBytes) stop('stream_limit');
      else if (!cause) { try { writeFileSync(descriptor, chunk); } catch { stop('capture_failed'); } }
    };
    child.stdout.on('data', output); child.stderr.on('data', output);
    child.once('error', () => { cause ??= 'start_failed'; finish(null, null); });
    child.once('close', finish);
    timeout = setTimeout(() => stop('phase_deadline'), Math.max(1, deadline - Date.now()));
    monitor = setInterval(() => { try { check(p); } catch { stop('resource_limit'); } }, 1000);
    process.on('SIGTERM', interrupted); process.on('SIGINT', interrupted);
  });
}

function canonical(p, phase) {
  const root = join(p.root, 'evidence', 'reports', phase), entries = readdirSync(root);
  assert.equal(entries.length, 1, 'Expected exactly one canonical invocation');
  const directory = join(root, safeRelative(entries[0]));
  const log = readEvidenceBytes(join(directory, 'console.log'), LIMITS.phaseLogBytes);
  const summary = validateSummary(json(join(directory, 'summary.json')), log, 'a1901-' + phase, p.context);
  assert.equal(summary.worktreeDirty, true, 'Proof must declare its patched working tree');
  for (const name of ['summary.json', 'context.json', 'console.log']) p.state.evidenceFiles.push(relative(p.root, join(directory, name)));
  return { summary, log: log.toString('utf8') };
}
function graph(p, phase, mode, required) {
  const report = json(join(p.root, 'evidence', phase + '-graph.json'));
  assert.equal(report.schemaVersion, 1); assert.equal(report.ok, true);
  assert.equal(report.expectation, mode); assert.deepEqual(report.licensePrerequisites, []);
  assert.equal(new Set(report.tasks).size, report.tasks.length);
  assert.ok(required.every(task => report.tasks.includes(task)));
  assert.equal(report.tasks.filter(task => task === ':licenseCheck').length, mode === 'required' ? 1 : 0);
  p.state.evidenceFiles.push('evidence/' + phase + '-graph.json');
  return report;
}
function unchangedSource(p, allowFormatting) {
  assert.equal(fixed('git', ['write-tree'], p.target), p.state.stagedTree, 'The staged source tree changed');
  for (const item of [...p.profile.sourceFiles, ...p.profile.toolHashes]) {
    if (allowFormatting && item.path === 'build.gradle.kts') continue;
    assert.equal(fileHash(join(p.target, item.path)), item.sha256, 'Unexpected source or tool modification');
  }
  const changed = fixed('git', ['diff', '--name-only', '--no-ext-diff'], p.target);
  assert.ok(changed === '' || (allowFormatting && changed === 'build.gradle.kts'), 'Formatting modified other source files');
  assert.deepEqual(fixed('git', ['ls-files', '--others', '--exclude-standard'], p.target), '');
}
async function execute() {
  const p = load(); assert.equal(p.state.status, 'staged'); verifyAssets();
  p.state.status = 'running'; p.state.commands = []; p.state.streamBytes = 0; p.state.evidenceFiles = [];
  // Leave several minutes for owned-process teardown and the bounded upload.
  assert.ok(Date.now() < p.state.deadline);
  mkdirSync(join(p.root, 'evidence'), { mode: 0o700 });
  mkdirSync(join(p.root, 'evidence/inputs'), { mode: 0o700 });
  for (const path of [...PAYLOAD_PATHS, '.github/a1901-validation/payload.json']) {
    const destination = 'evidence/inputs/' + path.split('/').at(-1);
    writeFileSync(join(p.root, destination), readEvidenceBytes(join(repository, path), 1024 * 1024), { flag: 'wx', mode: 0o600 });
    p.state.evidenceFiles.push(destination);
  }
  save(p.stateFile, p.state);
  const node = process.execPath;
  const gradle = (phase, tasks, options = []) => [node, ['tools/ci/run-gradle.mjs', '--report-root',
    join(p.root, 'evidence/reports', phase), '--producer', 'a1901-' + phase, ...tasks,
    '--no-daemon', '--parallel', '--max-workers=2', '--no-configuration-cache', '--no-build-cache',
    '-Dorg.gradle.jvmargs=-Xms64m -Xmx2048m -XX:MaxMetaspaceSize=1024m -Dfile.encoding=UTF-8',
    '-Pkotlin.compiler.execution.strategy=in-process', '-Dorg.gradle.scan=false',
    '-Dorg.gradle.caching=false', '--console=plain', '--stacktrace', ...options]];
  const init = name => join(assets, name + '.init.gradle');
  const graphOptions = (phase, mode) => ['--init-script', init('ordering-graph'),
    '-Dgvy.license.ordering.expect=' + mode,
    '-Dgvy.license.ordering.report=' + join(p.root, 'evidence', phase + '-graph.json')];
  const uv = args => ['uv', ['run', '--offline', '--locked', '--project', '.reuse', ...args]];
  try {
    for (const phase of PHASES) {
      const deadline = Math.min(p.state.deadline, Date.now() + LIMITS.phaseSeconds * 1000);
      p.state.currentPhase = phase; save(p.stateFile, p.state); resourceCheck(p);
      let commands;
      if (phase === 'node-controls') commands = [['java', ['-Xmx256m', '-XX:MaxMetaspaceSize=256m', '-version']],
        [node, ['--test', 'tools/ci/gradle-memory-probe.test.mjs', 'tools/ci/verify-license-task-ordering.test.mjs']]];
      else if (phase === 'python-controls') commands = [
        ['python', ['-m', 'pip', 'install', '--require-hashes', '--only-binary=:all:', '-r', '.reuse/bootstrap-requirements.txt']],
        ['uv', ['--version']],
        ['uv', ['sync', '--locked', '--project', '.reuse']],
        uv(['python', '-B', '-c', 'import importlib.metadata as m, json, sys; assert sys.version_info[:2] == (3, 11); expected = {"reuse": "6.3.0", "ruff": "0.14.14", "basedpyright": "1.38.2", "ty": "0.0.14"}; actual = {name: m.version(name) for name in expected}; assert actual == expected; print("A1901_RUNTIME=" + json.dumps({"python": sys.version.split()[0], **actual}, sort_keys=True))']),
        uv(['python', '-B', '-m', 'unittest', 'discover', '-s', '.reuse/tests']),
        uv(['ruff', 'format', '--check', '.reuse/check.py', '.reuse/tests/test_check.py']),
        uv(['ruff', 'check', '.reuse/check.py', '.reuse/tests/test_check.py']),
        uv(['basedpyright', '.reuse/check.py', '.reuse/tests/test_check.py']),
        uv(['ty', 'check', '.reuse/check.py', '.reuse/tests/test_check.py'])];
      else if (phase === 'fixtures') commands = [[node, ['tools/ci/verify-license-task-ordering.mjs', '--execute', '--report-root', join(p.root, 'evidence/fixtures')]]];
      else if (['root-format-apply', 'root-format-check', 'root-format-final-check'].includes(phase)) {
        commands = [gradle(phase, [phase === 'root-format-apply' ? ':spotlessKotlinGradleApply' : ':spotlessKotlinGradleCheck'],
          ['--init-script', init('root-kotlin-format'), '--rerun-tasks'])];
      } else if (phase === 'prerequisites') commands = [gradle(phase, [':licenseCheck', '--dry-run'], graphOptions(phase, 'required'))];
      else if (phase === 'probe-order') commands = [gradle(phase, [':licenseCheck', ':verifyKotlinFormattingTargets', '--rerun-tasks'], graphOptions(phase, 'required'))];
      else if (phase === 'lintfix' || phase === 'lint') commands = [gradle(phase, [':licenseCheck', phase === 'lintfix' ? 'lintFix' : 'lint', '--rerun-tasks'], graphOptions(phase, 'required'))];
      else if (phase === 'focused-compile') commands = [gradle(phase, [':gls:compileKotlin', ':semantics:core:compileKotlin', '--rerun-tasks'], graphOptions(phase, 'absent'))];
      else throw new Error('Unknown fixed phase');
      for (const [command, args] of commands) {
        await capture(p, phase, command, args, deadline);
        if (command === 'java') {
          const line = readEvidenceText(join(p.root, 'evidence', phase + '.log'), LIMITS.phaseLogBytes)
            .split('\n').find(line => /^(?:openjdk|java) version "21(?:\.|"|\+)/.test(line));
          assert.ok(line, 'Actual JDK version does not match the fixed profile');
          p.state.runtime = { node: process.version, javaVersionLine: line };
        }
        if (command === 'uv' && args[0] === '--version') {
          assert.ok(/^uv 0\.13\.7(?:\s|$)/m.test(readEvidenceText(join(p.root, 'evidence', phase + '.log'), LIMITS.phaseLogBytes)));
        }
      }
      if (phase === 'python-controls') {
        const rows = readEvidenceText(join(p.root, 'evidence', phase + '.log'), LIMITS.phaseLogBytes)
          .split('\n').filter(line => line.startsWith('A1901_RUNTIME='));
        assert.equal(rows.length, 1); p.state.runtime.pythonTools = JSON.parse(rows[0].slice('A1901_RUNTIME='.length));
        p.state.runtime.uv = '0.13.7';
      }
      if (!['node-controls', 'python-controls', 'fixtures'].includes(phase)) {
        const result = canonical(p, phase);
        if (phase === 'prerequisites') assert.deepEqual(graph(p, phase, 'required', [':licenseCheck']).tasks, [':licenseCheck']);
        if (phase === 'probe-order') {
          graph(p, phase, 'required', [':licenseCheck', ':verifyKotlinFormattingTargets']);
          verifyProbeLog(result.log);
        }
        if (phase === 'lintfix' || phase === 'lint') graph(p, phase, 'required', [':licenseCheck', phase === 'lintfix' ? ':lintFix' : ':lint']);
        if (phase === 'focused-compile') graph(p, phase, 'absent', [':gls:compileKotlin', ':semantics:core:compileKotlin']);
      }
      if (phase === 'root-format-apply') {
        unchangedSource(p, true);
        p.state.formattedBuildSha256 = fileHash(join(p.target, 'build.gradle.kts'));
        writeFileSync(join(p.root, 'evidence/formatter.diff'), fixed('git', ['diff', '--no-ext-diff', '--no-textconv', '--', 'build.gradle.kts'], p.target, 20000, false), { flag: 'wx', mode: 0o600 });
        copyFileSync(join(p.target, 'build.gradle.kts'), join(p.root, 'evidence/formatted-build.gradle.kts'));
        p.state.evidenceFiles.push('evidence/formatter.diff', 'evidence/formatted-build.gradle.kts');
      }
      if (phase === 'fixtures') {
        const report = json(join(p.root, 'evidence/fixtures/ordering-verification.json'));
        assert.equal(report.ok, true); assert.equal(report.currentCase, null);
        assert.equal(report.buildFileSha256, p.state.formattedBuildSha256);
        assert.deepEqual(report.cases.map(item => item.name), ['missing-order', 'eager-selection', 'ordered', 'focused', 'license-rejected']);
        assert.ok(report.cases.every(item => item.ok === true && item.complete === true && item.captureExitCode === 0));
        p.state.evidenceFiles.push('evidence/fixtures/ordering-verification.json');
        for (const item of report.cases) {
          const directory = join(p.root, 'evidence/fixtures', item.name, 'evidence');
          const entries = readdirSync(directory); assert.equal(entries.length, 1);
          safeRelative(entries[0]);
          for (const name of ['summary.json', 'context.json', 'console.log']) {
            p.state.evidenceFiles.push(relative(p.root, join(directory, entries[0], name)));
          }
        }
      }
      unchangedSource(p, true);
      if (p.state.formattedBuildSha256) assert.equal(fileHash(join(p.target, 'build.gradle.kts')), p.state.formattedBuildSha256, 'Later phase changed root formatting');
      p.state.phases.push({ name: phase, ok: true }); p.state.evidenceFiles.push('evidence/' + phase + '.log');
      save(p.stateFile, p.state);
    }
    p.state.currentPhase = null; p.state.status = 'passed';
  } catch (error) {
    p.state.status = 'failed'; p.state.failure = { phase: p.state.currentPhase, code: 'validation_failed' };
    // Retain the original canonical failure when it exists, without treating it
    // as accepted positive evidence or falling back to a recursive raw upload.
    const directory = join(p.root, 'evidence/reports', p.state.currentPhase);
    if (existsSync(directory)) {
      for (const entry of readdirSync(directory)) {
        safeRelative(entry);
        for (const name of ['summary.json', 'context.json', 'console.log']) {
          const path = join(directory, entry, name);
          if (existsSync(path)) p.state.evidenceFiles.push(relative(p.root, path));
        }
      }
    }
    if (p.state.currentPhase === 'fixtures') {
      const report = join(p.root, 'evidence/fixtures/ordering-verification.json');
      if (existsSync(report)) p.state.evidenceFiles.push(relative(p.root, report));
      for (const name of ['missing-order', 'eager-selection', 'ordered', 'focused', 'license-rejected']) {
        const reports = join(p.root, 'evidence/fixtures', name, 'evidence');
        if (!existsSync(reports)) continue;
        for (const entry of readdirSync(reports)) {
          safeRelative(entry);
          for (const file of ['summary.json', 'context.json', 'console.log']) {
            const path = join(reports, entry, file);
            if (existsSync(path)) p.state.evidenceFiles.push(relative(p.root, path));
          }
        }
      }
    }
    throw new Error('Fixed validation failed; retain the phase and original command evidence', { cause: error });
  } finally { save(p.stateFile, p.state); }
}

async function cleanup() {
  const p = load(), observed = [];
  const signal = (row, kind) => {
    try {
      const current = ownedProcesses(p).find(item => item.pid === row.pid && item.start === row.start);
      if (current) process.kill(current.pid, kind);
    } catch (error) { if (!['ENOENT', 'ESRCH'].includes(error.code)) throw error; }
  };
  try {
    for (const row of ownedProcesses(p)) {
      observed.push({ pid: row.pid, start: row.start }); signal(row, 'SIGTERM');
    }
    await sleep(1000);
    for (const row of ownedProcesses(p)) signal(row, 'SIGKILL');
    await sleep(1000);
    assert.deepEqual(ownedProcesses(p), [], 'Owned process teardown did not finish');
    p.state.cleanup = { ok: !p.state.teardownUncertain, observed, foreignProcessesSignalled: false };
    assert.equal(p.state.cleanup.ok, true);
  } catch {
    p.state.cleanup = { ok: false, observed, foreignProcessesSignalled: false };
    throw new Error('Owned-process cleanup failed');
  } finally { save(p.stateFile, p.state); }
}
export function publishEvidence(p, payloadSha256) {
  assert.match(payloadSha256, /^[a-f0-9]{64}$/);
  const completePhases = JSON.stringify(p.state.phases) === JSON.stringify(PHASES.map(name => ({ name, ok: true })));
  const receipt = { schemaVersion: 1, context: p.context, status: p.state.status,
    ok: p.state.status === 'passed' && completePhases && p.state.cleanup?.ok === true,
    profileSha256: p.state.profileSha256, payloadSha256,
    patchSha256: p.state.patchSha256, sourceTree: p.profile.sourceTree, stagedTree: p.state.stagedTree ?? null,
    sourceFiles: p.profile.sourceFiles, tools: p.profile.toolHashes, capacity: p.state.capacity ?? null,
    phases: p.state.phases, failure: p.state.failure ?? p.state.error ?? null, cleanup: p.state.cleanup,
    operationFailures: p.state.operationFailures ?? [],
    commands: p.state.commands ?? [], streamBytes: p.state.streamBytes ?? 0,
    runtime: p.state.runtime ?? null,
    formattedBuildSha256: p.state.formattedBuildSha256 ?? null,
    limits: p.profile.limits, scope: p.profile.scope, limitsExplanation: p.profile.limitsExplanation,
    files: [], omitted: [] };
  // Explicit, verified allowlist only. Raw state, environment and proc records
  // are never fallback artifacts. Failed commands retain only their bounded log.
  const evidence = new Set(p.state.evidenceFiles ?? []);
  if (p.state.currentPhase && PHASES.includes(p.state.currentPhase)) evidence.add('evidence/' + p.state.currentPhase + '.log');
  const required = [...PHASES.map(phase => 'evidence/' + phase + '.log'),
    'evidence/formatter.diff', 'evidence/formatted-build.gradle.kts', 'evidence/fixtures/ordering-verification.json',
    ...[...PAYLOAD_PATHS, '.github/a1901-validation/payload.json'].map(path => 'evidence/inputs/' + path.split('/').at(-1))];
  const canonicalPrefixes = [
    ...PHASES.filter(phase => !['node-controls', 'python-controls', 'fixtures'].includes(phase)).map(phase => 'evidence/reports/' + phase + '/'),
    ...['missing-order', 'eager-selection', 'ordered', 'focused', 'license-rejected'].map(name => 'evidence/fixtures/' + name + '/evidence/')];
  receipt.coverageComplete = required.every(path => evidence.has(path)) && canonicalPrefixes.every(prefix =>
    ['summary.json', 'context.json', 'console.log'].every(name =>
      [...evidence].filter(path => path.startsWith(prefix) && path.endsWith('/' + name)).length === 1));
  receipt.ok &&= receipt.coverageComplete;
  let total = 0, ordinal = 0;
  for (const path of [...evidence].sort()) {
    ordinal++;
    let validPath = false, temporary, created = false;
    try {
      safeRelative(path); validPath = true;
      const source = join(p.root, path), destination = join(p.publication, path);
      assert.ok(realpathSync(source).startsWith(realpathSync(p.root) + sep));
      const bytes = readEvidenceBytes(source, LIMITS.phaseLogBytes);
      assert.ok(total + bytes.length <= LIMITS.evidenceBytes - 1024 * 1024);
      mkdirSync(resolve(destination, '..'), { recursive: true, mode: 0o700 });
      assert.equal(existsSync(destination), false);
      temporary = join(p.root, 'publication-copy-' + ordinal + '.new');
      const descriptor = openSync(temporary, 'wx', 0o600); created = true;
      try { writeFileSync(descriptor, bytes); } finally { closeSync(descriptor); }
      renameSync(temporary, destination); created = false;
      total += bytes.length;
      receipt.files.push({ path, bytes: bytes.length, sha256: sha(bytes) });
    } catch {
      if (created) { try { unlinkSync(temporary); } catch { /* Partial copies remain private, outside publication. */ } }
      receipt.ok = false;
      receipt.omitted.push({ ...(validPath ? { path } : { ordinal }), reason: 'unreadable-unsafe-or-over-budget' });
    }
  }
  try { treeSize(p.publication); } catch { receipt.ok = false; receipt.publicationTreeInvalid = true; }
  assert.ok(Buffer.byteLength(JSON.stringify(receipt)) <= 256 * 1024);
  save(join(p.publication, 'receipt.json'), receipt);
  return receipt;
}
function publish() {
  const p = load(); verifyAssets();
  const receipt = publishEvidence(p, fileHash(join(assets, 'payload.json')));
  assert.equal(receipt.ok, true, 'Published failure evidence does not satisfy validation');
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    assert.equal(process.argv.length, 3, 'Only a fixed worker operation is accepted');
    const operations = { prepare, stage, execute, cleanup, publish };
    assert.ok(Object.hasOwn(operations, process.argv[2]));
    const operation = operations[process.argv[2]];
    assert.equal(typeof operation, 'function'); await operation();
  } catch {
    try {
      const p = load(); p.state.operationFailures ??= [];
      const operation = ['prepare', 'stage', 'execute', 'cleanup', 'publish'].includes(process.argv[2]) ? process.argv[2] : 'invalid';
      p.state.operationFailures.push({ operation, code: 'operation_failed' });
      p.state.status = 'failed'; save(p.stateFile, p.state);
    } catch { /* Invalid or unavailable context cannot manufacture trusted state. */ }
    console.error('A1901 fixed validation operation failed; inspect the bounded receipt.'); process.exitCode = 1;
  }
}
