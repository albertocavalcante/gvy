// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict';
import { chmodSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import test from 'node:test';

const root = new URL('../../', import.meta.url);
const read = (path) => readFileSync(new URL(path, root), 'utf8');
const setup = 'uses: ./.github/actions/setup-licensing';
const check = 'uv run --offline --locked --no-dev --project .reuse python .reuse/check.py --root .';

function job(workflow, name) {
  const jobs = workflow.split(/^jobs:\s*$/m)[1];
  const match = jobs?.match(new RegExp(`^  ${name}:\\s*\\n([\\s\\S]*?)(?=^  [\\w-]+:\\s*$|(?![\\s\\S]))`, 'm'));
  assert.ok(match, `missing job ${name}`);
  return match[1];
}

function requireBefore(body, first, second) {
  const a = body.indexOf(first);
  const b = body.indexOf(second);
  assert.ok(a >= 0 && b > a, `${first} must precede ${second}`);
}

test('license producer is unconditional and in the required readiness needs', () => {
  const ci = read('.github/workflows/ci.yml');
  const license = job(ci, 'license');
  assert.doesNotMatch(license, /^    if:/m);
  requireBefore(license, setup, check);
  assert.match(job(ci, 'validate'), /needs:\s*\[[^\]]*\blicense\b/);
  // The release-artifact fixture tests (including the direct CLI gate) are
  // included in the always-running policy self-test without extra discovery.
  assert.match(job(ci, 'check-paths'), /node --test tools\/ci\/\*\.test\.mjs/);
  assert.throws(() => requireBefore(license.replace(check, ''), setup, check));
});

test('Gradle jobs install the locked licensing environment', () => {
  for (const [file, jobs] of [
    ['ci.yml', ['lint', 'build', 'e2e-test', 'dependency-check']],
    ['ci-launcher.yml', ['build-server', 'native']],
    ['nightly.yml', ['lint', 'build']],
    ['extension.yml', ['build-server']],
    ['gradle-compat.yml', ['scoped-lock-api']],
  ]) {
    const workflow = read(`.github/workflows/${file}`);
    for (const name of jobs) requireBefore(job(workflow, name), setup, 'gradle/actions/setup-gradle@');
  }
});

test('agent workflows provision the tools before granting Gradle-capable action execution', () => {
  for (const [file, name] of [['_claude-base.yml', 'claude'], ['claude-code-review.yml', 'claude-review']]) {
    const body = job(read(`.github/workflows/${file}`), name);
    requireBefore(body, setup, 'anthropics/claude-code-action@');
  }
});

test('release and parser publishing validate before mutations and publishing', () => {
  const release = job(read('.github/workflows/release.yml'), 'build-release');
  requireBefore(release, setup, check);
  requireBefore(release, check, 'Set build environment variables');
  requireBefore(release, check, './tools/release.sh build');
  const parser = read('.github/workflows/publish-groovyparser.yml');
  const build = job(parser, 'build');
  const publish = job(parser, 'publish');
  requireBefore(build, setup, check);
  requireBefore(build, check, 'Determine version');
  requireBefore(publish, setup, check);
  requireBefore(publish, check, './gradlew :parser:core:publish');
  assert.throws(() => requireBefore(publish.replace(check, ''), setup, check));
});

test('owner native, Rust and nightly safety gates remain present', () => {
  const native = job(read('.github/workflows/ci-launcher.yml'), 'native');
  assert.match(native, /--native-provisioning "\$NATIVE_TARGET"/);
  const zed = job(read('.github/workflows/ci.yml'), 'zed');
  for (const stage of ['rustup', 'check', 'build']) {
    assert.match(zed, new RegExp(`check-rust-diagnostics\\.mjs[^\\n]*zed-${stage}\\.log`));
  }
  const nightly = job(read('.github/workflows/nightly.yml'), 'publish');
  assert.match(nightly, /needs:\s*\[lint, build\]/);
  assert.match(nightly, /needs\.lint\.result == 'success'/);
  assert.match(nightly, /needs\.build\.result == 'success'/);
});

test('the bootstrap pins and syncs the runtime without fetching during validation', () => {
  const action = read('.github/actions/setup-licensing/action.yml');
  assert.match(action, /actions\/setup-python@6d4fd22b20465ccc0f7354476588e8df7c9ca0e0 # v7\.1\.0/);
  assert.match(action, /python -m pip install --require-hashes --only-binary=:all: -r \.reuse\/bootstrap-requirements\.txt/);
  assert.match(action, /uv sync --project \.reuse --locked --no-dev/);
  assert.match(check, /--offline --locked --no-dev/);
});

function assertMirrorPushInputs(workflow, config) {
  const on = workflow.split(/^jobs:\s*$/m)[0];
  const push = on.match(/^  push:\s*\n([\s\S]*?)(?=^  [\w-]+:|$(?![\s\S]))/m)?.[1];
  assert.ok(push, 'missing mirror push trigger');
  const pathList = push.match(/^    paths:\s*\n((?:      - [^\n]+\n)+)/m)?.[1];
  assert.ok(pathList, 'missing mirror push path filters');
  const patterns = [...pathList.matchAll(/^      - ['"]([^'"]+)['"]\s*$/gm)].map((match) => match[1]);
  for (const required of [
    'editors/code/**',
    'LICENSES/Apache-2.0.txt',
    'infra/copybara/vscode-groovy.bara.sky',
    '.github/workflows/mirror-vscode-groovy.yml',
  ]) assert.ok(patterns.includes(required), `mirror push filter must include ${required}`);
  const sourceInputs = config.match(/origin_files\s*=\s*glob\(\s*include\s*=\s*\[([^\]]+)\]/s)?.[1];
  assert.ok(sourceInputs, 'missing Copybara origin selection');
  for (const required of ['editors/code/**', 'LICENSES/Apache-2.0.txt']) {
    assert.ok(sourceInputs.includes(`"${required}"`), `Copybara must select ${required}`);
  }
}

test('editor mirror push trigger includes source, licensing and configuration inputs', () => {
  const workflow = read('.github/workflows/mirror-vscode-groovy.yml');
  const config = read('infra/copybara/vscode-groovy.bara.sky');
  assertMirrorPushInputs(workflow, config);
  for (const required of [
    'editors/code/**',
    'LICENSES/Apache-2.0.txt',
    'infra/copybara/vscode-groovy.bara.sky',
    '.github/workflows/mirror-vscode-groovy.yml',
  ]) {
    assert.throws(() => assertMirrorPushInputs(workflow.replace(`      - '${required}'\n`, ''), config));
  }
  assert.throws(() => assertMirrorPushInputs(workflow, config.replace('"LICENSES/Apache-2.0.txt"', '"missing.txt"')));
});

test('source export workflows check licensing before invoking Copybara', () => {
  for (const [file, name] of [['mirror-vscode-groovy.yml', 'mirror'], ['sync-rewrite-codenarc.yml', 'sync']]) {
    const body = job(read(`.github/workflows/${file}`), name);
    requireBefore(body, setup, check);
    requireBefore(body, check, 'uses: olivr/copybara-action@');
    const guard = body.slice(body.indexOf('- name: Check license metadata'), body.indexOf('- name: Run Copybara'));
    assert.doesNotMatch(guard, /continue-on-error:|^\s+if:/m);
    assert.throws(() => requireBefore(body.replace(setup, ''), setup, check));
    assert.throws(() => requireBefore(body.replace(check, ''), setup, check));
  }
});

test('the licensing bootstrap explicitly copies on Windows and retains other platform defaults', () => {
  const action = read('.github/actions/setup-licensing/action.yml');
  const step = action.slice(action.indexOf('- name: Install locked licensing dependencies'));
  const match = step.match(/\n      run: \|\n((?:        .*\n?)+)/);
  assert.ok(match, 'missing platform-aware licensing sync script');
  const script = match[1].split('\n').map((line) => line.slice(8)).join('\n');
  const directory = mkdtempSync(join(tmpdir(), 'gvy-license-sync-'));
  try {
    const executable = join(directory, 'uv');
    writeFileSync(executable, '#!/usr/bin/env node\nprocess.stdout.write(JSON.stringify(process.argv.slice(2)));\n');
    chmodSync(executable, 0o755);
    const args = (source, platform) => {
      const result = spawnSync('bash', ['-e', '-o', 'pipefail', '-c', source], {
        encoding: 'utf8',
        env: { ...process.env, PATH: `${directory}:${process.env.PATH}`, RUNNER_OS: platform },
      });
      assert.equal(result.status, 0, result.stderr || String(result.error));
      return JSON.parse(result.stdout);
    };
    const standard = ['sync', '--project', '.reuse', '--locked', '--no-dev'];
    const windows = [...standard, '--link-mode', 'copy'];
    assert.deepEqual(args(script, 'Windows'), windows);
    for (const platform of ['Linux', 'macOS']) assert.deepEqual(args(script, platform), standard);
    assert.throws(() => assert.deepEqual(args(script.replace('--link-mode copy', ''), 'Windows'), windows));
  } finally {
    rmSync(directory, { recursive: true });
  }
});
