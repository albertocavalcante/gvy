import { createHash, randomBytes } from 'node:crypto';
import { constants } from 'node:fs';
import { open, lstat, statfs, unlink } from 'node:fs/promises';
import { spawn } from 'node:child_process';
import { pathToFileURL } from 'node:url';

const MAX_ARTIFACT_BYTES = 2 * 1024 * 1024 * 1024;
const PROBE_TIMEOUT_MS = 5000;
const PROBE_OUTPUT_LIMIT = 64 * 1024;

/**
 * Run a fixed command in an owned POSIX process group, discarding its output.
 * A supported status means only that the fixed command exited successfully;
 * processes that deliberately detach from the group cannot be contained here.
 */
export function probeTool(tool, { executable = tool, timeoutMs = PROBE_TIMEOUT_MS, environment = process.env } = {}) {
  if (!['darwin', 'linux'].includes(process.platform)) return Promise.resolve({ status: 'unknown' });
  const injectedOptions = ['JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'];
  if (injectedOptions.some((name) => environment[name])) {
    return Promise.resolve({ status: 'unknown', reason: 'inherited_jvm_options' });
  }
  const env = {};
  for (const name of ['PATH', 'JAVA_HOME', 'HOME', 'SYSTEMROOT', 'WINDIR', 'TMPDIR', 'TMP', 'TEMP']) {
    if (environment[name] !== undefined) env[name] = environment[name];
  }
  const args = tool === 'java' ? ['-Xmx64m', '-version'] : ['-J-Xmx64m', '--help'];
  return new Promise((resolve) => {
    let child;
    try { child = spawn(executable, args, { stdio: ['ignore', 'pipe', 'pipe'], env, detached: true }); } catch { resolve({ status: 'unknown' }); return; }
    let size = 0;
    let finished = false;
    const terminateGroup = () => {
      if (child.pid === undefined) return;
      try { process.kill(-child.pid, 'SIGKILL'); } catch (error) {
        if (error.code !== 'ESRCH') return false;
      }
      return true;
    };
    const finish = (status) => {
      if (finished) return;
      finished = true;
      clearTimeout(timer);
      child.stdout.destroy();
      child.stderr.destroy();
      resolve({ status });
    };
    const timer = setTimeout(() => { terminateGroup(); finish('unknown'); }, timeoutMs);
    const consume = (chunk) => {
      size += chunk.length;
      if (size > PROBE_OUTPUT_LIMIT) { terminateGroup(); finish('unknown'); }
    };
    child.stdout.on('data', consume);
    child.stderr.on('data', consume);
    child.on('error', (error) => finish(error.code === 'ENOENT' ? 'unavailable' : 'unknown'));
    child.on('close', (code) => finish(terminateGroup() !== false && code === 0 ? 'supported' : 'unknown'));
  });
}

async function artifactEvidenceInner(path) {
  if (constants.O_NOFOLLOW === undefined || constants.O_NONBLOCK === undefined) return { status: 'unknown', compatibility: 'unknown' };
  let handle;
  let timer;
  let timedOut = false;
  try {
    handle = await open(path, constants.O_RDONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK);
    const info = await handle.stat();
    if (!info.isFile() || info.size > MAX_ARTIFACT_BYTES) return { status: 'unavailable', compatibility: 'unknown' };
    const hashing = (async () => {
      const hash = createHash('sha256');
      const buffer = Buffer.alloc(64 * 1024);
      let size = 0;
      while (true) {
        const { bytesRead } = await handle.read(buffer, 0, buffer.length, null);
        if (!bytesRead) break;
        size += bytesRead;
        if (size > MAX_ARTIFACT_BYTES) return { status: 'unknown', compatibility: 'unknown' };
        hash.update(buffer.subarray(0, bytesRead));
      }
      const after = await handle.stat();
      if (size !== info.size || after.size !== info.size || after.mtimeMs !== info.mtimeMs) return { status: 'unknown', compatibility: 'unknown' };
      return { status: 'supported', sha256: hash.digest('hex'), compatibility: 'unknown' };
    })();
    return await Promise.race([hashing, new Promise((resolve) => { timer = setTimeout(() => { timedOut = true; resolve({ status: 'unknown', compatibility: 'unknown' }); }, PROBE_TIMEOUT_MS); })]);
  } catch { return { status: 'unavailable', compatibility: 'unknown' }; }
  finally {
    clearTimeout(timer);
    if (handle) {
      const closing = handle.close().catch(() => {});
      if (!timedOut) await closing;
    }
  }
}

