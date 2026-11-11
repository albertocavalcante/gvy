import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { collectDiagnostics, checkGradleDiagnostics } from './check-gradle-diagnostics.mjs';

const now = new Date('2026-11-11T00:00:00Z');
const warning = "The ReportingExtension.file(String) method has been deprecated. This is scheduled to be removed in Gradle 10.";
const diagnostic = `[project :] ${warning}`;
const exception = {
  diagnostic,
  owner: 'build maintainers',
  issue: 'https://github.com/albertocavalcante/gvy/issues/1603',
  reason: 'A tested plugin calls a deprecated API.',
  nextAction: 'Verify the plugin migration.',
  acceptance: 'The plugin no longer calls the deprecated API.',
  expires: '2026-12-11',
};
const check = (log, extra = {}) => checkGradleDiagnostics({
  log, commandExitCode: 0, captureExitCode: 0, exceptions: [], now, ...extra,
});

test('a warning from an otherwise successful command fails', () => {
  const result = check(`> Configure project :\n${warning}\nBUILD SUCCESSFUL in 1s\n`);
  assert.equal(result.complete, true);
  assert.equal(result.ok, false);
  assert.deepEqual(result.diagnostics, [diagnostic]);
  assert.deepEqual(result.unexpected, [diagnostic]);
});

test('a failed command retains all warnings with complete failure evidence', () => {
  const result = check(`> Configure project :\n${warning}\nBUILD FAILED in 1s\n`, {
    commandExitCode: 1, exceptions: [exception],
  });
  assert.equal(result.complete, true);
  assert.equal(result.ok, false);
  assert.equal(result.commandExitCode, 1);
  assert.deepEqual(result.diagnostics, [diagnostic]);
  assert.equal(result.accepted.length, 1);
});

test('missing, empty, truncated, conflicting and failed captures cannot pass', () => {
  for (const log of [undefined, '', '> Task :gls:compileKotlin\nw: warning\n',
    'BUILD SUCCESSFUL in 1s', 'BUILD SUCCESSFUL in 1s\nBUILD FAILED in 1s\n',
    'BUILD SUCCESSFUL in 1s\n> Task :gls:compileKotlin\n']) {
    assert.equal(check(log).complete, false, JSON.stringify(log));
    assert.equal(check(log).ok, false, JSON.stringify(log));
  }
  assert.equal(check('BUILD SUCCESSFUL in 1s\n', { captureExitCode: 1 }).complete, false);
  assert.equal(check('BUILD FAILED in 1s\n').complete, false);
  assert.equal(check('BUILD SUCCESSFUL in 1s\n', { commandExitCode: 1 }).complete, false);
  assert.equal(check('BUILD SUCCESSFUL in 1s\n', { commandExitCode: undefined }).complete, false);
  assert.equal(check('BUILD SUCCESSFUL in 1s\n', { captureExitCode: undefined }).complete, false);
});

test('preserves project, task and source identity while normalizing only the checkout root and ANSI', () => {
  const rootDir = '/work/gvy';
  const log = '> Configure project :schemas\n' + warning + '\n' + warning + '\n' +
    '> Configure project :indexer:scip\n' + warning + '\n' +
    '> Task :gls:compileKotlin\n\x1b[33mw: file:///work/gvy/gls/src/Foo.kt:4:3 Deprecated in Java\x1b[0m\n' +
    'w: file:///work/gvy/gls/src/Foo.kt:4:3 Deprecated in Java\n' +
    '> Task :gls:compileTestKotlin\nw: file:///work/gvy/gls/src/Foo.kt:4:3 Deprecated in Java\n' +
    'w: file:///work/gvy/gls/src/Other.kt:4:3 Deprecated in Java\n';
  const diagnostics = collectDiagnostics(log, { rootDir });
  assert.equal(diagnostics.length, 5);
  assert.ok(diagnostics.includes('[project :schemas] ' + warning));
  assert.ok(diagnostics.includes('[project :indexer:scip] ' + warning));
  assert.ok(diagnostics.includes('[task :gls:compileKotlin] w: gls/src/Foo.kt:4:3 Deprecated in Java'));
  assert.equal(check(log + 'BUILD SUCCESSFUL in 1s\n', {
    rootDir, exceptions: [{ ...exception, diagnostic: diagnostics[0] }],
  }).unexpected.length, 4);
});

test('the same deprecated API in a different plugin origin is new debt', () => {
  const prefix = `> Configure project :\n${warning}\n\tat org.gradle.api.reporting.ReportingExtension.file(ReportingExtension.java:102)\n`;
  const first = collectDiagnostics(`${prefix}\tat io.gitlab.arturbosch.detekt.DetektPlugin.apply(DetektPlugin.kt:28)\n`);
  const other = collectDiagnostics(`${prefix}\tat other.Plugin.apply(Plugin.kt:28)\n`);
  assert.deepEqual(first, [`${diagnostic} [origin io.gitlab.arturbosch.detekt.DetektPlugin.apply(DetektPlugin.kt:28)]`]);
  assert.notDeepEqual(first, other);
  const result = check(`${prefix}\tat other.Plugin.apply(Plugin.kt:28)\nBUILD SUCCESSFUL in 1s\n`, {
    exceptions: [{ ...exception, diagnostic: first[0] }],
  });
  assert.equal(result.unexpected.length, 1);
});

