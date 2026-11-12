import { createHash } from 'node:crypto';
import { isUtf8 } from 'node:buffer';
import { closeSync, constants, fstatSync, ftruncateSync, lstatSync, openSync, readdirSync, writeFileSync } from 'node:fs';
import { isDeepStrictEqual } from 'node:util';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { readEvidenceBytes, readEvidenceText } from './read-evidence.mjs';
import { evaluateEvidence } from './check-bazel-evidence.mjs';
import { PUBLIC_CONTRACT, MAX_BEP_BYTES, MAX_PROFILE_BYTES, projectBep, validatePublicProfile } from './public-bazel-evidence.mjs';

const files = ['context.json', 'bep.json', 'profile.json', 'summary.json'];
const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');
const canonicalJson = value => `${JSON.stringify(value, null, 2)}\n`;

export function publicSummary(bep, context, status, producer, dirty) {
  return {
    ...evaluateEvidence(bep, context, status.commandExitCode, status.captureExitCode),
    producer, remoteTelemetry: 'disabled-by-owner', worktreeDirty: dirty,
    commandSignal: status.commandSignal ?? null, publicEvidenceContract: PUBLIC_CONTRACT,
  };
}

export function writePublicManifest(dir, context) {
  const descriptions = {};
  for (const name of files) {
    const bytes = readEvidenceBytes(join(dir, name), name === 'bep.json' ? MAX_BEP_BYTES : name === 'profile.json' ? MAX_PROFILE_BYTES : 4 * 1024 * 1024);
    descriptions[name] = { bytes: bytes.length, sha256: sha256(bytes) };
  }
  writeFileSync(join(dir, 'public-evidence.json'), canonicalJson({ schemaVersion: 1, contract: PUBLIC_CONTRACT, context, files: descriptions }), { flag: 'wx', mode: 0o600 });
}

function validateContext(context, expected) {
  const checked = evaluateEvidence('', context, 0).context;
  if (!checked || !isDeepStrictEqual(checked, context)) throw new Error('Invalid public invocation context');
  if (!/^[1-9][0-9]*$/.test(context.runId) && context.runId !== `local-${context.invocationId}`) throw new Error('Invalid public run identifier');
  for (const key of ['headSha', 'testedSha', 'runId', 'runAttempt']) {
    if (expected?.[key] !== undefined && String(context[key]) !== String(expected[key])) throw new Error('Stale public invocation context');
  }
}

