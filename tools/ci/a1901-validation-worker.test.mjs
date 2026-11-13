// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { chmodSync, mkdtempSync, mkdirSync, readFileSync, readdirSync, rmSync, statSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import test from 'node:test';
import * as worker from './a1901-validation-worker.mjs';
import { checkGradleDiagnostics } from './check-gradle-diagnostics.mjs';

const profileBytes = readFileSync(new URL('../../.github/a1901-validation/profile.json', import.meta.url));
const profile = JSON.parse(profileBytes.toString('utf8'));
const runtime = { platform: 'linux', arch: 'x64', node: '24.15.0' };
const GiB = 1024 ** 3;
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const environment = () => ({
  GITHUB_ACTIONS: 'true', RUNNER_ENVIRONMENT: 'github-hosted', GITHUB_REPOSITORY: 'albertocavalcante/gvy',
  GITHUB_EVENT_NAME: 'workflow_dispatch', GITHUB_REF: 'refs/heads/ci/a1901-validation-worker-v3',
  GITHUB_SHA: 'a'.repeat(40), GITHUB_RUN_ID: '43279999999', GITHUB_RUN_ATTEMPT: '1',
  RUNNER_TEMP: resolve(tmpdir(), 'gvy-a1901-context'),
});
const memory = (totalGiB = 14, availableGiB = 12) =>
  `MemTotal: ${totalGiB * 1024 ** 2} kB\nMemAvailable: ${availableGiB * 1024 ** 2} kB\n`;
const context = () => worker.validateContext(environment(), runtime);
const producer = 'a1901-lint';
const log = Buffer.from('> Configure project :\nBUILD SUCCESSFUL in 1s\n');

function summary() {
  const identity = context();
  return {
    ...checkGradleDiagnostics({ log: log.toString('utf8'), commandExitCode: 0, captureExitCode: 0, exceptions: [] }),
    producer,
    log: { bytes: log.length, sha256: hash(log) },
    worktreeDirty: true,
    commandSignal: null,
    context: { schemaVersion: 1, headSha: worker.SOURCE, testedSha: worker.SOURCE,
      runId: identity.runId, runAttempt: identity.attempt,
      invocationId: '12345678-1234-4234-8234-123456789abc', command: 'gradle' },
  };
}

test('context binds the hosted dispatch to the helper ref while retaining the distinct source identity', () => {
  assert.deepEqual(context(), {
    repository: 'albertocavalcante/gvy', helperSha: 'a'.repeat(40), runId: '43279999999',
    attempt: 1, sourceBase: worker.SOURCE, kind: 'precommit-source-plus-patch-proof',
  });
  for (const changed of [{ platform: 'darwin' }, { arch: 'arm64' }, { node: '26.0.0' }]) {
    assert.throws(() => worker.validateContext(environment(), { ...runtime, ...changed }));
  }
});

test('context rejects missing worker ref, reruns and malformed or foreign execution identities', () => {
  const changes = [
    { GITHUB_REF: undefined }, { GITHUB_REF: 'refs/heads/main' }, { GITHUB_RUN_ATTEMPT: '2' },
    { GITHUB_RUN_ATTEMPT: '01' }, { GITHUB_RUN_ID: '0' }, { GITHUB_RUN_ID: '01' },
    { GITHUB_RUN_ID: '9'.repeat(19) }, { GITHUB_SHA: 'A'.repeat(40) }, { GITHUB_SHA: 'a'.repeat(39) },
    { GITHUB_REPOSITORY: 'other/gvy' }, { GITHUB_EVENT_NAME: 'pull_request' },
    { RUNNER_ENVIRONMENT: 'self-hosted' }, { GITHUB_ACTIONS: 'false' }, { RUNNER_TEMP: 'relative/path' },
  ];
  for (const changed of changes) {
    assert.throws(() => worker.validateContext({ ...environment(), ...changed }, runtime), undefined, JSON.stringify(changed));
  }
});

test('the actual checked-in fixed profile passes without changing its bytes or object', () => {
  const input = structuredClone(profile);
  const before = structuredClone(input);
  assert.deepEqual(worker.validateProfile(input), before);
  assert.deepEqual(input, before);
});

test('profile rejects duplicate source/tool paths and path escape without widening its manifest', () => {
  const mutations = [
    value => value.sourceFiles.push(structuredClone(value.sourceFiles[0])),
    value => value.toolHashes.push(structuredClone(value.toolHashes[0])),
    value => value.toolHashes.push({ path: value.sourceFiles[0].path, sha256: value.sourceFiles[0].sha256 }),
    value => { value.sourceFiles[0].path = '../escape.py'; },
    value => { value.toolHashes[0].path = '/escape'; },
    value => { value.toolHashes[0].path = 'nested/../escape'; },
    value => { value.toolHashes[0].path = 'nested\\escape'; },
    value => { value.toolHashes[0].path = 'nested//escape'; },
  ];
  for (const mutate of mutations) {
    const changed = structuredClone(profile); mutate(changed);
    assert.throws(() => worker.validateProfile(changed));
  }
});

test('profile rejects missing worker ref and drift in source hashes, phases, capacity or declared scope', () => {
  const mutations = [
    value => { delete value.workerRef; }, value => { value.sourceBase = 'b'.repeat(40); },
    value => { value.sourceTree = 'bad'; }, value => { value.sourceFiles[0].sha256 = 'bad'; },
    value => { value.sourceFiles[0].gitBlob = 'bad'; }, value => { value.phases.reverse(); },
    value => { value.limits.maxWorkers = 3; }, value => { value.limits.fullDaemonHeapMiB = 4096; },
    value => { value.scope.sourcePublication = true; }, value => { value.scope.remoteCache = true; },
  ];
  for (const mutate of mutations) {
    const changed = structuredClone(profile); mutate(changed);
    assert.throws(() => worker.validateProfile(changed));
  }
});

