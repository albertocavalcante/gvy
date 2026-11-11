import assert from 'node:assert/strict';
import test from 'node:test';
import { execFileSync, spawnSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { classifyPaths } from './ci-applicability.mjs';

test('known narrative changes exclude runtime suites with explicit reasons', () => {
  const report = classifyPaths(['README.md', 'AGENTS.md', 'docs/ci-diagnostics.md', 'kb/testing/plan.md']);
  assert.equal(report.runMain, false);
  assert.equal(report.runBazel, false);
  assert.equal(report.paths.length, 4);
  assert.ok(report.paths.every((path) => path.reason === 'narrative documentation'));
});

test('fixtures and executable documentation are inputs regardless of extension', () => {
  for (const path of ['tests/e2e/resources/workspaces/foo/README.md', 'parser/core/src/test/resources/input.md',
    'docs/website/package.json', 'docs/website/src/content/docs/example.md', 'docs/schema.json', 'unknown.md',
    'tests/fixtures/example.bzl', 'parser/api/src/test/resources/BUILD.bazel', 'docs/fixtures/example.md',
    'README.sh', 'AGENTS.py', 'CONTRIBUTING.kt', 'LICENSE.json']) {
    const report = classifyPaths([path]);
    assert.equal(report.runMain, true, path);
    assert.equal(report.runBazel, true, path);
  }
});

test('Bazel configuration has its own required validation', () => {
  const report = classifyPaths(['.bazelrc', 'bazel/ci.bazelrc', 'parser/api/BUILD.bazel']);
  assert.equal(report.runMain, false);
  assert.equal(report.runBazel, true);
});

test('new code and release branches cannot bypass validation', () => {
  for (const path of ['new-module/src/Foo.kt', 'build.gradle.kts', '.github/workflows/ci.yml', 'tools/ci/check-readiness.mjs']) {
    const report = classifyPaths(['CHANGELOG.md', path]);
    assert.equal(report.runMain, true);
    assert.equal(report.runBazel, true);
  }
});

test('empty change sets are conservative; invalid paths fail', () => {
  assert.equal(classifyPaths([]).runMain, true);
  for (const path of ['', '../secret', '/absolute', 'foo/../bar', 'foo\u0000bar']) {
    assert.throws(() => classifyPaths([path]), /Invalid repository path/);
  }
});

test('CLI binds checkout and exact event revisions, including deleted and renamed fixtures', (t) => {
  // Tiny synthetic fixture repository, not a project checkout or clone.
  const dir = mkdtempSync(join(tmpdir(), 'gvy-applicability-test-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const git = (...args) => execFileSync('git', args, { cwd: dir, encoding: 'utf8' }).trim();
  git('init', '-q');
  git('config', 'user.email', 'fixture@example.invalid');
  git('config', 'user.name', 'CI fixture');
  writeFileSync(join(dir, 'README.md'), 'narrative\n');
  git('add', 'README.md');
  git('commit', '-qm', 'fixture base');
  const base = git('rev-parse', 'HEAD');
  mkdirSync(join(dir, 'tests/fixtures'), { recursive: true });
  git('mv', 'README.md', 'tests/fixtures/README.md');
  git('commit', '-qm', 'fixture input');
  const source = git('rev-parse', 'HEAD');
  git('commit', '--allow-empty', '-qm', 'distinct tested revision');
  const tested = git('rev-parse', 'HEAD');
  const event = join(dir, 'event.json');
  const output = join(dir, 'output');
  const report = join(dir, 'report.json');
  writeFileSync(event, JSON.stringify({ pull_request: { base: { sha: base }, head: { sha: source } } }));
  const env = { ...process.env, GITHUB_EVENT_NAME: 'pull_request', GITHUB_EVENT_PATH: event, GITHUB_SHA: tested,
    GITHUB_RUN_ID: '123', GITHUB_RUN_ATTEMPT: '2', GITHUB_OUTPUT: output };
  const script = resolve('tools/ci/ci-applicability.mjs');
  const result = spawnSync(process.execPath, [script, report], { cwd: dir, env, encoding: 'utf8' });
  assert.equal(result.status, 0, result.stderr);
  const receipt = JSON.parse(readFileSync(report, 'utf8'));
  assert.deepEqual(receipt.paths.map(({ path }) => path), ['README.md', 'tests/fixtures/README.md']);
  assert.equal(receipt.context.headSha, source);
  assert.equal(receipt.context.testedSha, tested);
  assert.match(readFileSync(output, 'utf8'), /run_main_ci=true/);
  assert.notEqual(spawnSync(process.execPath, [script, report], { cwd: dir,
    env: { ...env, GITHUB_SHA: source }, encoding: 'utf8' }).status, 0);
  writeFileSync(event, JSON.stringify({ pull_request: { base: {}, head: { sha: source } } }));
  assert.notEqual(spawnSync(process.execPath, [script, report], { cwd: dir, env, encoding: 'utf8' }).status, 0);
});
