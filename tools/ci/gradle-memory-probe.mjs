import { spawn } from 'node:child_process';
import { closeSync, constants, lstatSync, openSync, opendirSync, writeFileSync } from 'node:fs';
import { isAbsolute, join } from 'node:path';
import { readEvidenceBytes } from './read-evidence.mjs';
import { gradleMemoryObservationProfile } from './gradle-memory-options.mjs';

const MAIN = 'org.gradle.launcher.daemon.bootstrap.GradleDaemon';
const MAX_PROC_ENTRIES = 4096;
const MAX_PROC_BYTES = 4 * 1024 * 1024;
const MAX_TOOL_BYTES = 32 * 1024;
const GC_REQUIRED = ['Timestamp', 'S0C', 'S1C', 'S0U', 'S1U', 'EC', 'EU', 'OC', 'OU', 'MC', 'MU', 'YGC', 'YGCT', 'FGC', 'FGCT', 'GCT'];
const GC_OPTIONAL = ['CCSC', 'CCSU', 'CGC', 'CGCT'];
const GC_METRICS = ['heapUsedKiB', 'heapCommittedKiB', 'metaspaceUsedKiB', 'metaspaceCommittedKiB', 'compressedClassUsedKiB', 'compressedClassCommittedKiB', 'youngGcCount', 'youngGcTimeSeconds', 'fullGcCount', 'fullGcTimeSeconds', 'concurrentGcCount', 'concurrentGcTimeSeconds', 'totalGcTimeSeconds'];
const CLASS_METRICS = ['loadedClasses', 'loadedClassBytesKiB', 'unloadedClasses', 'unloadedClassBytesKiB', 'classLoadTimeSeconds'];
export const sampleColumns = Object.freeze(['elapsedMs', 'gcVmUptimeSeconds', 'classVmUptimeSeconds', ...GC_METRICS, ...CLASS_METRICS]);
const fail = code => { const error = new Error(code); error.probeCode = code; throw error; };

function rows(output) {
  const text = Buffer.isBuffer(output) ? output.toString('utf8') : output;
  if (typeof text !== 'string' || Buffer.byteLength(text) > MAX_TOOL_BYTES || !/^[\x09\x0a\x0d\x20-\x7e]*$/.test(text)) throw new Error('Invalid jstat output');
  const lines = text.trim().split(/\r?\n/).filter(line => line.trim());
  if (lines.length !== 2) throw new Error('Invalid jstat output');
  const [header, values] = lines.map(line => line.trim().split(/\s+/));
  if (header.length !== values.length || header.length > 24) throw new Error('Invalid jstat output');
  return { header, values };
}
function number(value, integer = false, optional = false) {
  if (optional && (value === undefined || value === '-')) return null;
  if (typeof value !== 'string' || !(integer ? /^\d+$/ : /^\d+(?:\.\d+)?$/).test(value)) throw new Error('Invalid jstat output');
  const result = Number(value);
  if (!Number.isFinite(result) || result > Number.MAX_SAFE_INTEGER || (integer && !Number.isSafeInteger(result))) throw new Error('Invalid jstat output');
  return result;
}
export function parseGcSample(output) {
  const { header, values } = rows(output);
  if (new Set(header).size !== header.length || GC_REQUIRED.some(name => !header.includes(name)) || header.some(name => ![...GC_REQUIRED, ...GC_OPTIONAL].includes(name))) throw new Error('Invalid jstat output');
  const read = (name, integer = false) => number(values[header.indexOf(name)], integer, GC_OPTIONAL.includes(name));
  const sum = names => { const result = names.reduce((value, name) => value + read(name), 0); if (result > Number.MAX_SAFE_INTEGER) throw new Error('Invalid jstat output'); return result; };
  return {
    vmUptimeSeconds: read('Timestamp'), heapUsedKiB: sum(['S0U', 'S1U', 'EU', 'OU']), heapCommittedKiB: sum(['S0C', 'S1C', 'EC', 'OC']),
    metaspaceUsedKiB: read('MU'), metaspaceCommittedKiB: read('MC'), compressedClassUsedKiB: read('CCSU'), compressedClassCommittedKiB: read('CCSC'),
    youngGcCount: read('YGC', true), youngGcTimeSeconds: read('YGCT'), fullGcCount: read('FGC', true), fullGcTimeSeconds: read('FGCT'),
    concurrentGcCount: read('CGC', true), concurrentGcTimeSeconds: read('CGCT'), totalGcTimeSeconds: read('GCT'),
  };
}
export function parseClassSample(output) {
  const { header, values } = rows(output);
  // jstat intentionally repeats Bytes: the positions refer to loaded/unloaded classes.
  if (header.join(' ') !== 'Timestamp Loaded Bytes Unloaded Bytes Time') throw new Error('Invalid jstat output');
  return { vmUptimeSeconds: number(values[0]), loadedClasses: number(values[1], true), loadedClassBytesKiB: number(values[2], false, true), unloadedClasses: number(values[3], true), unloadedClassBytesKiB: number(values[4], false, true), classLoadTimeSeconds: number(values[5], false, true) };
}

