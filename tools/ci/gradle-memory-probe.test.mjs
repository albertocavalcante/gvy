// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync, rmSync, chmodSync, symlinkSync, statSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import childProcess, { spawn, spawnSync } from 'node:child_process';
import { syncBuiltinESMExports } from 'node:module';
import test from 'node:test';
import { identifyOwnedGradleDaemon, parseProcStat, parseGcSample, parseClassSample, startGradleMemoryProbe } from './gradle-memory-probe.mjs';

const marker = '-XX:ErrorFile=/private/owned/gvy-memory-' + 'c'.repeat(64) + '-hs_err_pid%p.log';
const main = 'org.gradle.launcher.daemon.bootstrap.GradleDaemon';
const identity = { headSha: 'a'.repeat(40), testedSha: 'b'.repeat(40), runId: '123', runAttempt: 1, producer: 'unit', invocationId: '12345678-1234-4234-8234-123456789abc' };
const gc = 'Timestamp S0C S1C S0U S1U EC EU OC OU MC MU CCSC CCSU YGC YGCT FGC FGCT CGC CGCT GCT\n' +
  '12.5 0 1024 0 512 4096 2048 8192 3072 65536 60000 8192 7000 2 0.1 1 0.2 3 0.3 0.6\n';
const classes = 'Timestamp Loaded Bytes Unloaded Bytes Time\n12.6 100 200.5 4 8.5 0.25\n';
const uid = process.getuid?.() ?? 1000;

function stat(pid = 42, ticks = 1234, comm = 'java (worker) name') {
  return `${pid} (${comm}) ${['S', ...Array(18).fill('0'), String(ticks), '0'].join(' ')}\n`;
}
function record(extra = {}) {
  return { pid: 42, stat: stat(), status: `Name:\tjava\nUid:\t${uid}\t${uid}\t${uid}\t${uid}\n`, cmdline: Buffer.from(['/jdk/bin/java', marker, '-cp', '/private/CANARY/classpath.jar', main, '9.8.2'].join('\0') + '\0'), ...extra };
}
function deferred() { let resolve; const promise = new Promise(value => { resolve = value; }); return { promise, resolve }; }
function sleep(ms, signal) {
  return new Promise(resolve => {
    if (signal.aborted) return resolve();
    const finish = () => { clearTimeout(timer); signal.removeEventListener('abort', finish); resolve(); };
    const timer = setTimeout(finish, ms);
    signal.addEventListener('abort', finish, { once: true });
  });
}
function fixture(t, entries = [record()], autoCleanup = true) {
  const dir = mkdtempSync(join(tmpdir(), 'gvy-memory-probe-'));
  if (autoCleanup) t.after(() => rmSync(dir, { recursive: true, force: true }));
  const procRoot = join(dir, 'proc'); mkdirSync(procRoot);
  const put = value => {
    const path = join(procRoot, String(value.pid)); mkdirSync(path, { recursive: true });
    for (const key of ['stat', 'status', 'cmdline']) writeFileSync(join(path, key), value[key]);
  };
  entries.forEach(put);
  const reachedInterval = deferred();
  const calls = [];
  const options = { ownerArgument: marker, identity, reportPath: join(dir, 'memory.json'), intervalMs: 5000, maxDurationMs: 1800000 };
  const io = {
    platform: 'linux', procRoot, uid,
    runTool: async (args, signal) => { calls.push(args); return { kind: 'ok', stdout: Buffer.from(args.includes('-gc') ? gc : classes) }; },
    sleep: (ms, signal) => { if (ms >= 1000) reachedInterval.resolve(); return sleep(ms, signal); },
  };
  return { dir, procRoot, put, calls, options, io, reachedInterval };
}
const settled = () => new Promise(resolve => setImmediate(resolve));

