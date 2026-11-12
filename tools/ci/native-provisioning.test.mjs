// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { chmodSync, closeSync, constants, existsSync, fstatSync, lstatSync, mkdirSync, mkdtempSync, openSync, readFileSync, readdirSync, realpathSync, renameSync, rmSync, symlinkSync, truncateSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import test from 'node:test';
import { checkGradleDiagnostics, collectDiagnostics } from './check-gradle-diagnostics.mjs';
import { prepareNativeProvisioning, readPinnedKotlinVersion, verifyNativeProvisioning } from './native-provisioning.mjs';

const invocationId = '12345678-1234-4234-8234-123456789abc';
const context = { schemaVersion: 1, headSha: 'a'.repeat(40), testedSha: 'b'.repeat(40), runId: '12345', runAttempt: 2, invocationId, command: 'gradle' };
const digest = bytes => createHash('sha256').update(bytes).digest('hex');
const invalid = operation => assert.throws(operation, error => /^Invalid Native provisioning (?:preparation|evidence)$/.test(error.message));
const record = path => JSON.parse(readFileSync(path, 'utf8'));
const put = (path, value) => writeFileSync(path, JSON.stringify(value) + '\n');

function fixture(t, platform = 'linux', arch = 'x64') {
  const directory = realpathSync(mkdtempSync(join(tmpdir(), 'gvy-native-provisioning-test-')));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const sourceRoot = join(directory, 'workspace'); mkdirSync(join(sourceRoot, 'gradle'), { recursive: true });
  writeFileSync(join(sourceRoot, 'gradle/libs.versions.toml'), '[versions]\nkotlin = "2.3.0"\n');
  const runnerTemp = join(directory, 'runner-temp'); mkdirSync(runnerTemp);
  const producer = platform === 'linux' ? 'launcher-linux-x64' : 'launcher-macos-arm64';
  const reportRoot = join(runnerTemp, 'launcher-gradle-evidence');
  const producerDir = join(reportRoot, `${producer}-${invocationId}`); mkdirSync(producerDir, { recursive: true });
  put(join(producerDir, 'context.json'), context);
  const options = { context, producer, invocationId, platform, arch, sourceRoot, runnerTemp, producerDir,
    target: platform === 'linux' ? 'linuxX64' : 'macosArm64' };
  const io = { checkoutSha: () => context.testedSha };
  const prepare = () => prepareNativeProvisioning(options, io);
  const provision = (plan, warning = true) => {
    const home = plan.preflight.expectedBundleHome;
    mkdirSync(join(home, 'bin'), { recursive: true });
    mkdirSync(join(home, 'konan', 'lib'), { recursive: true });
    // Synthetic offline fixtures only: production never writes a marker/compiler.
    writeFileSync(join(home, 'provisioned.ok'), '');
    for (const name of ['konanc', 'kotlinc-native']) writeFileSync(join(home, 'bin', name), '#!/bin/sh\nexit 0\n', { mode: 0o755 });
    writeFileSync(join(home, 'konan', 'lib', 'kotlin-native-compiler-embeddable.jar'), 'offline compiler fixture');
    writeFileSync(join(home, 'konan', 'konan.properties'), 'offline properties fixture\n');
    const message = `Kotlin/Native bundle directory ${home} is not empty. Native bundle files will be overwritten.`;
    const raw = `> Task :launcher:downloadKotlinNativeDistribution\n${warning ? message + '\n' : ''}> Task :launcher:${options.target}Test\nBUILD SUCCESSFUL in 1s\n`;
    writeFileSync(join(producerDir, 'console.log'), raw);
    const diagnostics = collectDiagnostics(raw);
    const report = { schemaVersion: 1, producer, context, complete: true, ok: !warning, commandExitCode: 0, captureExitCode: 0,
      diagnostics, unexpected: diagnostics, accepted: [], exceptions: [], errors: [], commandSignal: null,
      log: { bytes: Buffer.byteLength(raw), sha256: digest(raw) } };
    const verifyOptions = { plan, context, producer, rawBytes: Buffer.from(raw), preliminaryDiagnostics: report };
    return { home, raw, message, report, verifyOptions,
      verify: () => verifyNativeProvisioning(verifyOptions, io),
      writeRaw: next => {
        writeFileSync(join(producerDir, 'console.log'), next);
        verifyOptions.rawBytes = Buffer.from(next);
        verifyOptions.preliminaryDiagnostics = { ...report, diagnostics: collectDiagnostics(next), unexpected: collectDiagnostics(next),
          log: { bytes: Buffer.byteLength(next), sha256: digest(next) } };
      },
    };
  };
  return { directory, options, reportRoot, prepare, provision, io };
}

