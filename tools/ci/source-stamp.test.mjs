// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, readFileSync, symlinkSync, rmSync, mkdirSync, chmodSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { execFileSync } from 'node:child_process';
import { collectStatus, renderStatus, renderProperties } from '../build-info/source-stamp.mjs';

const version = '// x-release-please-start-version\nval baseVersion = "0.5.4"\n// x-release-please-end\n';
function git(root, ...args) {
  return execFileSync('git', args, { cwd: root, stdio: 'pipe' }).toString().trim();
}
function fixture(fn) {
  const root = mkdtempSync(join(tmpdir(), 'source-stamp-'));
  try {
    git(root, 'init', '-q');
    git(root, 'config', 'user.email', 'test@example.invalid');
    git(root, 'config', 'user.name', 'Test');
    writeFileSync(join(root, 'tracked'), 'first');
    git(root, 'add', 'tracked');
    git(root, 'commit', '-qm', 'initial');
    fn(root);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
}
function props(status, volatile = '') { return renderProperties(renderStatus(status), volatile, version); }

test('clean, tracked and untracked changes are honestly stamped', () => fixture((root) => {
  const clean = collectStatus(root, {});
  assert.equal(clean.DIRTY, 'false');
  assert.match(clean.SHA, /^[a-f0-9]{40}$/);
  assert.match(props(clean), /displayVersion=0\.5\.4-dev\+g[a-f0-9]{7}\n/);
  assert.equal(props(clean), props(clean));
  writeFileSync(join(root, 'tracked'), 'changed');
  const tracked = collectStatus(root, {});
  assert.equal(tracked.DIRTY, 'true');
  assert.match(tracked.DIRTY_HASH, /^[a-f0-9]{12}$/);
  writeFileSync(join(root, 'untracked'), 'one');
  const one = collectStatus(root, {});
  writeFileSync(join(root, 'untracked'), 'two');
  const two = collectStatus(root, {});
  assert.notEqual(one.DIRTY_HASH, two.DIRTY_HASH);
  assert.match(props(two, 'BUILD_TIMESTAMP 123\n'), /buildTime=1970-01-01T00:02:03\.000Z/);
}));

test('untracked symlink hashes its link text', () => fixture((root) => {
  symlinkSync('first', join(root, 'link'));
  const one = collectStatus(root, {});
  rmSync(join(root, 'link'));
  symlinkSync('second', join(root, 'link'));
  const two = collectStatus(root, {});
  assert.equal(one.DIRTY, 'true');
  assert.notEqual(one.DIRTY_HASH, two.DIRTY_HASH);
}));

test('nested non-repository and failed git remain unknown', () => fixture((root) => {
  mkdirSync(join(root, 'nested'));
  const nested = collectStatus(join(root, 'nested'), {});
  assert.equal(nested.DIRTY, 'unknown');
  assert.match(props(nested), /displayVersion=0\.5\.4-dev\+unknown/);
  assert.equal(collectStatus(root, {}, '/missing/git').DIRTY, 'unknown');
}));

test('release and malformed inputs', () => fixture((root) => {
  const clean = collectStatus(root, { GITHUB_REF_TYPE: 'tag' });
  assert.match(props(clean), /displayVersion=0\.5\.4\n/);
  const status = renderStatus(clean);
  assert.throws(() => renderProperties(status + 'STABLE_GVY_SCHEMA 1\n', '', version));
  assert.throws(() => renderProperties(status.replace('STABLE_GVY_DIRTY false', 'STABLE_GVY_DIRTY true'), '', version));
  assert.throws(() => renderProperties(status, '', 'val baseVersion = "0.5.4"'));
  assert.throws(() => renderProperties(status, '', version + version));
}));

test('status validation rejects injection, unknown contradictions, and duplicate timestamps', () => fixture((root) => {
  const status = renderStatus(collectStatus(root, {}));
  assert.throws(() => renderProperties(status.replace('STABLE_GVY_SCHEMA 1', 'STABLE_GVY_SCHEMA 1\rINJECTED yes'), '', version));
  assert.throws(() => renderProperties(status.replace(/STABLE_GVY_SHA [^\n]+/, 'STABLE_GVY_SHA unknown'), '', version));
  assert.throws(() => renderProperties(status, 'BUILD_TIMESTAMP 1\nBUILD_TIMESTAMP 2\n', version));
  const dirty = collectStatus(root, { GITHUB_HEAD_REF: 'release-please--main' });
  assert.match(props(dirty), /release=true\n/);
}));


test('properties CLI accepts declared input symlinks', () => fixture((root) => {
  const status = renderStatus(collectStatus(root, {}));
  writeFileSync(join(root, 'status-real'), status);
  writeFileSync(join(root, 'volatile-real'), 'BUILD_TIMESTAMP 123\n');
  writeFileSync(join(root, 'version-real'), version);
  for (const name of ['status', 'volatile', 'version']) symlinkSync(`${name}-real`, join(root, name));
  const cli = join(import.meta.dirname, '../build-info/source-stamp.mjs');
  execFileSync(process.execPath, [cli, 'properties', '--stable-status', join(root, 'status'), '--volatile-status', join(root, 'volatile'), '--version-source', join(root, 'version'), '--output', join(root, 'out')]);
  assert.match(readFileSync(join(root, 'out'), 'utf8'), /gitDirty=false\n/);
}));

test('HEAD changes during collection downgrade provenance', { skip: process.platform === 'win32' }, () => fixture((root) => {
  const wrapper = join(root, 'git-wrapper');
  const counter = join(root, 'calls');
  const gitPath = execFileSync('which', ['git']).toString().trim();
  writeFileSync(wrapper, `#!/bin/sh\ncase "$*" in\n  *"rev-parse --verify HEAD"*) if [ -f "${counter}" ]; then printf '%040d\\n' 0; exit 0; fi; : > "${counter}";;\nesac\nexec "${gitPath}" "$@"\n`);
  chmodSync(wrapper, 0o755);
  assert.equal(collectStatus(root, {}, wrapper).SHA, 'unknown');
}));

test('unstamped status produces unknown snapshot while partial status is rejected', () => {
  for (const stable of ['', 'BUILD_EMBED_LABEL ignored\n']) {
    const output = renderProperties(stable, '', version);
    assert.match(output, /version=0\.5\.4-SNAPSHOT\n/);
    assert.match(output, /release=false\n/);
    assert.match(output, /gitDirty=unknown\n/);
    assert.match(output, /gitSha=unknown\n/);
  }
  assert.throws(() => renderProperties('STABLE_GVY_SCHEMA 1\n', '', version));
});

test('CRLF status lines are accepted but embedded carriage returns are rejected', () => fixture((root) => {
  const status = renderStatus(collectStatus(root, {}));
  assert.equal(renderProperties(status.replaceAll('\n', '\r\n'), 'BUILD_TIMESTAMP 123\r\n', version), renderProperties(status, 'BUILD_TIMESTAMP 123\n', version));
  assert.throws(() => renderProperties(status.replace('STABLE_GVY_SCHEMA 1', 'STABLE_GVY_SCHEMA 1\rINJECTED'), '', version));
}));

test('properties CLI creates its output parent directory', () => fixture((root) => {
  writeFileSync(join(root, 'stable'), '');
  writeFileSync(join(root, 'volatile'), '');
  writeFileSync(join(root, 'version'), version);
  const output = join(root, 'nested', 'version.properties');
  execFileSync(process.execPath, [join(import.meta.dirname, '../build-info/source-stamp.mjs'), 'properties', '--stable-status', join(root, 'stable'), '--volatile-status', join(root, 'volatile'), '--version-source', join(root, 'version'), '--output', output]);
  assert.match(readFileSync(output, 'utf8'), /gitDirty=unknown\n/);
}));

test('hidden index flags never qualify as clean', () => {
  for (const flag of ['--assume-unchanged', '--skip-worktree']) {
    fixture((root) => {
      git(root, 'update-index', flag, 'tracked');
      writeFileSync(join(root, 'tracked'), 'hidden change');
      assert.equal(collectStatus(root, {}).DIRTY, 'unknown');
    });
  }
});

test('ignored source, tools and Bazel definitions prevent a clean stamp', () => fixture((root) => {
  writeFileSync(join(root, '.gitignore'), '*.ignored\nBUILD.bazel\n');
  git(root, 'add', '.gitignore');
  git(root, 'commit', '-qm', 'ignore fixtures');
  for (const name of ['module/src/main/resources/a.ignored', 'tools/a.ignored', 'module/BUILD.bazel']) {
    const path = join(root, name);
    mkdirSync(join(path, '..'), { recursive: true });
    writeFileSync(path, 'ignored input');
    assert.equal(collectStatus(root, {}).DIRTY, 'unknown', name);
    rmSync(path);
  }
}));

test('dirty content changes during Git collection downgrade provenance', { skip: process.platform === 'win32' }, () => fixture((root) => {
  const wrapper = join(root, 'git-wrapper');
  const gitPath = execFileSync('which', ['git']).toString().trim();
  writeFileSync(join(root, 'tracked'), 'first dirty content');
  writeFileSync(wrapper, `#!/bin/sh\ncase "$*" in\n  *"diff HEAD"*) "${gitPath}" "$@"; printf 'second dirty content' > "${join(root, 'tracked')}"; exit $?;;\nesac\nexec "${gitPath}" "$@"\n`);
  chmodSync(wrapper, 0o755);
  assert.equal(collectStatus(root, {}, wrapper).DIRTY, 'unknown');
}));

test('CLI executes when its script path is a symlink', () => fixture((root) => {
  const cli = join(root, 'stamp.mjs');
  symlinkSync(join(import.meta.dirname, '../build-info/source-stamp.mjs'), cli);
  const output = execFileSync(process.execPath, [cli, 'status'], { cwd: root }).toString();
  assert.match(output, /^STABLE_GVY_SCHEMA 1\n/);
}));

test('oversized untracked content fails closed', () => fixture((root) => {
  writeFileSync(join(root, 'large'), Buffer.alloc(16 * 1024 * 1024 + 1));
  assert.equal(collectStatus(root, {}).DIRTY, 'unknown');
}));

test('ignored e2e and lsp-client inputs prevent a clean stamp', () => fixture((root) => {
  writeFileSync(join(root, '.gitignore'), '*.kt\n');
  git(root, 'add', '.gitignore');
  git(root, 'commit', '-qm', 'ignore Kotlin fixtures');
  for (const name of ['tests/e2e/kotlin/Probe.kt', 'tests/lsp-client/kotlin/Probe.kt']) {
    const path = join(root, name);
    mkdirSync(join(path, '..'), { recursive: true });
    writeFileSync(path, 'ignored test source');
    assert.equal(collectStatus(root, {}).DIRTY, 'unknown', name);
    rmSync(path);
  }
}));

test('ignores only Jenkins extractor Detekt reports', () => fixture((root) => {
  writeFileSync(join(root, '.gitignore'), '*.kt\n*.md\n');
  git(root, 'add', '.gitignore');
  git(root, 'commit', '-qm', 'ignore fixtures');
  const report = join(root, 'tools/jenkins-extractor/build/reports/detekt/detekt.md');
  mkdirSync(join(report, '..'), { recursive: true });
  writeFileSync(report, 'generated report');
  assert.equal(collectStatus(root, {}).DIRTY, 'false');
  const source = join(root, 'tools/jenkins-extractor/src/main/kotlin/gvy/build/Probe.kt');
  mkdirSync(join(source, '..'), { recursive: true });
  writeFileSync(source, 'ignored source');
  assert.equal(collectStatus(root, {}).DIRTY, 'unknown');
  rmSync(source);
  const otherOutput = join(root, 'tools/jenkins-extractor/build/Unexpected.kt');
  writeFileSync(otherOutput, 'unclassified output');
  assert.equal(collectStatus(root, {}).DIRTY, 'unknown');
}));