test('proc identity requires the immutable argument, actual Gradle main class, UID and valid start ticks', () => {
  assert.deepEqual(parseProcStat(stat(), 42), { pid: 42, startTicks: 1234 });
  assert.deepEqual(identifyOwnedGradleDaemon(record(), { ownerArgument: marker, uid }), { pid: 42, startTicks: 1234 });
  const argv = values => Buffer.from(values.join('\0') + '\0');
  for (const changed of [
    record({ cmdline: argv(['/jdk/bin/java', '-Dgvy.ci.memory.owner=' + 'c'.repeat(64), main]) }),
    record({ cmdline: argv(['/jdk/bin/java', marker, '-cp', main, 'ForeignMain']) }),
    record({ cmdline: argv(['/jdk/bin/java', main, marker]) }),
    record({ cmdline: argv(['/jdk/bin/java', marker, marker, main]) }),
    record({ cmdline: argv(['/jdk/bin/java', marker, '-XX:ErrorFile=/private/other', main]) }),
    record({ cmdline: argv(['/jdk/bin/java', marker, '-XX:+ErrorFileToStdout', main]) }),
    record({ cmdline: argv(['/jdk/bin/java', marker, '-XX:+ErrorFileToStderr', main]) }),
    record({ status: `Name:\tjava\nUid:\t${uid + 1}\t${uid + 1}\t${uid + 1}\t${uid + 1}\n` }),
  ]) assert.equal(identifyOwnedGradleDaemon(changed, { ownerArgument: marker, uid }), null);
  assert.throws(() => parseProcStat(stat(43), 42), /Invalid process/);
  assert.throws(() => parseProcStat(stat(42, '9007199254740992'), 42), /Invalid process/);
});

test('jstat numeric parsers preserve heap/metaspace/GC and disambiguate both class Bytes columns', () => {
  const parsed = parseGcSample(gc);
  assert.equal(parsed.heapUsedKiB, 5632);
  assert.equal(parsed.heapCommittedKiB, 13312);
  assert.equal(parsed.metaspaceUsedKiB, 60000);
  assert.equal(parsed.compressedClassUsedKiB, 7000);
  assert.equal(parsed.concurrentGcCount, 3);
  assert.equal(parsed.totalGcTimeSeconds, 0.6);
  assert.deepEqual(parseClassSample(classes), { vmUptimeSeconds: 12.6, loadedClasses: 100, loadedClassBytesKiB: 200.5, unloadedClasses: 4, unloadedClassBytesKiB: 8.5, classLoadTimeSeconds: 0.25 });
  const unknown = parseGcSample(gc.replace('3 0.3 0.6', '- - 0.6'));
  assert.equal(unknown.concurrentGcCount, null);
  assert.equal(unknown.concurrentGcTimeSeconds, null);
  const zeroHeap = parseGcSample(gc.replace('12.5 0 1024 0 512 4096 2048 8192 3072', '12.5 0 1024 0 0 4096 0 8192 0'));
  assert.equal(zeroHeap.heapUsedKiB, 0, 'an observed zero is valid data, unlike an unavailable measurement');
});

test('malformed, duplicate, missing, non-finite or unsafe numeric output is rejected without echoing input', () => {
  for (const text of [gc.replace(' MU ', ' MC '), gc.replace(' MU ', ' CANARY '), gc.replace('60000', 'NaN'), gc.replace('60000', 'Infinity'), gc.replace('2 0.1', '9007199254740992 0.1'), gc + 'CANARY\n']) {
    assert.throws(() => parseGcSample(text), error => error.message === 'Invalid jstat output');
  }
  for (const text of [classes.replace('Unloaded', 'Loaded'), classes.replace('100', '-'), classes.replace('200.5', 'CANARY')]) {
    assert.throws(() => parseClassSample(text), error => error.message === 'Invalid jstat output');
  }
});

test('a complete owned pair yields bounded numeric-only samples and observed maxima with exact invocation identity', async t => {
  const f = fixture(t);
  const probe = startGradleMemoryProbe(f.options, f.io);
  await f.reachedInterval.promise;
  const result = await probe.stop();
  assert.equal(result.complete, true);
  assert.equal(result.status, 'complete');
  assert.deepEqual(result.identity, identity);
  assert.equal(result.samples.length, 1);
  assert.equal(result.sampledMax.metaspaceUsedKiB, 60000);
  assert.equal(result.discovery.daemon.pid, 42);
  assert.equal(result.discovery.daemon.startTicks, 1234);
  assert.ok(result.samples.every(row => row.every(value => value === null || Number.isFinite(value))));
  assert.ok(f.calls.every(args => args[0] === '-J-Xmx64m'));
  assert.deepEqual(await probe.stop(), result);
  const artifact = readFileSync(f.options.reportPath, 'utf8');
  assert.deepEqual(JSON.parse(artifact), result);
  assert.ok(Buffer.byteLength(artifact) <= 256 * 1024);
  for (const secret of ['CANARY', marker, '/private/', 'classpath', 'ErrorFile']) assert.ok(!artifact.includes(secret));
});