test('captures actual Gradle 9 API, Kotlin plugin/compiler, javac and raw JVM warning formats', () => {
  const log = readFileSync(new URL('./fixtures/gradle-tool-warnings.txt', import.meta.url), 'utf8');
  const diagnostics = collectDiagnostics(log);
  assert.equal(diagnostics.length, 12);
  for (const value of ['ReportingExtension.file', 'Configuration.setVisible', 'by extra', 'by creating',
    'by registering', 'archives configuration', 'HotReloadUsageType', 'Kotlin compiler', 'Foo.kt',
    '[removal]', 'OpenJDK', 'Deprecated Gradle features']) {
    assert.ok(diagnostics.some(line => line.includes(value)), value);
  }
  assert.ok(diagnostics.some(line => line.endsWith('[source gls/build.gradle.kts:34]')));
});

test('semantic LSP payloads, passing-test names and runtime logger output belong to the harness producer', () => {
  const log = '> Task :tests:e2eTest\n' +
    'LoggingConfigTest > third party dependencies stay at WARN() PASSED\n' +
    'DocTest > deprecated method shows warning() PASSED\n' +
    'DocTest > the API is deprecated() PASSED\n' +
    '    11:23:40.078 WARN  FixtureTest - Deliberate failure\n' +
    '    WARNING: intentional test input\n' +
    '    11:23:41.293 INFO Factory - [server:fixture] 11:23:41,037 |-WARN in LoggerContext - mismatch\n' +
    '    Diagnostic [severity = Warning, message = deprecated API]\n' +
    '    deprecated = null\n' +
    'BUILD SUCCESSFUL in 1s\n';
  assert.deepEqual(collectDiagnostics(log), []);
});

test('captured compiler-shaped test payloads stay separate while genuine subsequent compiler warnings fail', () => {
  const captured = '> Task :gls:test\n' +
    'SomeTest > expected warning STANDARD_ERROR\n' +
    '    w: expected negative-test compiler warning\n' +
    '    gls/src/Fixture.kt:4:3: warning: expected negative-test compiler warning\n' +
    'OtherTest STANDARD_OUT\n' +
    '    w: expected class-level captured warning\n';
  assert.equal(check(captured + 'BUILD SUCCESSFUL in 1s\n').ok, true);
  const diagnostics = collectDiagnostics(captured +
    'SomeTest > expected warning PASSED\n' +
    '    w: warning outside captured output\n' +
    '> Task :gls:compileKotlin\n' +
    '    gls/src/Real.kt:4:3: warning: actual compiler warning\n' +
    '> Task :gls:test\nOtherTest > expected warning STANDARD_OUT\n    w: expected again\n' +
    '> Configure project :other\nw: actual plugin warning\n');
  assert.deepEqual(diagnostics, [
    '[project :other] w: actual plugin warning',
    '[task :gls:compileKotlin] gls/src/Real.kt:4:3: warning: actual compiler warning',
    '[task :gls:test] w: warning outside captured output',
  ]);
});

test('a legitimate Unicode replacement character is valid log text', () => {
  assert.equal(check('> Task :gls:test\nSomeTest > replacement STANDARD_OUT\n    replacement character: \ufffd\nBUILD SUCCESSFUL in 1s\n').ok, true);
});

test('warning prefixes take precedence over class-level captured-output and test-status headers', () => {
  const log = '> Task :gls:compileKotlin\nw: STANDARD_OUT\nWARNING: STANDARD_ERROR\nWARN: PASSED\nWARNING STANDARD_OUT\nwarning: STARTED\nBUILD SUCCESSFUL in 1s\n';
  const result = check(log);
  assert.equal(result.ok, false);
  assert.deepEqual(result.unexpected, [
    '[task :gls:compileKotlin] WARN: PASSED',
    '[task :gls:compileKotlin] WARNING STANDARD_OUT',
    '[task :gls:compileKotlin] WARNING: STANDARD_ERROR',
    '[task :gls:compileKotlin] w: STANDARD_OUT',
    '[task :gls:compileKotlin] warning: STARTED',
  ]);
});

test('validates exact owned exceptions including cached-out and expired debt', () => {
  assert.equal(check(`> Configure project :\n${warning}\nBUILD SUCCESSFUL in 1s\n`, {
    exceptions: [exception],
  }).ok, true);
  const cached = check('BUILD SUCCESSFUL in 1s\n', { exceptions: [exception] });
  assert.deepEqual(cached.exceptions, [exception]);
  assert.equal(cached.ok, true);
  assert.equal(check('BUILD SUCCESSFUL in 1s\n', {
    exceptions: [{ ...exception, expires: '2026-11-10' }],
  }).ok, false);
  assert.equal(check('BUILD SUCCESSFUL in 1s\n', { exceptions: [{ diagnostic }] }).ok, false);
});