export function parseProcStat(text, pid) {
  text = Buffer.isBuffer(text) ? text.toString('utf8') : text;
  const end = typeof text === 'string' ? text.lastIndexOf(') ') : -1;
  if (end < 0 || !text.startsWith(`${pid} (`)) throw new Error('Invalid process evidence');
  const fields = text.slice(end + 2).trim().split(/\s+/);
  const startTicks = Number(fields[19]);
  if (!/^[A-Za-z]$/.test(fields[0]) || !/^\d+$/.test(fields[19] ?? '') || !Number.isSafeInteger(startTicks) || startTicks < 1) throw new Error('Invalid process evidence');
  return { pid, startTicks };
}
function sameUid(status, uid) {
  const entries = [...String(status).matchAll(/^Uid:\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s*$/gm)];
  return entries.length === 1 && entries[0].slice(1).every(value => Number(value) === uid);
}
function javaMainIndex(argv) {
  if (argv[0]?.split('/').at(-1) !== 'java') return -1;
  const valued = new Set(['-cp', '-classpath', '--class-path', '-p', '--module-path', '--upgrade-module-path', '--add-exports', '--add-opens', '--add-reads', '--add-modules', '--limit-modules', '--patch-module', '--enable-native-access']);
  for (let index = 1; index < argv.length; index++) {
    const arg = argv[index];
    if (valued.has(arg)) { if (++index >= argv.length) return -1; continue; }
    if (arg === '-jar' || arg === '-m' || arg === '--module' || arg.startsWith('--module=')) return -1;
    if (/^(?:-D|-X|-javaagent:|-agentlib:|-agentpath:|--[a-z-]+=)/.test(arg) || /^(?:-server|-client|-ea(?::.*)?|-da(?::.*)?|-esa|-dsa|--enable-preview)$/.test(arg)) continue;
    return arg.startsWith('-') ? -1 : index;
  }
  return -1;
}
export function identifyOwnedGradleDaemon(record, { ownerArgument, uid }) {
  if (!sameUid(record.status, uid)) return null;
  const bytes = Buffer.isBuffer(record.cmdline) ? record.cmdline : Buffer.from(record.cmdline);
  if (!bytes.length || bytes.length > 64 * 1024 || bytes.at(-1) !== 0 || /[^\x00\x20-\x7e]/.test(bytes.toString('latin1'))) return null;
  const argv = bytes.toString('utf8').split('\0'); argv.pop();
  const mainIndex = javaMainIndex(argv);
  const owners = argv.flatMap((arg, index) => /^-XX:[+-]?ErrorFile/.test(arg) ? [{ arg, index }] : []);
  if (mainIndex < 0 || argv[mainIndex] !== MAIN || owners.length !== 1 || owners[0].arg !== ownerArgument || owners[0].index >= mainIndex) return null;
  return parseProcStat(record.stat, record.pid);
}

