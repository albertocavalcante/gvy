import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { collectDiagnostics, checkGradleDiagnostics } from './check-gradle-diagnostics.mjs';
import { evaluate } from './bazel-diagnostics.mjs';

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

// Gradle 9.8.2 HealthExpirationStrategy advice captured from the unit producer.
const daemonMemoryAdvice = [
  'The Daemon will expire after the build after running out of JVM Metaspace.',
  'The project memory settings are likely not configured or are configured to an insufficient value.',
  'The daemon will restart for the next build, which may increase subsequent build times.',
  "These settings can be adjusted by setting 'org.gradle.jvmargs' in 'gradle.properties'.",
  "The currently configured max heap space is '4 GiB' and the configured max metaspace is '1 GiB'.",
  'For more information on how to set these values, please refer to https://docs.gradle.org/9.8.2/userguide/build_environment.html#sec:configuring_jvm_memory in the Gradle documentation.',
  "To disable this warning, set 'org.gradle.daemon.performance.disable-logging=true'.",
  'Daemon will be stopped at the end of the build after running out of JVM Metaspace',
];
const daemonDiagnostic = line => `[gradle daemon] ${line}`;

test('actual plain daemon memory advice fails a successful command without borrowing the nearest task identity', () => {
  for (const header of ['> Task :gls:processTestResources', '> Task :fmt:koverFindJar', '> Configure project :schemas']) {
    const result = check(`${header}\n${daemonMemoryAdvice.join('\n')}\nBUILD SUCCESSFUL in 1s\n`);
    assert.equal(result.commandExitCode, 0);
    assert.equal(result.complete, true);
    assert.equal(result.ok, false);
    assert.deepEqual(result.diagnostics, daemonMemoryAdvice.map(daemonDiagnostic).sort());
    assert.deepEqual(result.unexpected, result.diagnostics);
    assert.ok(result.diagnostics.includes(daemonDiagnostic(daemonMemoryAdvice[4])));
  }
});

test('each known daemon memory or GC expiry and stop variant is independently accountable', async t => {
  const reasons = [
    'after running out of JVM heap space',
    'after running out of JVM Metaspace',
    'after running out of JVM heap space and after running out of JVM Metaspace',
    'since the JVM garbage collector is thrashing',
    'since the JVM garbage collector is thrashing and after running out of JVM Metaspace',
  ];
  for (const reason of reasons) {
    const immediate = reason.startsWith('since');
    const variants = [
      `The Daemon will expire ${immediate ? 'immediately' : 'after the build'} ${reason}.`,
      `${immediate ? 'Daemon is stopping immediately' : 'Daemon will be stopped at the end of the build'} ${reason}`,
    ];
    for (const line of variants) await t.test(line, () => {
      const result = check(`> Task :parser:core:test\n${line}\nBUILD SUCCESSFUL in 1s\n`);
      assert.equal(result.complete, true);
      assert.equal(result.ok, false);
      assert.deepEqual(result.unexpected, [daemonDiagnostic(line)]);
    });
  }
});

test('daemon advice continuation lines retain exact settings even when task headers interleave', () => {
  const lines = [...daemonMemoryAdvice.slice(1, 7), 'The memory settings for this project must be adjusted to avoid this failure.'];
  for (const line of lines) {
    const result = check(`> Task :gls:test\nSomeTest STANDARD_OUT\n    expected test text\n> Task :common:compileKotlin\n${line}\nBUILD SUCCESSFUL in 1s\n`);
    assert.equal(result.ok, false, line);
    assert.deepEqual(result.diagnostics, [daemonDiagnostic(line)]);
  }
  const settings = "The currently configured max heap space is '512 MiB' and the configured max metaspace is 'unknown'.";
  assert.deepEqual(collectDiagnostics(settings + '\n'), [daemonDiagnostic(settings)]);
});

