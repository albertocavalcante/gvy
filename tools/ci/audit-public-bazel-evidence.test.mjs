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
  writeFileSync(join(invocation, 'context.json'), JSON.stringify(context));
  writeFileSync(join(invocation, 'bep.json'), bep);
  writeFileSync(join(invocation, 'profile.json.gz'), profile);
  writeFileSync(join(invocation, 'summary.json'), JSON.stringify(summary));
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
  writeFileSync(join(profile.invocation, 'profile.json.gz'), gzipSync(JSON.stringify({ traceEvents: [{ ph: 'X', name: 'Bazel event 1', ts: 0, dur: 1, args: { token: canary } }] })));
  profile.reseal();
  assert.equal(auditPublicRoot(profile.root).ok, false);
  const summary = fixture(t);
  const data = JSON.parse(readFileSync(join(summary.invocation, 'summary.json')));
  writeFileSync(join(summary.invocation, 'summary.json'), JSON.stringify({ ...data, rawEnv: canary }));
  summary.reseal();
  assert.equal(auditPublicRoot(summary.root).ok, false);
  const raw = fixture(t);
  writeFileSync(join(raw.invocation, 'console.log'), canary);
  assert.equal(auditPublicRoot(raw.root).ok, false);
  const link = fixture(t);
  unlinkSync(join(link.invocation, 'profile.json.gz'));
  const privateFile = join(link.dir, 'private');
  writeFileSync(privateFile, canary);
  symlinkSync(privateFile, join(link.invocation, 'profile.json.gz'));
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
  writeFileSync(join(marker.root, 'public-audit.json'), JSON.stringify({ schemaVersion: 1, contract: 'gvy-public-bazel-v1', ok: true, errors: [], invocations: 1, raw: canary }));
  assert.equal(auditPublicRoot(marker.root).ok, false);
  const status = fixture(t);
  const data = JSON.parse(readFileSync(join(status.invocation, 'summary.json')));
  data.commandExitCode = canary;
  writeFileSync(join(status.invocation, 'summary.json'), JSON.stringify(data));
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