test('capacity admits the exact floors and reports observed bytes', () => {
  assert.deepEqual(worker.parseCapacity(memory(), 12 * GiB), {
    memTotalBytes: 14 * GiB, memAvailableBytes: 12 * GiB, diskFreeBytes: 12 * GiB,
  });
  assert.equal(worker.parseCapacity(memory(16, 14), 20 * GiB).memAvailableBytes, 14 * GiB);
});

test('capacity rejects low available memory or disk and ambiguous proc rows', () => {
  for (const [text, free] of [
    [memory(13, 12), 12 * GiB], [memory(14, 11), 12 * GiB], [memory(), 12 * GiB - 1],
    [memory(), NaN], [memory() + 'MemTotal: 14680064 kB\n', 12 * GiB],
    ['MemTotal: 14680064 kB\n', 12 * GiB], [memory().replace(' kB', ' MB'), 12 * GiB],
    ['MemTotal: 9007199254740992 kB\nMemAvailable: 12582912 kB\n', 12 * GiB],
  ]) assert.throws(() => worker.parseCapacity(text, free));
});

test('explicit heap caps include the admitted 2 GiB boundary and normalize supported units', () => {
  assert.equal(worker.heapMiB(['java', '-Xmx2g', 'Main']), 2048);
  assert.equal(worker.heapMiB(['-Xmx256m']), 256);
  assert.equal(worker.heapMiB(['-Xmx262144K']), 256);
});

test('implicit, malformed, unsafe and oversized JVM heaps are rejected', () => {
  for (const argv of [[], ['-XX:MaxRAMPercentage=12.5'], ['-Xmx3g'], ['-Xmx2049m'],
    ['-Xmx0m'], ['-Xmx-1m'], ['-Xmx1.5g'], ['-Xmx2t'], ['-Xmx9007199254740992g']]) {
    assert.throws(() => worker.heapMiB(argv));
  }
});

test('safe relative manifest paths are returned unchanged', () => {
  assert.equal(typeof worker.safeRelative, 'function');
  for (const path of ['gradlew', '.reuse/check.py', 'tools/ci/example.test.mjs']) {
    assert.equal(worker.safeRelative(path), path);
  }
});

test('unsafe relative paths are rejected rather than normalized', () => {
  assert.equal(typeof worker.safeRelative, 'function');
  for (const path of ['', '/', '/absolute', 'C:/absolute', 'C:\\absolute', '.', '..', './file', 'dir/./file',
    '../file', 'dir/../file', 'dir//file', 'dir/', 'dir\\file', '-option', 'dir/-option',
    'dir/line\nfile', 'dir/tab\tfile', 'dir/\0file', 'dir/\x1bfile', 'dir/\x7ffile']) {
    assert.throws(() => worker.safeRelative(path), undefined, JSON.stringify(path));
  }
});

test('canonical complete success requires matching raw log and source/run context', () => {
  assert.equal(typeof worker.validateSummary, 'function');
  const input = summary();
  assert.equal(input.complete, true); assert.equal(input.ok, true);
  assert.deepEqual(input.errors, []); assert.deepEqual(input.unexpected, []);
  assert.doesNotThrow(() => worker.validateSummary(input, log, producer, context()));
});

test('summary rejects failed, missing or loosely typed success flags even if ok is asserted', () => {
  assert.equal(typeof worker.validateSummary, 'function');
  const mutations = [
    value => { value.schemaVersion = 2; }, value => { delete value.schemaVersion; },
    value => { value.complete = false; }, value => { value.complete = 1; },
    value => { delete value.complete; }, value => { value.commandExitCode = 1; },
    value => { value.commandExitCode = '0'; }, value => { delete value.commandExitCode; },
    value => { value.captureExitCode = 1; }, value => { value.captureExitCode = false; },
    value => { delete value.captureExitCode; }, value => { value.ok = false; },
    value => { value.ok = 'true'; }, value => { delete value.ok; },
  ];
  for (const mutate of mutations) {
    const changed = summary(); mutate(changed);
    assert.throws(() => worker.validateSummary(changed, log, producer, context()));
  }
});

test('summary cannot turn errors, unexpected diagnostics or malformed arrays into success', () => {
  assert.equal(typeof worker.validateSummary, 'function');
  for (const changed of [
    { errors: ['capture incomplete'] }, { unexpected: ['new warning'] },
    { errors: null }, { unexpected: {} }, { errors: '' }, { unexpected: '' },
    { errors: undefined }, { unexpected: undefined },
  ]) assert.throws(() => worker.validateSummary({ ...summary(), ...changed }, log, producer, context()));
});

test('summary identity rejects helper SHA substitution, a different run, attempt or producer', () => {
  assert.equal(typeof worker.validateSummary, 'function');
  for (const changed of [
    { headSha: context().helperSha }, { testedSha: context().helperSha },
    { runId: '43279999998' }, { runAttempt: 2 }, { runAttempt: '1' },
    { headSha: undefined }, { testedSha: undefined }, { runId: undefined }, { runAttempt: undefined },
  ]) {
    const input = summary(); Object.assign(input.context, changed);
    assert.throws(() => worker.validateSummary(input, log, producer, context()));
  }
  assert.throws(() => worker.validateSummary({ ...summary(), producer: 'other' }, log, producer, context()));
  assert.throws(() => worker.validateSummary(summary(), log, 'other', context()));
  assert.throws(() => worker.validateSummary(summary(), log, producer, { ...context(), attempt: 2 }));
  assert.throws(() => worker.validateSummary({ ...summary(), context: null }, log, producer, context()));
});