test('stop during missing discovery is immediate, explicit and never fabricates zeros', async t => {
  const f = fixture(t, []);
  const before = performance.now();
  const result = await startGradleMemoryProbe(f.options, f.io).stop();
  assert.ok(performance.now() - before < 1000);
  assert.equal(result.complete, false);
  assert.equal(result.status, 'incomplete');
  assert.ok(result.reasonCodes.includes('no_owned_daemon'));
  assert.equal(result.discovery.daemon, null);
  assert.equal(result.sampledMax.metaspaceUsedKiB, null);
  assert.deepEqual(result.samples, []);
  assert.equal(f.calls.length, 0);
});

test('ambiguous owned daemons are not guessed and unsupported platforms do not probe', async t => {
  const f = fixture(t, [record(), record({ pid: 43, stat: stat(43, 1235) })]);
  const ambiguous = await startGradleMemoryProbe(f.options, f.io).stop();
  assert.equal(ambiguous.complete, false);
  assert.ok(ambiguous.reasonCodes.includes('ambiguous_daemons'));
  assert.equal(f.calls.length, 0);
  const other = fixture(t);
  const unsupported = await startGradleMemoryProbe(other.options, { ...other.io, platform: 'darwin' }).stop();
  assert.equal(unsupported.status, 'unsupported');
  assert.equal(unsupported.complete, false);
  assert.equal(other.calls.length, 0);
});

test('PID reuse between gc and class sampling rejects the partial pair', async t => {
  const f = fixture(t);
  f.io.runTool = async args => {
    f.calls.push(args);
    if (args.includes('-gc')) f.put(record({ stat: stat(42, 9999) }));
    return { kind: 'ok', stdout: Buffer.from(args.includes('-gc') ? gc : classes) };
  };
  const probe = startGradleMemoryProbe(f.options, f.io);
  await new Promise(resolve => setImmediate(resolve));
  const result = await probe.stop();
  assert.equal(result.complete, false);
  assert.ok(result.reasonCodes.includes('pid_reused'));
  assert.deepEqual(result.samples, []);
  assert.equal(f.calls.length, 1);
});

test('invalid identity or owner argument fails before any artifact write', t => {
  const f = fixture(t);
  for (const patch of [{ identity: { ...identity, environment: 'CANARY' } }, { identity: { ...identity, headSha: 'CANARY' } }, { ownerArgument: '-Dgvy.ci.memory.owner=CANARY' }]) {
    assert.throws(() => startGradleMemoryProbe({ ...f.options, ...patch }, f.io), /Invalid memory probe/);
  }
  assert.equal(f.calls.length, 0);
  assert.throws(() => readFileSync(f.options.reportPath), { code: 'ENOENT' });
});

test('identity is copied from primitive data fields and cannot invoke caller coercion or getters', t => {
  const f = fixture(t);
  let invoked = false;
  const coercible = { toString() { invoked = true; return '123'; } };
  const getter = { ...identity };
  Object.defineProperty(getter, 'runId', { enumerable: true, get() { invoked = true; return '123'; } });
  const hidden = { ...identity }; Object.defineProperty(hidden, 'secret', { value: 'CANARY' });
  const optionsGetter = { ...f.options };
  Object.defineProperty(optionsGetter, 'reportPath', { enumerable: true, get() { invoked = true; return f.options.reportPath; } });
  for (const options of [
    { ...f.options, identity: { ...identity, runId: 123 } },
    { ...f.options, identity: { ...identity, runId: coercible } },
    { ...f.options, identity: { ...identity, producer: new String('unit') } },
    { ...f.options, identity: { ...identity, invocationId: new String(identity.invocationId) } },
    { ...f.options, identity: getter }, { ...f.options, identity: hidden }, optionsGetter,
    { ...f.options, [Symbol('CANARY')]: 'CANARY' },
  ]) assert.throws(() => startGradleMemoryProbe(options, f.io), /Invalid memory probe/);
  assert.equal(invoked, false);
  assert.throws(() => readFileSync(f.options.reportPath), { code: 'ENOENT' });
});

