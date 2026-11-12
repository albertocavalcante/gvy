// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { chmodSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { runInNewContext } from 'node:vm';
import test from 'node:test';
import { evaluateCiWorkflowContract, readCiJobs } from './ci-workflow-contract.mjs';

const source = readFileSync(new URL('../../.github/workflows/ci.yml', import.meta.url), 'utf8');

test('the actual workflow declares every job in the terminal readiness graph', () => {
  const result = evaluateCiWorkflowContract(source);
  assert.equal(result.ok, true, result.errors.join('\n'));
});

for (const [name, change] of [
  ['new independent job omitted from readiness', value => value + '\n  independent:\n    runs-on: ubuntu-24.04\n    steps: []\n'],
  ['dependency omitted from final needs', value => value.replace(', dependency-check]', ']')],
  ['duplicate source job', value => value + '\n  dependency-check:\n    steps: []\n'],
  ['quoted job key', value => value.replace('  dependency-check:\n', '  "dependency-check":\n')],
  ['anchored job', value => value.replace('  dependency-check:\n', '  dependency-check: &shared\n')],
  ['mapping merge', value => value + '\n  <<: *shared\n'],
  ['duplicate final needs', value => value.replace('    needs: [license,', '    needs: check-paths\n    needs: [license,')],
  ['unsupported final needs shape', value => value.replace('    needs: [license,', '    needs: &shared [lint,')],
  ['observer with conditional success shortcut', value => value.replaceAll('    if: always()\n', '    if: success()\n')],
  ['missing jobs block', value => value.replace('jobs:\n', 'jobs: &all\n')],
  ['a second jobs block', value => value + '\njobs:\n  hidden:\n    steps: []\n'],
]) {
  test(`source graph fails closed: ${name}`, () => {
    const changed = change(source);
    assert.notEqual(changed, source, 'the mutation must alter the actual source');
    assert.equal(evaluateCiWorkflowContract(changed).ok, false);
  });
}

test('existing classifier output and the typed manual input determine dependency applicability', () => {
  const jobs = readCiJobs(source);
  const output = jobs.get('check-paths').lines.find(line => line.startsWith('      run_dependency_check: '));
  assert.ok(output);
  assert.match(source, /      dependency_updates:\n        description: [^\n]+\n        required: false\n        type: boolean\n        default: false\n/);
  // The source contract first verifies this one expression exactly. These known
  // string/boolean inputs use the common JS/GitHub subset; this is not a general
  // GitHub expression evaluator.
  assert.equal(evaluateCiWorkflowContract(source).ok, true);
  const expression = output.slice('      run_dependency_check: ${{ '.length, -' }}'.length);
  for (const [name, runMain, eventName, ref, override, expected] of [
    ['main code push', true, 'push', 'refs/heads/main', false, true],
    ['main narrative push', false, 'push', 'refs/heads/main', false, false],
    ['fork PR', true, 'pull_request', 'refs/pull/123/merge', false, false],
    ['PR cannot use a dispatch override', true, 'pull_request', 'refs/pull/123/merge', true, false],
    ['feature manual default', true, 'workflow_dispatch', 'refs/heads/feature', false, false],
    ['feature manual opt-in', true, 'workflow_dispatch', 'refs/heads/feature', true, true],
    ['main manual default', true, 'workflow_dispatch', 'refs/heads/main', false, true],
    ['narrative applicability remains excluded', false, 'workflow_dispatch', 'refs/heads/feature', true, false],
  ]) {
    const result = runInNewContext(expression, {
      steps: { filter: { outputs: { run_main_ci: String(runMain) } } },
      github: { ref, event_name: eventName }, inputs: { dependency_updates: override },
    }, { timeout: 100 });
    assert.equal(result, expected, name);
  }
});

// Read only this named step's reviewed plain/folded command form. Unsupported
// source changes require deliberate updates, not a general YAML interpretation.
function dependencyCommand() {
  const lines = source.split('\n');
  const marker = '      - name: Check for dependency updates';
  assert.equal(lines.filter(line => line === marker).length, 1);
  const start = lines.indexOf(marker);
  const body = [];
  for (const line of lines.slice(start + 1)) {
    if (line.trim() && !line.startsWith('        ')) break;
    body.push(line);
  }
  const commands = body.filter(line => line.startsWith('        run: '));
  assert.equal(commands.length, 1);
  const value = commands[0].slice('        run: '.length);
  if (value !== '>-') return value;
  const at = body.indexOf(commands[0]);
  const folded = [];
  for (const line of body.slice(at + 1)) {
    if (line.trim() && !line.startsWith('          ')) break;
    if (line.trim()) folded.push(line.trim());
  }
  assert.ok(folded.length);
  return folded.join(' ');
}