async function outputEvidenceInner(root) {
  let writable = 'unavailable';
  let availableBytes = null;
  let file;
  let identity;
  try {
    file = `${root}/.gvy-perf-doctor-${randomBytes(12).toString('hex')}`;
    const handle = await open(file, 'wx', 0o600);
    identity = await handle.stat();
    await handle.close();
    const current = await lstat(file);
    if (current.dev !== identity.dev || current.ino !== identity.ino) throw new Error('changed');
    await unlink(file);
    identity = undefined;
    writable = 'supported';
  } catch { /* No raw error or path belongs in exported evidence. */ }
  finally {
    if (identity) {
      const current = await lstat(file).catch(() => null);
      if (current && current.dev === identity.dev && current.ino === identity.ino) await unlink(file).catch(() => {});
    }
  }
  try {
    const filesystem = await statfs(root, { bigint: true });
    const free = filesystem.bavail * filesystem.bsize;
    if (free >= 0n && free <= BigInt(Number.MAX_SAFE_INTEGER)) availableBytes = Number(free);
  } catch { /* Free-space support is platform dependent. */ }
  return { writable, freeSpace: availableBytes === null ? 'unknown' : 'supported', availableBytes, capacity: 'unknown', parentPolicy: 'trusted_required' };
}

// This bounds the result wait only: Node cannot cancel a blocked OS filesystem call,
// and that call may still prevent the process from exiting.
async function bounded(operation, fallback) {
  let timer;
  try {
    return await Promise.race([
      operation(),
      new Promise((resolve) => { timer = setTimeout(() => resolve(fallback), PROBE_TIMEOUT_MS); }),
    ]);
  } finally { clearTimeout(timer); }
}

/**
 * Report probe availability, not operational readiness. A supported file hash or
 * command does not verify JAR compatibility, JFR recording, disk capacity for a
 * run, or build-slot availability. The output directory must have a trusted parent.
 */
export async function inspectPrerequisites({ artifact, outputRoot, probe = probeTool }) {
  const safeProbe = async (tool) => {
    try {
      const result = await probe(tool);
      return { status: ['supported', 'unavailable', 'unknown'].includes(result?.status) ? result.status : 'unknown', ...(result?.reason === 'inherited_jvm_options' ? { reason: result.reason } : {}) };
    } catch { return { status: 'unknown' }; }
  };
  const [artifactResult, output, runtime, jfr] = await Promise.all([
    bounded(() => artifactEvidenceInner(artifact), { status: 'unknown', compatibility: 'unknown' }),
    bounded(() => outputEvidenceInner(outputRoot), { writable: 'unknown', freeSpace: 'unknown', availableBytes: null, capacity: 'unknown', parentPolicy: 'trusted_required' }), safeProbe('java'), safeProbe('jfr'),
  ]);
  return {
    schemaVersion: 1,
    artifact: artifactResult, output, runtime, jfr: { ...jfr, recording: 'unknown' }, buildSlot: 'unknown',
  };
}

async function main(args) {
  if (args.length !== 5 || args[0] !== '--json' || args[1] !== '--artifact' || args[3] !== '--output-root') {
    process.stderr.write('Usage: doctor.mjs --json --artifact <file> --output-root <directory>\n');
    process.exitCode = 2;
    return;
  }
  const result = await inspectPrerequisites({ artifact: args[2], outputRoot: args[4] });
  process.stdout.write(`${JSON.stringify(result)}\n`);
  if (result.artifact.status !== 'supported' || result.output.writable !== 'supported' || result.runtime.status !== 'supported' || result.jfr.status !== 'supported') process.exitCode = 1;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main(process.argv.slice(2)).catch(() => { process.stderr.write('Doctor failed\n'); process.exitCode = 1; });
}