test('mutable caller options cannot change ownership, invocation identity or output after start', async t => {
  const f = fixture(t);
  const caller = { ...f.options, identity: { ...identity } };
  const probe = startGradleMemoryProbe(caller, f.io);
  caller.ownerArgument = '-XX:ErrorFile=CANARY'; caller.identity.producer = 'CANARY'; caller.reportPath = join(f.dir, 'CANARY');
  await f.reachedInterval.promise;
  const result = await probe.stop();
  assert.equal(result.complete, true);
  assert.deepEqual(result.identity, identity);
  assert.deepEqual(JSON.parse(readFileSync(f.options.reportPath)), result);
  assert.throws(() => readFileSync(caller.reportPath), { code: 'ENOENT' });
});

test('later tool failure preserves the successful pair but never reports complete', async t => {
  for (const kind of ['timeout', 'output_limit', 'stderr', 'exit', 'start_failed', 'termination_timeout']) {
    const f = fixture(t); let calls = 0;
    f.io.runTool = async args => ++calls <= 2 ? { kind: 'ok', stdout: args.includes('-gc') ? gc : classes } : { kind, stdout: 'CANARY', stderr: 'CANARY' };
    f.io.sleep = async () => {};
    const probe = startGradleMemoryProbe(f.options, f.io); await settled();
    const result = await probe.stop();
    assert.equal(result.complete, false);
    assert.deepEqual(result.reasonCodes, [`jstat_${kind}`]);
    assert.equal(result.samples.length, 1);
    assert.equal(result.tools.calls, 3); assert.equal(result.tools.failures, 1);
    assert.ok(!readFileSync(f.options.reportPath, 'utf8').includes('CANARY'));
  }
});

test('malformed tool output and reversed uptime cannot replace a valid sampled maximum', async t => {
  for (const malformed of [true, false]) {
    const f = fixture(t); let calls = 0;
    f.io.runTool = async args => ({ kind: 'ok', stdout: ++calls <= 2 ? args.includes('-gc') ? gc : classes : malformed ? 'CANARY' : (args.includes('-gc') ? gc.replace('12.5', '1.5') : classes) });
    f.io.sleep = async () => {};
    const probe = startGradleMemoryProbe(f.options, f.io); await settled();
    const result = await probe.stop();
    assert.equal(result.complete, false); assert.equal(result.samples.length, 1);
    assert.ok(result.reasonCodes.includes(malformed ? 'jstat_invalid_output' : 'jstat_time_reversed'));
    assert.equal(result.sampledMax.metaspaceUsedKiB, 60000);
    assert.ok(!readFileSync(f.options.reportPath, 'utf8').includes('CANARY'));
  }
});

test('the final class tool is identity-checked and a newly ambiguous daemon rejects the pending pair', async t => {
  for (const reuse of [true, false]) {
    const f = fixture(t);
    f.io.runTool = async args => {
      if (args.includes('-class')) f.put(reuse ? record({ stat: stat(42, 9999) }) : record({ pid: 43, stat: stat(43, 1235) }));
      return { kind: 'ok', stdout: args.includes('-gc') ? gc : classes };
    };
    const probe = startGradleMemoryProbe(f.options, f.io); await settled();
    const result = await probe.stop();
    assert.equal(result.complete, false); assert.deepEqual(result.samples, []);
    assert.ok(result.reasonCodes.includes(reuse ? 'pid_reused' : 'ambiguous_daemons'));
  }
});

test('missing optional measurements remain null in rows and maxima', async t => {
  const f = fixture(t);
  f.io.runTool = async args => ({ kind: 'ok', stdout: args.includes('-gc') ? gc.replace('8192 7000', '- -').replace('3 0.3 0.6', '- - 0.6') : classes.replace('200.5', '-').replace('8.5', '-').replace('0.25', '-') });
  const probe = startGradleMemoryProbe(f.options, f.io); await f.reachedInterval.promise;
  const result = await probe.stop();
  assert.equal(result.complete, true);
  for (const name of ['compressedClassUsedKiB', 'compressedClassCommittedKiB', 'concurrentGcCount', 'concurrentGcTimeSeconds', 'loadedClassBytesKiB', 'unloadedClassBytesKiB', 'classLoadTimeSeconds']) {
    assert.equal(result.sampledMax[name], null);
    assert.equal(result.samples[0][result.sampleColumns.indexOf(name)], null);
  }
});

