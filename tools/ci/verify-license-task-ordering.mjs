// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, realpathSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, isAbsolute, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { readEvidenceText } from './read-evidence.mjs';

const repository = fileURLToPath(new URL('../../', import.meta.url));
const hash = bytes => createHash('sha256').update(bytes).digest('hex');

// Extract this single reviewed top-level block, rather than interpreting Kotlin.
// A changed source layout requires deliberate fixture maintenance.
export function licensingPolicy(source) {
  const blocks = [...source.matchAll(/^allprojects \{\n[\s\S]*?^\}\n/gm)];
  assert.equal(blocks.length, 1, 'Expected exactly one plain allprojects licensing block');
  assert.ok(blocks[0][0].includes('licenseCheck'), 'The extracted block must contain licensing policy');
  return blocks[0][0];
}

function fixture(policy, mode) {
  return `// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

val licenseCheck = tasks.register("licenseCheck") {
    doLast {
        check(!rootProject.file("producer/build/order-probe.txt").exists()) { "LICENSE_PROBE_RAN_EARLY" }
        if (providers.gradleProperty("fixtureRejectLicense").isPresent) {
            error("LICENSE_REJECTED")
        }
        rootProject.file("license.done").writeText("passed")
    }
}

subprojects {
    tasks.register("unselectedSentinel") {
        error("LICENSE_UNSELECTED_REALIZED")
    }
    tasks.register("compileKotlin") {
        doLast {
            check(!rootProject.file("license.done").exists()) { "LICENSE_FOCUSED_DEPENDENCY" }
            file("compile.done").writeText("passed")
        }
    }
    tasks.register("writeProbe") {
        doLast {
            check(rootProject.file("license.done").isFile) { "LICENSE_PROBE_RAN_EARLY" }
            file("build/order-probe.txt").apply {
                parentFile.mkdirs()
                writeText("passed")
            }
        }
    }
    for (gate in listOf("check", "lint", "quality", "publishProbe")) {
        tasks.register(gate) { dependsOn("writeProbe") }
    }
}

${policy}
gradle.taskGraph.whenReady {
    val selected = allTasks
    check(selected.none { it.name == "unselectedSentinel" }) { "LICENSE_UNSELECTED_SELECTED" }
    if ("${mode}" == "focused") {
        check(selected.none { it.path == ":licenseCheck" }) { "LICENSE_FOCUSED_DEPENDENCY" }
        check(selected.map { it.path } == listOf(":producer:compileKotlin")) { "LICENSE_FOCUSED_GRAPH" }
    } else {
        val license = selected.single { it.path == ":licenseCheck" }
        check(license.taskDependencies.getDependencies(license).isEmpty()) { "LICENSE_HAS_PREREQUISITES" }
        check(license.mustRunAfter.getDependencies(license).isEmpty()) { "LICENSE_SELF_ORDER" }
        for (task in selected.filter { it != license }) {
            check(license in task.mustRunAfter.getDependencies(task)) { "LICENSE_ORDERING_MISSING" }
        }
    }
    rootProject.file("selected-tasks.txt").writeText(selected.joinToString("\\n") { it.path })
}
`;
}

function cacheFixture(policy) {
  return `val licenseCheck = tasks.register("licenseCheck") {
    doLast { System.out.println("LICENSE_CACHE_LICENSE") }
}
project(":producer") {
    tasks.register("lint") { doLast { System.out.println("LICENSE_CACHE_PRODUCER") } }
}
project(":consumer") {
    tasks.register("check") { doLast { System.out.println("LICENSE_CACHE_CONSUMER") } }
}

${policy}`;
}

export function verifyCacheLog(log, phase) {
  assert.ok(phase === 'store' || phase === 'reuse');
  const lines = log.split(/\r?\n/);
  const store = lines.filter(line => line === 'Configuration cache entry stored.').length;
  const reuse = lines.filter(line => line === 'Reusing configuration cache.').length;
  assert.equal(store, phase === 'store' ? 1 : 0, 'Expected cache store only on the first invocation');
  assert.equal(reuse, phase === 'reuse' ? 1 : 0, 'Expected cache reuse only on the second invocation');
  const markers = lines.filter(line => line.startsWith('LICENSE_CACHE_'));
  assert.equal(markers.length, 3, 'Each task action must run exactly once');
  assert.equal(markers[0], 'LICENSE_CACHE_LICENSE', 'License must run before both producer actions');
  assert.deepEqual(markers.slice(1).sort(), ['LICENSE_CACHE_CONSUMER', 'LICENSE_CACHE_PRODUCER']);
}