test('version is pinned to the known catalog section and rejects ambiguity/unsupported versions', () => {
  assert.equal(readPinnedKotlinVersion('[versions]\nkotlin = "2.3.0"\n[libraries]\nkotlin = "ignored"\n'), '2.3.0');
  for (const text of ['', '[versions]\nkotlin = "2.3.20"\n', '[versions]\nkotlin = "2.3.0"\nkotlin = "2.3.0"\n']) invalid(() => readPinnedKotlinVersion(text));
});

for (const [platform, arch] of [['linux', 'x64'], ['darwin', 'arm64']]) {
  test(`prepare ${platform} binds the UUID and creates a unique private namespace outside artifacts`, t => {
    const f = fixture(t, platform, arch), plan = f.prepare(), preflight = record(plan.preflightPath);
    assert.deepEqual(preflight.context, context);
    assert.equal(preflight.invocationId, invocationId);
    assert.equal(preflight.target, f.options.target);
    assert.equal(preflight.kotlinVersion, '2.3.0');
    assert.equal(lstatSync(preflight.freshRoot.path).mode & 0o777, 0o700);
    assert.equal(lstatSync(preflight.freshRoot.path).uid, process.getuid());
    assert.deepEqual(readdirSync(preflight.freshRoot.path), []);
    assert.equal(existsSync(plan.childEnv.KONAN_DATA_DIR), false);
    assert.equal(dirname(preflight.freshRoot.path), f.options.runnerTemp);
    assert.equal(dirname(plan.preflightPath), f.options.producerDir);
    assert.deepEqual(plan.gradleArgs, [`-Pkonan.data.dir=${plan.childEnv.KONAN_DATA_DIR}`]);
    const descriptor = openSync(plan.preflightPath, constants.O_RDONLY | constants.O_NOFOLLOW);
    try {
      const info = fstatSync(descriptor);
      assert.equal(info.mode & 0o777, 0o400);
      assert.ok(info.isFile() && info.size > 0 && info.size <= 16 * 1024);
      assert.equal(plan.preflightSha256, digest(readFileSync(descriptor)));
    } finally {
      closeSync(descriptor);
    }
    assert.notEqual(plan.childEnv.KONAN_DATA_DIR, fixture(t, platform, arch).prepare().childEnv.KONAN_DATA_DIR);
    invalid(f.prepare);
  });

  for (const warning of [false, true]) {
    test(`verify ${platform} requires home proof for ${warning ? 'one exact warning' : 'zero warnings'} without adopting policy`, t => {
      const f = fixture(t, platform, arch), plan = f.prepare(), original = readFileSync(plan.preflightPath);
      const observed = f.provision(plan, warning), originalReport = structuredClone(observed.report);
      const verified = observed.verify();
      assert.equal(verified.ok, true);
      assert.equal(verified.provisioningWarningCount, Number(warning));
      assert.equal(verified.wrapper.diagnosticGateOk, !warning);
      assert.equal(verified.wrapper.invocationId, invocationId);
      assert.equal(verified.preflightSha256, plan.preflightSha256);
      assert.deepEqual(verified.eligibleDiagnostics, warning ? observed.report.unexpected : []);
      assert.deepEqual(readFileSync(plan.preflightPath), original);
      assert.deepEqual(observed.report, originalReport);
      assert.equal(verified.compilerFiles.length, 4);
      const path = join(f.options.producerDir, 'native-provisioning-verification.json');
      assert.equal(lstatSync(path).mode & 0o777, 0o400);
      assert.deepEqual(record(join(f.options.producerDir, 'native-provisioning-unapproved-diagnostics.json')), originalReport);
      invalid(observed.verify);
    });
  }
}

