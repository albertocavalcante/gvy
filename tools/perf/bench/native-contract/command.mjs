import { spawn } from 'node:child_process';
import { performance } from 'node:perf_hooks';

/** Own one POSIX process group; detached sessions and blocked OS calls remain outside containment. */
export function runCommand(executable, args, { cwd, input, environment = process.env, timeoutMs = 300000, outputLimit = 4 * 1024 * 1024, signal } = {}) {
  if (!['darwin', 'linux'].includes(process.platform)) return Promise.reject(new Error('NATIVE_COMMAND_PLATFORM_UNSUPPORTED'));
  if (!Number.isSafeInteger(timeoutMs) || timeoutMs <= 0 || timeoutMs > 300000 || !Number.isSafeInteger(outputLimit) || outputLimit <= 0 || outputLimit > 4 * 1024 * 1024 || (input !== undefined && Buffer.byteLength(input) > 64 * 1024)) return Promise.reject(new Error('NATIVE_COMMAND_OPTIONS_INVALID'));
  if (signal?.aborted) return Promise.reject(new Error('NATIVE_COMMAND_ABORTED'));
  return new Promise((resolve, reject) => {
    let child;
    let finished = false;
    let exited = false;
    let closed = false;
    let failure;
    let size = 0;
    let cleanupTimer;
    let cleanupDeadline;
    let terminationRequested = false;
    const stdout = [];
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
      if (code) reject(new Error(code)); else resolve(Buffer.concat(stdout).toString('utf8'));
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
      if (finished || failure) return;
      size += chunk.length;
      if (size > outputLimit) { stop('NATIVE_COMMAND_OUTPUT_LIMIT'); return; }
      if (keep) stdout.push(chunk);
    };
    child.stdout.on('data', chunk => consume(chunk, true));
    child.stderr.on('data', chunk => consume(chunk, false));
    child.stdin.on('error', () => stop('NATIVE_COMMAND_FAILED'));
    child.once('error', () => { exited = true; closed = true; stop('NATIVE_COMMAND_FAILED'); });
    child.once('exit', code => {
      exited = true;
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
