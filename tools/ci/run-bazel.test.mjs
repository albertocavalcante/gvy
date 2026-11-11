import assert from 'node:assert/strict';
import { chmodSync, mkdtempSync, readFileSync, readdirSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { spawn, spawnSync } from 'node:child_process';
import test from 'node:test';

const runner = resolve('tools/ci/run-bazel.mjs');
const sha = spawnSync('git', ['rev-parse', 'HEAD'], { encoding: 'utf8' }).stdout.trim();

function fixture(t) {
  const dir = mkdtempSync(join(tmpdir(), 'gvy-run-bazel-test-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const binary = join(dir, 'bazel');
  const gitPath = spawnSync('which', ['git'], { encoding: 'utf8' }).stdout.trim();
  symlinkSync(gitPath, join(dir, 'git'));
  writeFileSync(binary, `#!${process.execPath}
import { writeFileSync } from 'node:fs';
const args = process.argv.slice(2);
const value = flag => args.filter(arg => arg.startsWith(flag + '=')).at(-1)?.slice(flag.length + 1);
const id = value('--invocation_id');
const command = args.find(arg => ['build', 'test'].includes(arg));
const metadata = Object.fromEntries(args.filter(arg => arg.startsWith('--build_metadata=')).map(arg => {
  const assignment = arg.slice('--build_metadata='.length); const index = assignment.indexOf('=');
  return [assignment.slice(0, index), assignment.slice(index + 1)];
}));
const mode = process.env.FAKE_BAZEL_MODE;
const code = mode === 'failure' ? 3 : 0;
const events = [
  { id: { started: {} }, started: { uuid: id, command, buildToolVersion: '9.0.0' } },
  { id: { buildMetadata: {} }, buildMetadata: { metadata } },
  { id: { testSummary: { label: '//common:test' } }, testSummary: { overallStatus: code ? 'FAILED' : 'PASSED', totalRunCount: 1 } },
  { id: { buildFinished: {} }, finished: { exitCode: { code } } },
  { id: { buildToolLogs: {} }, buildToolLogs: {}, lastMessage: true },
];
events[0].children = events.slice(1).map(event => event.id);
writeFileSync(value('--build_event_json_file'), mode === 'truncated' ? '{' : events.map(event => JSON.stringify(event)).join('\\n') + '\\n');
if (mode !== 'missing-profile') writeFileSync(value('--profile'), 'nonempty trace');
writeFileSync(process.env.FAKE_ARGS_PATH, JSON.stringify(args));
console.log('stdout evidence'); console.error('stderr evidence');
process.exitCode = code;
if (mode === 'wait-for-signal') setInterval(() => {}, 1000);
`);
  chmodSync(binary, 0o755);
  // An absent fake executable must never fall through to the installed Bazel.
  const env = { ...process.env, PATH: dir, FAKE_ARGS_PATH: join(dir, 'args.json') };
  for (const key of Object.keys(env)) if (key.startsWith('GITHUB_')) delete env[key];
  const reportRoot = join(dir, 'reports');
  const run = (args = [], extraEnv = {}) => spawnSync(process.execPath, [runner, '--report-root', reportRoot, '--producer', 'bazel-test', ...args], { encoding: 'utf8', env: { ...env, ...extraEnv } });
  const artifactDirs = () => readdirSync(reportRoot).map(name => join(reportRoot, name));
  const summary = () => JSON.parse(readFileSync(join(artifactDirs().at(-1), 'summary.json'), 'utf8'));
  return { dir, env, run, summary, artifactDirs };
}

test('wrapper creates unique local evidence, preserves both streams and forces identity/remote-off last', t => {
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
  const artifactDir = f.artifactDirs()[0];
  assert.match(readFileSync(join(artifactDir, 'stdout.log'), 'utf8'), /stdout evidence/);
  assert.match(readFileSync(join(artifactDir, 'stderr.log'), 'utf8'), /stderr evidence/);
  assert.match(readFileSync(join(artifactDir, 'console.log'), 'utf8'), /stdout evidence/);
  assert.match(readFileSync(join(artifactDir, 'console.log'), 'utf8'), /stderr evidence/);
  assert.equal(f.run(['build', '//common:common']).status, 0);
  assert.equal(f.artifactDirs().length, 2);
});

test('GitHub context distinguishes PR head from tested merge SHA and rejects a stale checkout', t => {
  const f = fixture(t);
  const event = join(f.dir, 'event.json');
  const headSha = 'a'.repeat(40);
  writeFileSync(event, JSON.stringify({ pull_request: { head: { sha: headSha } } }));
  const env = { GITHUB_EVENT_PATH: event, GITHUB_SHA: sha, GITHUB_RUN_ID: '123', GITHUB_RUN_ATTEMPT: '2' };
  assert.equal(f.run(['test'], env).status, 0);
  const summary = f.summary();
  assert.equal(summary.context.headSha, headSha);
  assert.equal(summary.context.testedSha, sha);
  assert.equal(summary.context.runId, '123');
  assert.equal(summary.context.runAttempt, 2);
  assert.notEqual(f.run(['test'], { ...env, GITHUB_SHA: 'b'.repeat(40) }).status, 0);
  const summaries = f.artifactDirs().map(dir => JSON.parse(readFileSync(join(dir, 'summary.json'), 'utf8')));
  assert.ok(summaries.some(value => value.errors.some(error => /checkout/i.test(error))));
});

test('wrapper preserves original failure status and rejects truncated or missing artifacts', t => {
  for (const mode of ['failure', 'truncated', 'missing-profile']) {
    const f = fixture(t);
    const result = f.run(['test'], { FAKE_BAZEL_MODE: mode });
    assert.notEqual(result.status, 0, mode);
    assert.equal(f.summary().ok, false);
    if (mode === 'failure') { assert.equal(result.status, 3); assert.equal(f.summary().commandExitCode, 3); }
  }
});

test('wrapper rejects invalid invocation/startup config and records a failed producer summary', t => {
  const f = fixture(t);
  assert.notEqual(f.run(['--startup-arg', '--host_jvm_args=-Xmx512m;echo danger', 'test']).status, 0);
  assert.notEqual(f.run(['--startup-arg', '--bazelrc=/untrusted', 'test']).status, 0);
  assert.notEqual(f.run(['test'], { GITHUB_RUN_ATTEMPT: '0' }).status, 0);
  assert.notEqual(f.run(['test'], { GITHUB_EVENT_PATH: join(f.dir, 'missing.json') }).status, 0);
  assert.ok(f.artifactDirs().length >= 2);
});

test('missing Bazel executable is a failed invocation with a retained summary', t => {
  const f = fixture(t);
  writeFileSync(join(f.dir, 'bazel'), '#!/unavailable-interpreter\n');
  const result = f.run(['test']);
  assert.equal(result.status, 127);
  assert.equal(f.summary().commandExitCode, 127);
  assert.equal(f.summary().complete, false);
});

test('wrapper forwards termination to its own Bazel child and preserves the interrupted status', async t => {
  const f = fixture(t);
  const reportRoot = join(f.dir, 'reports');
  const child = spawn(process.execPath, [runner, '--report-root', reportRoot, '--producer', 'bazel-test', 'test'], {
    env: { ...f.env, FAKE_BAZEL_MODE: 'wait-for-signal' }, stdio: ['ignore', 'pipe', 'pipe'],
  });
  t.after(() => { if (child.exitCode === null) child.kill('SIGTERM'); });
  const closed = new Promise(resolve => child.on('close', code => resolve(code)));
  await new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error('Bazel child did not start')), 10000);
    child.stdout.once('data', () => { clearTimeout(timeout); resolve(); });
  });
  child.kill('SIGTERM');
  assert.equal(await closed, 143);
  assert.equal(f.summary().commandSignal, 'SIGTERM');
  assert.equal(f.summary().ok, false);
});
