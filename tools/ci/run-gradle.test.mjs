// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import assert from 'node:assert/strict';
import { spawn, spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { chmodSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, realpathSync, rmSync, writeFileSync } from 'node:fs';
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
if (process.argv[2] === 'rev-parse' || (process.argv[2] === '-C' && process.argv[4] === 'rev-parse')) console.log('${sha}');
else if (process.argv[2] === 'status') {
  if (process.env.FAKE_CAPTURE_FAILURE) {
    for (const path of readdirSync('reports')) writeFileSync('reports/' + path + '/console.log', 'blocked capture');
  }
  console.log('');
}
else process.exitCode = 1;
`);
  chmodSync(join(dir, 'git'), 0o755);
  writeFileSync(join(dir, 'gradle.properties'), 'org.gradle.jvmargs=-Xms512m -Xmx3g -XX:MaxMetaspaceSize=2g\n');
  const env = { ...process.env, PATH: dir };
  for (const key of Object.keys(env)) if (key.startsWith('GITHUB_')) delete env[key];
  delete env.GVY_GRADLE_MEMORY_PROBE;
  delete env.GVY_GRADLE_MEMORY_PROFILE;
  const reportRoot = join(dir, 'reports');
  const run = (args = ['lint'], extraEnv = {}) => spawnSync(process.execPath,
    [runner, '--report-root', reportRoot, '--producer', 'gradle-lint', ...args],
    { encoding: 'utf8', cwd: dir, env: { ...env, ...extraEnv }, timeout: 10000 });
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

for (const scenario of [
  { name: 'no warning', warning: false, status: 0 },
  { name: 'one eligible warning', warning: true, status: 0 },
  { name: 'missing proof', warning: true, status: 1, failure: 'FAKE_NO_PROOF' },
  { name: 'occupied verdict', warning: true, status: 1, failure: 'FAKE_OCCUPIED_VERDICT' },
  { name: 'other warning', warning: true, status: 1, failure: 'FAKE_EXTRA_WARNING' },
  { name: 'original exit seven', warning: true, status: 7, failure: 'FAKE_EXIT_7' },
]) {
  test(`Native wrapper verifies synthetic ${scenario.name} before acceptance`, t => {
    const { warning } = scenario;
    const host = process.platform === 'darwin' && process.arch === 'arm64' ? ['macosArm64', 'macos-arm64', 'macos-aarch64'] :
      process.platform === 'linux' && process.arch === 'x64' ? ['linuxX64', 'linux-x64', 'linux-x86_64'] : null;
    if (!host) return t.skip('Supported Native wrapper host required');
    const f = fixture(t);
    const temp = realpathSync(mkdtempSync(join(tmpdir(), 'gvy-native-wrapper-')));
    t.after(() => rmSync(temp, { recursive: true, force: true }));
    mkdirSync(join(f.dir, 'gradle'));
    writeFileSync(join(f.dir, 'gradle', 'libs.versions.toml'), '[versions]\nkotlin = "2.3.0"\n');
    const event = join(f.dir, 'event.json');
    writeFileSync(event, '{}');
    writeFileSync(f.executable, `#!${process.execPath}
import { mkdirSync, readdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
const data = process.env.KONAN_DATA_DIR;
writeFileSync('child.json', JSON.stringify({ data, args: process.argv.slice(2) }));
const home = join(data, 'kotlin-native-prebuilt-${host[2]}-2.3.0');
mkdirSync(join(home, 'bin'), { recursive: true });
mkdirSync(join(home, 'konan', 'lib'), { recursive: true });
if (!process.env.FAKE_NO_PROOF) writeFileSync(join(home, 'provisioned.ok'), '');
for (const name of ['konanc', 'kotlinc-native']) writeFileSync(join(home, 'bin', name), 'fixture', { mode: 0o755 });
writeFileSync(join(home, 'konan', 'konan.properties'), 'fixture');
writeFileSync(join(home, 'konan', 'lib', 'kotlin-native-compiler-embeddable.jar'), 'fixture');
if (process.env.FAKE_OCCUPIED_VERDICT) {
  const report = join(process.env.FAKE_REPORT_ROOT, readdirSync(process.env.FAKE_REPORT_ROOT)[0]);
  writeFileSync(join(report, 'native-provisioning-final-verdict.json'), 'occupied');
}
if (${warning}) console.log('Kotlin/Native bundle directory ' + home + ' is not empty. Native bundle files will be overwritten.');
if (process.env.FAKE_EXTRA_WARNING) console.log('warning: unexpected failure');
console.log('BUILD ' + (process.env.FAKE_EXIT_7 ? 'FAILED' : 'SUCCESSFUL') + ' in 1s');
if (process.env.FAKE_EXIT_7) process.exitCode = 7;
`);
    chmodSync(f.executable, 0o755);
    const env = { ...f.env, RUNNER_TEMP: temp, GITHUB_SHA: sha, GITHUB_EVENT_PATH: event,
      GITHUB_RUN_ID: '123', GITHUB_RUN_ATTEMPT: '1' };
    if (scenario.failure) env[scenario.failure] = '1';
    const reportRoot = join(temp, 'reports');
    env.FAKE_REPORT_ROOT = reportRoot;
    const result = spawnSync(process.execPath, [runner, '--report-root', reportRoot, '--producer', `launcher-${host[1]}`,
      '--native-provisioning', host[0], ':launcher:test'], { cwd: f.dir, env, encoding: 'utf8', timeout: 10000 });
    assert.equal(result.status, scenario.status, result.stderr);
    const dir = join(reportRoot, readdirSync(reportRoot)[0]);
    const report = JSON.parse(readFileSync(join(dir, 'diagnostics.json'), 'utf8'));
    const child = JSON.parse(readFileSync(join(f.dir, 'child.json'), 'utf8'));
    if (scenario.failure) {
      assert.equal(report.ok, false);
      assert.equal(report.nativeProvisioning.complete, false);
      assert.equal(report.accepted.filter(entry => entry.requiresProof).length, 0);
      assert.ok(report.unexpected.some(entry => entry.includes('kotlin-native-prebuilt')));
      return;
    }
    const preliminary = JSON.parse(readFileSync(join(dir, 'native-provisioning-unapproved-diagnostics.json'), 'utf8'));
    const proof = JSON.parse(readFileSync(join(dir, 'native-provisioning-verification.json'), 'utf8'));
    const verdict = JSON.parse(readFileSync(join(dir, 'native-provisioning-final-verdict.json'), 'utf8'));
    assert.equal(report.ok, true);
    assert.equal(report.nativeProvisioning.complete, true);
    assert.equal(report.accepted.filter(entry => entry.requiresProof).length, Number(warning));
    assert.equal(preliminary.unexpected.length, Number(warning));
    assert.equal(proof.preliminaryDiagnosticsSha256, createHash('sha256').update(readFileSync(join(dir, 'native-provisioning-unapproved-diagnostics.json'))).digest('hex'));
    assert.equal(verdict.diagnosticsSha256, createHash('sha256').update(readFileSync(join(dir, 'diagnostics.json'))).digest('hex'));
    assert.equal(verdict.proofSha256, createHash('sha256').update(readFileSync(join(dir, 'native-provisioning-verification.json'))).digest('hex'));
    assert.equal(child.data, proof.konanDataDir);
    assert.equal(child.args.filter(arg => arg.startsWith('-Pkonan.data.dir=')).length, 1);
    assert.ok(child.args.includes(`-Pkonan.data.dir=${child.data}`));
    assert.deepEqual(child.args.slice(-3), ['--console=plain', '--warning-mode=all', '-Dorg.gradle.deprecation.trace=true']);
  });
}

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

test('ordinary wrapper never accepts the conditional Native warning', t => {
  const f = fixture(t);
  writeFileSync(f.executable, `#!${process.execPath}
console.log('Kotlin/Native bundle directory /tmp/.konan/kotlin-native-prebuilt-linux-x86_64-2.3.0 is not empty. Native bundle files will be overwritten.');
console.log('BUILD SUCCESSFUL in 1s');
`);
  chmodSync(f.executable, 0o755);
  assert.equal(f.run().status, 1);
  const report = f.summaries()[0];
  assert.equal(report.unexpected.length, 1);
  assert.equal(report.accepted.length, 0);
  assert.equal(report.exceptions.filter(entry => entry.requiresProof).length, 3);
});

test('Native wrapper rejects malformed options before starting the child', t => {
  const f = fixture(t);
  for (const options of [
    ['--native-provisioning'], ['--native-provisioning', '--producer'],
    ['--native-provisioning', 'linuxX64', '--native-provisioning', 'linuxX64', 'test'],
    ['test', '--native-provisioning', 'linuxX64'],
    ['--native-provisioning', 'linuxX64', 'test', '-Pkonan.data.dir=/other'],
  ]) {
    const result = spawnSync(process.execPath, [runner, '--report-root', f.reportRoot, '--producer', 'launcher-linux-x64', ...options],
      { encoding: 'utf8', cwd: f.dir, env: f.env, timeout: 10000 });
    assert.equal(result.status, 2);
    assert.throws(() => readFileSync(join(f.dir, 'args.json')));
  }
});

test('Native provisioning rejects numeric memory profiles before starting Gradle', t => {
  for (const setting of [{ GVY_GRADLE_MEMORY_PROBE: 'true' }, { GVY_GRADLE_MEMORY_PROFILE: 'nightly' }]) {
    const f = fixture(t);
    const result = spawnSync(process.execPath, [runner, '--report-root', f.reportRoot, '--producer', 'launcher-linux-x64',
      '--native-provisioning', 'linuxX64', 'test'], { cwd: f.dir, env: { ...f.env, ...setting }, encoding: 'utf8', timeout: 10000 });
    assert.notEqual(result.status, 0);
    assert.throws(() => readFileSync(join(f.dir, 'args.json')));
    assert.match(f.summaries()[0].errors.join(' '), /does not support numeric memory observation/);
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

for (const profile of [undefined, 'nightly']) {
  test(`memory opt-in (${profile ?? 'default Main'}) fails closed on unsupported runners and short producers`, t => {
    const f = fixture(t);
    const event = join(f.dir, 'event.json');
    const privateTemp = mkdtempSync(join(tmpdir(), 'gvy-memory-runner-temp-'));
    t.after(() => rmSync(privateTemp, { recursive: true, force: true }));
    writeFileSync(event, JSON.stringify({ pull_request: { head: { sha: 'a'.repeat(40) } } }));
    const env = { GVY_GRADLE_MEMORY_PROBE: 'true', GITHUB_ACTIONS: 'true', GITHUB_SHA: sha,
      GITHUB_EVENT_PATH: event, GITHUB_RUN_ID: '123', GITHUB_RUN_ATTEMPT: '1', GITHUB_JOB: 'build',
      RUNNER_OS: 'Linux', RUNNER_ENVIRONMENT: 'github-hosted', RUNNER_TEMP: privateTemp };
    if (profile !== undefined) env.GVY_GRADLE_MEMORY_PROFILE = profile;
    const result = f.run(['test'], env);
    assert.equal(result.signal, null, 'a stopped discovery loop must not wait out its full timeout');
    assert.notEqual(result.status, 0);
    const summary = f.summaries()[0];
    assert.equal(summary.memoryProbe.required, true);
    assert.equal(summary.memoryProbe.complete, false);
    assert.equal(summary.complete, false);
    assert.equal(summary.ok, false);
    if (process.platform === 'linux') {
      assert.equal(summary.commandExitCode, 0);
      assert.ok(summary.memoryProbe.report);
      const args = JSON.parse(readFileSync(join(f.dir, 'args.json'), 'utf8'));
      const jvm = args.find(value => value.startsWith('-Dorg.gradle.jvmargs='));
      assert.match(jvm, /-Xmx3g -XX:MaxMetaspaceSize=2g -XX:ErrorFile=/);
      assert.doesNotMatch(JSON.stringify(summary), /gvy-memory-[a-f0-9]{64}-hs_err/);
      const memory = JSON.parse(readFileSync(join(f.artifactDirs()[0], 'memory.json'), 'utf8'));
      assert.equal(memory.limits.intervalMs, profile === 'nightly' ? 10000 : 5000);
      assert.equal(memory.limits.maxDurationMs, profile === 'nightly' ? 3600000 : 1800000);
      assert.equal(memory.limits.maxSamples, 360);
      assert.equal(memory.limits.maxReportBytes, 262144);
      assert.equal(memory.profile, undefined);
    } else {
      assert.equal(summary.commandExitCode, 2);
      assert.equal(summary.memoryProbe.report, null);
      assert.match(summary.errors.join(' '), /Linux GitHub-hosted runner/);
    }
  });
}

test('invalid memory configuration does not disclose its value or become successful evidence', t => {
  const f = fixture(t);
  const secret = 'PRIVATE-MEMORY-OPTION';
  const result = f.run(['test'], { GVY_GRADLE_MEMORY_PROBE: secret });
  assert.notEqual(result.status, 0);
  const summary = f.summaries()[0];
  assert.equal(summary.ok, false);
  assert.equal(summary.complete, false);
  assert.match(summary.errors.join(' '), /Invalid memory observation opt-in/);
  assert.doesNotMatch(JSON.stringify(summary) + result.stdout + result.stderr, new RegExp(secret));
});

test('unknown profiles and profiles without probe opt-in fail before Gradle without disclosing input', t => {
  for (const env of [
    { GVY_GRADLE_MEMORY_PROFILE: 'CANARY-PROFILE' },
    { GVY_GRADLE_MEMORY_PROFILE: 'CANARY-PROFILE', GVY_GRADLE_MEMORY_PROBE: 'true' },
    { GVY_GRADLE_MEMORY_PROFILE: '' },
    { GVY_GRADLE_MEMORY_PROFILE: 'nightly' },
    { GVY_GRADLE_MEMORY_PROFILE: 'nightly', GVY_GRADLE_MEMORY_PROBE: 'false' },
  ]) {
    const f = fixture(t);
    const result = f.run(['test'], env);
    assert.notEqual(result.status, 0);
    const summary = f.summaries()[0];
    assert.equal(summary.commandExitCode, 2);
    assert.equal(summary.complete, false);
    assert.match(summary.errors.join(' '), /memory observation profile/i);
    assert.doesNotMatch(JSON.stringify(summary) + result.stdout + result.stderr, /CANARY-PROFILE/);
    assert.throws(() => readFileSync(join(f.dir, 'args.json')), { code: 'ENOENT' });
  }
});

test('Nightly keeps lint and server tasks in separate measured jobs and requires both before publication', () => {
  const workflow = readFileSync('.github/workflows/nightly.yml', 'utf8');
  const jobs = new Map(); let current;
  for (const line of workflow.split('\n')) {
    const match = /^  ([a-z][a-z0-9-]*):$/.exec(line);
    if (match) { current = match[1]; jobs.set(current, ''); }
    else if (current) jobs.set(current, jobs.get(current) + line + '\n');
  }
  for (const name of ['lint', 'build']) {
    const job = jobs.get(name);
    assert.ok(job, `Missing Nightly ${name} job`);
    assert.match(job, /GVY_GRADLE_MEMORY_PROBE: 'true'/);
    assert.match(job, /GVY_GRADLE_MEMORY_PROFILE: nightly/);
    assert.match(job, /--daemon /);
    assert.doesNotMatch(job, /--no-daemon/);
    assert.match(job, /--max-workers=2/);
  }
  assert.match(jobs.get('lint'), /--daemon lint --stacktrace --max-workers=2/);
  assert.match(jobs.get('build'), /--daemon test :tests:e2eTest :gls:stageEditorServer --stacktrace --max-workers=2/);
  assert.doesNotMatch(jobs.get('build'), /--daemon lint/);
  assert.match(jobs.get('publish'), /needs: \[lint, build\]/);
  assert.match(jobs.get('publish'), /needs\.lint\.result == 'success' && needs\.build\.result == 'success'/);
  assert.match(jobs.get('publish'), /needs\.build\.outputs\.version/);
});

test('Nightly watches observer runtime, test, schema and JVM-profile inputs', () => {
  const workflow = readFileSync('.github/workflows/nightly.yml', 'utf8');
  const paths = /^  pull_request:\n    paths:\n((?:      - .+\n)+)/m.exec(workflow)?.[1];
  assert.ok(paths, 'Nightly must declare explicit PR input paths');
  const patterns = paths.trim().split('\n').map(line => line.trim().slice(2));
  const watched = path => patterns.some(pattern => pattern.endsWith('*') ? path.startsWith(pattern.slice(0, -1)) : path === pattern);
  const inputs = [
    '.github/workflows/nightly.yml', '.github/workflows/extension-nightly.yml',
    'tools/ci/nightly-artifacts.mjs', 'tools/ci/nightly-artifacts.test.mjs',
    'tools/ci/gradle-memory-options.mjs', 'tools/ci/gradle-memory-options.test.mjs',
    'tools/ci/gradle-memory-probe.mjs', 'tools/ci/gradle-memory-probe.test.mjs',
    'tools/ci/run-gradle.mjs', 'tools/ci/run-gradle.test.mjs', 'tools/ci/run-gradle.schema.json', 'gradle.properties',
    'tools/ci/check-gradle-diagnostics.mjs', 'tools/ci/check-gradle-diagnostics.test.mjs', 'tools/ci/check-gradle-diagnostics.schema.json',
    'tools/ci/gradle-diagnostics.json', 'tools/ci/read-evidence.mjs', 'tools/ci/read-evidence.test.mjs',
    'tools/ci/bazel-diagnostics.mjs', 'tools/ci/bazel-diagnostics.test.mjs',
    'tools/ci/native-provisioning.mjs', 'tools/ci/native-provisioning.test.mjs', 'tools/ci/native-provisioning-diagnostics.json',
  ];
  assert.deepEqual(inputs.filter(path => !watched(path)), [], 'Nightly must exercise every observer/profile input');
  for (const unrelated of ['README.md', '.github/workflows/native.yml', 'editors/zed/src/lib.rs']) {
    assert.equal(watched(unrelated), false, `Unrelated input should not select Nightly: ${unrelated}`);
  }
});

test('JetBrains visibility exceptions apply only to its producer', t => {
  const f = fixture(t);
  writeFileSync(f.executable, `#!${process.execPath}
console.log('> Configure project :');
console.log('The Configuration.setVisible(boolean) method has been deprecated. This is scheduled to be removed in Gradle 11. Consult the upgrading guide for further information: https://docs.gradle.org/9.8.2/userguide/upgrading_version_9.html#deprecate-visible-property');
console.log('    at org.jetbrains.kotlin.gradle.plugin.DefaultKotlinBasePlugin.apply(KotlinPluginWrapper.kt:94)');
console.log('BUILD SUCCESSFUL in 1s');
`);
  chmodSync(f.executable, 0o755);
  const run = producer => spawnSync(process.execPath,
    [runner, '--report-root', f.reportRoot, '--producer', producer, 'test'],
    { encoding: 'utf8', cwd: f.dir, env: f.env, timeout: 10000 });
  assert.equal(run('jetbrains').status, 0);
  assert.equal(run('gradle-lint').status, 1);
  const reports = f.artifactDirs().map(path => JSON.parse(readFileSync(join(path, 'diagnostics.json'), 'utf8')));
  const accepted = reports.find(report => report.producer === 'jetbrains');
  const rejected = reports.find(report => report.producer === 'gradle-lint');
  assert.equal(accepted.accepted.length, 1);
  assert.equal(rejected.unexpected.length, 1);
  assert.equal(accepted.diagnostics[0], rejected.unexpected[0]);
});

test('JetBrains diagnostic debt remains exact, owned and current', async () => {
  const { evaluate } = await import('./bazel-diagnostics.mjs');
  const entries = JSON.parse(readFileSync(new URL('./gradle-jetbrains-diagnostics.json', import.meta.url), 'utf8'));
  assert.equal(entries.length, 12);
  assert.ok(entries.every(entry => entry.diagnostic.startsWith('[project :] The Configuration.setVisible(boolean)')));
  assert.equal(evaluate([], entries, new Date()).ok, true);
});