function validate(options) {
  const allowed = ['ownerArgument', 'identity', 'reportPath', 'profile', 'intervalMs', 'maxDurationMs', 'discoveryTimeoutMs', 'maxSamples', 'maxReportBytes', 'toolTimeoutMs'];
  const fields = ['headSha', 'testedSha', 'runId', 'runAttempt', 'producer', 'invocationId'];
  try {
    const copy = object => {
      if (!object || ![Object.prototype, null].includes(Object.getPrototypeOf(object)) || Object.getOwnPropertySymbols(object).length) throw new Error();
      return Object.fromEntries(Object.getOwnPropertyNames(object).map(key => {
        const property = Object.getOwnPropertyDescriptor(object, key);
        if (!property || !Object.hasOwn(property, 'value')) throw new Error();
        return [key, property.value];
      }));
    };
    options = copy(options);
    if (Object.keys(options).some(key => !allowed.includes(key))) throw new Error();
    const original = options.identity;
    const identity = copy(original);
    if (Object.keys(identity).sort().join() !== [...fields].sort().join()) throw new Error();
    if (![identity.headSha, identity.testedSha].every(value => typeof value === 'string' && /^[a-f0-9]{40}$/.test(value)) || typeof identity.runId !== 'string' || !/^[1-9][0-9]{0,19}$/.test(identity.runId) || !Number.isSafeInteger(Number(identity.runId)) || !Number.isSafeInteger(identity.runAttempt) || identity.runAttempt < 1 || typeof identity.producer !== 'string' || !/^[a-z0-9][a-z0-9-]{0,79}$/.test(identity.producer) || typeof identity.invocationId !== 'string' || !/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(identity.invocationId)) throw new Error();
    if (typeof options.ownerArgument !== 'string' || options.ownerArgument.length > 4096 || !/^-XX:ErrorFile=\/[A-Za-z0-9_./-]+\/gvy-memory-[a-f0-9]{64}-hs_err_pid%p\.log$/.test(options.ownerArgument) || /\/(?:\.|\.\.)\//.test(options.ownerArgument)) throw new Error();
    if (typeof options.reportPath !== 'string' || !isAbsolute(options.reportPath) || options.reportPath.length > 4096 || options.reportPath.includes('\0')) throw new Error();
    const profile = gradleMemoryObservationProfile(options.profile);
    const limits = { intervalMs: options.intervalMs ?? profile.intervalMs, maxDurationMs: options.maxDurationMs ?? profile.maxDurationMs, discoveryTimeoutMs: options.discoveryTimeoutMs ?? 60000, maxSamples: options.maxSamples ?? profile.maxSamples, maxReportBytes: options.maxReportBytes ?? profile.maxReportBytes, toolTimeoutMs: options.toolTimeoutMs ?? 2000 };
    for (const [key, min, max] of [['intervalMs', 1000, 60000], ['maxDurationMs', 1000, profile.maxDurationMs], ['discoveryTimeoutMs', 1, 60000], ['maxSamples', 1, 360], ['maxReportBytes', 4096, 256 * 1024], ['toolTimeoutMs', 10, 5000]]) if (!Number.isSafeInteger(limits[key]) || limits[key] < min || limits[key] > max) throw new Error();
    return { limits, identity, ownerArgument: options.ownerArgument, reportPath: options.reportPath };
  } catch { throw new Error('Invalid memory probe options or identity'); }
}
function delay(ms, signal) {
  return new Promise(resolve => {
    if (signal.aborted) return resolve();
    const finish = () => { clearTimeout(timer); signal.removeEventListener('abort', finish); resolve(); };
    const timer = setTimeout(finish, ms); signal.addEventListener('abort', finish, { once: true });
  });
}
function toolEnvironment() {
  const env = Object.fromEntries(['PATH', 'HOME', 'USER', 'LOGNAME', 'TMPDIR', 'JAVA_HOME'].flatMap(key => process.env[key] === undefined ? [] : [[key, process.env[key]]]));
  // Deliberately omit inherited Java option variables, agents and credentials.
  return { ...env, LANG: 'C', LC_ALL: 'C' };
}
function runJstat(path, args, signal, timeoutMs) {
  if (signal.aborted) return Promise.resolve({ kind: 'stopped' });
  return new Promise(resolve => {
    let child, cause, done = false, total = 0, timer, grace, hard;
    const chunks = [];
    const finish = result => { if (done) return; done = true; clearTimeout(timer); clearTimeout(grace); clearTimeout(hard); signal.removeEventListener('abort', abort); resolve(result); };
    const terminate = reason => {
      if (done || cause) return; cause = reason;
      child.kill('SIGTERM');
      grace = setTimeout(() => {
        if (done) return; child.kill('SIGKILL');
        hard = setTimeout(() => { child.stdout?.destroy(); child.stderr?.destroy(); child.unref(); finish({ kind: 'termination_timeout' }); }, 1000);
      }, 250);
    };
    const abort = () => terminate('stopped');
    try { child = spawn(path, args, { stdio: ['ignore', 'pipe', 'pipe'], env: toolEnvironment() }); }
    catch { finish({ kind: 'start_failed' }); return; }
    child.stdout.on('data', chunk => { total += chunk.length; if (total > MAX_TOOL_BYTES) terminate('output_limit'); else if (!cause) chunks.push(chunk); });
    child.stderr.on('data', () => terminate('stderr'));
    child.on('error', () => finish({ kind: cause ?? 'start_failed' }));
    child.on('close', code => finish(cause ? { kind: cause } : code === 0 ? { kind: 'ok', stdout: Buffer.concat(chunks) } : { kind: 'exit' }));
    timer = setTimeout(() => terminate('timeout'), timeoutMs);
    signal.addEventListener('abort', abort, { once: true }); if (signal.aborted) abort();
  });
}

/** Linux/JDK17 CI probe. Samples are observations, not true peaks; no GC is requested.
 * Fixed jstat17 headers/units: https://docs.oracle.com/en/java/javase/17/docs/specs/man/jstat.html
 * IO overrides are a test seam, never serialized.
 */
export function startGradleMemoryProbe(options, overrides = {}) {
  const { limits: settings, identity, ownerArgument, reportPath } = validate(options); // Snapshot caller strings before IO.
  const io = { platform: process.platform, procRoot: '/proc', uid: process.getuid?.(), now: () => performance.now(), sleep: delay, readBytes: readEvidenceBytes, ...overrides };
  const started = io.now(); const controller = new AbortController(); let requested = false, stopPromise;
  const report = {
    schemaVersion: 1, kind: 'gradle-daemon-memory', identity, status: 'incomplete', complete: false, reasonCodes: [],
    limits: { ...settings, maxProcEntries: MAX_PROC_ENTRIES, maxProcScanBytes: MAX_PROC_BYTES, maxToolOutputBytes: MAX_TOOL_BYTES },
    discovery: { attempts: 0, processEntriesScanned: 0, unreadableProcessRecords: 0, matchedDaemonCount: null, daemon: null },
    startedAtEpochMs: Date.now(), endedAtEpochMs: null, durationMs: null, lastIdentityCheckElapsedMs: null,
    tools: { calls: 0, failures: 0, cancelled: 0 }, sampleColumns: [...sampleColumns], samples: [],
    sampledMax: Object.fromEntries([...GC_METRICS, ...CLASS_METRICS].map(key => [key, null])),
  };
  const elapsed = () => Math.max(0, Math.floor(io.now() - started));
  const reason = code => { if (!report.reasonCodes.includes(code)) report.reasonCodes.push(code); };
  const deadline = setTimeout(() => { reason('duration_limit'); controller.abort(); }, settings.maxDurationMs);
  function discover() {
    report.discovery.attempts++;
    let entries = 0, bytesRead = 0; const matches = [];
    const read = (path, maximum) => { const bytes = io.readBytes(path, maximum); bytesRead += bytes.length; if (bytesRead > MAX_PROC_BYTES) fail('process_byte_limit'); return bytes; };
    const directory = opendirSync(io.procRoot);
    try {
      for (let entry; (entry = directory.readSync()) !== null;) {
        if (++entries > MAX_PROC_ENTRIES) fail('process_scan_limit');
        report.discovery.processEntriesScanned++;
        if (!/^[1-9][0-9]*$/.test(entry.name) || !Number.isSafeInteger(Number(entry.name))) continue;
        const pid = Number(entry.name), root = join(io.procRoot, entry.name);
        try {
          const info = lstatSync(root); if (!info.isDirectory() || info.uid !== io.uid) continue;
          const firstStat = read(join(root, 'stat'), 8 * 1024);
          const status = read(join(root, 'status'), 64 * 1024).toString('utf8');
          if (!sameUid(status, io.uid)) continue;
          const cmdline = read(join(root, 'cmdline'), 64 * 1024);
          const match = identifyOwnedGradleDaemon({ pid, stat: firstStat, status, cmdline }, { ownerArgument, uid: io.uid });
          if (!match) continue;
          const after = parseProcStat(read(join(root, 'stat'), 8 * 1024), pid);
          if (after.startTicks !== match.startTicks) continue;
          matches.push(match);
        } catch (error) {
          if (['ENOENT', 'ESRCH'].includes(error.code)) { report.discovery.unreadableProcessRecords++; continue; }
          fail(error.probeCode ?? 'process_read_failed');
        }
      }
    } finally { directory.closeSync(); }
    report.discovery.matchedDaemonCount = matches.length;
    return matches;
  }
  function verifyPinned() {
    const matches = discover();
    if (matches.length > 1) fail('ambiguous_daemons');
    const pinned = report.discovery.daemon;
    const current = parseProcStat(io.readBytes(join(io.procRoot, String(pinned.pid), 'stat'), 8 * 1024), pinned.pid);
    if (current.startTicks !== pinned.startTicks) fail('pid_reused');
    if (matches.length !== 1 || matches[0].pid !== pinned.pid || matches[0].startTicks !== pinned.startTicks) fail('daemon_ownership_changed');
    report.lastIdentityCheckElapsedMs = elapsed();
  }
  async function sample(mode) {
    verifyPinned();
    report.tools.calls++;
    const args = ['-J-Xmx64m', mode, '-t', String(report.discovery.daemon.pid), '1', '1'];
    const output = await (io.runTool ? io.runTool(args, controller.signal) : runJstat(io.jstatPath ?? (process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin/jstat') : 'jstat'), args, controller.signal, settings.toolTimeoutMs));
    if (output.kind === 'stopped') { report.tools.cancelled++; return null; }
    if (output.kind !== 'ok') { report.tools.failures++; fail(['timeout', 'output_limit', 'stderr', 'exit', 'start_failed', 'termination_timeout'].includes(output.kind) ? `jstat_${output.kind}` : 'jstat_failed'); }
    verifyPinned();
    try { return mode === '-gc' ? parseGcSample(output.stdout) : parseClassSample(output.stdout); }
    catch { fail('jstat_invalid_output'); }
  }
  const collection = (async () => {
    if (io.platform !== 'linux') { report.status = 'unsupported'; reason('unsupported_platform'); return; }
    if (!Number.isSafeInteger(io.uid) || io.uid < 0) fail('uid_unavailable');
    while (!controller.signal.aborted && !report.discovery.daemon) {
      if (elapsed() >= settings.maxDurationMs) fail('duration_limit');
      const matches = discover(); if (matches.length > 1) fail('ambiguous_daemons');
      if (matches.length === 1) { report.discovery.daemon = matches[0]; break; }
      if (elapsed() >= settings.discoveryTimeoutMs) fail('discovery_timeout');
      await io.sleep(Math.min(250, settings.discoveryTimeoutMs - elapsed()), controller.signal);
    }
    while (!controller.signal.aborted && report.discovery.daemon) {
      if (elapsed() >= settings.maxDurationMs) fail('duration_limit');
      if (report.samples.length >= settings.maxSamples) fail('sample_limit');
      const gc = await sample('-gc'); if (gc === null || controller.signal.aborted) break;
      const classes = await sample('-class'); if (classes === null || controller.signal.aborted) break;
      verifyPinned();
      const row = [elapsed(), gc.vmUptimeSeconds, classes.vmUptimeSeconds, ...GC_METRICS.map(key => gc[key]), ...CLASS_METRICS.map(key => classes[key])];
      const previous = report.samples.at(-1); if (previous && (row[1] < previous[1] || row[2] < previous[2])) fail('jstat_time_reversed');
      if (Buffer.byteLength(JSON.stringify({ ...report, samples: [...report.samples, row] })) > settings.maxReportBytes - 2048) fail('report_size_limit');
      report.samples.push(row);
      for (const key of [...GC_METRICS, ...CLASS_METRICS]) { const value = key in gc ? gc[key] : classes[key]; if (value !== null) report.sampledMax[key] = Math.max(report.sampledMax[key] ?? value, value); }
      await io.sleep(settings.intervalMs, controller.signal);
    }
  })().catch(error => reason(error.probeCode ?? 'probe_failed')).finally(() => clearTimeout(deadline));
  return {
    stop() {
      if (!stopPromise) {
        requested = true; controller.abort();
        stopPromise = collection.then(() => {
          if (!report.discovery.daemon && report.status !== 'unsupported') reason('no_owned_daemon');
          if (!report.samples.length && report.status !== 'unsupported') reason('no_samples');
          report.durationMs = elapsed(); report.endedAtEpochMs = Date.now();
          report.complete = requested && report.reasonCodes.length === 0 && report.samples.length > 0;
          if (report.status !== 'unsupported') report.status = report.complete ? 'complete' : 'incomplete';
          try {
            const bytes = Buffer.from(JSON.stringify(report) + '\n'); if (bytes.length > settings.maxReportBytes) throw new Error();
            if (!Number.isInteger(constants.O_NOFOLLOW) || constants.O_NOFOLLOW === 0) throw new Error();
            const fd = openSync(reportPath, constants.O_CREAT | constants.O_EXCL | constants.O_WRONLY | constants.O_NOFOLLOW, 0o600);
            try { writeFileSync(fd, bytes); } finally { closeSync(fd); }
          } catch { report.complete = false; report.status = 'incomplete'; reason('report_write_failed'); }
          return report;
        });
      }
      return stopPromise;
    },
  };
}
