// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { inventory, evaluate, fingerprint } from './dependency-maintenance.mjs';

const today = new Date('2026-11-11T22:00:00Z');
const source = { id: 'MODULE.bazel:archive_override:rules_kotlin', sha256: 'a'.repeat(64) };
const record = () => ({
  id: 'kotlin-fork', issue: 'https://github.com/albertocavalcante/gvy/issues/1618',
  responsibleScope: 'Bazel maintainers', reason: 'Official compiler prebuilt was unavailable.',
  upstreamStatus: 'Released; integration pending.', upstreamTrigger: 'Official 2.3.0 is available.',
  lastVerified: '2026-11-11', reviewDue: '2026-11-18',
  verification: 'Release metadata inspected; integration has not run.',
  evidence: ['https://github.com/bazel-contrib/rules_kotlin/releases/tag/v2.3.0'],
  removalCriteria: 'Official graph and tests pass without fork.', nextAction: 'Run candidate integration.',
  sources: [{ ...source }],
});

test('valid exact accounting is independent of logs and warm caches', () => {
  assert.equal(evaluate([source], [record()], today).ok, true);
});

test('missing, changed, stale and duplicate sources fail', () => {
  assert.match(evaluate([source], [], today).errors.join('\n'), /Untracked/);
  assert.match(evaluate([{ ...source, sha256: 'b'.repeat(64) }], [record()], today).errors.join('\n'), /Changed/);
  assert.match(evaluate([], [record()], today).errors.join('\n'), /Stale/);
  assert.match(evaluate([source], [record(), { ...record(), id: 'second' }], today).errors.join('\n'), /Duplicate source/);
});

test('all accountability fields, issue URLs and evidence are required', () => {
  for (const field of ['responsibleScope', 'reason', 'upstreamStatus', 'upstreamTrigger', 'lastVerified',
    'reviewDue', 'verification', 'removalCriteria', 'nextAction', 'issue', 'id', 'sources', 'evidence']) {
    const entry = record();
    delete entry[field];
    assert.equal(evaluate([source], [entry], today).ok, false, field);
  }
  for (const issue of ['https://github.com/other/repo/issues/1', 'https://github.com/albertocavalcante/gvy/pull/1']) {
    assert.equal(evaluate([source], [{ ...record(), issue }], today).ok, false);
  }
});

test('expired, invalid, future verification and distant review dates fail without build output', () => {
  for (const dates of [
    { reviewDue: '2026-11-10' }, { reviewDue: '2026-02-30' },
    { lastVerified: '2026-11-12' }, { lastVerified: '2026-11-19' },
    { reviewDue: '2099-01-01' },
  ]) assert.equal(evaluate([source], [{ ...record(), ...dates }], today).ok, false);
  assert.equal(evaluate([source], [{ ...record(), reviewDue: '2026-11-11' }], today).ok, true);
});

test('inventory sees overrides, every patch and aliased/custom download primitives', () => {
  const files = new Map([
    ['MODULE.bazel', `# archive_override(module_name = "comment")\narchive_override(module_name = "fork", urls = ["https://example.test/f(x).tgz"])\nsingle_version_override(module_name = "resolver", patches = ["//third_party:fix.patch"])\nmaven.override(coordinates = "org:lib", target = "//tools:lib")`],
    ['third_party/fix.patch', 'patch body'],
    ['tools/new.bzl', 'load("@bazel_tools//tools/build_defs/repo:http.bzl", mirror = "http_file")\nmirror(name = "escape")'],
    ['tools/other.bzl', 'def impl(ctx):\n    ctx.download_and_extract(url = "https://example.test/f.tgz")'],
  ]);
  const items = inventory(files);
  assert.deepEqual(items.map(item => item.id), [
    'MODULE.bazel:archive_override:fork', 'MODULE.bazel:maven.override:org:lib',
    'MODULE.bazel:single_version_override:resolver', 'third_party/fix.patch:patch',
    'tools/new.bzl:direct-download', 'tools/other.bzl:direct-download',
  ]);
  files.set('tools/new.bzl', `${files.get('tools/new.bzl')}\n_VERSION = "2"`);
  assert.notEqual(inventory(files).find(item => item.id === 'tools/new.bzl:direct-download').sha256,
    items.find(item => item.id === 'tools/new.bzl:direct-download').sha256);
});

