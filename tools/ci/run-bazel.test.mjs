import assert from 'node:assert/strict';
import { chmodSync, mkdtempSync, readFileSync, readdirSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { spawn, spawnSync } from 'node:child_process';
import { gunzipSync } from 'node:zlib';
import test from 'node:test';
import { auditPublicRoot } from './audit-public-bazel-evidence.mjs';

const runner = resolve('tools/ci/run-bazel.mjs');
const sha = spawnSync('git', ['rev-parse', 'HEAD'], { encoding: 'utf8' }).stdout.trim();
const canary = 'synthetic-publish-privacy-canary';

function fixture(t) {
  const dir = mkdtempSync(join(tmpdir(), 'gvy-run-bazel-test-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const binary = join(dir, 'bazel');
  const gitPath = spawnSync('which', ['git'], { encoding: 'utf8' }).stdout.trim();
  symlinkSync(gitPath, join(dir, 'git'));
  writeFileSync(binary, `#!${process.execPath}
import { writeFileSync, readFileSync, truncateSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { gzipSync } from 'node:zlib';
const fixture = dirname(fileURLToPath(import.meta.url));
const { mode, canary } = JSON.parse(readFileSync(join(fixture, 'fixture.json'), 'utf8'));
const args = process.argv.slice(2);
const value = flag => args.filter(arg => arg.startsWith(flag + '=')).at(-1)?.slice(flag.length + 1);
const id = value('--invocation_id');
const command = args.find(arg => ['build', 'test'].includes(arg));
const metadata = Object.fromEntries(args.filter(arg => arg.startsWith('--build_metadata=')).map(arg => {
  const assignment = arg.slice('--build_metadata='.length); const index = assignment.indexOf('=');
  return [assignment.slice(0, index), assignment.slice(index + 1)];
}));
metadata.EXTRA = canary;
const code = mode === 'failure' ? 3 : 0;
const events = [
  { id: { started: {} }, started: { uuid: id, command, buildToolVersion: '9.0.0', optionsDescription: canary, host: canary, user: canary } },
  { id: { buildMetadata: {} }, buildMetadata: { metadata } },
  { id: { unstructuredCommandLine: {} }, unstructuredCommandLine: { args: [canary] } },
  { id: { structuredCommandLine: { commandLineLabel: 'original' } }, structuredCommandLine: { sections: [{ options: [canary] }] } },
  { id: { optionsParsed: {} }, optionsParsed: { cmdLine: [canary] } },
  { id: { actionCompleted: { primaryOutput: 'out' } }, action: { success: !code, type: 'KotlinCompile', commandLine: [canary] } },
  { id: { testSummary: { label: '//common:test' } }, testSummary: { overallStatus: code ? 'FAILED' : 'PASSED', totalRunCount: 1 } },
  { id: { buildFinished: {} }, finished: { exitCode: { code } } },
  { id: { buildToolLogs: {} }, buildToolLogs: { log: [{ contents: canary }] }, lastMessage: true },
];
events[0].children = events.slice(1).map(event => event.id);
writeFileSync(value('--build_event_json_file'), mode === 'truncated' ? '{' + canary : events.map(event => JSON.stringify(event)).join('\\n') + '\\n');
if (mode === 'oversized') truncateSync(value('--build_event_json_file'), 256 * 1024 * 1024 + 1);
if (mode !== 'missing-profile') writeFileSync(value('--profile'), mode === 'malformed-profile' ? canary : gzipSync(JSON.stringify({ otherData: { secret: canary }, traceEvents: [{ ph: 'X', name: canary, ts: -1, dur: 2, pid: 1, tid: 1, args: { token: canary } }] })));
writeFileSync(join(fixture, 'args.json'), JSON.stringify(args));
writeFileSync(join(fixture, 'child-env.json'), JSON.stringify(process.env));
console.log('stdout evidence ' + canary); console.error('stderr evidence ' + canary);
process.exitCode = code;
if (mode === 'wait-for-signal') setInterval(() => {}, 1000);
`);
  chmodSync(binary, 0o755);
  // The fake child reads controls from a fixture file, never an inherited env.
  const env = { ...process.env, PATH: dir, BAZELISK_GITHUB_TOKEN: canary, GH_TOKEN: canary, UNRELATED_SECRET: canary };
  for (const key of Object.keys(env)) if (key.startsWith('GITHUB_')) delete env[key];
  const reportRoot = join(dir, 'reports');
  const privateRoot = join(dir, 'raw-private');
  const configure = mode => writeFileSync(join(dir, 'fixture.json'), JSON.stringify({ mode, canary }));
  configure('normal');
  const args = extra => [runner, '--report-root', reportRoot, '--private-report-root', privateRoot, '--producer', 'bazel-test', ...extra];
  const run = (extra = [], extraEnv = {}) => spawnSync(process.execPath, args(extra), { encoding: 'utf8', env: { ...env, ...extraEnv } });
  const artifactDirs = () => readdirSync(reportRoot).map(name => join(reportRoot, name));
  const summary = () => JSON.parse(readFileSync(join(artifactDirs().at(-1), 'summary.json'), 'utf8'));
  return { dir, env, run, summary, artifactDirs, reportRoot, privateRoot, configure, args };
}

function assertPublicNoCanary(root) {
  for (const entry of readdirSync(root, { withFileTypes: true })) {
    const path = join(root, entry.name);
    if (entry.isDirectory()) assertPublicNoCanary(path);
    else {
      const bytes = readFileSync(path);
      assert.equal((entry.name.endsWith('.gz') ? gunzipSync(bytes) : bytes).includes(Buffer.from(canary)), false);
    }
  }
}

test('wrapper separates private capture, clears credentials and only publishes audited allowlisted evidence', t => {
  const f = fixture(t);
  const args = ['test', '//common:test', '--bes_backend=grpcs://unwanted.invalid', '--invocation_id=wrong', '--', '//other:test'];
  assert.equal(f.run(args).status, 0);
  const first = f.summary();
  assert.equal(first.context.headSha, sha);
  assert.equal(first.context.testedSha, sha);
  assert.match(first.context.runId, /^local-/);
  assert.equal(first.remoteTelemetry, 'disabled-by-owner');
  const emitted = JSON.parse(readFileSync(join(f.dir, 'args.json'), 'utf8'));
  assert.ok(emitted.indexOf('--nohome_rc') < emitted.indexOf('test'));
  assert.equal(emitted.filter(arg => arg.startsWith('--bes_backend=')).at(-1), '--bes_backend=');
  assert.equal(emitted.filter(arg => arg.startsWith('--invocation_id=')).at(-1), `--invocation_id=${first.context.invocationId}`);
  assert.ok(emitted.indexOf(`--invocation_id=${first.context.invocationId}`) < emitted.indexOf('--'));
  assert.equal(readFileSync(join(f.dir, 'child-env.json'), 'utf8').includes(canary), false);
  const invocationName = f.artifactDirs()[0].split('/').at(-1);
  const privateDir = join(f.privateRoot, invocationName);
  assert.equal(readFileSync(join(privateDir, 'console.log'), 'utf8').includes(canary), true);
  assert.equal(readFileSync(join(privateDir, 'bep.json'), 'utf8').includes(canary), true);
  assert.deepEqual(readdirSync(f.artifactDirs()[0]).sort(), ['bep.json', 'context.json', 'profile.json', 'public-evidence.json', 'summary.json']);
  assertPublicNoCanary(f.reportRoot);
  assert.equal(auditPublicRoot(f.reportRoot, { testedSha: sha }).ok, true);
  assert.equal(f.run(['build', '//common:common']).status, 0);
  assert.equal(f.artifactDirs().length, 2);
  assertPublicNoCanary(f.reportRoot);
});

test('GitHub context distinguishes source head and checkout SHA before the child environment clears it', t => {
  const f = fixture(t);
  const event = join(f.dir, 'event.json');
  const headSha = 'a'.repeat(40);
  writeFileSync(event, JSON.stringify({ pull_request: { head: { sha: headSha } } }));
  const env = { GITHUB_ACTIONS: 'true', GITHUB_EVENT_PATH: event, GITHUB_SHA: sha, GITHUB_RUN_ID: '123', GITHUB_RUN_ATTEMPT: '2' };
  assert.equal(f.run(['test'], env).status, 0);
  assert.equal(f.summary().context.headSha, headSha);
  assert.equal(f.summary().context.testedSha, sha);
  assert.equal(f.summary().context.runAttempt, 2);
  const childEnv = JSON.parse(readFileSync(join(f.dir, 'child-env.json'), 'utf8'));
  assert.equal(Object.keys(childEnv).some(key => key.startsWith('GITHUB_')), false);
  assert.equal(auditPublicRoot(f.reportRoot, { headSha, testedSha: sha, runId: '123', runAttempt: 2 }).ok, true);
  assert.equal(auditPublicRoot(f.reportRoot, { headSha, testedSha: sha, runId: '123', runAttempt: 1 }).ok, false);
  assert.notEqual(f.run(['test'], { ...env, GITHUB_SHA: 'b'.repeat(40) }).status, 0);
});

test('original build failures retain public outcomes while malformed or oversized evidence never gains a publication marker', t => {
  for (const mode of ['failure', 'truncated', 'missing-profile', 'malformed-profile', 'oversized']) {
    const f = fixture(t);
    f.configure(mode);
    const result = f.run(['test']);
    assert.notEqual(result.status, 0, mode);
    assert.equal(f.summary().ok, false);
    assertPublicNoCanary(f.reportRoot);
    if (mode === 'failure') {
      assert.equal(result.status, 3);
      assert.equal(f.summary().commandExitCode, 3);
      assert.equal(f.summary().complete, true);
      assert.equal(auditPublicRoot(f.reportRoot).ok, true);
    } else assert.equal(auditPublicRoot(f.reportRoot).ok, false);
  }
});

test('configuration errors and missing executables fail safely without publishing raw errors', t => {
  const f = fixture(t);
  assert.notEqual(f.run(['--startup-arg', '--host_jvm_args=-Xmx512m;echo danger', 'test']).status, 0);
  assert.notEqual(f.run(['--startup-arg', '--bazelrc=/untrusted', 'test']).status, 0);
  assert.notEqual(f.run(['test'], { GITHUB_RUN_ATTEMPT: '0' }).status, 0);
  assert.notEqual(f.run(['test'], { GITHUB_ACTIONS: 'true' }).status, 0);
  assert.notEqual(f.run(['test'], { GITHUB_EVENT_PATH: join(f.dir, 'missing.json') }).status, 0);
  writeFileSync(join(f.dir, 'bazel'), '#!/unavailable-interpreter\n');
  assert.equal(f.run(['test']).status, 127);
  assertPublicNoCanary(f.reportRoot);
});

test('wrapper forwards termination only to its own child and retains failed interrupted evidence', async t => {
  const f = fixture(t);
  f.configure('wait-for-signal');
  const child = spawn(process.execPath, f.args(['test']), { env: f.env, stdio: ['ignore', 'pipe', 'pipe'] });
  t.after(() => { if (child.exitCode === null) child.kill('SIGTERM'); });
  const closed = new Promise(resolve => child.on('close', code => resolve(code)));
  await new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error('Fake Bazel child did not start')), 10000);
    child.stdout.once('data', () => { clearTimeout(timeout); resolve(); });
  });
  child.kill('SIGTERM');
  assert.equal(await closed, 143);
  assert.equal(f.summary().commandSignal, 'SIGTERM');
  assert.equal(f.summary().ok, false);
  assertPublicNoCanary(f.reportRoot);
});

test('local default private root is a disjoint sibling and nested roots are rejected', t => {
  const f = fixture(t);
  const baseArgs = [runner, '--report-root', f.reportRoot, '--producer', 'bazel-test', 'test'];
  const local = spawnSync(process.execPath, baseArgs, { env: f.env, encoding: 'utf8' });
  assert.equal(local.status, 0);
  assert.equal(readdirSync(f.reportRoot + '-private').length, 1);
  assertPublicNoCanary(f.reportRoot);
  const nested = spawnSync(process.execPath, [runner, '--report-root', f.reportRoot, '--private-report-root', join(f.reportRoot, 'raw'), '--producer', 'bazel-test', 'test'], { env: f.env, encoding: 'utf8' });
  assert.equal(nested.status, 2);
  const ci = spawnSync(process.execPath, baseArgs, { env: { ...f.env, GITHUB_ACTIONS: 'true' }, encoding: 'utf8' });
  assert.equal(ci.status, 2);
});
