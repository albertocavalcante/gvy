// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import * as fs from 'node:fs';
import { randomBytes } from 'node:crypto';
import { join, resolve, dirname } from 'node:path';
import { pathToFileURL, fileURLToPath } from 'node:url';
import { performance } from 'node:perf_hooks';
import { setImmediate as yieldEvents } from 'node:timers/promises';
import { exclusive, intent, budget, readBounded, sha256, retainOutcome } from './io.mjs';
import { HERE, ROOT, readJSON, check, git, verifySupport, applyOverlay, projectTree, sourceState, requirePreserved } from './verify-source.mjs';
import { commands, runStages, remainingBudget, failureExit } from './stages.mjs';
import { waitForJava } from './observe-java.mjs';

export async function main(args) {
  check(args.length === 0, 'Fixed fixture-only entry accepts no arguments');
  const receipt = readJSON(join(HERE, 'receipt.json'));
  check(receipt.dispatchAllowed === true && /^[a-f0-9]{64}$/.test(receipt.independentSupportReviewSha256), 'HOLD: independent final support review and authorized publication are pending');
  check(/^[a-f0-9]{64}$/.test(receipt.independentProductProjectionReviewSha256), 'HOLD: exact current product composition review is pending');
  const identity = verifySupport(receipt);
  check(process.platform === 'linux' && process.arch === 'x64' && process.env.RUNNER_ENVIRONMENT === 'github-hosted', 'Isolated GitHub-hosted Linux x64 only');
  check(process.versions.node.split('.')[0] === '24', 'Hosted proof requires the reviewed Node24 runtime');
  check(process.env.GRADLE_USER_HOME === undefined || fs.realpathSync(process.env.GRADLE_USER_HOME) === join(fs.realpathSync(process.env.HOME), '.gradle'), 'Default Gradle home required');
  const temp = fs.realpathSync(process.env.RUNNER_TEMP), output = join(temp, 'detekt-adoption-fixture-proof');
  check(temp !== ROOT && !temp.startsWith(ROOT + '/'), 'Evidence must be outside product source');
  fs.mkdirSync(output, { mode: 0o700 });
  const startWall = Date.now(), startMono = performance.now(), nonce = randomBytes(16).toString('hex');
  const remaining = () => remainingBudget(startWall, startMono, Date.now(), performance.now());
  let interrupted;
  const onInt = () => { interrupted ??= 'SIGINT'; }, onTerm = () => { interrupted ??= 'SIGTERM'; };
  process.on('SIGINT', onInt); process.on('SIGTERM', onTerm);
  async function checkpoint() {
    await yieldEvents(); await yieldEvents();
    if (interrupted) { const error = Error('Hosted proof interrupted by ' + interrupted); error.exitCode = interrupted === 'SIGINT' ? 130 : 143; throw error; }
    check(remaining() > 0, 'Original hosted producer envelope expired');
  }
  const state = { schemaVersion: 1, complete: false, ok: false, fixtureOnly: true, productCommitAllowed: false, ...identity,
    expectedProductProjection: receipt.productTree, nonce, startedAt: new Date(startWall).toISOString(), wallDeadline: new Date(startWall + 55 * 60_000).toISOString(), results: [],
    canonical: { status: 'pending-final-source-A1901-and-canonical-gates', hooksExecuted: false, productCommitCreated: false }, containment: 'Owned POSIX groups plus visible nonce-owned declared Java observation; arbitrary escaped/nonce-removing descendants not proven' };
  let exitCode = 75;
  try {
    exclusive(join(output, 'identity.json'), state);
    const { createOwnedCommandScope } = await import('./owned-command.mjs');
    const jdk17 = fs.realpathSync(process.env.JAVA_HOME_17_X64), jdk21 = fs.realpathSync(process.env.JAVA_HOME_21_X64);
    const javaExecutables = [jdk17, jdk21].map(home => fs.realpathSync(join(home, 'bin/java')));
    check(![jdk17, jdk21].some(home => /[\s,]/.test(home)), 'JDK paths cannot contain argument delimiters');
    intent(output, { action: 'apply-fixed-runtime-overlay', patch: receipt.patch, expectedProductProjection: receipt.productTree, realSupportHead: identity.supportHead });
    await checkpoint(); applyOverlay(receipt);
    const initial = sourceState(receipt, output, 'initial');
    state.initialProductProjection = projectTree(receipt, output, 'initial.index');
    check(state.initialProductProjection === receipt.productTree, 'Dirty product projection differs from reviewed composition');
    const common = ['--no-configuration-cache', '--no-build-cache', '--rerun-tasks', '--no-daemon', '--no-parallel', '--max-workers=1', '--console=plain', '--warning-mode=all', '--no-scan', '--info', '--stacktrace',
      '-Dorg.gradle.deprecation.trace=true', '-Dorg.gradle.java.installations.auto-download=false', '-Dorg.gradle.java.installations.auto-detect=false',
      `-Dorg.gradle.java.installations.paths=${jdk17},${jdk21}`, '-Pkotlin.compiler.execution.strategy=in-process',
      `-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=768m -XX:+UseSerialGC -Dgvy.hostedProofNonce=${nonce}`];
    const env = { ...process.env, JAVA_HOME: jdk17, PATH: join(jdk17, 'bin') + ':' + process.env.PATH, GRADLE_OPTS: '',
      JAVA_OPTS: '-Xmx128m -Dgvy.hostedProofNonce=' + nonce, JDK_JAVA_OPTIONS: '-Xmx512m -Dgvy.hostedProofNonce=' + nonce,
      ORG_GRADLE_PROJECT_org_gradle_jvmargs: '', NODE_OPTIONS: '--max-old-space-size=256', GVY_HOSTED_PROOF_NONCE: nonce,
      GVY_HOSTED_PROOF_ROOT: ROOT, GVY_HOSTED_PROOF_OUTPUT: output, GVY_HOSTED_PROOF_TOKEN: nonce };
    const selected = commands(common, join(HERE, 'graph.init.gradle'), output);
    let previous = initial;
    const flow = await runStages({ checkpoint, stage: async name => {
      verifySupport(receipt); budget(output, 34 * 1024 * 1024);
      const stage = join(output, name); fs.mkdirSync(stage, { mode: 0o700 });
      const before = sourceState(receipt, stage, 'before'); requirePreserved(previous, before, receipt, false);
      if (name === 'format') retainFormattingTexts(stage, 'before', receipt);
      const rootSha = before.files.find(row => row.path === 'build.gradle.kts').sha256;
      const argv = [...selected[name]];
      if (['ordering', 'fixture'].includes(name)) argv.splice(1, 0, '-Pr6.rootScriptSha256=' + rootSha);
      // direnv runs repository environment setup; explicit env overrides its machine-specific heaps afterwards.
      const assignments = ['JAVA_HOME', 'PATH', 'GRADLE_OPTS', 'JAVA_OPTS', 'JDK_JAVA_OPTIONS', 'ORG_GRADLE_PROJECT_org_gradle_jvmargs', 'NODE_OPTIONS', 'GVY_HOSTED_PROOF_NONCE', 'GVY_HOSTED_PROOF_ROOT', 'GVY_HOSTED_PROOF_OUTPUT', 'GVY_HOSTED_PROOF_TOKEN'].map(key => key + '=' + env[key]);
      const command = ['direnv', 'exec', ROOT, '/usr/bin/env', ...assignments, '/bin/bash', join(HERE, 'producer.sh'), ...argv];
      const total = Math.floor(Math.min(900_000, remaining() - 20_000));
      check(total > 15_250, 'No producer/drain reserve');
      intent(stage, { name, command, nonce, totalMsIncludingDrain: total, sourceBefore: before, supportHead: identity.supportHead });
      await checkpoint(); const timeoutMs = Math.floor(Math.min(total - 12_000, remaining() - 32_000));
      check(timeoutMs > 3250, 'No producer budget after durable prelaunch IO');
      const scope = createOwnedCommandScope({ timeoutMs, maxOutputBytes: 16 * 1024 * 1024 });
      let actual, result, finalError;
      try {
        try { actual = await scope.run(command[0], command.slice(1), { cwd: ROOT, env }); }
        catch (error) { actual = { status: null, signal: null, error: String(error.message ?? error), lifecycle: { rawComplete: false, cleanupComplete: false, ambiguous: true }, stdoutBytes: Buffer.alloc(0), stderrBytes: Buffer.alloc(0) }; }
        const record = { name, command, streamContract: 'producer.sh merges producer stderr into stdout at the OS descriptor; helper stderr retains launcher diagnostics', status: actual.status, signal: actual.signal, error: actual.error, lifecycle: actual.lifecycle };
        // Keep known actual outcome in memory and persist metadata first, even when raw stream persistence fails.
        state.results.push(record);
        result = retainOutcome(record, actual.stdoutBytes, actual.stderrBytes, (file, value) => exclusive(join(stage, file), value));
      } finally { scope.dispose(); }
      try {
        result.javaDrain = await waitForJava(nonce, javaExecutables, () => Math.max(0, Math.min(10_000, remaining() - 5000)), checkpoint);
        exclusive(join(stage, 'java-drain.json'), result.javaDrain); result.ok &&= result.javaDrain.ok;
      } catch (error) { finalError = error; result.ok = false; }
      // Record readable source evidence on the failure path too; preservation errors never advance stages.
      try {
        const after = sourceState(receipt, stage, 'after');
        if (name === 'format') retainFormattingTexts(stage, 'after', receipt);
        requirePreserved(before, after, receipt, name === 'format'); previous = after;
        verifySupport(receipt); budget(output);
        if (result.ok) await validateStage(name, output, stage, identity.supportHead, actual);
      } catch (error) { finalError ??= error; result.ok = false; }
      if (finalError || !result.ok) exclusive(join(stage, 'stage-failure.json'), { originalOutcome: state.results.at(-1), error: finalError?.message ?? 'Producer, storage, Java drain or lifecycle failed', ok: false });
      exclusive(join(stage, 'result.json'), result);
      if (finalError) result.error = finalError.message;
      return result;
    } });
    Object.assign(state, flow); exitCode = flow.exitCode;
    state.finalProductProjection = projectTree(receipt, output, 'final.index');
  } catch (error) { state.error = String(error.message ?? error); state.complete = false; state.ok = false; exitCode = failureExit(error.exitCode); }
  finally {
    try {
      exclusive(join(output, 'result.json'), state);
      await checkpoint();
      if (!state.ok) exclusive(join(output, 'failure.json'), { ok: false, resultSuperseded: true, error: state.error ?? 'Fixture stages incomplete', knownResults: state.results });
    } catch (error) {
      state.ok = false; state.complete = false; exitCode = failureExit(error.exitCode);
      try { exclusive(join(output, 'finalization-failure.json'), { ok: false, resultSuperseded: true, knownResults: state.results, error: String(error.message ?? error) }); } catch { /* Original known outcomes remain in memory and attempted command metadata. */ }
    } finally { process.off('SIGINT', onInt); process.off('SIGTERM', onTerm); }
  }
  return exitCode;
}
function retainFormattingTexts(directory, label, receipt) {
  const target = join(directory, 'formatting-texts-' + label); fs.mkdirSync(target, { mode: 0o700 });
  const rows = receipt.allowedFormattingPaths.map((path, index) => {
    const bytes = readBounded(join(ROOT, path)), file = `${index}.txt`;
    exclusive(join(target, file), bytes); return { path, file, bytes: bytes.length, sha256: sha256(bytes) };
  });
  exclusive(join(target, 'manifest.json'), rows);
}
async function validateStage(name, output, stage, head, actual) {
  if (['format', 'ordering', 'fixture'].includes(name)) {
    const { checkGradleDiagnostics, loadNativeDiagnosticPolicy } = await import(pathToFileURL(join(ROOT, 'tools/ci/check-gradle-diagnostics.mjs')));
    const bytes = Buffer.concat([actual.stdoutBytes, actual.stderrBytes]);
    const report = checkGradleDiagnostics({ log: bytes.toString(), commandExitCode: actual.status, captureExitCode: actual.lifecycle.rawComplete ? 0 : 74,
      exceptions: [...readJSON(join(ROOT, 'tools/ci/gradle-diagnostics.json')), ...loadNativeDiagnosticPolicy()], rootDir: ROOT, encodingValid: new TextDecoder('utf-8', { fatal: true }).decode(bytes) !== undefined });
    exclusive(join(stage, 'diagnostics.json'), report); check(report.ok, 'Unaccounted actual Gradle diagnostics');
  }
  if (['ordering', 'fixture'].includes(name)) { const model = readJSON(join(output, 'graph-' + name + '.json')); check(model.complete === true && model.ok === true && model.errors.length === 0, 'Incomplete actual graph control'); }
  if (name === 'detekt-health') {
    const report = readJSON(join(output, 'detekt-health/analysis-health.json'));
    check(report.complete === true && report.ok === true && report.sameTestedHead === true && report.runs.length === 2 && report.runs.every((run, i) => run.originalCommandExitCode === [0, 1][i] && run.result.ok && run.context.testedSha === head && run.context.headSha === head), 'Wrong real FULL positive/negative control receipt');
  }
  if (name === 'formatting-health') {
    const report = readJSON(join(output, 'formatting-health/formatting-health.json'));
    check(report.complete === true && report.ok === true && report.fixtureCleanedUp === true && report.runs.length === 5 && report.runs.every((run, i) => run.name === ['positive', 'negative', 'apply', 'recheck', 'idempotent'][i] && run.originalCommandExitCode === [0, 1, 0, 0, 0][i] && run.context.testedSha === head && run.context.headSha === head), 'Wrong real five-stage formatter receipt');
  }
}
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try { process.exitCode = await main(process.argv.slice(2)); }
  catch (error) { console.error(error.message); process.exitCode = failureExit(error.exitCode); }
}