test('proc entries, record bytes and aggregate discovery bytes have enforced bounds', async t => {
  for (const limit of ['entries', 'record', 'aggregate']) {
    const f = fixture(t, []);
    if (limit === 'entries') for (let index = 0; index < 4097; index++) mkdirSync(join(f.procRoot, `entry${index}`));
    if (limit === 'record') f.put(record({ cmdline: Buffer.alloc(65537, 'C') }));
    if (limit === 'aggregate') for (let index = 1; index < 70; index++) f.put(record({ pid: index, stat: stat(index), cmdline: Buffer.alloc(64000, 'C') }));
    const probe = startGradleMemoryProbe(f.options, f.io); await settled();
    const result = await probe.stop();
    assert.equal(result.complete, false); assert.equal(f.calls.length, 0);
    assert.ok(result.reasonCodes.includes(limit === 'entries' ? 'process_scan_limit' : limit === 'aggregate' ? 'process_byte_limit' : 'process_read_failed'));
  }
});

test('discovery deadline, sample count and report byte limits are explicit incomplete outcomes', async t => {
  for (const limit of ['discovery', 'samples', 'bytes']) {
    const f = fixture(t, limit === 'discovery' ? [] : [record()]); let clock = 0;
    f.io.now = () => clock;
    f.io.sleep = async ms => { clock += ms; };
    const options = { ...f.options, ...(limit === 'discovery' ? { discoveryTimeoutMs: 250 } : limit === 'samples' ? { maxSamples: 2 } : { maxReportBytes: 4096 }) };
    const probe = startGradleMemoryProbe(options, f.io); await settled();
    const result = await probe.stop();
    assert.equal(result.complete, false);
    assert.ok(result.reasonCodes.includes(limit === 'discovery' ? 'discovery_timeout' : limit === 'samples' ? 'sample_limit' : 'report_size_limit'));
    if (limit === 'samples') assert.equal(result.samples.length, 2);
    assert.ok(readFileSync(options.reportPath).length <= options.maxReportBytes || options.maxReportBytes === undefined);
  }
});

test('Nightly records a simulated 31-minute observation while Main retains its 30-minute ceiling', async t => {
  for (const profile of ['main', 'nightly']) {
    const f = fixture(t); let clock = 0;
    const secondPair = deferred();
    f.io.now = () => clock;
    f.io.sleep = async (ms, signal) => {
      if (clock === 0) { clock = 31 * 60 * 1000; return; }
      secondPair.resolve();
      await sleep(10000, signal);
    };
    const { intervalMs, maxDurationMs, ...options } = f.options;
    const probe = startGradleMemoryProbe({ ...options, profile }, f.io);
    if (profile === 'nightly') await secondPair.promise;
    else await settled();
    const result = await probe.stop();
    assert.equal(result.complete, profile === 'nightly');
    assert.equal(result.samples.length, profile === 'nightly' ? 2 : 1);
    assert.equal(result.durationMs, 31 * 60 * 1000);
    assert.equal(result.limits.maxDurationMs, profile === 'nightly' ? 3600000 : 1800000);
    assert.equal(result.limits.intervalMs, profile === 'nightly' ? 10000 : 5000);
    assert.deepEqual(result.reasonCodes, profile === 'nightly' ? [] : ['duration_limit']);
    assert.equal(result.profile, undefined, 'resolved numeric limits preserve the report schema');
  }
});

test('profile duration ceilings and existing sample, byte and tool bounds fail closed', t => {
  const f = fixture(t);
  for (const patch of [
    { profile: '' }, { profile: 'CANARY' }, { maxDurationMs: 1800001 },
    { profile: 'main', maxDurationMs: 3600000 }, { profile: 'nightly', maxDurationMs: 3600001 },
    { profile: 'nightly', maxSamples: 361 }, { profile: 'nightly', maxReportBytes: 262145 },
    { profile: 'nightly', toolTimeoutMs: 5001 }, { profile: 'nightly', intervalMs: 999 },
  ]) assert.throws(() => startGradleMemoryProbe({ ...f.options, ...patch }, f.io), /Invalid memory probe/);
  assert.equal(f.calls.length, 0);
});