test('summary binds the original raw bytes rather than trusting success booleans', () => {
  assert.equal(typeof worker.validateSummary, 'function');
  const mutated = Buffer.from(log); mutated[0] ^= 1;
  for (const bytes of [mutated, log.subarray(1), Buffer.concat([log, Buffer.from([0])])]) {
    assert.throws(() => worker.validateSummary(summary(), bytes, producer, context()));
  }
});

test('summary rejects mutated, missing or loosely typed log length and hash metadata', () => {
  assert.equal(typeof worker.validateSummary, 'function');
  for (const changed of [
    { bytes: log.length + 1, sha256: hash(log) }, { bytes: String(log.length), sha256: hash(log) },
    { bytes: log.length, sha256: '0'.repeat(64) }, { bytes: log.length },
    { sha256: hash(log) }, null,
  ]) assert.throws(() => worker.validateSummary({ ...summary(), log: changed }, log, producer, context()));
});

function captureFixture(t) {
  assert.equal(typeof worker.capture, 'function');
  const root = mkdtempSync(join(tmpdir(), 'gvy-a1901-capture-test-'));
  mkdirSync(join(root, 'evidence'), { mode: 0o700 });
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const p = { root, target: root, stateFile: join(root, 'state.json'),
    context: { runId: '123', helperSha: 'a'.repeat(40) },
    state: { commands: [], streamBytes: 0, deadline: Date.now() + 15000 } };
  let childPid;
  const observations = {
    identityOf(pid) {
      childPid ??= pid;
      assert.equal(pid, childPid, 'the observer must never authorize another process');
      return { pid, start: 1, uid: process.getuid() };
    },
    check() {},
  };
  return { p, observations,
    raw: () => readFileSync(join(root, 'evidence/node-controls.log')),
    saved: () => JSON.parse(readFileSync(p.stateFile, 'utf8')) };
}

function captureNode(fixture, script, milliseconds = 4000, observations = {}) {
  return worker.capture(fixture.p, 'node-controls', process.execPath,
    ['--max-old-space-size=64', '--input-type=module', '-e', script],
    Date.now() + milliseconds, { ...fixture.observations, ...observations });
}

const nativeCapture = { concurrency: false, timeout: 10000 };

test('native capture retains exact stdout and stderr bytes and persists completed state', nativeCapture, async t => {
  const fixture = captureFixture(t);
  const stdout = Buffer.from([0x53, 0, 0x4f, 0xff]);
  const stderr = Buffer.from([0x45, 0x1b, 0x52]);
  const result = await captureNode(fixture,
    'process.stdout.write(Buffer.from([0x53,0,0x4f,0xff]),()=>process.stderr.write(Buffer.from([0x45,0x1b,0x52])));');
  const bytes = fixture.raw();
  assert.equal(bytes.length, stdout.length + stderr.length);
  assert.ok(bytes.includes(stdout)); assert.ok(bytes.includes(stderr));
  assert.equal(result.code, 0); assert.equal(result.signal, null); assert.equal(result.cause, null);
  assert.equal(result.bytes, bytes.length);
  assert.equal(fixture.p.state.streamBytes, bytes.length);
  assert.deepEqual(fixture.saved().commands, fixture.p.state.commands);
  assert.equal(fixture.saved().commands[0].phase, 'node-controls');
});

test('native capture preserves the original nonzero command status and preceding output', nativeCapture, async t => {
  const fixture = captureFixture(t);
  await assert.rejects(captureNode(fixture, 'process.stdout.write("before failure\\n",()=>process.exit(7));'),
    { message: 'command_exit' });
  const recorded = fixture.saved().commands[0];
  assert.equal(recorded.code, 7); assert.equal(recorded.signal, null); assert.equal(recorded.cause, null);
  assert.equal(fixture.raw().toString('utf8'), 'before failure\n');
});

test('native capture escalates a SIGTERM-trapping child to SIGKILL within the deadline bound', nativeCapture, async t => {
  const fixture = captureFixture(t);
  // The child also has an independent lifetime bound if a controller assertion fails.
  const script = 'import {writeFileSync} from "node:fs";' +
    'process.on("SIGTERM",()=>writeFileSync("term-trapped","yes",{flag:"wx"}));' +
    'process.stdout.write("READY\\n");setInterval(()=>{},100);setTimeout(()=>process.exit(91),6000);';
  const started = Date.now();
  await assert.rejects(captureNode(fixture, script, 1200), { message: 'phase_deadline' });
  const elapsed = Date.now() - started;
  const recorded = fixture.saved().commands[0];
  assert.equal(recorded.cause, 'phase_deadline'); assert.equal(recorded.code, null);
  assert.equal(recorded.signal, 'SIGKILL');
  assert.ok(elapsed < 4000, `deadline escalation took ${elapsed}ms`);
  assert.match(fixture.raw().toString('utf8'), /READY\n/);
  // Capture deliberately stops retaining output once a failure cause is set.
  assert.equal(readFileSync(join(fixture.p.root, 'term-trapped'), 'utf8'), 'yes');
});

