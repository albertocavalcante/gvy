import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, readFileSync, rmSync, symlinkSync, unlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import { gzipSync } from 'node:zlib';
import test from 'node:test';
import { auditPublicRoot, publicSummary, writePublicManifest } from './audit-public-bazel-evidence.mjs';
import { projectBep, projectProfile } from './public-bazel-evidence.mjs';

const canary = 'synthetic-unpublishable-audit-canary';
const canonicalJson = value => JSON.stringify(value, null, 2) + '\n';
const context = { schemaVersion: 1, headSha: 'a'.repeat(40), testedSha: 'b'.repeat(40), runId: '123', runAttempt: 1, invocationId: '12345678-1234-4234-8234-123456789abc', command: 'build' };
function fixture(t, code = 0) {
  const dir = mkdtempSync(join(tmpdir(), 'gvy-public-audit-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const root = join(dir, 'public');
  const invocation = join(root, `bazel-build-build-${context.invocationId}`);
  mkdirSync(invocation, { recursive: true });
  const events = [
    { id: { started: {} }, started: { uuid: context.invocationId, command: 'build', buildToolVersion: '9.0.0' } },
    { id: { buildMetadata: {} }, buildMetadata: { metadata: { HEAD_SHA: context.headSha, TESTED_SHA: context.testedSha, RUN_ID: '123', RUN_ATTEMPT: '1' } } },
    { id: { buildFinished: {} }, finished: { exitCode: { code } } },
    { id: { buildToolLogs: {} }, buildToolLogs: {}, lastMessage: true },
  ];
  events[0].children = events.slice(1).map(event => event.id);
  const bep = projectBep(events.map(event => JSON.stringify(event)).join('\n'), context);
  const profile = projectProfile(gzipSync(JSON.stringify({ traceEvents: [{ ph: 'X', name: 'phase', pid: 1, tid: 1, ts: 0, dur: 1 }] })));
  const summary = publicSummary(bep, context, { commandExitCode: code, captureExitCode: 0, commandSignal: null }, 'bazel-build', false);
  writeFileSync(join(invocation, 'context.json'), canonicalJson(context));
  writeFileSync(join(invocation, 'bep.json'), bep);
  writeFileSync(join(invocation, 'profile.json'), profile);
  writeFileSync(join(invocation, 'summary.json'), canonicalJson(summary));
  writePublicManifest(invocation, context);
  const reseal = () => { unlinkSync(join(invocation, 'public-evidence.json')); writePublicManifest(invocation, context); };
  return { dir, root, invocation, reseal };
}

test('privacy audit accepts complete projected evidence independently of the original build verdict', t => {
  for (const code of [0, 3]) {
    const f = fixture(t, code);
    const result = auditPublicRoot(f.root, { testedSha: context.testedSha });
    assert.equal(result.ok, true);
    assert.equal(result.invocations, 1);
    assert.equal(JSON.parse(readFileSync(join(f.invocation, 'summary.json'))).ok, code === 0);
    assert.equal(auditPublicRoot(f.root, { runAttempt: 2 }).ok, false);
  }
});

test('privacy audit permits genuine failed evidence for skipped target coverage', t => {
  const f = fixture(t);
  const events = readFileSync(join(f.invocation, 'bep.json'), 'utf8').trim().split('\n').map(line => JSON.parse(line));
  const skipped = { id: { targetCompleted: { label: 'skipped-target' } }, aborted: { reason: 'SKIPPED' } };
  events.splice(2, 0, skipped);
  events[0].children.push(skipped.id);
  // Reproject the entire synthetic stream so every raw string ID remains opaque.
  const bep = projectBep(events.map(event => JSON.stringify(event)).join('\n'), context);
  const summary = publicSummary(bep, context, { commandExitCode: 0, captureExitCode: 0, commandSignal: null }, 'bazel-build', false);
  assert.equal(summary.ok, false);
  assert.equal(summary.complete, true);
  assert.equal(summary.targets.skipped, 1);
  writeFileSync(join(f.invocation, 'bep.json'), bep);
  writeFileSync(join(f.invocation, 'summary.json'), canonicalJson(summary));
  f.reseal();
  assert.equal(auditPublicRoot(f.root).ok, true);
});

test('audit rejects raw command payloads even after a forged matching checksum manifest', t => {
  const f = fixture(t);
  const bep = readFileSync(join(f.invocation, 'bep.json'), 'utf8').trim().split('\n').map(line => JSON.parse(line));
  bep[0].started.optionsDescription = canary;
  writeFileSync(join(f.invocation, 'bep.json'), bep.map(event => JSON.stringify(event)).join('\n') + '\n');
  f.reseal();
  const result = auditPublicRoot(f.root);
  assert.equal(result.ok, false);
  assert.equal(JSON.stringify(result).includes(canary), false);
});

test('audit rejects raw trace args, arbitrary summary fields, unknown files and symlinks', t => {
  const profile = fixture(t);
  writeFileSync(join(profile.invocation, 'profile.json'), JSON.stringify({ traceEvents: [{ ph: 'X', name: 'Bazel event 1', ts: 0, dur: 1, args: { token: canary } }] }));
  profile.reseal();
  assert.equal(auditPublicRoot(profile.root).ok, false);
  const summary = fixture(t);
  const data = JSON.parse(readFileSync(join(summary.invocation, 'summary.json')));
  writeFileSync(join(summary.invocation, 'summary.json'), canonicalJson({ ...data, rawEnv: canary }));
  summary.reseal();
  assert.equal(auditPublicRoot(summary.root).ok, false);
  const raw = fixture(t);
  writeFileSync(join(raw.invocation, 'console.log'), canary);
  assert.equal(auditPublicRoot(raw.root).ok, false);
  const link = fixture(t);
  unlinkSync(join(link.invocation, 'profile.json'));
  const privateFile = join(link.dir, 'private');
  writeFileSync(privateFile, canary);
  symlinkSync(privateFile, join(link.invocation, 'profile.json'));
  assert.equal(auditPublicRoot(link.root).ok, false);
});

test('audit CLI binds current workflow context and publishes only a controlled safety marker', t => {
  const f = fixture(t);
  const event = join(f.dir, 'event.json');
  writeFileSync(event, JSON.stringify({ pull_request: { head: { sha: context.headSha } } }));
  const env = { ...process.env, GITHUB_ACTIONS: 'true', GITHUB_EVENT_PATH: event, GITHUB_SHA: context.testedSha, GITHUB_RUN_ID: context.runId, GITHUB_RUN_ATTEMPT: '1' };
  const run = () => spawnSync(process.execPath, [resolve('tools/ci/audit-public-bazel-evidence.mjs'), '--report-root', f.root], { env, encoding: 'utf8' });
  assert.equal(run().status, 0);
  assert.equal(JSON.parse(readFileSync(join(f.root, 'public-audit.json'))).ok, true);
  writeFileSync(join(f.root, 'unreviewed.log'), canary);
  const failed = run();
  assert.equal(failed.status, 1);
  const result = readFileSync(join(f.root, 'public-audit.json'), 'utf8');
  assert.equal(JSON.parse(result).ok, false);
  assert.equal(result.includes(canary), false);
  assert.equal(failed.stdout.includes(canary) || failed.stderr.includes(canary), false);
});

test('audit does not allow an existing unsafe safety marker or untyped status fields', t => {
  const marker = fixture(t);
  writeFileSync(join(marker.root, 'public-audit.json'), canonicalJson({ schemaVersion: 1, contract: 'gvy-public-bazel-v2', ok: true, errors: [], invocations: 1, raw: canary }));
  assert.equal(auditPublicRoot(marker.root).ok, false);
  const status = fixture(t);
  const data = JSON.parse(readFileSync(join(status.invocation, 'summary.json')));
  data.commandExitCode = canary;
  writeFileSync(join(status.invocation, 'summary.json'), canonicalJson(data));
  status.reseal();
  assert.equal(auditPublicRoot(status.root).ok, false);
});

test('a changing file cannot be validated as safe bytes but hashed as different raw bytes', t => {
  const f = fixture(t);
  const bepPath = join(f.invocation, 'bep.json');
  const safe = readFileSync(bepPath);
  const raw = safe.toString('utf8').trim().split('\n').map(line => JSON.parse(line));
  raw[0].started.optionsDescription = canary;
  writeFileSync(bepPath, raw.map(event => JSON.stringify(event)).join('\n') + '\n');
  f.reseal();
  let reads = 0;
  const changingRead = (path) => path === bepPath && ++reads === 1 ? safe : readFileSync(path);
  const result = auditPublicRoot(f.root, {}, changingRead);
  assert.equal(result.ok, false);
  assert.equal(reads, 1);
});

for (const file of ['context.json', 'summary.json', 'public-evidence.json', 'public-audit.json']) {
  for (const escapedKey of [false, true]) {
    test(`audit rejects shadowed credential bytes in ${file}${escapedKey ? ' through an escaped duplicate key' : ''}`, t => {
      const f = fixture(t);
      const path = file === 'public-audit.json' ? join(f.root, file) : join(f.invocation, file);
      if (file === 'public-audit.json') writeFileSync(path, canonicalJson({ schemaVersion: 1, contract: 'gvy-public-bazel-v2', ok: true, errors: [], invocations: 1 }));
      assert.equal(auditPublicRoot(f.root).ok, true);
      const key = file === 'context.json' ? 'headSha' : file === 'summary.json' ? 'producer' : 'contract';
      const marker = '  "' + key + '": ';
      const shadowKey = escapedKey ? '\\u' + key.charCodeAt(0).toString(16).padStart(4, '0') + key.slice(1) : key;
      const original = readFileSync(path, 'utf8');
      const modified = original.replace(marker, '  "' + shadowKey + '": ' + JSON.stringify(canary) + ',\n' + marker);
      assert.notEqual(modified, original);
      assert.deepEqual(JSON.parse(modified), JSON.parse(original));
      writeFileSync(path, modified);
      if (['context.json', 'summary.json'].includes(file)) f.reseal();
      const result = auditPublicRoot(f.root);
      assert.equal(result.ok, false);
      assert.equal(JSON.stringify(result).includes(canary), false);
    });
  }
}

test('public profile JSON hashes bind original canonical bytes without recompression', t => {
  const f = fixture(t);
  const profilePath = join(f.invocation, 'profile.json');
  const original = readFileSync(profilePath);
  assert.equal(auditPublicRoot(f.root).ok, true);
  assert.deepEqual(readFileSync(profilePath), original);
  const manifest = JSON.parse(readFileSync(join(f.invocation, 'public-evidence.json')));
  assert.equal(manifest.files['profile.json'].bytes, original.length);
});

test('public profile audit rejects gzip bytes, legacy gzip files and mixed contract versions', t => {
  const compressed = fixture(t);
  const profilePath = join(compressed.invocation, 'profile.json');
  writeFileSync(profilePath, gzipSync(readFileSync(profilePath)));
  compressed.reseal();
  assert.equal(auditPublicRoot(compressed.root).ok, false);
  const legacy = fixture(t);
  writeFileSync(join(legacy.invocation, 'profile.json.gz'), gzipSync(readFileSync(join(legacy.invocation, 'profile.json'))));
  assert.equal(auditPublicRoot(legacy.root).ok, false);
  unlinkSync(join(legacy.invocation, 'profile.json'));
  assert.equal(auditPublicRoot(legacy.root).ok, false);
  for (const file of ['summary.json', 'public-evidence.json', 'public-audit.json']) {
    const mixed = fixture(t);
    const path = file === 'public-audit.json' ? join(mixed.root, file) : join(mixed.invocation, file);
    if (file === 'public-audit.json') writeFileSync(path, canonicalJson({ schemaVersion: 1, contract: 'gvy-public-bazel-v1', ok: true, errors: [], invocations: 1 }));
    else {
      const value = JSON.parse(readFileSync(path));
      value[file === 'summary.json' ? 'publicEvidenceContract' : 'contract'] = 'gvy-public-bazel-v1';
      writeFileSync(path, canonicalJson(value));
      if (file === 'summary.json') mixed.reseal();
    }
    assert.equal(auditPublicRoot(mixed.root).ok, false);
  }
});