function mutations(policy) {
  const ordering = '            mustRunAfter(licenseCheck)\n';
  assert.equal(policy.split(ordering).length, 2, 'Review the ordering mutation when its source changes');
  const lazy = '    tasks.configureEach {\n';
  assert.equal(policy.split(lazy).length, 2, 'Review the realization mutation when its source changes');
  return [
    { name: 'missing-order', policy: policy.replace(ordering, ''), expected: 'LICENSE_ORDERING_MISSING' },
    { name: 'eager-selection', policy: policy.replace(lazy, '    tasks.all {\n'), expected: 'LICENSE_UNSELECTED_REALIZED' },
    { name: 'ordered', policy },
    { name: 'focused', policy },
    { name: 'license-rejected', policy, expected: 'LICENSE_REJECTED', reject: true },
  ];
}

function boundedText(path, limit) {
  return readEvidenceText(path, limit);
}

export function verifyInvocation(summary, exitCode, log, control, producer) {
  assert.equal(summary.schemaVersion, 1);
  assert.equal(summary.producer, producer);
  assert.equal(summary.log?.bytes, Buffer.byteLength(log));
  assert.equal(summary.log?.sha256, hash(log));
  assert.equal(summary.complete, true, 'The canonical capture must be complete, including negative controls');
  assert.equal(summary.captureExitCode, 0);
  assert.deepEqual(summary.unexpected, []);
  assert.ok(Number.isInteger(exitCode) && exitCode >= 0 && exitCode <= 255, 'Invalid process exit');
  assert.equal(exitCode, summary.commandExitCode, 'The process must retain the original Gradle exit');
  if (control.expected) {
    assert.notEqual(exitCode, 0, `${control.name} must fail`);
    assert.deepEqual(summary.errors, [`Gradle command failed with exit ${exitCode}`]);
    assert.equal(summary.ok, false);
    assert.ok(log.includes(control.expected), 'Only the intended failure witness satisfies a negative control');
  } else {
    assert.equal(exitCode, 0, `${control.name} must succeed`);
    assert.deepEqual(summary.errors, []);
    assert.equal(summary.ok, true);
  }
}

function runGradle(args) {
  return new Promise((finish, fail) => {
    const child = spawn(process.execPath, args, { cwd: repository, stdio: 'inherit', detached: true,
      env: { ...process.env, JDK_JAVA_OPTIONS: '-Xmx256m -XX:MaxMetaspaceSize=256m', GVY_GRADLE_MEMORY_PROBE: 'false' } });
    let stopped = false;
    let hardStop;
    const signalGroup = signal => {
      if (!child.pid) return;
      try { process.kill(-child.pid, signal); }
      catch (error) { if (error.code !== 'ESRCH') throw error; }
    };
    const stop = signal => {
      stopped = true;
      signalGroup(signal);
      hardStop ??= setTimeout(() => signalGroup('SIGKILL'), 15000);
    };
    const interrupt = () => stop('SIGINT');
    const terminate = () => stop('SIGTERM');
    const deadline = setTimeout(terminate, 180000);
    process.on('SIGINT', interrupt);
    process.on('SIGTERM', terminate);
    const cleanup = () => {
      clearTimeout(deadline);
      clearTimeout(hardStop);
      process.off('SIGINT', interrupt);
      process.off('SIGTERM', terminate);
      if (stopped) signalGroup('SIGKILL');
    };
    child.once('error', () => {
      cleanup();
      fail(new Error('Cannot start the canonical fixture wrapper'));
    });
    child.once('close', (code, signal) => {
      cleanup();
      if (stopped || signal !== null || code === null) fail(new Error('The fixture was interrupted or exceeded its deadline'));
      else finish(code);
    });
  });
}