test('a new warning fails even when all previous diagnostics are excepted', () => {
  const result = check(`> Configure project :\n${warning}\n> Task :gls:test\n⚠️ SLOW TEST: Suite.test took 61000ms\nBUILD SUCCESSFUL in 1s\n`, {
    exceptions: [exception],
  });
  assert.equal(result.ok, false);
  assert.equal(result.accepted.length, 1);
  assert.deepEqual(result.unexpected, ['[task :gls:test] ⚠️ SLOW TEST: Suite.test took 61000ms']);
});

test('the inspected pinned-plugin trace matches only its exact reviewed debt ledger', () => {
  const log = readFileSync(new URL('./fixtures/gradle-plugin-origins.txt', import.meta.url), 'utf8');
  const exceptions = JSON.parse(readFileSync(new URL('./gradle-diagnostics.json', import.meta.url)));
  const result = check(log, { exceptions });
  assert.equal(result.ok, true);
  assert.equal(result.diagnostics.length, 33);
  assert.equal(result.accepted.length, 33);
  assert.equal(check(log.replaceAll('DetektPlugin.kt:28', 'DetektPlugin.kt:29'), { exceptions }).ok, false);
  assert.equal(check(log.replace('> Configure project :viz:desktop', '> Configure project :other'), { exceptions }).ok, false);
  assert.equal(check('BUILD SUCCESSFUL in 1s\n', { exceptions, now: new Date('2026-12-12T00:00:00Z') }).ok, false);
});

test('CLI always publishes structured evidence and a raw copy, including warnings on failure', t => {
  const dir = mkdtempSync(join(tmpdir(), 'gvy-gradle-diagnostics-test-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const baseline = join(dir, 'baseline.json');
  const log = join(dir, 'gradle.log');
  const report = join(dir, 'report');
  writeFileSync(baseline, '[]\n');
  const run = (exit = '0') => spawnSync(process.execPath, ['tools/ci/check-gradle-diagnostics.mjs',
    '--log', log, '--exit-code', exit, '--capture-exit-code', '0', '--producer', 'gradle-lint',
    '--baseline', baseline, '--report', report], { encoding: 'utf8' });
  assert.notEqual(run().status, 0);
  assert.equal(JSON.parse(readFileSync(join(report, 'diagnostics.json'))).complete, false);
  const text = `> Configure project :\n${warning}\nBUILD FAILED in 1s\n`;
  writeFileSync(log, text);
  assert.notEqual(run('1').status, 0);
  const result = JSON.parse(readFileSync(join(report, 'diagnostics.json')));
  assert.equal(result.schemaVersion, 1);
  assert.equal(result.producer, 'gradle-lint');
  assert.equal(result.complete, true);
  assert.equal(result.ok, false);
  assert.deepEqual(result.unexpected, [diagnostic]);
  assert.equal(result.log.bytes, Buffer.byteLength(text));
  assert.match(result.log.sha256, /^[a-f0-9]{64}$/);
  const schema = JSON.parse(readFileSync(new URL('./check-gradle-diagnostics.schema.json', import.meta.url)));
  assert.equal(result.schemaVersion, schema.properties.schemaVersion.const);
  assert.deepEqual(Object.keys(result).sort(), schema.required.toSorted());
  assert.equal(readFileSync(join(report, 'raw.log'), 'utf8'), text);
  assert.match(readFileSync(join(report, 'summary.md'), 'utf8'), /ReportingExtension.file/);
  writeFileSync(log, 'BUILD SUCCESSFUL in 1s\n');
  assert.equal(run().status, 0);
  writeFileSync(log, Buffer.concat([
    Buffer.from('> Task :gls:compileKotlin\nw: gls/src/Foo.kt:4:3 warning before invalid bytes\n'),
    Buffer.from([0xff, 0x0a]), Buffer.from('BUILD SUCCESSFUL in 1s\n'),
  ]));
  assert.notEqual(run().status, 0);
  const invalidEncoding = JSON.parse(readFileSync(join(report, 'diagnostics.json')));
  assert.equal(invalidEncoding.complete, false);
  assert.deepEqual(invalidEncoding.unexpected, ['[task :gls:compileKotlin] w: gls/src/Foo.kt:4:3 warning before invalid bytes']);
  assert.match(invalidEncoding.errors.join(' '), /encoding/);
  writeFileSync(baseline, '{malformed');
  writeFileSync(log, text);
  assert.notEqual(run('1').status, 0);
  assert.deepEqual(JSON.parse(readFileSync(join(report, 'diagnostics.json'))).diagnostics, [diagnostic]);
});