test('Nightly still stops at the pair count and byte caps without retaining tool payloads', async t => {
  for (const limit of ['samples', 'bytes']) {
    const f = fixture(t);
    f.io.now = () => 0;
    f.io.sleep = async () => {};
    const probe = startGradleMemoryProbe({ ...f.options, profile: 'nightly', maxDurationMs: 3600000,
      ...(limit === 'bytes' ? { maxReportBytes: 4096 } : {}) }, f.io);
    await settled();
    const result = await probe.stop();
    assert.equal(result.complete, false);
    assert.deepEqual(result.reasonCodes, [limit === 'samples' ? 'sample_limit' : 'report_size_limit']);
    if (limit === 'samples') assert.equal(result.samples.length, 360);
    const bytes = readFileSync(f.options.reportPath);
    assert.ok(bytes.length <= (limit === 'bytes' ? 4096 : 262144));
    for (const secret of [marker, 'CANARY', 'ErrorFile']) assert.ok(!bytes.toString('utf8').includes(secret));
  }
});

test('duration bound aborts an active tool before its independent longer timeout', async t => {
  const f = fixture(t);
  f.io.runTool = async (args, signal) => { await sleep(10000, signal); return { kind: signal.aborted ? 'stopped' : 'ok', stdout: gc }; };
  const before = performance.now();
  const probe = startGradleMemoryProbe({ ...f.options, maxDurationMs: 1000, toolTimeoutMs: 5000 }, f.io);
  await sleep(1300, new AbortController().signal);
  const result = await probe.stop();
  assert.ok(performance.now() - before < 2000);
  assert.ok(result.reasonCodes.includes('duration_limit'));
  assert.equal(result.complete, false); assert.deepEqual(result.samples, []);
});

test('report descriptor refuses existing files and symlinks and writes a private new file', async t => {
  for (const symlink of [false, true]) {
    const f = fixture(t); const destination = join(f.dir, 'existing'); writeFileSync(destination, 'CANARY');
    if (symlink) symlinkSync(destination, f.options.reportPath); else writeFileSync(f.options.reportPath, 'CANARY');
    const probe = startGradleMemoryProbe(f.options, f.io); await f.reachedInterval.promise;
    const result = await probe.stop();
    assert.equal(result.complete, false); assert.ok(result.reasonCodes.includes('report_write_failed'));
    assert.equal(readFileSync(destination, 'utf8'), 'CANARY'); assert.equal(readFileSync(f.options.reportPath, 'utf8'), 'CANARY');
  }
  const f = fixture(t); const probe = startGradleMemoryProbe(f.options, f.io); await f.reachedInterval.promise; await probe.stop();
  assert.equal(statSync(f.options.reportPath).mode & 0o777, 0o600);
});

test('real fake-tool subprocesses receive bounded JVM args and stop promptly without touching the daemon', async t => {
  const f = fixture(t, [record()], false); let probe;
  t.after(async () => {
    try { await probe?.stop(); }
    finally { rmSync(f.dir, { recursive: true, force: true }); }
  });
  const executable = join(f.dir, 'fake-jstat');
  const started = join(f.dir, 'started');
  writeFileSync(executable, `#!${process.execPath} --max-old-space-size=64\nimport { writeFileSync, renameSync } from 'node:fs';\nif (process.argv[2] !== '-J-Xmx64m') process.exit(3);\nprocess.on('SIGTERM', () => process.exit(0));\nwriteFileSync(${JSON.stringify(started + '.tmp')}, 'started');\nrenameSync(${JSON.stringify(started + '.tmp')}, ${JSON.stringify(started)});\nsetInterval(() => {}, 1000);\n`);
  chmodSync(executable, 0o755);
  const io = { ...f.io, jstatPath: executable }; delete io.runTool;
  probe = startGradleMemoryProbe(f.options, io);
  for (let attempt = 0; attempt < 200; attempt++) {
    try { readFileSync(started); break; } catch {}
    await new Promise(resolve => setTimeout(resolve, 10));
  }
  assert.equal(readFileSync(started, 'utf8'), 'started');
  const before = performance.now();
  const result = await probe.stop();
  assert.ok(performance.now() - before < 1500);
  assert.equal(result.complete, false);
  assert.equal(readFileSync(join(f.procRoot, '42/stat'), 'utf8'), stat());
});