test('prepare rejects invalid CI context, producer/UUID, host/target/version, workspace/artifact paths and controls', t => {
  const changes = [
    { platform: 'win32' }, { arch: 'arm64' }, { target: 'macosArm64' }, { producer: 'launcher-server' }, { invocationId: 'bad' },
    ...[{ headSha: '' }, { testedSha: 'c'.repeat(40) }, { runId: 'local-1' }, { runId: '9007199254740992' },
      { runAttempt: 0 }, { invocationId: '22345678-1234-4234-8234-123456789abc' }].map(change => ({ context: { ...context, ...change } })),
    { runnerTemp: '/CANARY\nprivate' }, { runnerTemp: 'relative' },
  ];
  for (const changed of changes) {
    const f = fixture(t); invalid(() => prepareNativeProvisioning({ ...f.options, ...changed }, f.io));
  }
  const f = fixture(t);
  writeFileSync(join(f.options.sourceRoot, 'gradle/libs.versions.toml'), '[versions]\nkotlin = "2.3.20"\n'); invalid(f.prepare);
  invalid(() => prepareNativeProvisioning({ ...f.options, runnerTemp: f.options.sourceRoot }, f.io));
  invalid(() => prepareNativeProvisioning({ ...f.options, producerDir: f.options.sourceRoot }, f.io));
});

test('prepare rejects a symlinked report-root component rather than resolving it to another namespace', t => {
  const f = fixture(t);
  const moved = join(f.options.runnerTemp, 'other-evidence');
  renameSync(f.reportRoot, moved);
  symlinkSync(moved, f.reportRoot);
  invalid(f.prepare);
});

test('already accepted diagnostic debt remains visible and is not eligible for provisioning acceptance', t => {
  const f = fixture(t), plan = f.prepare(), observed = f.provision(plan);
  const raw = observed.raw.replace('BUILD SUCCESSFUL', 'warning: fixture tracked debt\nBUILD SUCCESSFUL');
  observed.writeRaw(raw);
  const diagnostic = '[task :launcher:linuxX64Test] warning: fixture tracked debt';
  const exception = { diagnostic, owner: 'fixture', issue: 'https://github.com/albertocavalcante/gvy/issues/1603',
    reason: 'fixture debt', nextAction: 'remove fixture debt', acceptance: 'fixture warning absent', expires: '2099-01-01' };
  const report = checkGradleDiagnostics({ log: raw, commandExitCode: 0, captureExitCode: 0, exceptions: [exception] });
  observed.verifyOptions.preliminaryDiagnostics = { ...report, producer: f.options.producer, context, commandSignal: null,
    log: { bytes: Buffer.byteLength(raw), sha256: digest(raw) } };
  const verified = observed.verify();
  assert.equal(verified.eligibleDiagnostics.length, 1);
  assert.ok(!verified.eligibleDiagnostics.includes(diagnostic));
  const preserved = record(join(f.options.producerDir, 'native-provisioning-unapproved-diagnostics.json'));
  assert.ok(preserved.diagnostics.includes(diagnostic));
  assert.deepEqual(preserved.accepted, [exception]);
});

test('verification rejects changed actual context, plan binding, producer or checkout identity', t => {
  for (const changed of [
    { context: { ...context, headSha: 'c'.repeat(40) } }, { context: { ...context, runAttempt: 3 } }, { producer: 'launcher-server' },
  ]) {
    const f = fixture(t), observed = f.provision(f.prepare()); invalid(() => verifyNativeProvisioning({ ...observed.verifyOptions, ...changed }, f.io));
  }
  for (const change of [
    plan => { plan.preflightSha256 = '0'.repeat(64); }, plan => { plan.childEnv.KONAN_DATA_DIR = '/CANARY/shared-cache'; },
    plan => { plan.gradleArgs = ['-Pkonan.data.dir=/CANARY/shared-cache']; },
  ]) {
    const f = fixture(t), plan = f.prepare(), observed = f.provision(plan); change(plan); invalid(observed.verify);
  }
  const f = fixture(t), observed = f.provision(f.prepare());
  invalid(() => verifyNativeProvisioning(observed.verifyOptions, { checkoutSha: () => 'c'.repeat(40) }));
});

