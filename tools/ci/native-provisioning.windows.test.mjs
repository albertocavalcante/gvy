// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { chmodSync, existsSync, linkSync, mkdirSync, mkdtempSync, readFileSync, realpathSync, renameSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { collectDiagnostics } from './check-gradle-diagnostics.mjs';
import { prepareNativeProvisioning, verifyNativeProvisioning } from './native-provisioning.mjs';
import { inspectWindowsPrivateRoot } from './windows-private-root.mjs';

const invocationId = '12345678-1234-4234-8234-123456789abc';
const context = { schemaVersion: 1, headSha: 'a'.repeat(40), testedSha: 'b'.repeat(40), runId: '12345', runAttempt: 2, invocationId, command: 'gradle' };
const digest = bytes => createHash('sha256').update(bytes).digest('hex');
const invalid = operation => assert.throws(operation, error => /^Invalid Native provisioning (?:preparation|evidence)$/.test(error.message));
const put = (path, value) => writeFileSync(path, JSON.stringify(value) + '\n');
const record = path => JSON.parse(readFileSync(path, 'utf8'));

test('Windows target rejects invalid paths or context without exposing inputs on every host', () => {
  invalid(() => prepareNativeProvisioning({ target: 'mingwX64', platform: 'win32', arch: 'x64', producer: 'launcher-windows-x64',
    context: { ...context, headSha: 'CANARY\n' }, invocationId, runnerTemp: 'CANARY', sourceRoot: 'CANARY', producerDir: 'CANARY' }));
});