test('real tool stderr, output overflow, nonzero exit and timeout fail without retaining tool text', async t => {
  for (const [kind, source] of [
    ['stderr', "process.stderr.write('CANARY'); setInterval(() => {}, 1000);"],
    ['output_limit', "process.stdout.write('C'.repeat(32769)); setInterval(() => {}, 1000);"],
    ['exit', 'setTimeout(() => process.exit(7), 1700);'],
    ['timeout', 'setInterval(() => {}, 1000);'],
  ]) {
    const f = fixture(t); const executable = join(f.dir, 'fake-jstat');
    writeFileSync(executable, `#!${process.execPath} --max-old-space-size=64\n${source}\n`); chmodSync(executable, 0o755);
    const io = { ...f.io, jstatPath: executable }; delete io.runTool;
    const closed = deferred(); const realSpawn = childProcess.spawn;
    childProcess.spawn = (...args) => {
      const child = realSpawn(...args);
      child.once('close', (code, signal) => closed.resolve({ code, signal }));
      return child;
    };
    syncBuiltinESMExports();
    let probe, watchdog;
    try {
      // Wait for the actual owned tool, not a wall-clock guess about process startup/exit.
      probe = startGradleMemoryProbe({ ...f.options, toolTimeoutMs: kind === 'timeout' ? 1000 : 5000 }, io);
      const exit = await Promise.race([closed.promise, new Promise((_, reject) => {
        watchdog = setTimeout(() => reject(new Error(`Tool fixture did not close: ${kind}`)), 8000);
      })]);
      await settled(); // Let the probe consume close and finish its failure path.
      const result = await probe.stop();
      const details = JSON.stringify({ kind, reasons: result.reasonCodes });
      assert.equal(result.complete, false); assert.equal(result.tools.failures, 1, details);
      assert.ok(result.reasonCodes.includes(`jstat_${kind}`), details);
      if (kind === 'exit') assert.deepEqual(exit, { code: 7, signal: null });
      assert.ok(!readFileSync(f.options.reportPath, 'utf8').includes('CANARY'));
      assert.equal(readFileSync(join(f.procRoot, '42/stat'), 'utf8'), stat());
    } finally {
      clearTimeout(watchdog);
      try { await probe?.stop(); }
      finally { childProcess.spawn = realSpawn; syncBuiltinESMExports(); }
    }
  }
});

test('stop escalates only its own unresponsive tool and awaits its exit', async t => {
  const f = fixture(t, [record()], false); let probe;
  t.after(async () => {
    try { await probe?.stop(); }
    finally { rmSync(f.dir, { recursive: true, force: true }); }
  });
  const executable = join(f.dir, 'fake-jstat'); const started = join(f.dir, 'started');
  // Publish only a complete marker: open/create alone is not a readiness signal.
  writeFileSync(executable, `#!${process.execPath} --max-old-space-size=64\nimport { writeFileSync, renameSync } from 'node:fs';\nprocess.on('SIGTERM', () => {});\nwriteFileSync(${JSON.stringify(started + '.tmp')}, String(process.pid));\nrenameSync(${JSON.stringify(started + '.tmp')}, ${JSON.stringify(started)});\nsetInterval(() => {}, 1000);\n`);
  chmodSync(executable, 0o755);
  const io = { ...f.io, jstatPath: executable }; delete io.runTool;
  probe = startGradleMemoryProbe(f.options, io);
  for (let attempt = 0; attempt < 200; attempt++) { try { readFileSync(started); break; } catch {} await sleep(10, new AbortController().signal); }
  const text = readFileSync(started, 'utf8');
  assert.match(text, /^[1-9][0-9]*$/, 'Tool fixture must publish a complete positive PID');
  const pid = Number(text);
  assert.ok(Number.isSafeInteger(pid) && pid > 0);
  const before = performance.now();
  const result = await probe.stop();
  assert.ok(performance.now() - before < 1500);
  assert.throws(() => process.kill(pid, 0), { code: 'ESRCH' });
  assert.equal(result.tools.cancelled, 1);
  assert.equal(readFileSync(join(f.procRoot, '42/stat'), 'utf8'), stat());
});