test('override fingerprints ignore comments/formatting but catch URLs, targets and patch lists', () => {
  const scan = text => inventory(new Map([['MODULE.bazel', text]]))[0];
  const original = scan('archive_override(module_name="a", urls=["https://example.test/a"])');
  assert.deepEqual(scan('# note\narchive_override( module_name = "a", # why\n urls = ["https://example.test/a"])'), original);
  assert.notEqual(scan('archive_override(module_name="a", urls=["https://example.test/b"])').sha256, original.sha256);
});

test('non-GMM Maven reproducibility settings are independent of unrelated dependency versions', () => {
  const scan = artifacts => inventory(new Map([['MODULE.bazel', `maven.install(artifacts=["${artifacts}"], fail_on_missing_checksum=False, additional_coursier_options=["--enable-modules"])`]]));
  const items = scan('group:a:1');
  assert.deepEqual(scan('group:a:2').filter(source => !source.id.endsWith(':gmm')), items.filter(source => !source.id.endsWith(':gmm')));
  assert.deepEqual(items.map(item => item.id), [
    'MODULE.bazel:maven.install:maven:gmm', 'MODULE.bazel:maven.install:maven:missing-checksums',
    'MODULE.bazel:maven.install:maven:sources-disabled', 'MODULE.bazel:maven.install:maven:unlocked',
  ]);
});

test('manual workaround anchors require an existing exact source and detect edits', () => {
  const manualSources = [{ path: 'fmt/Formatter.kt', anchor: 'class Workaround', id: 'formatter' }];
  const files = new Map([['fmt/Formatter.kt', 'class Workaround {}']]);
  assert.deepEqual(inventory(files, manualSources), [{ id: 'manual:formatter', sha256: fingerprint('class Workaround {}') }]);
  assert.throws(() => inventory(new Map(), manualSources), /Missing manual source/);
  assert.throws(() => inventory(new Map([['fmt/Formatter.kt', 'class Fixed {}']]), manualSources), /Missing manual anchor/);
  assert.throws(() => inventory(files, [{ ...manualSources[0], anchor: '' }]), /manual anchor/);
});