if (process.platform === 'win32') {
  function fixture(t) {
    const directory = realpathSync(mkdtempSync(join(tmpdir(), 'gvy-win-guard-')));
    t.after(() => rmSync(directory, { recursive: true, force: true }));
    const sourceRoot = join(directory, 'workspace 世界'); mkdirSync(join(sourceRoot, 'gradle'), { recursive: true });
    writeFileSync(join(sourceRoot, 'gradle', 'libs.versions.toml'), '[versions]\nkotlin = "2.3.0"\n');
    const runnerTemp = join(directory, 'runner-temp'); mkdirSync(runnerTemp);
    const producer = 'launcher-windows-x64', reportRoot = join(runnerTemp, 'evidence');
    const producerDir = join(reportRoot, `${producer}-${invocationId}`); mkdirSync(producerDir, { recursive: true });
    put(join(producerDir, 'context.json'), context);
    const io = { checkoutSha: () => context.testedSha, windowsHelperPath: process.env.GVY_CI_PRIVATE_ROOT_HELPER };
    const options = { context, producer, invocationId, target: 'mingwX64', platform: 'win32', arch: 'x64', sourceRoot, runnerTemp, producerDir };
    const prepare = () => prepareNativeProvisioning(options, io);
    function provision(plan, warning = true) {
      const home = plan.preflight.expectedBundleHome;
      mkdirSync(join(home, 'bin'), { recursive: true }); mkdirSync(join(home, 'konan', 'lib'), { recursive: true });
      // Offline synthetic files; no compiler/JVM is installed or executed by these controls.
      writeFileSync(join(home, 'provisioned.ok'), '');
      for (const name of ['konanc.bat', 'kotlinc-native.bat']) writeFileSync(join(home, 'bin', name), '@exit /b 0\r\n');
      writeFileSync(join(home, 'konan', 'konan.properties'), 'synthetic properties\n');
      writeFileSync(join(home, 'konan', 'lib', 'kotlin-native-compiler-embeddable.jar'), 'synthetic compiler\n');
      const message = `Kotlin/Native bundle directory ${home} is not empty. Native bundle files will be overwritten.`;
      const raw = `> Task :launcher:downloadKotlinNativeDistribution\n${warning ? message + '\n' : ''}> Task :launcher:mingwX64Test\nBUILD SUCCESSFUL in 1s\n`;
      writeFileSync(join(producerDir, 'console.log'), raw);
      const diagnostics = collectDiagnostics(raw);
      const report = { schemaVersion: 1, producer, context, complete: true, ok: !warning, commandExitCode: 0, captureExitCode: 0,
        commandSignal: null, diagnostics, unexpected: diagnostics, accepted: [], exceptions: [], errors: [], log: { bytes: Buffer.byteLength(raw), sha256: digest(raw) } };
      const verifyOptions = { plan, context, producer, rawBytes: Buffer.from(raw), preliminaryDiagnostics: report };
      return { home, raw, message, report, verifyOptions,
        verify: () => verifyNativeProvisioning(verifyOptions, io),
        replaceRaw: next => {
          writeFileSync(join(producerDir, 'console.log'), next);
          verifyOptions.rawBytes = Buffer.from(next);
          verifyOptions.preliminaryDiagnostics = { ...report, diagnostics: collectDiagnostics(next), unexpected: collectDiagnostics(next),
            log: { bytes: Buffer.byteLength(next), sha256: digest(next) } };
        },
      };
    }
    function powershell(source, extraEnv) {
      const file = join(directory, 'fixture-acl.ps1'); writeFileSync(file, "$ErrorActionPreference='Stop'\n" + source + '\n');
      const result = spawnSync(process.env.GVY_TEST_PWSH || 'pwsh.exe', ['-NoLogo', '-NoProfile', '-NonInteractive', '-File', file],
        { env: { ...process.env, ...extraEnv }, encoding: 'utf8', timeout: 10000 });
      assert.ifError(result.error); assert.equal(result.status, 0, result.stderr);
      return result.stdout;
    }
    return { directory, options, reportRoot, prepare, provision, powershell, io };
  }

  for (const warning of [false, true]) {
    test(`Windows fresh root and ${warning ? 'one exact warning' : 'zero warnings'} require real native policy checks without publishing SID/file IDs`, t => {
      const f = fixture(t), plan = f.prepare(), original = readFileSync(plan.preflightPath);
      assert.equal(existsSync(plan.childEnv.KONAN_DATA_DIR), false);
      const privateIdentity = inspectWindowsPrivateRoot(plan.preflight.freshRoot.path, { helperPath: f.io.windowsHelperPath });
      const observed = f.provision(plan, warning), proof = observed.verify();
      assert.equal(proof.ok, true); assert.equal(proof.provisioningWarningCount, Number(warning));
      assert.equal(proof.wrapper.diagnosticGateOk, !warning);
      assert.match(plan.preflight.freshRoot.identitySha256, /^[a-f0-9]{64}$/);
      for (const value of [plan, plan.preflight, proof, record(join(f.options.producerDir, 'native-provisioning-unapproved-diagnostics.json'))]) {
        const serialized = JSON.stringify(value);
        assert.doesNotMatch(serialized, /ownerSid|volumeSerial|fileId|S-1-/);
        for (const raw of Object.values(privateIdentity)) assert.ok(!serialized.includes(raw));
      }
      assert.deepEqual(readFileSync(plan.preflightPath), original);
      assert.deepEqual(proof.compilerFiles.map(file => file.relativePath), ['bin/konanc.bat', 'bin/kotlinc-native.bat', 'konan/konan.properties', 'konan/lib/kotlin-native-compiler-embeddable.jar']);
      assert.ok(proof.compilerFiles.every(file => file.executableBitRequired === false));
      invalid(observed.verify);
    });
  }

  test('Windows trusted existing directories can have ordinary ACLs while fresh child owners follow TokenUser/TokenOwner policy', t => {
    const f = fixture(t), observed = f.provision(f.prepare(), false);
    const owners = f.powershell("[Console]::WriteLine((Get-Acl -LiteralPath $env:FIXTURE_ROOT).Owner); [Console]::WriteLine((Get-Acl -LiteralPath $env:FIXTURE_CHILD).Owner)",
      { FIXTURE_ROOT: observed.verifyOptions.plan.preflight.freshRoot.path, FIXTURE_CHILD: join(observed.home, 'bin') });
    assert.ok(owners.trim().split(/\r?\n/).length === 2);
    assert.equal(observed.verify().ok, true);
  });

  test('Windows preparation rejects aliases/junctions, wrong context, target, producer and missing native helper', t => {
    for (const change of [
      { producer: 'launcher-linux-x64' }, { target: 'linuxX64' }, { context: { ...context, runAttempt: 0 } }, { invocationId: 'bad' },
    ]) {
      const f = fixture(t); invalid(() => prepareNativeProvisioning({ ...f.options, ...change }, f.io));
    }
    const f = fixture(t); invalid(() => prepareNativeProvisioning(f.options, { ...f.io, windowsHelperPath: 'CANARY-missing.exe' }));
    const moved = join(f.options.runnerTemp, 'other-evidence'); renameSync(f.reportRoot, moved); symlinkSync(moved, f.reportRoot, 'junction'); invalid(f.prepare);
  });

  test('Windows root replacement, ACL broadening and lost private plan identity fail closed', t => {
    for (const kind of ['replace', 'broaden', 'clone']) {
      const f = fixture(t), plan = f.prepare(), observed = f.provision(plan, false);
      if (kind === 'replace') {
        renameSync(plan.preflight.freshRoot.path, plan.preflight.freshRoot.path + '-old');
        mkdirSync(plan.preflight.freshRoot.path);
      } else if (kind === 'broaden') {
        f.powershell("$acl=Get-Acl -LiteralPath $env:FIXTURE_PATH; $rule=[System.Security.AccessControl.FileSystemAccessRule]::new([System.Security.Principal.SecurityIdentifier]::new('S-1-1-0'),'FullControl','ContainerInherit,ObjectInherit','None','Allow'); $acl.AddAccessRule($rule); Set-Acl -LiteralPath $env:FIXTURE_PATH -AclObject $acl", { FIXTURE_PATH: plan.preflight.freshRoot.path });
      } else observed.verifyOptions.plan = structuredClone(plan);
      invalid(observed.verify);
    }
  });

  test('Windows broadened child ACL, junction, marker hard link and missing compiler fail with zero warnings', t => {
    for (const kind of ['acl', 'junction', 'hardlink', 'missing']) {
      const f = fixture(t), observed = f.provision(f.prepare(), false);
      if (kind === 'acl') {
        f.powershell("$acl=Get-Acl -LiteralPath $env:FIXTURE_PATH; $rule=[System.Security.AccessControl.FileSystemAccessRule]::new([System.Security.Principal.SecurityIdentifier]::new('S-1-1-0'),'ReadAndExecute','Allow'); $acl.AddAccessRule($rule); Set-Acl -LiteralPath $env:FIXTURE_PATH -AclObject $acl", { FIXTURE_PATH: join(observed.home, 'bin', 'konanc.bat') });
      } else if (kind === 'junction') {
        renameSync(join(observed.home, 'bin'), join(observed.home, 'other-bin')); symlinkSync(join(observed.home, 'other-bin'), join(observed.home, 'bin'), 'junction');
      } else if (kind === 'hardlink') linkSync(join(observed.home, 'provisioned.ok'), join(observed.home, 'marker-copy'));
      else rmSync(join(observed.home, 'konan', 'lib', 'kotlin-native-compiler-embeddable.jar'));
      invalid(observed.verify);
    }
  });

  test('Windows exact path/count, all statuses, preliminary hashes and receipt no-replace/read-only policy remain mandatory', t => {
    for (const kind of ['path', 'duplicate', 'status', 'hash', 'readonly', 'context']) {
      const f = fixture(t), plan = f.prepare(), observed = f.provision(plan);
      if (kind === 'path') observed.replaceRaw(observed.raw.replace(observed.home, 'C:\\CANARY\\wrong'));
      else if (kind === 'duplicate') observed.replaceRaw(observed.raw.replace(observed.message, observed.message + '\n' + observed.message));
      else if (kind === 'status') observed.verifyOptions.preliminaryDiagnostics.commandExitCode = 1;
      else if (kind === 'hash') observed.verifyOptions.preliminaryDiagnostics.log.sha256 = '0'.repeat(64);
      else if (kind === 'readonly') chmodSync(plan.preflightPath, 0o666);
      else observed.verifyOptions.context = { ...context, runAttempt: 3 };
      invalid(observed.verify);
    }
  });
}