// Explicit hosted Linux smoke; no skipped/native test is registered by default.
if (process.env.GVY_MEMORY_PROBE_NATIVE_TEST === 'true') test('Linux JDK17 live jstat headers and attach produce a private numeric pair', { timeout: 20000 }, async t => {
  assert.equal(process.platform, 'linux', 'Native probe smoke requires Linux');
  assert.ok(process.env.JAVA_HOME, 'Native probe smoke requires setup-java17');
  const f = fixture(t, [], false); let child, probe;
  t.after(async () => {
    if (probe) await probe.stop();
    if (child && child.exitCode === null && child.signalCode === null) await new Promise(resolve => {
      const timer = setTimeout(() => child.kill('SIGKILL'), 250);
      child.once('close', () => { clearTimeout(timer); resolve(); }); child.kill('SIGTERM');
    });
    rmSync(f.dir, { recursive: true, force: true });
  });
  const env = Object.fromEntries(['PATH', 'HOME', 'JAVA_HOME'].flatMap(key => process.env[key] === undefined ? [] : [[key, process.env[key]]]));
  env.LANG = 'C'; env.LC_ALL = 'C';
  const java = join(process.env.JAVA_HOME, 'bin/java'), javac = join(process.env.JAVA_HOME, 'bin/javac');
  const version = spawnSync(java, ['-Xmx64m', '-version'], { env, timeout: 5000, maxBuffer: 4096, encoding: 'utf8' });
  assert.equal(version.status, 0, 'Fixture JDK version check failed');
  assert.match(version.stderr, /version "17(?:\.|"|-)/, 'Fixture requires JDK17');
  const sourceRoot = join(f.dir, 'source'); mkdirSync(sourceRoot);
  const source = join(sourceRoot, 'GradleDaemon.java');
  writeFileSync(source, `package org.gradle.launcher.daemon.bootstrap;\npublic final class GradleDaemon { public static void main(String[] args) throws Exception { System.out.print(1); System.out.flush(); Thread.sleep(60000); } }\n`);
  const compile = spawnSync(javac, ['-J-Xmx64m', '-d', f.dir, source], { env, timeout: 5000, maxBuffer: 4096 });
  assert.equal(compile.status, 0, 'Tiny owned fixture compilation failed');
  const ownerArgument = `-XX:ErrorFile=${f.dir}/gvy-memory-${'d'.repeat(64)}-hs_err_pid%p.log`;
  child = spawn(java, ['-Xmx64m', '-XX:MaxMetaspaceSize=64m', ownerArgument, '-cp', f.dir, main], { env, stdio: ['ignore', 'pipe', 'ignore'] });
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('Owned fixture startup timed out')), 5000);
    child.once('error', () => { clearTimeout(timer); reject(new Error('Owned fixture startup failed')); });
    child.stdout.once('data', chunk => { clearTimeout(timer); if (chunk.toString() === '1') resolve(); else reject(new Error('Invalid fixture readiness')); });
  });
  const paired = deferred();
  probe = startGradleMemoryProbe({ ...f.options, ownerArgument, toolTimeoutMs: 5000, maxDurationMs: 10000 }, {
    sleep: (ms, signal) => { if (ms >= 1000) paired.resolve(); return sleep(ms, signal); },
  });
  const waitTimer = setTimeout(paired.resolve, 11000);
  try { await paired.promise; } finally { clearTimeout(waitTimer); }
  const result = await probe.stop();
  console.log(JSON.stringify({ nativeMemorySample: {
    heapUsedKiB: result.sampledMax.heapUsedKiB,
    metaspaceUsedKiB: result.sampledMax.metaspaceUsedKiB,
    loadedClasses: result.sampledMax.loadedClasses,
  } }));
  assert.equal(result.complete, true, 'Native probe did not produce a complete owned pair');
  assert.equal(result.discovery.daemon.pid, child.pid);
  assert.equal(result.samples.length, 1);
  for (const name of ['heapUsedKiB', 'metaspaceUsedKiB', 'loadedClasses']) {
    assert.ok(Number.isFinite(result.sampledMax[name]) && result.sampledMax[name] >= 0, name + ' must be an observed nonnegative number');
  }
  assert.ok(result.sampledMax.loadedClasses > 0, 'the owned fixture must have loaded real classes');
  const artifact = readFileSync(f.options.reportPath, 'utf8');
  for (const privateText of [ownerArgument, f.dir, 'GradleDaemon', 'ErrorFile', 'CANARY']) assert.ok(!artifact.includes(privateText));
});