test('native capture rejects stream overflow while keeping the raw log below its fixed byte limit',
  { ...nativeCapture, timeout: 15000 }, async t => {
  const fixture = captureFixture(t);
  const limit = profile.limits.phaseLogBytes;
  const script = `process.stdout.write(Buffer.alloc(${limit + 65536},0x78));` +
    'setInterval(()=>{},100);setTimeout(()=>process.exit(91),12000);';
  await assert.rejects(captureNode(fixture, script, 8000), { message: 'stream_limit' });
  const recorded = fixture.saved().commands[0];
  assert.equal(recorded.cause, 'stream_limit'); assert.ok(recorded.bytes > limit);
  assert.equal(fixture.p.state.streamBytes, recorded.bytes);
  const retained = statSync(join(fixture.p.root, 'evidence/node-controls.log')).size;
  assert.ok(retained > 0 && retained <= limit);
});

test('native capture records a failed resource monitor without replacing it with command success', nativeCapture, async t => {
  const fixture = captureFixture(t);
  let checks = 0;
  const script = 'process.stdout.write("READY\\n");setInterval(()=>{},100);setTimeout(()=>process.exit(91),6000);';
  await assert.rejects(captureNode(fixture, script, 5000, {
    check(p) { assert.equal(p, fixture.p); checks++; throw new Error('owned fixture capacity failure'); },
  }), { message: 'resource_limit' });
  assert.ok(checks > 0);
  assert.equal(fixture.saved().commands[0].cause, 'resource_limit');
  assert.notEqual(fixture.saved().commands[0].code, 0);
});

function publisherFixture(t, state = {}) {
  assert.equal(typeof worker.publishEvidence, 'function');
  const fixture = captureFixture(t);
  const p = fixture.p;
  p.profile = structuredClone(profile);
  p.publication = join(p.root, 'publication');
  mkdirSync(p.publication, { mode: 0o700 });
  Object.assign(p.state, { status: 'failed', phases: [], evidenceFiles: [], cleanup: { ok: true },
    profileSha256: hash(profileBytes), patchSha256: profile.patchSha256, ...state });
  return p;
}

test('publisher retains bounded good failure evidence and omits oversized and missing files', t => {
  const p = publisherFixture(t, {
    evidenceFiles: ['evidence/good.log', 'evidence/oversize.log', 'evidence/missing.log'],
  });
  const good = Buffer.from('bounded failure diagnostic\n');
  writeFileSync(join(p.root, 'evidence/good.log'), good, { flag: 'wx', mode: 0o600 });
  writeFileSync(join(p.root, 'evidence/oversize.log'), Buffer.alloc(profile.limits.phaseLogBytes + 1), { flag: 'wx' });
  writeFileSync(p.stateFile, 'PRIVATE_STATE_NOT_EVIDENCE', { flag: 'wx', mode: 0o600 });
  const receipt = worker.publishEvidence(p, 'b'.repeat(64));
  assert.equal(receipt.ok, false);
  assert.deepEqual(receipt.files, [{ path: 'evidence/good.log', bytes: good.length, sha256: hash(good) }]);
  assert.deepEqual(receipt.omitted.map(item => item.path).sort(), ['evidence/missing.log', 'evidence/oversize.log']);
  assert.deepEqual(readFileSync(join(p.publication, 'evidence/good.log')), good);
  const saved = JSON.parse(readFileSync(join(p.publication, 'receipt.json'), 'utf8'));
  assert.deepEqual(saved, receipt);
  assert.ok(!JSON.stringify(saved).includes('PRIVATE_STATE_NOT_EVIDENCE'));
  assert.deepEqual(readdirSync(p.publication).sort(), ['evidence', 'receipt.json']);
  assert.deepEqual(readdirSync(join(p.publication, 'evidence')), ['good.log']);
});

test('publisher cannot call a passed status with missing phases successful', t => {
  const p = publisherFixture(t, { status: 'passed', phases: [], evidenceFiles: ['evidence/good.log'] });
  writeFileSync(join(p.root, 'evidence/good.log'), 'good\n', { flag: 'wx' });
  const receipt = worker.publishEvidence(p, 'b'.repeat(64));
  assert.equal(receipt.ok, false);
  assert.deepEqual(receipt.omitted, []);
  assert.equal(receipt.files.length, 1);
  assert.equal(JSON.parse(readFileSync(join(p.publication, 'receipt.json'), 'utf8')).ok, false);
});

test('publisher omits outside symlinks and unsafe allowlist entries even when all phases passed', t => {
  const p = publisherFixture(t, { status: 'passed', phases: worker.PHASES.map(name => ({ name, ok: true })),
    evidenceFiles: ['evidence/good.log', 'evidence/escaped.log', '../unsafe.log'] });
  writeFileSync(join(p.root, 'evidence/good.log'), 'good\n', { flag: 'wx' });
  const outside = p.root + '-outside';
  writeFileSync(outside, 'PRIVATE_OUTSIDE_NOT_EVIDENCE', { flag: 'wx', mode: 0o600 });
  t.after(() => rmSync(outside, { force: true }));
  symlinkSync(outside, join(p.root, 'evidence/escaped.log'));
  const receipt = worker.publishEvidence(p, 'b'.repeat(64));
  assert.equal(receipt.ok, false);
  assert.deepEqual(receipt.files.map(item => item.path), ['evidence/good.log']);
  assert.equal(receipt.omitted.length, 2);
  assert.ok(receipt.omitted.some(item => item.path === 'evidence/escaped.log'));
  assert.ok(receipt.omitted.some(item => Number.isInteger(item.ordinal) && !Object.hasOwn(item, 'path')));
  assert.ok(!JSON.stringify(receipt).includes('PRIVATE_OUTSIDE_NOT_EVIDENCE'));
  assert.deepEqual(readdirSync(join(p.publication, 'evidence')), ['good.log']);
});