test('verification rejects failed/incomplete/mismatched preliminary report and every other unexpected diagnostic', t => {
  for (const change of [
    { producer: 'launcher-server' }, { context: { ...context, runAttempt: 1 } }, { context: null },
    { commandExitCode: 1 }, { captureExitCode: 1 }, { complete: false }, { commandSignal: 'SIGINT' },
    { errors: ['CANARY'] }, { log: { bytes: 1, sha256: '0'.repeat(64) } }, { diagnostics: [] },
    { unexpected: ['warning: unrelated diagnostic'] }, { ok: true },
  ]) {
    const f = fixture(t), observed = f.provision(f.prepare());
    observed.verifyOptions.preliminaryDiagnostics = { ...observed.report, ...change }; invalid(observed.verify);
  }
  const f = fixture(t), observed = f.provision(f.prepare());
  observed.writeRaw(observed.raw.replace('BUILD SUCCESSFUL', 'warning: a different compiler warning\nBUILD SUCCESSFUL'));
  invalid(observed.verify);
});

test('verification rejects wrong/duplicate/malformed provisioning messages and incomplete raw capture', t => {
  for (const mutate of [
    x => x.raw.replace(x.home, '/CANARY/shared/.konan/other'), x => x.raw.replace('linux-x86_64', 'windows-x86_64'),
    x => x.raw.replace('2.3.0', '2.3.20'), x => x.raw.replace(x.message, x.message + '\n' + x.message),
    x => x.raw.replace('files will be overwritten.', 'files will be overwritten!'),
    x => x.raw.replace(x.message, '  ' + x.message.replace(x.home, '/CANARY/unsafe')),
    x => x.raw.replace('BUILD SUCCESSFUL in 1s\n', ''),
  ]) {
    const f = fixture(t), observed = f.provision(f.prepare()); observed.writeRaw(mutate(observed)); invalid(observed.verify);
  }
});

test('missing/unsafe marker or compiler fails even with zero warnings', t => {
  for (const mutate of [
    x => rmSync(join(x.home, 'provisioned.ok')),
    x => { rmSync(join(x.home, 'provisioned.ok')); symlinkSync(join(x.home, 'bin', 'konanc'), join(x.home, 'provisioned.ok')); },
    x => rmSync(join(x.home, 'konan', 'lib', 'kotlin-native-compiler-embeddable.jar')),
    x => truncateSync(join(x.home, 'bin', 'konanc'), 0), x => chmodSync(join(x.home, 'bin', 'konanc'), 0o644),
    x => chmodSync(join(x.home, 'konan', 'konan.properties'), 0o666),
    x => { renameSync(join(x.home, 'bin'), join(x.home, 'other-bin')); symlinkSync(join(x.home, 'other-bin'), join(x.home, 'bin')); },
  ]) {
    const f = fixture(t), observed = f.provision(f.prepare(), false); mutate(observed); invalid(observed.verify);
  }
  const f = fixture(t), plan = f.prepare(), observed = f.provision(plan);
  chmodSync(plan.preflight.freshRoot.path, 0o755); invalid(observed.verify);
});

test('verification rejects extra invocation, symlinked, oversized, malformed or missing evidence without dumping paths', t => {
  for (const mutate of [
    (x, f) => mkdirSync(join(f.reportRoot, 'launcher-linux-x64-22345678-1234-4234-8234-123456789abc')),
    (x, f) => rmSync(join(f.options.producerDir, 'context.json')),
    (x, f) => writeFileSync(join(f.options.producerDir, 'context.json'), '{malformed CANARY'),
    (x, f) => { rmSync(join(f.options.producerDir, 'console.log')); symlinkSync(join(f.options.producerDir, 'context.json'), join(f.options.producerDir, 'console.log')); },
    (x, f) => truncateSync(join(f.options.producerDir, 'context.json'), 16 * 1024 + 1),
    (x, f) => truncateSync(join(f.options.producerDir, 'console.log'), 128 * 1024 * 1024 + 1),
    (x, f) => writeFileSync(join(f.options.producerDir, 'console.log'), Buffer.from([0xff, 0x0a])),
    x => { x.verifyOptions.rawBytes = Buffer.from('mismatched CANARY'); },
  ]) {
    const f = fixture(t), observed = f.provision(f.prepare()); mutate(observed, f); invalid(observed.verify);
  }
});