test('CLI fails closed for unknown flags and malformed ledger, and prints all owned debt', t => {
  const root = mkdtempSync(join(tmpdir(), 'gvy-dependency-test-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const script = fileURLToPath(new URL('./dependency-maintenance.mjs', import.meta.url));
  spawnSync('git', ['init', '-q', root]);
  mkdirSync(join(root, 'tools/ci'), { recursive: true });
  writeFileSync(join(root, 'MODULE.bazel'), 'archive_override(module_name="rules_kotlin", urls=["https://example.test/a"])');
  const discovered = inventory(new Map([['MODULE.bazel', 'archive_override(module_name="rules_kotlin", urls=["https://example.test/a"])']]));
  const entry = { ...record(), lastVerified: new Date().toISOString().slice(0, 10), reviewDue: new Date().toISOString().slice(0, 10), sources: discovered };
  const ledger = join(root, 'tools/ci/dependency-maintenance.json');
  writeFileSync(ledger, JSON.stringify({ records: [entry], manualSources: [] }));
  const summary = join(root, 'github-step-summary.md');
  const run = (...args) => spawnSync(process.execPath, [script, '--root', root, ...args], {
    encoding: 'utf8', env: { ...process.env, GITHUB_STEP_SUMMARY: summary },
  });
  assert.equal(run().status, 0);
  assert.match(readFileSync(summary, 'utf8'), /Temporary dependency maintenance/);
  assert.match(run().stdout, /Released; integration pending/);
  assert.match(run().stdout, /1618/);
  assert.equal(run('--ignore-overdue').status, 1);
  writeFileSync(ledger, '{bad');
  assert.equal(run().status, 1);
});


test('renamed Maven extensions and spaced member calls remain in the inventory', () => {
  const items = inventory(new Map([['MODULE.bazel', 'deps . override(coordinates="a:b", target="//tools:b")\ndeps . install(artifacts=["a:b:1"])']]));
  assert.ok(items.some(item => item.id === 'MODULE.bazel:maven.override:a:b'));
  assert.ok(items.some(item => item.id === 'MODULE.bazel:maven.install:maven:unlocked'));
});

test('changing the declared patched module version requires renewed verification', () => {
  const scan = version => inventory(new Map([['MODULE.bazel', 'bazel_dep(name="resolver",version="' + version + '")\nsingle_version_override(module_name="resolver",patches=["//:fix.patch"])']]))[0];
  assert.notEqual(scan('6.9').sha256, scan('6.10').sha256);
});

test('Maven lock escape requires a nonempty literal path and literal strict repin enforcement', () => {
  const unsafe = [
    '', 'lock_file=None,', 'lock_file="",', 'lock_file="   ",',
    'lock_file="""""", fail_if_repin_required=True,',
    "lock_file='''''', fail_if_repin_required=True,",
    'lock_file="""   """, fail_if_repin_required=True,',
    "lock_file='''   ''', fail_if_repin_required=True,",
    'lock_file=r"//:lock.json", fail_if_repin_required=True,',
    'lock_file="//:lock\\.json", fail_if_repin_required=True,',
    'lock_file=LOCK_PATH, fail_if_repin_required=True,',
    'lock_file="//:maven_install.json",',
    'lock_file="//:maven_install.json", fail_if_repin_required=False,',
    'lock_file="//:maven_install.json", fail_if_repin_required=STRICT,',
  ];
  const scan = attributes => inventory(new Map([['MODULE.bazel',
    `maven.install(artifacts=["a:b:1"], fetch_sources=True, fail_on_missing_checksum=True, ${attributes})`]]));
  for (const attributes of unsafe) {
    const items = scan(attributes);
    assert.equal(items.length, 1, attributes);
    assert.equal(items[0].id, 'MODULE.bazel:maven.install:maven:unlocked', attributes);
  }
  assert.deepEqual(scan('lock_file="//:maven_install.json", fail_if_repin_required=True,'), []);
  assert.notEqual(scan('lock_file=None,')[0].sha256, scan('lock_file="",')[0].sha256);
});

test('present unknown checksum policy remains debt; only literal True clears it', () => {
  const scan = value => inventory(new Map([['MODULE.bazel',
    `maven.install(artifacts=["a:b:1"], lock_file="//:lock.json", fail_if_repin_required=True, fetch_sources=True, ${value === undefined ? '' : `fail_on_missing_checksum=${value},`})`]]));
  assert.deepEqual(scan(undefined), []);
  assert.deepEqual(scan('True'), []);
  const hashes = new Set();
  for (const value of ['False', 'None', 'allow_missing', 'checksum_policy()', 'False if flag else True']) {
    const items = scan(value);
    assert.equal(items.length, 1, value);
    assert.equal(items[0].id, 'MODULE.bazel:maven.install:maven:missing-checksums', value);
    hashes.add(items[0].sha256);
  }
  assert.equal(hashes.size, 5, 'actual unknown policy tokens require distinct reviewed fingerprints');
});

test('GMM graph evidence expires when install artifacts or versions change with the same options', () => {
  const scan = artifacts => inventory(new Map([['MODULE.bazel',
    `maven.install(artifacts=[${artifacts}], additional_coursier_options=["--enable-modules", "--variant", "org.gradle.usage=runtime"])`]]))
    .find(source => source.id.endsWith(':gmm'));
  const before = scan('"com.fasterxml.jackson.core:jackson-databind:2.20.1"');
  for (const artifacts of [
    '"com.fasterxml.jackson.core:jackson-databind:2.21.2"',
    '"com.fasterxml.jackson.core:jackson-databind:2.20.1", "com.fasterxml.jackson.datatype:jackson-datatype-jdk8:2.20.1"',
  ]) {
    const after = scan(artifacts);
    assert.notEqual(before.sha256, after.sha256, artifacts);
    assert.match(evaluate([after], [{ ...record(), sources: [before] }], today).errors.join('\n'), /Changed source needs verification/);
  }
});

test('synthetic CLI tests leave an inherited CI summary untouched', t => {
  const dir = mkdtempSync(join(tmpdir(), 'gvy-summary-isolation-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const summary = join(dir, 'real-ci-summary.md');
  const sentinel = '# Actual CI evidence\n';
  writeFileSync(summary, sentinel);
  const env = { ...process.env, GITHUB_STEP_SUMMARY: summary };
  delete env.NODE_TEST_CONTEXT;
  const result = spawnSync(process.execPath, [
    '--test', '--test-reporter=tap',
    '--test-name-pattern=^(?:CLI fails closed|a failed build still reports)',
    fileURLToPath(new URL('./dependency-maintenance.test.mjs', import.meta.url)),
    fileURLToPath(new URL('./bazel-diagnostics.test.mjs', import.meta.url)),
  ], { encoding: 'utf8', env, timeout: 30_000 });
  assert.equal(result.status, 0, result.stdout + result.stderr);
  assert.match(result.stdout, /# pass 3\b/);
  assert.equal(readFileSync(summary, 'utf8'), sentinel);
});