const probeWitness = [
  '> Task :licenseCheck',
  'REUSE and first-party header checks passed',
  '> Task :verifyKotlinFormattingTargets',
  'Verified Kotlin formatter source and generated-output targets for 42 layouts',
];

test('probe witness requires actual licensing success before the formatter probe', () => {
  assert.equal(typeof worker.verifyProbeLog, 'function');
  for (const newline of ['\n', '\r\n']) {
    const text = ['> Configure project :', ...probeWitness, 'BUILD SUCCESSFUL in 1s', ''].join(newline);
    assert.doesNotThrow(() => worker.verifyProbeLog(text));
  }
});

test('probe witness rejects missing, reordered, duplicate or substring markers and zero layouts', () => {
  assert.equal(typeof worker.verifyProbeLog, 'function');
  const valid = probeWitness.join('\n') + '\n';
  const invalid = [
    probeWitness.slice(1).join('\n'),
    [probeWitness[0], ...probeWitness.slice(2)].join('\n'),
    [probeWitness[1], probeWitness[0], ...probeWitness.slice(2)].join('\n'),
    [probeWitness[0], probeWitness[2], probeWitness[1], probeWitness[3]].join('\n'),
    valid.replace(probeWitness[0], 'not a task ' + probeWitness[0]),
    valid.replace('42 layouts', '0 layouts'),
    ...probeWitness.map(marker => valid + marker + '\n'),
  ];
  for (const text of invalid) assert.throws(() => worker.verifyProbeLog(text));
});

