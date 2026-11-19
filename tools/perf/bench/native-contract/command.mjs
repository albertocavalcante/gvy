// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import { spawn } from 'node:child_process';
import { performance } from 'node:perf_hooks';
import { TextDecoder } from 'node:util';
import { classifyNativeFailure } from './diagnostics.mjs';
import { classifyRustDiagnostics } from '../../../ci/check-rust-diagnostics.mjs';

const incompleteRustCapture = () => ({ complete: false, stdout: 'empty', stderr: 'empty' });
const rustChannel = bytes => {
  if (!bytes.length) return 'empty';
  let text;
  try { text = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(bytes); }
  catch { return 'invalid'; }
  const result = classifyRustDiagnostics(text);
  return !result.valid ? 'invalid' : result.ok ? 'clean' : 'diagnostics';
};

/** Own one POSIX process group; detached sessions and blocked OS calls remain outside containment. */
export function runCommand(executable, args, { cwd, input, environment = process.env, timeoutMs = 300000, outputLimit = 4 * 1024 * 1024, signal, nativeProbe = false, rustDiagnostics = false } = {}) {
  const earlyFailure = code => Promise.reject(Object.assign(new Error(code), rustDiagnostics === true ? { rustCapture: incompleteRustCapture() } : {}));
  if (!['darwin', 'linux'].includes(process.platform)) return earlyFailure('NATIVE_COMMAND_PLATFORM_UNSUPPORTED');
  if (typeof nativeProbe !== 'boolean' || typeof rustDiagnostics !== 'boolean' || (nativeProbe && rustDiagnostics) || !Number.isSafeInteger(timeoutMs) || timeoutMs <= 0 || timeoutMs > 300000 || !Number.isSafeInteger(outputLimit) || outputLimit <= 0 || outputLimit > 4 * 1024 * 1024 || (input !== undefined && Buffer.byteLength(input) > 64 * 1024)) return earlyFailure('NATIVE_COMMAND_OPTIONS_INVALID');
  if (signal?.aborted) return earlyFailure('NATIVE_COMMAND_ABORTED');
  return new Promise((resolve, reject) => {
    let child;
    let finished = false;
    let exited = false;
    let exitCode;
    let closed = false;
    let failure;
    let size = 0;
    let cleanupTimer;
    let cleanupDeadline;
    let terminationRequested = false;
    let captureInterrupted = false;
    const stdout = [];
    const stderr = [];
    const group = () => {
      if (child?.pid === undefined) return 'gone';
      try { process.kill(-child.pid, 0); return 'alive'; } catch (error) { return error.code === 'ESRCH' ? 'gone' : 'unknown'; }
    };
    const finish = code => {
      if (finished) return;
      finished = true;
      clearTimeout(timer);
      clearTimeout(cleanupTimer);
      signal?.removeEventListener('abort', abort);
      child?.stdin?.destroy(); child?.stdout?.destroy(); child?.stderr?.destroy();
      let rustCapture;
      if (rustDiagnostics) {
        const complete = child?.pid !== undefined && exited && closed && Number.isInteger(exitCode) && !captureInterrupted && (code === undefined || code === 'NATIVE_COMMAND_FAILED');
        // Classify observed bytes even on failure; only drained, uninterrupted streams certify complete capture.
        rustCapture = { complete, stdout: rustChannel(Buffer.concat(stdout)), stderr: rustChannel(Buffer.concat(stderr)) };
        if (!code) {
          if (!complete || [rustCapture.stdout, rustCapture.stderr].includes('invalid')) code = 'NATIVE_RUST_CAPTURE_INVALID';
          else if ([rustCapture.stdout, rustCapture.stderr].includes('diagnostics')) code = 'NATIVE_RUST_DIAGNOSTICS';
          else if (rustCapture.stdout === 'empty' && rustCapture.stderr === 'empty') code = 'NATIVE_RUST_CAPTURE_EMPTY';
        }
      }
      if (code) {
        const safeCode = nativeProbe && code === 'NATIVE_COMMAND_FAILED' ? classifyNativeFailure(Buffer.concat(stderr).toString('utf8')) : code;
        const error = new Error(safeCode);
        if (rustDiagnostics) error.rustCapture = rustCapture;
        // Private bounded stdout is consumed only by the rejection verifier; raw stderr never leaves this helper.
        if (nativeProbe && code === 'NATIVE_COMMAND_FAILED' && safeCode !== 'NATIVE_PROBE_FAILED') {
          error.nativeOutput = Buffer.concat(stdout).toString('utf8');
          error.nativeExitCode = exitCode;
        }
        reject(error);
      } else resolve(Buffer.concat(stdout).toString('utf8'));
    };
    const reconcile = () => {
      if (finished) return;
      if (exited && closed && group() === 'gone') { finish(failure); return; }
      if (performance.now() >= cleanupDeadline) { child?.unref(); finish('NATIVE_COMMAND_CLEANUP_UNKNOWN'); return; }
      cleanupTimer = setTimeout(reconcile, 20);
    };
    const stop = code => {
      if (finished) return;
      failure ??= code;
      if (!exited || !closed) captureInterrupted = true;
      if (!terminationRequested && child?.pid !== undefined) {
        terminationRequested = true;
        // NOTE: repeated group kills can race with reaping (Darwin EPERM). Observed exit/closure/group state decides cleanup.
        try { process.kill(-child.pid, 'SIGKILL'); } catch { /* Cleanup remains unknown unless observation proves the group gone. */ }
      }
      child?.stdin?.destroy(); child?.stdout?.destroy(); child?.stderr?.destroy();
      if (cleanupDeadline === undefined) { cleanupDeadline = performance.now() + 1500; reconcile(); }
    };
    const abort = () => stop('NATIVE_COMMAND_ABORTED');
    const timer = setTimeout(() => stop('NATIVE_COMMAND_TIMEOUT'), timeoutMs);
    try { child = spawn(executable, args, { cwd, env: environment, detached: true, stdio: ['pipe', 'pipe', 'pipe'] }); }
    catch { exited = true; closed = true; stop('NATIVE_COMMAND_FAILED'); return; }
    signal?.addEventListener('abort', abort, { once: true });
    if (signal?.aborted) abort();
    const consume = (chunk, keep) => {
      if (finished || (failure && failure !== 'NATIVE_COMMAND_FAILED')) return;
      size += chunk.length;
      if (size > outputLimit) { failure = 'NATIVE_COMMAND_OUTPUT_LIMIT'; stop(failure); return; }
      if (keep) stdout.push(chunk);
      else if (nativeProbe || rustDiagnostics) stderr.push(chunk);
    };
    child.stdout.on('data', chunk => consume(chunk, true));
    child.stderr.on('data', chunk => consume(chunk, false));
    child.stdin.on('error', () => stop('NATIVE_COMMAND_FAILED'));
    child.once('error', () => { exited = true; closed = true; stop('NATIVE_COMMAND_FAILED'); });
    child.once('exit', code => {
      exited = true;
      exitCode = code;
      if (code !== 0 && !failure) failure = 'NATIVE_COMMAND_FAILED';
      if (group() !== 'gone') stop(failure ?? 'NATIVE_COMMAND_DESCENDANTS');
    });
    child.once('close', code => {
      closed = true;
      if (code !== 0 && !failure) failure = 'NATIVE_COMMAND_FAILED';
      if (failure || group() !== 'gone') stop(failure ?? 'NATIVE_COMMAND_DESCENDANTS');
      else finish();
    });
    child.stdin.end(input);
  });
}
