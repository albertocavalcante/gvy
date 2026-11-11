import assert from 'node:assert/strict';
import { spawn, spawnSync } from 'node:child_process';
import { chmodSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import test from 'node:test';

const runner = resolve('tools/ci/run-gradle.mjs');
const sha = 'b'.repeat(40);

function fixture(t) {
  const dir = mkdtempSync(join(tmpdir(), 'gvy-run-gradle-test-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const executable = join(dir, 'gradlew');
  writeFileSync(executable, `#!${process.execPath}
import { writeFileSync } from 'node:fs';
writeFileSync('args.json', JSON.stringify(process.argv.slice(2)));
const mode = process.env.FAKE_GRADLE_MODE;
console.log('> Task :gls:compileKotlin');
if (mode === 'warning' || mode === 'failure') console.error('w: gls/src/Foo.kt:4:3 New compiler warning');
console.log('stdout evidence');
console.error('stderr evidence');
if (mode === 'invalid-encoding') process.stdout.write(Buffer.from([0xff, 0x0a]));
if (mode === 'replacement-character') console.log('    valid replacement character: \ufffd');
if (mode === 'wait') setInterval(() => {}, 1000);
else {
  if (mode !== 'truncated') console.log('BUILD ' + (mode === 'failure' ? 'FAILED' : 'SUCCESSFUL') + ' in 1s');
  process.exitCode = mode === 'failure' ? 3 : 0;
}
`);
  chmodSync(executable, 0o755);
  writeFileSync(join(dir, 'git'), `#!${process.execPath}
import { readdirSync, writeFileSync } from 'node:fs';
if (process.argv[2] === 'rev-parse') console.log('${sha}');
else if (process.argv[2] === 'status') {
  if (process.env.FAKE_CAPTURE_FAILURE) {
    for (const path of readdirSync('reports')) writeFileSync('reports/' + path + '/console.log', 'blocked capture');
  }
  console.log('');
}
else process.exitCode = 1;
`);
  chmodSync(join(dir, 'git'), 0o755);
  const env = { ...process.env, PATH: dir };
  for (const key of Object.keys(env)) if (key.startsWith('GITHUB_')) delete env[key];
  const reportRoot = join(dir, 'reports');
  const run = (args = ['lint'], extraEnv = {}) => spawnSync(process.execPath,
    [runner, '--report-root', reportRoot, '--producer', 'gradle-lint', ...args],
    { encoding: 'utf8', cwd: dir, env: { ...env, ...extraEnv } });
  const artifactDirs = () => readdirSync(reportRoot).map(name => join(reportRoot, name));
  const summaries = () => artifactDirs().map(path => JSON.parse(readFileSync(join(path, 'summary.json'), 'utf8')));
  return { dir, env, reportRoot, executable, run, artifactDirs, summaries };
}

test('wrapper captures both streams in unique reports and forces full plain warnings last', t => {
  const f = fixture(t);
  assert.equal(f.run(['lint', '--console=rich', '--warning-mode=none', '-Dorg.gradle.deprecation.trace=false']).status, 0);
  assert.equal(f.run().status, 0);
  assert.equal(f.artifactDirs().length, 2);
  const args = JSON.parse(readFileSync(join(f.dir, 'args.json'), 'utf8'));
  assert.deepEqual(args.slice(-3), ['--console=plain', '--warning-mode=all', '-Dorg.gradle.deprecation.trace=true']);
  for (const path of f.artifactDirs()) {
    const summary = JSON.parse(readFileSync(join(path, 'summary.json'), 'utf8'));
    assert.equal(summary.complete, true);
    assert.equal(summary.ok, true);
    assert.equal(summary.context.headSha, sha);
    assert.equal(summary.context.testedSha, sha);
    assert.equal(summary.context.command, 'gradle');
    assert.match(summary.context.runId, /^local-/);
    assert.match(readFileSync(join(path, 'stdout.log'), 'utf8'), /stdout evidence/);
    assert.match(readFileSync(join(path, 'stderr.log'), 'utf8'), /stderr evidence/);
    assert.match(readFileSync(join(path, 'raw.log'), 'utf8'), /stdout evidence/);
    assert.match(readFileSync(join(path, 'raw.log'), 'utf8'), /stderr evidence/);
    assert.deepEqual(readFileSync(join(path, 'console.log')), readFileSync(join(path, 'raw.log')));
  }
});

test('a zero-exit warning fails and original command failure is retained with its warning', t => {
  for (const mode of ['warning', 'failure', 'truncated']) {
    const f = fixture(t);
    const result = f.run(['test'], { FAKE_GRADLE_MODE: mode });
    assert.equal(result.status, mode === 'failure' ? 3 : 1);
    const summary = f.summaries()[0];
    assert.equal(summary.ok, false);
    if (mode === 'truncated') assert.equal(summary.complete, false);
    else assert.deepEqual(summary.unexpected, ['[task :gls:compileKotlin] w: gls/src/Foo.kt:4:3 New compiler warning']);
  }
});

test('GitHub identity keeps PR source and tested merge commits separate, without exposing environment or args', t => {
  const f = fixture(t);
  const event = join(f.dir, 'event.json');
  const headSha = 'a'.repeat(40);
  writeFileSync(event, JSON.stringify({ pull_request: { head: { sha: headSha } } }));
  const env = { GITHUB_ACTIONS: 'true', GITHUB_SHA: sha, GITHUB_EVENT_PATH: event, GITHUB_RUN_ID: '123', GITHUB_RUN_ATTEMPT: '2', PRIVATE_VALUE: 'DO-NOT-EMIT' };
  assert.equal(f.run(['lint', '-Pcredential=DO-NOT-EMIT'], env).status, 0);
  const summary = f.summaries()[0];
  assert.equal(summary.context.headSha, headSha);
  assert.equal(summary.context.testedSha, sha);
  assert.equal(summary.context.runAttempt, 2);
  assert.equal(summary.context.runId, '123');
  assert.doesNotMatch(JSON.stringify(summary), /DO-NOT-EMIT/);
  for (const extra of [{ GITHUB_SHA: 'c'.repeat(40) }, { GITHUB_RUN_ATTEMPT: '0' },
    { GITHUB_EVENT_PATH: join(f.dir, 'absent.json') }]) {
    assert.notEqual(f.run(['lint'], { ...env, ...extra }).status, 0);
  }
  assert.ok(f.summaries().filter(value => !value.ok).every(value => !value.complete));
});

test('missing executable and report write failure cannot produce successful evidence', t => {
  const f = fixture(t);
  writeFileSync(f.executable, '#!/unavailable-interpreter\n');
  const result = f.run();
  assert.equal(result.status, 127);
  assert.equal(f.summaries()[0].commandExitCode, 127);
  assert.equal(f.summaries()[0].complete, false);
  const blocked = join(f.dir, 'blocked');
  writeFileSync(blocked, 'file instead of directory');
  const noReport = spawnSync(process.execPath, [runner, '--report-root', blocked, '--producer', 'gradle-lint', 'lint'], {
    cwd: f.dir, encoding: 'utf8', env: f.env,
  });
  assert.notEqual(noReport.status, 0);
});

test('a failed capture sink is retained as a failed producer even when Gradle exits zero', t => {
  const f = fixture(t);
  const result = f.run(['lint'], { FAKE_CAPTURE_FAILURE: '1' });
  assert.notEqual(result.status, 0);
  const summary = f.summaries()[0];
  assert.equal(summary.commandExitCode, 0);
  assert.equal(summary.captureExitCode, 1);
  assert.equal(summary.complete, false);
  assert.equal(summary.ok, false);
});

test('wrapper validates original UTF-8 bytes without rejecting a legitimate replacement character', t => {
  for (const mode of ['invalid-encoding', 'replacement-character']) {
    const f = fixture(t);
    const result = f.run(['lint'], { FAKE_GRADLE_MODE: mode });
    const summary = f.summaries()[0];
    assert.equal(result.status, mode === 'invalid-encoding' ? 1 : 0);
    assert.equal(summary.complete, mode !== 'invalid-encoding');
    assert.equal(summary.ok, mode !== 'invalid-encoding');
    if (mode === 'invalid-encoding') {
      assert.ok(readFileSync(join(f.artifactDirs()[0], 'raw.log')).includes(0xff));
      assert.match(summary.errors.join(' '), /encoding/);
    }
  }
});

test('termination reaches only the wrapper child and records the interrupted status', async t => {
  const f = fixture(t);
  const child = spawn(process.execPath, [runner, '--report-root', f.reportRoot, '--producer', 'gradle-lint', 'test'], {
    cwd: f.dir, env: { ...f.env, FAKE_GRADLE_MODE: 'wait' }, stdio: ['ignore', 'pipe', 'pipe'],
  });
  t.after(() => { if (child.exitCode === null) child.kill('SIGTERM'); });
  const closed = new Promise(resolve => child.on('close', code => resolve(code)));
  await new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error('Gradle child did not start')), 10000);
    child.stdout.once('data', () => { clearTimeout(timeout); resolve(); });
  });
  child.kill('SIGTERM');
  assert.equal(await closed, 143);
  const summary = f.summaries()[0];
  assert.equal(summary.commandSignal, 'SIGTERM');
  assert.equal(summary.commandExitCode, 143);
  assert.equal(summary.ok, false);
});
