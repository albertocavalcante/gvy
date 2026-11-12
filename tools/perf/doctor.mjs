import { createHash, randomBytes } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { open, stat, statfs, unlink } from 'node:fs/promises';
import { spawn } from 'node:child_process';
import { pathToFileURL } from 'node:url';

const MAX_ARTIFACT_BYTES = 2 * 1024 * 1024 * 1024;
const PROBE_TIMEOUT_MS = 5000;
const PROBE_OUTPUT_LIMIT = 64 * 1024;

/** Probe only fixed commands. Output is discarded and never included in evidence. */
export function probeTool(tool) {
  const args = tool === 'java' ? ['-Xmx64m', '-version'] : ['-J-Xmx64m', '--help'];
  return new Promise((resolve) => {
    let child;
    try { child = spawn(tool, args, { stdio: ['ignore', 'pipe', 'pipe'] }); } catch { resolve({ status: 'unknown' }); return; }
    let size = 0;
    let bounded = true;
    let finished = false;
    const timer = setTimeout(() => { bounded = false; child.kill('SIGKILL'); }, PROBE_TIMEOUT_MS);
    const consume = (chunk) => {
      size += chunk.length;
      if (size > PROBE_OUTPUT_LIMIT) { bounded = false; child.kill('SIGKILL'); }
    };
    child.stdout.on('data', consume);
    child.stderr.on('data', consume);
    child.on('error', (error) => {
      if (finished) return;
      finished = true;
      clearTimeout(timer);
      resolve({ status: error.code === 'ENOENT' ? 'unavailable' : 'unknown' });
    });
    child.on('close', (code) => {
      if (finished) return;
      finished = true;
      clearTimeout(timer);
      resolve({ status: bounded && code === 0 ? 'supported' : 'unknown' });
    });
  });
}

async function artifactEvidence(path) {
  try {
    const info = await stat(path);
    if (!info.isFile() || info.size > MAX_ARTIFACT_BYTES) return { status: 'unavailable' };
    const hash = createHash('sha256');
    let size = 0;
    for await (const chunk of createReadStream(path)) {
      size += chunk.length;
      if (size > MAX_ARTIFACT_BYTES) return { status: 'unknown' };
      hash.update(chunk);
    }
    if (size !== info.size) return { status: 'unknown' };
    return { status: 'supported', sha256: hash.digest('hex') };
  } catch { return { status: 'unavailable' }; }
}

async function outputEvidence(root) {
  let writable = 'unavailable';
  let availableBytes = null;
  let file;
  let owned = false;
  try {
    file = `${root}/.gvy-perf-doctor-${randomBytes(12).toString('hex')}`;
    const handle = await open(file, 'wx', 0o600);
    owned = true;
    await handle.close();
    await unlink(file);
    owned = false;
    writable = 'supported';
  } catch { /* No raw error or path belongs in exported evidence. */ }
  finally { if (owned) await unlink(file).catch(() => {}); }
  try {
    const filesystem = await statfs(root, { bigint: true });
    const free = filesystem.bavail * filesystem.bsize;
    if (free >= 0n && free <= BigInt(Number.MAX_SAFE_INTEGER)) availableBytes = Number(free);
  } catch { /* Free-space support is platform dependent. */ }
  return { writable, freeSpace: availableBytes === null ? 'unknown' : 'supported', availableBytes };
}

export async function inspectPrerequisites({ artifact, outputRoot, probe = probeTool }) {
  const safeProbe = async (tool) => {
    try {
      const result = await probe(tool);
      return { status: ['supported', 'unavailable', 'unknown'].includes(result?.status) ? result.status : 'unknown' };
    } catch { return { status: 'unknown' }; }
  };
  const [artifactResult, output, runtime, jfr] = await Promise.all([
    artifactEvidence(artifact), outputEvidence(outputRoot), safeProbe('java'), safeProbe('jfr'),
  ]);
  return {
    schemaVersion: 1,
    ready: artifactResult.status === 'supported' && output.writable === 'supported' && runtime.status === 'supported' && jfr.status === 'supported',
    artifact: artifactResult, output, runtime, jfr,
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
  if (!result.ready) process.exitCode = 1;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main(process.argv.slice(2)).catch(() => { process.stderr.write('Doctor failed\n'); process.exitCode = 1; });
}
