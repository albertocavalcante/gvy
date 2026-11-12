// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import { spawn, spawnSync } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join, isAbsolute } from 'node:path';
import { fileURLToPath } from 'node:url';
import { performance } from 'node:perf_hooks';
import { isDeepStrictEqual } from 'node:util';
import { digest, emptyCapture, provisioningLimits, validateAttempt, validatePhases } from './vscode-provisioning-policy.mjs';

const workerPath = fileURLToPath(new URL('./vscode-provisioning-worker.mjs', import.meta.url));
const sameKeys = (value, names) => value && typeof value === 'object' && !Array.isArray(value) && isDeepStrictEqual(Object.keys(value).sort(), names.slice().sort());
const envNames = ['PATH', 'PATHEXT', 'HOME', 'USERPROFILE', 'SystemRoot', 'TEMP', 'TMP', 'TMPDIR', 'LANG', 'HTTP_PROXY', 'HTTPS_PROXY', 'NO_PROXY', 'http_proxy', 'https_proxy', 'no_proxy', 'npm_config_proxy', 'npm_config_https_proxy', 'NODE_EXTRA_CA_CERTS', 'GVY_CI_EVIDENCE_READER', 'GVY_TEST_EXTENSION_ROOT'];

/** Owns one fresh session/process tree; never sends signals to a caller-supplied PID. */
export async function launchProvisioningAttempt(input, { logDir, script = workerPath, limits = provisioningLimits, consoleOutput = true } = {}) {
  mkdirSync(logDir, { mode: 0o700 });
  const started = performance.now();
  const events = [];
  const errorLogs = [];
  const chunks = { stdout: [], stderr: [] };
  const lengths = { stdout: 0, stderr: 0 };
  let seq = 0;
  let final;
  let fatal;
  let captureComplete = true;
  let cleanupComplete = true;
  let stopping = false;
  let closed = false;
  let forceTimer;
  let finishTimer;
  let finish;
  let watchdog;
  let cleanupDeadline;
  const resultPromise = new Promise(resolve => { finish = resolve; });
  const child = spawn(process.execPath, [script, '--worker'], {
    cwd: input.editorRoot, detached: process.platform !== 'win32', windowsHide: true,
    stdio: ['ignore', 'pipe', 'pipe', 'ipc'],
    env: Object.fromEntries(envNames.filter(name => process.env[name] !== undefined).map(name => [name, process.env[name]])),
  });
  const signalGroup = signal => {
    try { process.kill(-child.pid, signal); }
    catch (error) { if (error.code !== 'ESRCH') cleanupComplete = false; }
  };
  const stop = code => {
    fatal ??= code;
    if (stopping || closed) return;
    stopping = true;
    clearTimeout(watchdog);
    cleanupDeadline = performance.now() + limits.killMs;
    if (process.platform === 'win32') {
      const result = spawnSync(join(process.env.SystemRoot, 'System32', 'taskkill.exe'), ['/PID', String(child.pid), '/T', '/F'], { timeout: limits.killMs, maxBuffer: 4096, windowsHide: true, stdio: 'ignore', shell: false });
      if (result.error || result.status !== 0) cleanupComplete = false;
    } else {
      signalGroup('SIGTERM');
      forceTimer = setTimeout(() => signalGroup('SIGKILL'), Math.max(1, Math.floor(limits.killMs / 2)));
    }
    finishTimer = setTimeout(() => {
      if (!closed) {
        cleanupComplete = false;
        child.stdout.destroy(); child.stderr.destroy();
        if (child.connected) child.disconnect();
        child.unref();
        finish({ code: null, signal: null });
      }
    }, limits.killMs);
  };
  const receive = (stream, bytes) => {
    if (!captureComplete) return;
    const remaining = limits.captureBytes - lengths.stdout - lengths.stderr;
    const retained = bytes.subarray(0, Math.max(0, remaining));
    chunks[stream].push(retained);
    lengths[stream] += retained.length;
    if (consoleOutput && retained.length) process[stream].write(retained);
    if (bytes.length > remaining) {
      captureComplete = false;
      child.stdout.pause(); child.stderr.pause();
      stop('capture-limit');
    }
  };
  child.stdout.on('data', bytes => receive('stdout', bytes));
  child.stderr.on('data', bytes => receive('stderr', bytes));
  child.on('error', () => stop('invalid-evidence'));
  child.on('message', message => {
    try {
      if (final || ++seq > 64 || message.token !== input.token || message.attempt !== input.number || message.contextSha256 !== digest(JSON.stringify(input.context)) || message.seq !== seq) throw new Error();
      const base = ['token', 'attempt', 'contextSha256', 'seq', 'type'];
      if (message.type === 'phase') {
        if (!sameKeys(message, [...base, 'phase'])) throw new Error();
        events.push({ phase: message.phase, elapsedMs: Math.ceil(performance.now() - started) });
        validatePhases(events);
      } else if (message.type === 'error-log') {
        if (!sameKeys(message, [...base, 'phase', 'bytes', 'sha256']) || errorLogs.length >= 8 || message.phase !== events.at(-1)?.phase || !Number.isSafeInteger(message.bytes) || message.bytes < 1 || message.bytes > 16 * 1024 || !/^[a-f0-9]{64}$/.test(message.sha256)) throw new Error();
        errorLogs.push({ phase: message.phase, bytes: message.bytes, sha256: message.sha256 });
      } else if (message.type === 'result') {
        if (!sameKeys(message, [...base, 'outcome', 'captureComplete', 'error', 'delivery', 'verification', 'executable']) || typeof message.captureComplete !== 'boolean' || (message.executable !== null && (typeof message.executable !== 'string' || message.executable.length > 4096 || !isAbsolute(message.executable) || /[\0\r\n]/.test(message.executable)))) throw new Error();
        final = message;
        // The reviewed worker flushes raw evidence and then waits on IPC. Stop
        // its owned tree even on a handled result, including probe descendants.
        stop(undefined);
      } else throw new Error();
    } catch { stop('invalid-evidence'); }
  });
  child.on('close', async (code, signal) => {
    closed = true;
    clearTimeout(watchdog);
    clearTimeout(forceTimer); clearTimeout(finishTimer);
    if (process.platform !== 'win32') {
      cleanupDeadline ??= performance.now() + limits.killMs;
      // A crashed leader can leave children behind even though its pipes closed.
      // Keep ownership of the group through a bounded disappearance check.
      signalGroup('SIGKILL');
      while (true) {
        try { process.kill(-child.pid, 0); }
        catch (error) {
          if (error.code !== 'ESRCH') cleanupComplete = false;
          break;
        }
        if (performance.now() >= cleanupDeadline) { cleanupComplete = false; break; }
        await new Promise(resolve => setTimeout(resolve, Math.min(25, Math.max(1, cleanupDeadline - performance.now()))));
      }
    } else if (!stopping) {
      // taskkill /T can address a live owned leader. After an unexpected exit
      // its descendants cannot be proven gone by that PID; never claim cleanup.
      cleanupComplete = false;
    }
    finish({ code, signal });
  });
  watchdog = setTimeout(() => stop('deadline-exceeded'), input.budgetMs);
  const interrupted = () => stop('cleanup-incomplete');
  process.once('SIGINT', interrupted); process.once('SIGTERM', interrupted);
  child.once('spawn', () => child.send(input));
  const status = await resultPromise;
  clearTimeout(watchdog); clearTimeout(forceTimer); clearTimeout(finishTimer);
  process.off('SIGINT', interrupted); process.off('SIGTERM', interrupted);
  const stdout = Buffer.concat(chunks.stdout), stderr = Buffer.concat(chunks.stderr);
  try {
    writeFileSync(join(logDir, 'stdout.log'), stdout, { flag: 'wx', mode: 0o600 });
    writeFileSync(join(logDir, 'stderr.log'), stderr, { flag: 'wx', mode: 0o600 });
  } catch { fatal ??= 'invalid-evidence'; captureComplete = false; }
  let offset = 0;
  for (const error of errorLogs) {
    if (digest(stderr.subarray(offset, offset + error.bytes)) !== error.sha256) fatal ??= 'unexpected-output';
    offset += error.bytes;
  }
  if (stdout.length || offset !== stderr.length) fatal ??= 'unexpected-output';
  if (!final || (!stopping && (status.code !== 0 || status.signal))) fatal ??= 'invalid-evidence';
  if (!cleanupComplete) fatal = 'cleanup-incomplete';
  const result = {
    number: input.number, contextSha256: digest(JSON.stringify(input.context)),
    outcome: fatal ?? final.outcome, elapsedMs: Math.min(provisioningLimits.totalMs, Math.ceil(performance.now() - started)),
    phases: events.length ? events : [{ phase: 'configuration', elapsedMs: 0 }], errorLogs,
    error: fatal ? { code: fatal, messageSha256: null } : final.error,
    delivery: fatal ? null : final.delivery,
    verification: final?.verification ?? null,
    capture: { ...emptyCapture(), stdoutBytes: stdout.length, stdoutSha256: digest(stdout), stderrBytes: stderr.length, stderrSha256: digest(stderr), complete: captureComplete && (final?.captureComplete ?? true) },
    cleanupComplete,
  };
  try { validateAttempt(result, input.context, input.number); }
  catch {
    result.outcome = 'invalid-evidence'; result.error = { code: 'invalid-evidence', messageSha256: null }; result.delivery = null; result.verification = null;
    // Invalid worker data is never projected. Only parent-owned capture hashes survive.
    result.phases = [{ phase: 'configuration', elapsedMs: 0 }]; result.errorLogs = [];
    validateAttempt(result, input.context, input.number);
  }
  return { result, executable: result.outcome === 'success' ? final.executable : null };
}