function commandFixture(t, mode) {
  const dir = mkdtempSync(join(tmpdir(), 'gvy-dependency-command-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  mkdirSync(join(dir, 'tools'));
  symlinkSync(resolve('tools/ci'), join(dir, 'tools/ci'), 'dir');
  symlinkSync(process.execPath, join(dir, 'node'));
  const testedSha = 'b'.repeat(40);
  const headSha = 'a'.repeat(40);
  const executable = (name, body) => {
    const path = join(dir, name);
    writeFileSync(path, `#!${process.execPath}\n${body}`);
    chmodSync(path, 0o755);
  };
  executable('git', `
import { mkdirSync, readdirSync, writeFileSync } from 'node:fs';
if (process.argv[2] === 'rev-parse') console.log('${testedSha}');
else if (process.argv[2] === 'status') {
  if (process.env.FAKE_GRADLE_MODE === 'capture-failure') {
    for (const entry of readdirSync(process.env.RUNNER_TEMP + '/dependency-gradle-evidence')) {
      writeFileSync(process.env.RUNNER_TEMP + '/dependency-gradle-evidence/' + entry + '/console.log', 'occupied capture');
    }
  }
  console.log('');
} else process.exitCode = 1;
`);
  executable('gradlew', `
import { writeFileSync } from 'node:fs';
writeFileSync('args.json', JSON.stringify(process.argv.slice(2)));
console.log('> Task :dependencyUpdates');
if (process.env.FAKE_GRADLE_MODE === 'warning') console.error('WARNING: Unexpected dependency tool warning');
console.log('Dependency report generated');
console.log('BUILD ' + (process.env.FAKE_GRADLE_MODE === 'failure' ? 'FAILED' : 'SUCCESSFUL') + ' in 1s');
process.exitCode = process.env.FAKE_GRADLE_MODE === 'failure' ? 7 : 0;
`);
  writeFileSync(join(dir, 'event.json'), JSON.stringify({ pull_request: { head: { sha: headSha } } }));
  const env = { ...process.env, PATH: dir, RUNNER_TEMP: dir, FAKE_GRADLE_MODE: mode,
    GITHUB_ACTIONS: 'true', GITHUB_SHA: testedSha, GITHUB_EVENT_PATH: join(dir, 'event.json'),
    GITHUB_RUN_ID: '123', GITHUB_RUN_ATTEMPT: '2', GVY_GRADLE_MEMORY_PROBE: 'false' };
  delete env.GVY_GRADLE_MEMORY_PROFILE;
  delete env.GITHUB_STEP_SUMMARY;
  const run = spawnSync('/bin/bash', ['-c', dependencyCommand()], { cwd: dir, env, encoding: 'utf8', timeout: 10000 });
  const reports = join(dir, 'dependency-gradle-evidence');
  return { dir, run, summaries: () => readdirSync(reports).map(name => JSON.parse(readFileSync(join(reports, name, 'summary.json'), 'utf8'))) };
}

for (const mode of ['failure', 'warning', 'capture-failure']) {
  test(`actual dependency workflow command rejects ${mode}`, t => {
    const fixture = commandFixture(t, mode);
    assert.equal(fixture.run.status, mode === 'failure' ? 7 : 1, fixture.run.stdout + fixture.run.stderr);
    const [summary] = fixture.summaries();
    assert.equal(summary.ok, false);
    if (mode === 'failure') assert.equal(summary.commandExitCode, 7);
    if (mode === 'warning') {
      assert.equal(summary.commandExitCode, 0);
      assert.equal(summary.captureExitCode, 0);
      assert.equal(summary.unexpected.length, 1);
    }
    if (mode === 'capture-failure') assert.notEqual(summary.captureExitCode, 0);
  });
}

test('actual dependency workflow command retains complete current invocation evidence', t => {
  const fixture = commandFixture(t, 'success');
  assert.equal(fixture.run.status, 0, fixture.run.stdout + fixture.run.stderr);
  const [summary] = fixture.summaries();
  assert.equal(summary.ok, true);
  assert.equal(summary.complete, true);
  assert.equal(summary.producer, 'dependency-updates');
  assert.equal(summary.context.headSha, 'a'.repeat(40));
  assert.equal(summary.context.testedSha, 'b'.repeat(40));
  assert.equal(summary.context.runId, '123');
  assert.equal(summary.context.runAttempt, 2);
  assert.match(summary.log.sha256, /^[a-f0-9]{64}$/);
  assert.ok(summary.log.bytes > 0);
  assert.deepEqual(JSON.parse(readFileSync(join(fixture.dir, 'args.json'), 'utf8')).slice(-3),
    ['--console=plain', '--warning-mode=all', '-Dorg.gradle.deprecation.trace=true']);
});