/** Verify the projection contract, not just the presence of a marker. */
export function auditPublicRoot(root, expected = {}, readBytes = readEvidenceBytes) {
  const result = { schemaVersion: 1, contract: PUBLIC_CONTRACT, ok: false, errors: [], invocations: 0 };
  const text = bytes => {
    if (!isUtf8(bytes)) throw new Error('Invalid public UTF-8');
    return bytes.toString('utf8');
  };
  const parseJson = bytes => {
    try {
      const serialized = text(bytes);
      const value = JSON.parse(serialized);
      // Parsed objects lose shadowed duplicate keys and noncanonical escapes.
      // Certify the producer's exact public bytes, not only its last values.
      if (canonicalJson(value) !== serialized) throw new Error('Noncanonical public JSON');
      return value;
    } catch { throw new Error('Invalid public JSON'); }
  };
  const json = path => parseJson(readBytes(path, 4 * 1024 * 1024));
  try {
    if (lstatSync(root).isSymbolicLink() || !lstatSync(root).isDirectory()) throw new Error('Public root must be a real directory');
    const entries = readdirSync(root, { withFileTypes: true });
    if (entries.length > 101) throw new Error('Public invocation count exceeds limit');
    for (const entry of entries) {
      if (entry.name === 'public-audit.json') {
        if (!entry.isFile() || entry.isSymbolicLink()) throw new Error('Unsafe public audit marker');
        const marker = json(join(root, entry.name));
        const allowedErrors = ['Public evidence audit failed', 'Public evidence audit rejected unknown, stale, malformed, oversized or unprojected evidence'];
        if (!isDeepStrictEqual(Object.keys(marker).sort(), ['contract', 'errors', 'invocations', 'ok', 'schemaVersion']) || marker.schemaVersion !== 1 || marker.contract !== PUBLIC_CONTRACT || typeof marker.ok !== 'boolean' || !Number.isInteger(marker.invocations) || marker.invocations < 0 || marker.invocations > 100 || !Array.isArray(marker.errors) || marker.errors.some(error => !allowedErrors.includes(error))) throw new Error('Invalid public audit marker');
        continue;
      }
      if (!entry.isDirectory() || entry.isSymbolicLink()) throw new Error('Unknown public-root entry');
      const dir = join(root, entry.name);
      const names = readdirSync(dir, { withFileTypes: true });
      if (names.length !== 5 || names.some(item => !item.isFile() || item.isSymbolicLink() || ![...files, 'public-evidence.json'].includes(item.name))) throw new Error('Unknown or unsafe public invocation file');
      // Parse, validate and hash each exact bounded buffer. Reopening paths for
      // hashes could bind a later, unprojected replacement to an earlier check.
      const contents = Object.fromEntries(files.map(name => [name, readBytes(join(dir, name), name === 'bep.json' ? MAX_BEP_BYTES : name === 'profile.json' ? MAX_PROFILE_BYTES : 4 * 1024 * 1024)]));
      const context = parseJson(contents['context.json']);
      validateContext(context, expected);
      const summary = parseJson(contents['summary.json']);
      if (!/^[a-z0-9][a-z0-9-]{0,79}$/.test(summary.producer ?? '') || entry.name !== `${summary.producer}-${context.command}-${context.invocationId}`) throw new Error('Invalid public invocation directory');
      const bep = text(contents['bep.json']);
      if (projectBep(bep, context, { publicInput: true }) !== bep) throw new Error('Unprojected public BEP fields');
      const profile = contents['profile.json'];
      validatePublicProfile(profile);
      if (![null, true, false].includes(summary.worktreeDirty) || ![null, 'SIGINT', 'SIGTERM', 'SIGKILL', 'SIGHUP', 'SIGQUIT', 'SIGABRT'].includes(summary.commandSignal)) throw new Error('Invalid public invocation status');
      if (![summary.commandExitCode, summary.captureExitCode].every(code => Number.isInteger(code) && code >= 0 && code <= 255)) throw new Error('Invalid public exit status');
      const recomputed = publicSummary(bep, context, summary, summary.producer, summary.worktreeDirty);
      if (!isDeepStrictEqual(recomputed, summary)) throw new Error('Unprojected or inconsistent public summary');
      const manifest = json(join(dir, 'public-evidence.json'));
      const descriptions = {};
      for (const name of files) {
        const bytes = contents[name];
        descriptions[name] = { bytes: bytes.length, sha256: sha256(bytes) };
      }
      if (!isDeepStrictEqual(manifest, { schemaVersion: 1, contract: PUBLIC_CONTRACT, context, files: descriptions })) throw new Error('Missing or inconsistent public evidence manifest');
      result.invocations++;
    }
    if (result.invocations === 0) throw new Error('No projected public invocations');
    result.ok = true;
  } catch { result.errors.push('Public evidence audit rejected unknown, stale, malformed, oversized or unprojected evidence'); }
  return result;
}

function expectedContext() {
  if (process.env.GITHUB_ACTIONS === 'true') {
    if (!process.env.GITHUB_EVENT_PATH || !process.env.GITHUB_SHA || !process.env.GITHUB_RUN_ID || !process.env.GITHUB_RUN_ATTEMPT) throw new Error('Missing audit workflow context');
    let event;
    try { event = JSON.parse(readEvidenceText(process.env.GITHUB_EVENT_PATH, 4 * 1024 * 1024)); } catch { throw new Error('Invalid audit workflow context'); }
    return { headSha: event.pull_request?.head?.sha ?? process.env.GITHUB_SHA, testedSha: process.env.GITHUB_SHA, runId: process.env.GITHUB_RUN_ID, runAttempt: process.env.GITHUB_RUN_ATTEMPT };
  }
  const git = spawnSync('git', ['rev-parse', 'HEAD'], { encoding: 'utf8' });
  if (git.status !== 0) throw new Error('Cannot determine local audit revision');
  return { headSha: git.stdout.trim(), testedSha: git.stdout.trim() };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  let report = { schemaVersion: 1, contract: PUBLIC_CONTRACT, ok: false, errors: ['Public evidence audit failed'], invocations: 0 };
  const root = process.argv.length === 4 && process.argv[2] === '--report-root' ? resolve(process.argv[3]) : null;
  try {
    if (!root) throw new Error('Missing public report root');
    report = auditPublicRoot(root, expectedContext());
    if (!Number.isInteger(constants.O_NOFOLLOW) || constants.O_NOFOLLOW === 0) throw new Error('No-follow audit publication is unavailable');
    // Never follow a pre-existing audit marker symlink when replacing it.
    const fd = openSync(join(root, 'public-audit.json'), constants.O_WRONLY | constants.O_CREAT | constants.O_NOFOLLOW | constants.O_NONBLOCK, 0o600);
    try {
      if (!fstatSync(fd).isFile()) throw new Error('Invalid audit marker');
      // Descriptor-based write avoids a second path lookup; no raw input text.
      ftruncateSync(fd, 0);
      writeFileSync(fd, canonicalJson(report));
    } finally { closeSync(fd); }
  } catch { report.ok = false; }
  console.log(`Public Bazel evidence audit: ${report.ok ? 'passed' : 'failed'}`);
  process.exitCode = report.ok ? 0 : 1;
}