test('the actual canonical wrapper receives one console and warning policy from the helper call path', t => {
  const root = mkdtempSync(join(tmpdir(), 'gvy-a1901-gradle-args-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  writeFileSync(join(root, 'gradlew'), `#!${process.execPath}
import {writeFileSync} from 'node:fs';
const args = process.argv.slice(2);
writeFileSync('received-args.json', JSON.stringify(args));
if (args.filter(x=>x.startsWith('--console')).length !== 1 ||
    args.filter(x=>x.startsWith('--warning-mode')).length !== 1) process.exit(17);
console.log('BUILD SUCCESSFUL in 1s');
`, { mode: 0o700 });
  writeFileSync(join(root, 'git'), `#!${process.execPath}
if (process.argv[2] === 'rev-parse') console.log('${worker.SOURCE}');
else if (process.argv[2] === 'status') console.log('');
else process.exit(19);
`, { mode: 0o700 });
  chmodSync(join(root, 'gradlew'), 0o700); chmodSync(join(root, 'git'), 0o700);
  const env = { PATH: root, NODE_OPTIONS: '--max-old-space-size=64' };
  const [command, args] = worker.gradleCommand({ root }, 'root-format-apply', [':spotlessKotlinGradleApply']);
  args[0] = new URL('./run-gradle.mjs', import.meta.url).pathname;
  const result = spawnSync(command, args, { cwd: root, env, timeout: 10000, maxBuffer: 1024 * 1024, encoding: 'utf8' });
  assert.ifError(result.error); assert.equal(result.signal, null);
  assert.equal(result.status, 0, result.stdout + result.stderr);
  const actual = JSON.parse(readFileSync(join(root, 'received-args.json'), 'utf8'));
  assert.deepEqual(actual.filter(x => x.startsWith('--console')), ['--console=plain']);
  assert.deepEqual(actual.filter(x => x.startsWith('--warning-mode')), ['--warning-mode=all']);
  assert.ok(actual.includes('--max-workers=2'));
  assert.ok(actual.some(x => x.includes('-Xmx2048m')));
});

test('helper options cannot override the canonical console, warnings or deprecation policy', () => {
  for (const option of ['--console=plain', '--console', '--warning-mode=none', '--warning-mode',
    '-Dorg.gradle.deprecation.trace=false']) {
    assert.throws(() => worker.gradleCommand({ root: '/owned' }, 'lint', ['lint'], [option]));
    assert.throws(() => worker.gradleCommand({ root: '/owned' }, 'lint', [option]));
  }
});

function processes() {
  const uid = 42, rows = new Map(), reads = [], signals = [];
  const add = (pid, start, owned = false, extra = {}) => rows.set(pid,
    { pid, start, uid, state: 'S', owned, ...extra });
  const p = { context: { runId: '123', helperSha: 'a'.repeat(40) }, state: { processBaseline: [] } };
  const io = {
    uid, list: () => [...rows.keys()],
    identityOf(pid) {
      const row = rows.get(pid); if (!row) throw Object.assign(new Error('PRIVATE stat path'), { code: 'ENOENT' });
      return { pid: row.pid, start: row.start, uid: row.uid, state: row.state };
    },
    environmentOf(pid) {
      reads.push(pid); const row = rows.get(pid);
      if (row.error) throw Object.assign(new Error('PRIVATE environ SECRET=value'), { code: row.error });
      return Buffer.from(row.owned ? 'GVY_A1901_OWNER=123-' + 'a'.repeat(40) + '\0' : 'FOREIGN_SECRET=value\0');
    },
    signal(pid, kind) { signals.push({ pid, kind }); rows.delete(pid); },
    wait: async () => {},
  };
  return { uid, rows, reads, signals, add, p, io };
}

test('baseline snapshots same-uid identities without reading any process environment', () => {
  const f = processes(); f.add(101, 7, false, { error: 'EACCES', state: 't' }); f.add(102, 8, false, { uid: 99 });
  assert.deepEqual(worker.snapshotProcesses(f.io), [{ pid: 101, start: 7, uid: f.uid }]);
  assert.deepEqual(f.reads, []);
});

test('protected baseline processes are skipped only by the exact pid, start and uid tuple', () => {
  const f = processes(); f.add(101, 7, false, { error: 'EACCES' });
  f.p.state.processBaseline = [{ pid: 101, start: 7, uid: f.uid }];
  assert.deepEqual(worker.ownedProcesses(f.p, f.io), []); assert.deepEqual(f.reads, []);
  f.rows.get(101).start = 8; f.rows.get(101).error = undefined; f.rows.get(101).owned = true;
  assert.deepEqual(worker.ownedProcesses(f.p, f.io), [{ pid: 101, start: 8, uid: f.uid }]);
  assert.deepEqual(f.reads, [101]);
  f.p.state.processBaseline[0].uid = 99;
  assert.throws(() => worker.ownedProcesses(f.p, f.io));
});

test('new owned and foreign processes require marker reads while unreadable new candidates fail closed', () => {
  const f = processes(); f.add(101, 1, true); f.add(102, 2, false); f.add(103, 3, false, { uid: 99 });
  assert.deepEqual(worker.ownedProcesses(f.p, f.io), [{ pid: 101, start: 1, uid: f.uid }]);
  assert.deepEqual(f.reads, [101, 102]);
  f.add(104, 4, false, { error: 'EACCES' });
  assert.throws(() => worker.ownedProcesses(f.p, f.io), error => {
    assert.deepEqual(worker.safeFailure(error), { reason: 'process_scan_failed', errno: 'EACCES', readKind: 'process_environ', pid: 104 });
    assert.ok(!JSON.stringify(worker.safeFailure(error)).includes('PRIVATE')); return true;
  });
  assert.deepEqual(f.signals, []);
});

test('gone and zombie races do not read environments and changed identities cannot authorize a signal', () => {
  const f = processes(); f.add(101, 1, true, { state: 'Z' }); f.add(102, 2, true);
  const original = f.io.identityOf;
  f.io.identityOf = pid => { if (pid === 102) { f.rows.delete(pid); throw Object.assign(new Error('gone'), { code: 'ESRCH' }); } return original(pid); };
  assert.deepEqual(worker.ownedProcesses(f.p, f.io), []); assert.deepEqual(f.reads, []);
  f.add(103, 3, true); let visits = 0;
  f.io.identityOf = pid => { const row = original(pid); if (pid === 103 && ++visits > 1) row.start++; return row; };
  assert.throws(() => worker.ownedProcesses(f.p, f.io), error => worker.safeFailure(error).reason === 'process_identity_changed');
});

test('process scans retain process-count and environment-byte bounds', () => {
  const f = processes(); f.io.list = () => Array.from({ length: 4097 }, (_, i) => i + 1);
  assert.throws(() => worker.snapshotProcesses(f.io));
  f.io.list = () => Array.from({ length: 33 }, (_, i) => i + 1);
  f.io.identityOf = pid => ({ pid, uid: f.uid, start: 1, state: 'S' });
  f.io.environmentOf = () => Buffer.alloc(128 * 1024);
  assert.throws(() => worker.ownedProcesses(f.p, f.io));
});

test('cleanup rechecks owner and identity before signalling and never signals baseline or foreign processes', async () => {
  const f = processes(); f.add(101, 1, false, { error: 'EACCES' }); f.add(102, 2, false); f.add(103, 3, true);
  f.p.state.processBaseline = [{ pid: 101, start: 1, uid: f.uid }];
  const read = f.io.environmentOf;
  f.io.environmentOf = pid => { const bytes = read(pid); if (pid === 103) f.rows.get(pid).owned = false; return bytes; };
  await assert.rejects(worker.cleanupOwned(f.p, f.io));
  assert.deepEqual(f.signals, []); assert.equal(f.p.state.cleanup.ok, false);
  assert.equal(f.p.state.cleanup.diagnostic.reason, 'signal_refused');
  assert.equal(f.p.state.teardownUncertain, true);
  assert.ok(!f.reads.includes(101));
  const next = processes(); next.add(101, 1, false, { error: 'EACCES' }); next.add(102, 2, false); next.add(103, 3, true);
  next.p.state.processBaseline = [{ pid: 101, start: 1, uid: next.uid }];
  await worker.cleanupOwned(next.p, next.io);
  assert.deepEqual(next.signals, [{ pid: 103, kind: 'SIGTERM' }]);
  assert.ok(!next.reads.includes(101));
});

test('cleanup failures retain safe errno and do not replace an initiating validation failure', async () => {
  const f = processes(); f.add(104, 4, false, { error: 'EACCES' });
  f.p.state.failure = { phase: 'lint', code: 'validation_failed', diagnostic: { reason: 'command_exit', errno: null, readKind: 'none' } };
  const first = structuredClone(f.p.state.failure);
  await assert.rejects(worker.cleanupOwned(f.p, f.io));
  assert.deepEqual(f.p.state.failure, first);
  assert.equal(f.p.state.cleanup.ok, false);
  assert.deepEqual(f.p.state.cleanup.diagnostic, { reason: 'process_scan_failed', errno: 'EACCES', readKind: 'process_environ', pid: 104 });
  assert.ok(!JSON.stringify(f.p.state.cleanup).includes('SECRET'));
});

test('safe failures expose only fixed reason, native errno, read kind and an optional pid', () => {
  const error = Object.assign(new Error('PRIVATE /path SECRET=value argv stderr stack'), { code: 'EACCES',
    diagnostic: { reason: 'PRIVATE', errno: 'SECRET', readKind: '/path', pid: 'argv', extra: 'SECRET' } });
  assert.deepEqual(worker.safeFailure(error), { reason: 'operation_failed', errno: 'EACCES', readKind: 'none' });
});

test('native resource failure retains the first safe errno through a later teardown timeout', nativeCapture, async t => {
  const fixture = captureFixture(t), started = Date.now();
  // This owned child exits independently even when the synthetic identity
  // observer denies both signal attempts. No other process can be signalled.
  const script = 'process.stdout.write("READY\\n");setTimeout(()=>process.exit(91),5000);';
  let first = true;
  await assert.rejects(captureNode(fixture, script, 7000, {
    identityOf(pid) {
      if (first) { first = false; return { pid, start: 1, uid: process.getuid() }; }
      throw Object.assign(new Error('PRIVATE process stat /path'), { code: 'EPERM' });
    },
    check() { throw Object.assign(new Error('PRIVATE resource path SECRET=value'), { code: 'EACCES' }); },
  }), { message: 'resource_limit' });
  const recorded = fixture.saved().commands[0];
  assert.equal(recorded.cause, 'resource_limit'); assert.equal(recorded.signal, 'hard_bound');
  assert.deepEqual(recorded.diagnostic, { reason: 'resource_limit', errno: 'EACCES', readKind: 'resource_monitor' });
  assert.ok(recorded.secondaryDiagnostics.some(value => value.reason === 'teardown_timeout'));
  assert.ok(recorded.secondaryDiagnostics.some(value => value.errno === 'EPERM'));
  assert.ok(!JSON.stringify([recorded.diagnostic, recorded.secondaryDiagnostics]).match(/PRIVATE|SECRET|\/path/));
  // Wait for this test's independently bounded child, not a foreign process.
  await new Promise(done => setTimeout(done, Math.max(0, started + 5500 - Date.now())));
});

test('cleanup deadline is bounded and retains an earlier primary failure', async () => {
  const f = processes(); let now = 0;
  f.p.state.failure = { phase: 'lint', code: 'validation_failed' };
  f.io.now = () => now; f.io.wait = async () => { now += 11000; };
  await assert.rejects(worker.cleanupOwned(f.p, f.io));
  assert.equal(f.p.state.cleanup.diagnostic.reason, 'teardown_timeout');
  assert.deepEqual(f.p.state.failure, { phase: 'lint', code: 'validation_failed' });
  assert.deepEqual(f.signals, []);
});

test('new-process stat failures retain pid and safe read kind without reading the environment', () => {
  const f = processes(); f.io.list = () => [105];
  f.io.identityOf = () => { throw Object.assign(new Error('PRIVATE /proc path'), { code: 'EPERM' }); };
  assert.throws(() => worker.ownedProcesses(f.p, f.io), error => {
    assert.deepEqual(worker.safeFailure(error), { reason: 'process_scan_failed', errno: 'EPERM', readKind: 'process_stat', pid: 105 });
    return true;
  });
  assert.deepEqual(f.reads, []); assert.deepEqual(f.signals, []);
});

test('exact serialized-state limits round-trip and preserve the prior file on overflow', t => {
  const root = mkdtempSync(join(tmpdir(), 'gvy-a1901-state-budget-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const file = join(root, 'state.json'), maximum = 256 * 1024, headroom = 64 * 1024;
  const overhead = worker.serializeState({ value: '' }).length;
  const exact = { value: 'x'.repeat(maximum - overhead) };
  const bytes = worker.serializeState(exact);
  assert.equal(bytes.length, maximum); assert.deepEqual(JSON.parse(bytes.toString('utf8')), exact);
  worker.saveState(file, exact); assert.deepEqual(readFileSync(file), bytes);
  assert.throws(() => worker.saveState(file, { value: exact.value + 'x' }), error => {
    assert.deepEqual(worker.safeFailure(error), { reason: 'state_limit', errno: null, readKind: 'capture_state' }); return true;
  });
  assert.deepEqual(readFileSync(file), bytes); assert.equal(readdirSync(root).length, 1);
  const reserved = { value: 'x'.repeat(maximum - headroom - overhead) };
  assert.equal(worker.serializeState(reserved, headroom).length, maximum - headroom);
  assert.throws(() => worker.serializeState({ value: reserved.value + 'x' }, headroom));
  const baseline = Array.from({ length: 4096 }, (_, index) => ({ pid: index + 1, start: Number.MAX_SAFE_INTEGER, uid: 42 }));
  assert.throws(() => worker.serializeState({ processBaseline: baseline }, headroom));
  assert.deepEqual(readFileSync(file), bytes);
});

test('a baseline process that execs Java is rejected without reading its environment', () => {
  const f = processes(); f.add(106, 6, false, { error: 'EACCES' });
  f.p.state.processBaseline = [{ pid: 106, start: 6, uid: f.uid }];
  f.io.javaProcesses = () => [{ ...f.rows.get(106), argv: ['java', '-Xmx256m'] }];
  assert.throws(() => worker.inspectJvms(f.p, f.io), /unrelated JVM/);
  assert.deepEqual(f.reads, []);
  f.p.state.processBaseline = []; f.rows.get(106).uid = 99;
  assert.throws(() => worker.inspectJvms(f.p, f.io), /unrelated JVM/);
  assert.deepEqual(f.reads, []);
});

test('signal identities require complete equal pid, start and uid values before a numeric signal', () => {
  const expected = { pid: 123, start: 7, uid: 42 };
  assert.deepEqual(worker.validateSignalIdentity(expected, { ...expected }), expected);
  for (const current of [undefined, {}, { ...expected, pid: 124 }, { ...expected, start: 8 },
    { ...expected, uid: 43 }, { pid: 123, start: 7 }, { ...expected, start: NaN }]) {
    assert.throws(() => worker.validateSignalIdentity(expected, current), error => {
      assert.deepEqual(worker.safeFailure(error), { reason: 'signal_refused', errno: null, readKind: 'signal', pid: 123 });
      return true;
    });
  }
  assert.throws(() => worker.validateSignalIdentity({ pid: 123, start: 7 }, expected));
});

test('cleanup refuses a reused live pid after enumeration without signalling its replacement', async () => {
  const f = processes(); f.add(107, 7, true); let scans = 0;
  f.io.list = () => {
    if (++scans === 2) { f.rows.get(107).start = 8; f.rows.get(107).owned = false; }
    return [107];
  };
  await assert.rejects(worker.cleanupOwned(f.p, f.io));
  assert.deepEqual(f.signals, []); assert.equal(f.p.state.cleanup.diagnostic.reason, 'signal_refused');
  assert.equal(f.p.state.teardownUncertain, true);
});

test('cleanup and publication describe admitted numeric signalling without asserting atomic target safety', async t => {
  const expected = { scope: 'temporary-helper-controller',
    capture: 'leader-pid-start-uid-rechecked-numeric-group',
    cleanup: 'owner-marker-pid-start-uid-rechecked-numeric-pid',
    nativeTimeouts: 'node-managed-helper-started-fixed-command-child',
    atomicTargetBinding: false, pidReuseRacePossible: true };
  const f = processes(); await worker.cleanupOwned(f.p, f.io);
  assert.deepEqual(f.p.state.cleanup.signallingPolicy, expected);
  assert.equal(Object.hasOwn(f.p.state.cleanup, 'foreignProcessesSignalled'), false);
  const p = publisherFixture(t), receipt = worker.publishEvidence(p, 'b'.repeat(64));
  assert.deepEqual(receipt.signallingPolicy, expected); assert.equal(receipt.ok, false);
});

test('startup identity failure refuses a direct kill and preserves the first safe error', nativeCapture, async t => {
  const fixture = captureFixture(t);
  const script = 'import {writeFileSync} from "node:fs";' +
    'setTimeout(()=>{writeFileSync("survived-identity-refusal","yes");process.exit(0)},200);';
  await assert.rejects(captureNode(fixture, script, 4000, {
    identityOf() { throw Object.assign(new Error('PRIVATE proc path SECRET=value'), { code: 'EACCES' }); },
  }), { message: 'start_failed' });
  const recorded = fixture.saved().commands[0];
  assert.equal(recorded.cause, 'start_failed'); assert.equal(recorded.diagnostic.errno, 'EACCES');
  assert.equal(recorded.diagnostic.readKind, 'process_stat'); assert.equal(fixture.p.state.teardownUncertain, true);
  assert.ok(recorded.secondaryDiagnostics.some(value => value.reason === 'signal_refused'));
  assert.equal(readFileSync(join(fixture.p.root, 'survived-identity-refusal'), 'utf8'), 'yes');
  assert.ok(!JSON.stringify(recorded.diagnostic).match(/PRIVATE|SECRET|\/path/));
});

test('final and bookkeeping save failures remain secondary to the initiating safe diagnostic', t => {
  const root = mkdtempSync(join(tmpdir(), 'gvy-a1901-first-state-failure-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const stateFile = join(root, 'state.json'), baseline = { status: 'running', processBaseline: [{ pid: 123, start: 7, uid: 42 }] };
  worker.saveState(stateFile, baseline); const before = readFileSync(stateFile);
  const primary = { reason: 'command_exit', errno: null, readKind: 'none' }, reports = [];
  const p = { stateFile, state: { ...baseline, failure: { phase: 'lint', diagnostic: primary }, value: 'x'.repeat(256 * 1024) } };
  const original = structuredClone(primary);
  const secondary = worker.saveExecutionState(p, primary, { report: value => reports.push(value) });
  assert.deepEqual(secondary, { reason: 'state_limit', errno: null, readKind: 'capture_state' });
  assert.deepEqual(readFileSync(stateFile), before); assert.deepEqual(primary, original);
  worker.saveExecutionState(p, primary, {
    save() { throw Object.assign(new Error('PRIVATE bookkeeping /path SECRET=value'), { code: 'EIO' }); },
    report: value => reports.push(value),
  });
  assert.deepEqual(reports, [secondary, { reason: 'capture_state_failed', errno: 'EIO', readKind: 'capture_state' }]);
  assert.deepEqual(p.state.failure.diagnostic, original);
  assert.ok(!JSON.stringify(reports).match(/PRIVATE|SECRET|\/path/));
  assert.deepEqual(JSON.parse(readFileSync(stateFile, 'utf8')), baseline);
  assert.throws(() => worker.saveExecutionState(p, null), error => worker.safeFailure(error).reason === 'state_limit');
  worker.saveExecutionState(p, primary, {
    save() { throw Object.assign(new Error('private write'), { code: 'EIO' }); },
    report() { throw new Error('private stderr failure'); },
  });
  assert.equal(p.state.diagnosticOutputUncertain, true); assert.deepEqual(primary, original);
});

test('a native final-save failure without an earlier error remains the primary safe failure', () => {
  assert.throws(() => worker.saveExecutionState({ stateFile: '/unused', state: {} }, null, {
    save() { throw Object.assign(new Error('PRIVATE /path SECRET=value'), { code: 'ENOSPC' }); },
  }), error => {
    assert.deepEqual(worker.safeFailure(error), { reason: 'capture_state_failed', errno: 'ENOSPC', readKind: 'capture_state' });
    return true;
  });
});