export async function verifyLicenseTaskOrdering(reportRoot) {
  assert.notEqual(process.platform, 'win32', 'This fixture requires the approved POSIX hosted execution profile');
  assert.ok(isAbsolute(reportRoot), 'The ordering report root must be absolute');
  // Fresh directories prevent an earlier receipt or marker from satisfying a case.
  mkdirSync(reportRoot, { mode: 0o700 });
  const outside = relative(realpathSync(repository), realpathSync(reportRoot));
  assert.ok(outside === '..' || outside.startsWith('..' + sep) || isAbsolute(outside),
    'Fixture evidence must be outside the source checkout');
  const source = readFileSync(join(repository, 'build.gradle.kts'), 'utf8');
  const policy = licensingPolicy(source);
  const report = { schemaVersion: 1, buildFileSha256: hash(source), policySha256: hash(policy), cases: [], currentCase: null, ok: false,
    limits: ['Real Gradle fixtures exercise the extracted policy. They do not execute gvy Kotlin compilation, REUSE, or full lint.',
      'Full-repository lintFix, explicit root formatting, parallel lint and focused compile remain separate checks.',
      'No numeric daemon-memory, RSS, Native provisioning or uploaded-artifact consumption claim.',
      'Cache-reuse checks target only the isolated policy fixture; actual project reuse remains unresolved in #1948.'] };
  let cacheProject;
  const failures = [];
  try {
    for (const control of mutations(policy)) {
      report.currentCase = control.name;
      const directory = join(reportRoot, control.name);
      const project = join(directory, 'fixture');
      mkdirSync(project, { recursive: true, mode: 0o700 });
      for (const name of ['producer', 'consumer', 'publisher']) mkdirSync(join(project, name));
      writeFileSync(join(project, 'settings.gradle.kts'), 'rootProject.name = "license-policy-fixture"\ninclude("producer", "consumer", "publisher")\n');
      writeFileSync(join(project, 'build.gradle.kts'), fixture(control.policy, control.name));
      writeFileSync(join(project, 'gradle.properties'), 'org.gradle.configuration-cache=false\norg.gradle.parallel=true\n');
      const tasks = control.name === 'focused' ? [':producer:compileKotlin']
        : [':producer:lint', ':consumer:check', ':publisher:publishProbe'];
      const producer = `license-ordering-${control.name}`;
      const evidence = join(directory, 'evidence');
      const args = [join(repository, 'tools/ci/run-gradle.mjs'), '--report-root', evidence, '--producer', producer,
        '--project-dir', project, ...tasks, '--offline', '--no-daemon', '--parallel', '--max-workers=2',
        '--no-configuration-cache', '-Dorg.gradle.jvmargs=-Xms64m -Xmx512m -XX:MaxMetaspaceSize=384m',
        '-Dorg.gradle.scan=false', '--stacktrace'];
      if (control.reject) args.push('-PfixtureRejectLicense=true');
      const exitCode = await runGradle(args);
      const directories = readdirSync(evidence);
      assert.equal(directories.length, 1, 'Expected exactly one fresh canonical invocation');
      const invocation = join(evidence, directories[0]);
      const summary = JSON.parse(boundedText(join(invocation, 'summary.json'), 256 * 1024));
      const log = boundedText(join(invocation, 'console.log'), 4 * 1024 * 1024);
      verifyInvocation(summary, exitCode, log, control, producer);
      const selected = control.name === 'eager-selection' || control.name === 'missing-order' ? null
        : boundedText(join(project, 'selected-tasks.txt'), 64 * 1024).split('\n');
      if (control.name === 'focused') {
        assert.deepEqual(selected, [':producer:compileKotlin']);
        assert.equal(readFileSync(join(project, 'producer/compile.done'), 'utf8'), 'passed');
      }
      if (control.name === 'ordered') {
        assert.equal(readFileSync(join(project, 'license.done'), 'utf8'), 'passed');
        for (const name of ['producer', 'consumer', 'publisher']) {
          assert.equal(readFileSync(join(project, name, 'build/order-probe.txt'), 'utf8'), 'passed');
        }
      }
      if (control.name === 'license-rejected') {
        assert.equal(existsSync(join(project, 'license.done')), false);
        for (const name of ['producer', 'consumer', 'publisher']) {
          assert.equal(readdirSync(join(project, name)).length, 0, 'Rejected licensing must prevent protected probe execution');
        }
      }
      const result = { name: control.name, expectedFailure: control.expected ?? null, commandExitCode: summary.commandExitCode,
        captureExitCode: summary.captureExitCode, complete: summary.complete, context: summary.context,
        policySha256: hash(control.policy), log: summary.log, selected, ok: true };
      report.cases.push(result);
    }
    cacheProject = mkdtempSync(join(dirname(reportRoot), 'license-cache-fixture-'));
    for (const name of ['producer', 'consumer']) mkdirSync(join(cacheProject, name));
    writeFileSync(join(cacheProject, 'settings.gradle.kts'), 'rootProject.name = "license-cache-fixture"\ninclude("producer", "consumer")\n');
    writeFileSync(join(cacheProject, 'build.gradle.kts'), cacheFixture(policy));
    for (const phase of ['store', 'reuse']) {
      const name = `cache-${phase}`;
      report.currentCase = name;
      const producer = `license-ordering-${name}`;
      const evidence = join(reportRoot, name, 'evidence');
      const args = [join(repository, 'tools/ci/run-gradle.mjs'), '--report-root', evidence, '--producer', producer,
        '--project-dir', cacheProject, ':producer:lint', ':consumer:check', '--offline', '--no-daemon',
        '--parallel', '--max-workers=2', '--configuration-cache', '--configuration-cache-problems=fail',
        '-Dorg.gradle.jvmargs=-Xms64m -Xmx512m -XX:MaxMetaspaceSize=384m', '-Dorg.gradle.scan=false', '--stacktrace'];
      const exitCode = await runGradle(args);
      const directories = readdirSync(evidence);
      assert.equal(directories.length, 1, 'Expected exactly one fresh canonical invocation');
      const invocation = join(evidence, directories[0]);
      const summary = JSON.parse(boundedText(join(invocation, 'summary.json'), 256 * 1024));
      const log = boundedText(join(invocation, 'console.log'), 4 * 1024 * 1024);
      verifyInvocation(summary, exitCode, log, { name }, producer);
      verifyCacheLog(log, phase);
      report.cases.push({ name, expectedFailure: null, commandExitCode: summary.commandExitCode,
        captureExitCode: summary.captureExitCode, complete: summary.complete, context: summary.context,
        policySha256: hash(policy), log: summary.log, ok: true });
    }
    report.currentCase = null;
    report.ok = true;
  } catch (error) {
    failures.push(error);
    report.ok = false;
  } finally {
    // Retain fixture source, selected tasks and canonical evidence; discard only owned build outputs.
    for (const name of ['missing-order', 'eager-selection', 'ordered', 'focused', 'license-rejected']) {
      const project = join(reportRoot, name, 'fixture');
      for (const output of ['.gradle', 'build', 'producer/build', 'consumer/build', 'publisher/build']) {
        try {
          rmSync(join(project, output), { recursive: true, force: true });
        } catch (error) {
          failures.push(error);
          report.ok = false;
        }
      }
    }
    try {
      if (cacheProject) rmSync(cacheProject, { recursive: true, force: true });
    } catch (error) {
      failures.push(error);
      report.ok = false;
    }
    try {
      writeFileSync(join(reportRoot, 'ordering-verification.json'), JSON.stringify(report, null, 2) + '\n', { mode: 0o600, flag: 'wx' });
    } catch (error) {
      failures.push(error);
      report.ok = false;
    }
  }
  if (failures.length) throw new AggregateError(failures, 'License ordering verification or evidence cleanup failed');
  return report;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const args = process.argv.slice(2);
    assert.equal(args.length, 3);
    assert.equal(args[0], '--execute');
    assert.equal(args[1], '--report-root');
    const report = await verifyLicenseTaskOrdering(args[2]);
    console.log(JSON.stringify({ ok: report.ok, cases: report.cases.length, buildFileSha256: report.buildFileSha256 }));
  } catch {
    console.error('License ordering verification failed; preserve and inspect the retained case evidence.');
    process.exitCode = 1;
  }
}