test('captured daemon-looking test output and normal daemon lifecycle text are not tool memory advice', () => {
  const captured = '> Task :gls:test\nSomeTest > memory fixture STANDARD_ERROR\n' +
    daemonMemoryAdvice.map(line => `    ${line}`).join('\n') + '\n' +
    'OtherTest STANDARD_OUT\n    The memory settings for this project must be adjusted to avoid this failure.\n' +
    'OtherTest > The Daemon will expire after running out of JVM Metaspace() PASSED\n';
  const ordinary = 'Daemon will be stopped at the end of the build\n' +
    'The Daemon will expire after the build after being idle.\n' +
    'Daemon is stopping immediately stop command received\n' +
    'The currently configured max heap space is adequate.\n';
  assert.deepEqual(collectDiagnostics(captured + ordinary), []);
  assert.equal(check(captured + ordinary + 'BUILD SUCCESSFUL in 1s\n').ok, true);
  const actual = daemonMemoryAdvice[0];
  assert.deepEqual(collectDiagnostics(captured + actual + '\n'), [daemonDiagnostic(actual)]);
  const compiler = `> Task :gls:compileKotlin\nw: ${actual}\n`;
  assert.deepEqual(collectDiagnostics(compiler), [`[task :gls:compileKotlin] w: ${actual}`]);
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

test('NUL bytes cannot hide daemon health advice or produce complete evidence', () => {
  const warning = daemonMemoryAdvice[0];
  const cases = [
    `git-entry\0tail\n${warning}\n`,
    `${warning}\ngit-entry\0tail\n`,
    `\0${warning}\n`,
    `${warning}\0\n`,
  ];
  for (const [index, output] of cases.entries()) {
    const result = check(output + 'BUILD SUCCESSFUL in 1s\n');
    assert.equal(result.ok, false, `NUL case ${index}`);
    assert.equal(result.complete, false, `NUL case ${index}`);
    assert.match(result.errors.join(' '), /invalid encoding or non-plain console output/);
    if (index < 2) assert.deepEqual(result.unexpected, [daemonDiagnostic(warning)]);
  }
  assert.equal(check('BUILD SUCCESSFUL in 1s\n').ok, true);
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

const wireRoot = '/workspace/gvy';
const wireLog = readFileSync(new URL('./fixtures/gradle-wire-warnings.txt', import.meta.url), 'utf8');
const wireSuccess = wireLog.replace('BUILD FAILED', 'BUILD SUCCESSFUL');
const wireDiagnostics = [
  "[task :indexer:scip:compileKotlin] w: indexer/scip/build/generated/source/wire/scip/Descriptor.kt:226:9 Duplicate branch condition in 'when'.",
  "[task :indexer:scip:compileKotlin] w: indexer/scip/build/generated/source/wire/scip/Occurrence.kt:349:20 Unnecessary non-null assertion (!!) on a non-null receiver of type 'MutableList<Int>'.",
  "[task :indexer:scip:compileKotlin] w: indexer/scip/build/generated/source/wire/scip/Occurrence.kt:368:30 Unnecessary non-null assertion (!!) on a non-null receiver of type 'MutableList<Int>'.",
  "[task :indexer:scip:compileKotlin] w: indexer/scip/build/generated/source/wire/scip/SyntaxKind.kt:194:7 Duplicate branch condition in 'when'.",
  "[task :indexer:scip:compileKotlin] w: indexer/scip/build/generated/source/wire/scip/SyntaxKind.kt:205:7 Duplicate branch condition in 'when'.",
];
const gradleExceptions = () => JSON.parse(readFileSync(new URL('./gradle-diagnostics.json', import.meta.url)));

const nativeLogs = ['linux-x64', 'macos-arm64'].map(platform =>
  readFileSync(new URL(`./fixtures/gradle-native-${platform}.txt`, import.meta.url), 'utf8'));
const nativeCodecOrigins = [
  'org.gradle.internal.serialize.codecs.dm.transform.CalculateArtifactsCodec.decode(CalculateArtifactsCodec.kt:71)',
  'org.gradle.internal.serialize.codecs.dm.transform.DefaultTransformCodec.decode(DefaultTransformCodec.kt:65)',
  'org.gradle.internal.serialize.codecs.dm.transform.DefaultTransformCodec.decode(DefaultTransformCodec.kt:66)',
];
const nativeDownloadOrigin = 'org.jetbrains.kotlin.gradle.targets.native.toolchain.KotlinNativeBundleArtifactFormat$addKotlinNativeBundleConfiguration$1$1.execute(KotlinNativeBundleArtifactFormat.kt:79)';

test('historical Native fixtures retain all thirteen events per OS and bind to the actual source and job logs', () => {
  const provenance = JSON.parse(readFileSync(new URL('./fixtures/gradle-native-provenance.json', import.meta.url)));
  assert.equal(provenance.sourceHead, '0408df0fece209f36f06ec31c3bd040d98ace5df');
  assert.equal(provenance.testedSha, '4503e6ef826ea88063f7d973535daed8497c06c0');
  assert.equal(provenance.runId, '43159076362');
  assert.equal(provenance.runAttempt, 1);
  for (const [index, log] of nativeLogs.entries()) {
    const fixture = provenance.fixtures[index];
    assert.equal(createHash('sha256').update(log).digest('hex'), fixture.sha256);
    assert.equal(fixture.events.length, 13);
    assert.equal(log.match(/^.*\bhas been deprecated\b.*$/gm).length, 13);
    assert.equal(log.includes('BUILD SUCCESSFUL'), false, 'selective traces do not invent command completion');
    const diagnostics = collectDiagnostics(log);
    assert.equal(diagnostics.length, 13, fixture.file);
    for (const event of fixture.events) {
      assert.ok(diagnostics.some(line => line.endsWith(`[origin ${event.origin}]`)), event.eventId);
    }
    for (const origin of nativeCodecOrigins) {
      assert.equal(diagnostics.filter(line => line.endsWith(`[origin ${origin}]`)).length, 1, origin);
    }
  }
  const [linux, macos] = nativeLogs.map(log => collectDiagnostics(log));
  assert.equal(linux.filter(value => macos.includes(value)).length, 12);
  assert.equal(new Set([...linux, ...macos]).size, 14);
});

test('out-of-band Native download and codec events use emitter identity instead of the last unrelated project', () => {
  for (const log of nativeLogs) {
    const diagnostics = collectDiagnostics(log);
    assert.deepEqual(collectDiagnostics(log.replace('> Configure project :viz:desktop', '> Configure project :unrelated')), diagnostics);
    assert.deepEqual(collectDiagnostics(log.replace('> Configure project :viz:desktop', '> Task :unrelated:work')), diagnostics);
    assert.deepEqual(collectDiagnostics(log.replace('> Configure project :viz:desktop\n', '')), diagnostics);
    const download = diagnostics.find(line => line.endsWith(`[origin ${nativeDownloadOrigin}]`));
    assert.ok(download?.startsWith('[emitter kotlin-native bundle configuration] '));
    const codecs = diagnostics.filter(line => nativeCodecOrigins.some(origin => line.endsWith(`[origin ${origin}]`)));
    assert.equal(codecs.length, 3);
    assert.ok(codecs.every(line => line.startsWith('[emitter gradle configuration-cache] ')));
    assert.equal(diagnostics.filter(line => line.startsWith('[project :launcher] ')).length, 9);
    assert.equal(diagnostics.some(line => line.startsWith('[project :viz:desktop] ')), false);
  }
});

test('Native debt has fourteen exact identities and rejects changed types, messages, origins and call sites', () => {
  const exceptions = gradleExceptions();
  const diagnostics = [...new Set(nativeLogs.flatMap(log => collectDiagnostics(log)))].sort();
  const nativeExceptions = exceptions.filter(entry => diagnostics.includes(entry.diagnostic));
  assert.equal(nativeExceptions.length, 14);
  assert.equal(evaluate(diagnostics, exceptions, now).ok, true);
  assert.equal(evaluate(diagnostics, exceptions.filter(entry => !nativeExceptions.includes(entry)), now).unexpected.length, 14);
  assert.ok(nativeExceptions.every(entry => entry.owner === 'gvy build and CI maintainers' &&
    entry.issue.endsWith('/1603') && entry.expires === '2026-12-11'));
  for (const [before, after, count] of [
    // Unknown enum types retain the ordinary collector behavior; they cannot
    // borrow this Native attribution or its exact policy entries.
    ['KotlinNativeBundleArtifactsTypes', 'UnrecognizedNativeType', 2],
    ['This enum does not implement Named.', 'This enum has a different problem.', 4],
    ['CalculateArtifactsCodec.kt:71', 'CalculateArtifactsCodec.kt:72', 1],
    ['DefaultTransformCodec.kt:65', 'DefaultTransformCodec.kt:67', 1],
    ['DefaultTransformCodec.kt:66', 'DefaultTransformCodec.kt:68', 1],
    ['DefaultTransformCodec.decode(DefaultTransformCodec.kt:65)', 'OtherCodec.decode(OtherCodec.kt:65)', 1],
    ['KotlinNativeBundleArtifactFormat.kt:79', 'KotlinNativeBundleArtifactFormat.kt:80', 1],
    ['KotlinNativeBundleArtifactFormat$addKotlinNativeBundleConfiguration$1$1.execute', 'OtherPlugin.execute', 1],
    ['kotlin-native-prebuilt:2.3.0:', 'kotlin-native-prebuilt:2.3.1:', 1],
    ['KotlinNativeBundleArtifactFormat.kt:26', 'KotlinNativeBundleArtifactFormat.kt:27', 1],
    ['> Configure project :launcher', '> Configure project :other', 9],
  ]) {
    for (const log of nativeLogs) {
      assert.ok(log.includes(before), before);
      const changed = collectDiagnostics(log.replaceAll(before, after));
      const result = evaluate(changed, exceptions, now);
      assert.equal(result.ok, false, before);
      assert.equal(result.unexpected.length, count, before);
    }
  }
});

test('Native attribution cannot accept unrelated Gradle deprecations or bypass missing and expired policy', () => {
  const exceptions = gradleExceptions();
  const log = `${warning}\n\tat org.gradle.api.attributes.Attribute.of(Attribute.java:56)\n` +
    `\tat ${nativeCodecOrigins[0]}\n`;
  const diagnostics = collectDiagnostics(log);
  assert.deepEqual(diagnostics, [`[gradle] ${warning}`]);
  assert.equal(evaluate(diagnostics, exceptions, now).ok, false);
  for (const log of nativeLogs) {
    const diagnostics = collectDiagnostics(log);
    assert.equal(evaluate(diagnostics, [], now).unexpected.length, 13);
    assert.throws(() => evaluate(diagnostics, exceptions, new Date('2026-12-12T00:00:00Z')), /Expired exception/);
    const incomplete = check(log, { exceptions });
    assert.equal(incomplete.complete, false, 'historical selective traces remain incomplete command evidence');
    assert.equal(incomplete.ok, false);
  }
});

test('captured Gradle Wire warnings require five exact exceptions alongside the unchanged plugin debt', () => {
  const exceptions = gradleExceptions();
  const plugins = exceptions.filter(entry => entry.issue.endsWith('/1603'));
  const untracked = check(wireSuccess, { rootDir: wireRoot, exceptions: plugins });
  assert.equal(untracked.complete, true);
  assert.equal(untracked.ok, false);
  assert.deepEqual(untracked.unexpected, wireDiagnostics);
  assert.equal(untracked.accepted.length, 0);
  const tracked = check(wireSuccess, { rootDir: wireRoot, exceptions });
  assert.equal(tracked.ok, true, tracked.errors.join('\n'));
  assert.deepEqual(tracked.diagnostics, wireDiagnostics);
  assert.deepEqual(tracked.accepted.map(entry => entry.diagnostic), wireDiagnostics);
  assert.deepEqual(tracked.unexpected, []);
  assert.equal(plugins.length, 47);
  assert.equal(exceptions.length, 52);
});

test('Gradle Wire identities normalize only the supplied macOS or Linux worktree prefix', () => {
  const exceptions = gradleExceptions();
  for (const rootDir of ['/Users/runner/wt/gvy/ci/wire', '/home/runner/work/gvy/gvy']) {
    const log = wireSuccess.replaceAll(wireRoot, rootDir);
    const tracked = check(log, { rootDir, exceptions });
    assert.equal(tracked.ok, true, tracked.errors.join('\n'));
    assert.deepEqual(tracked.diagnostics, wireDiagnostics);
    assert.equal(tracked.accepted.length, 5);
    const foreignRoot = check(log, { rootDir: `${rootDir}-other`, exceptions });
    assert.equal(foreignRoot.ok, false);
    assert.equal(foreignRoot.unexpected.length, 5);
  }
});

test('a different Wire task, file, line, column, message or first-party source remains unexpected', async t => {
  const exceptions = gradleExceptions();
  const changes = [
    ['task', ':indexer:scip:compileKotlin', ':indexer:scip:compileTestKotlin', 5],
    ['file', '/Descriptor.kt', '/Another.kt', 1],
    ['line', 'Descriptor.kt:226:9', 'Descriptor.kt:227:9', 1],
    ['column', 'Descriptor.kt:226:9', 'Descriptor.kt:226:10', 1],
    ['message', "Duplicate branch condition in 'when'.", "Duplicate branch condition in another 'when'.", 1],
    ['first-party source', 'build/generated/source/wire/scip/Descriptor.kt', 'src/main/kotlin/scip/Descriptor.kt', 1],
  ];
  for (const [name, before, after, count] of changes) {
    await t.test(name, () => {
      assert.ok(wireSuccess.includes(before));
      const result = check(wireSuccess.replace(before, after), { rootDir: wireRoot, exceptions });
      assert.equal(result.complete, true);
      assert.equal(result.ok, false);
      assert.equal(result.unexpected.length, count);
      assert.equal(result.accepted.length, 5 - count);
    });
  }
});

test('accepted Wire diagnostics cannot hide the captured command failure, capture failure or expired debt', () => {
  const exceptions = gradleExceptions();
  const failed = check(wireLog, { rootDir: wireRoot, exceptions, commandExitCode: 1 });
  assert.equal(failed.complete, true);
  assert.equal(failed.ok, false);
  assert.equal(failed.commandExitCode, 1);
  assert.equal(failed.captureExitCode, 0);
  assert.deepEqual(failed.accepted.map(entry => entry.diagnostic), wireDiagnostics);
  assert.deepEqual(failed.unexpected, []);
  assert.match(failed.errors.join('\n'), /command failed with exit 1/);
  const capture = check(wireSuccess, { rootDir: wireRoot, exceptions, captureExitCode: 1 });
  assert.equal(capture.complete, false);
  assert.equal(capture.ok, false);
  assert.equal(capture.captureExitCode, 1);
  assert.equal(capture.accepted.length, 5);
  assert.match(capture.errors.join('\n'), /log capture exit code/);
  const expired = check(wireSuccess, { rootDir: wireRoot, exceptions: exceptions.map(entry =>
    entry.issue.endsWith('/1599') ? { ...entry, expires: '2026-11-10' } : entry) });
  assert.equal(expired.ok, false);
  assert.match(expired.errors.join('\n'), /Expired exception/);
});

test('CLI always publishes structured evidence and a raw copy, including warnings on failure', t => {
  const dir = mkdtempSync(join(tmpdir(), 'gvy-gradle-diagnostics-test-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const baseline = join(dir, 'baseline.json');
  const log = join(dir, 'gradle.log');
  const report = join(dir, 'report');
  const liveSummary = join(dir, 'live-summary.md');
  const previousSummary = process.env.GITHUB_STEP_SUMMARY;
  process.env.GITHUB_STEP_SUMMARY = liveSummary;
  t.after(() => {
    if (previousSummary === undefined) delete process.env.GITHUB_STEP_SUMMARY;
    else process.env.GITHUB_STEP_SUMMARY = previousSummary;
  });
  writeFileSync(liveSummary, 'Live job evidence only.\n');
  writeFileSync(baseline, '[]\n');
  const run = (exit = '0') => spawnSync(process.execPath, ['tools/ci/check-gradle-diagnostics.mjs',
    '--log', log, '--exit-code', exit, '--capture-exit-code', '0', '--producer', 'gradle-lint',
    '--baseline', baseline, '--report', report], {
    encoding: 'utf8', env: { ...process.env, GITHUB_STEP_SUMMARY: '' },
  });
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
  const memoryLog = `> Task :gls:compileKotlin\n${daemonMemoryAdvice.join('\n')}\nBUILD SUCCESSFUL in 1s\n`;
  writeFileSync(log, memoryLog);
  assert.notEqual(run().status, 0);
  const memoryResult = JSON.parse(readFileSync(join(report, 'diagnostics.json')));
  assert.equal(memoryResult.complete, true);
  assert.equal(memoryResult.commandExitCode, 0);
  assert.equal(memoryResult.ok, false);
  assert.deepEqual(memoryResult.unexpected, daemonMemoryAdvice.map(daemonDiagnostic).sort());
  assert.equal(readFileSync(join(report, 'raw.log'), 'utf8'), memoryLog);
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
  assert.equal(readFileSync(liveSummary, 'utf8').length, 'Live job evidence only.\n'.length);
});
