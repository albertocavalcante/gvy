import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import test from 'node:test';

const cli = resolve('tools/ci/nightly-artifacts.mjs');
const commit = 'a'.repeat(40);
const version = '0.5.4-nightly.20261112.123.1';
const digest = bytes => createHash('sha256').update(bytes).digest('hex');

function fixture(t, options = {}) {
  const root = mkdtempSync(join(tmpdir(), 'gvy-nightly-test-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const jarDir = join(root, 'jar');
  const extension = join(root, 'vsix', 'extension');
  const dist = join(root, 'dist');
  mkdirSync(jarDir);
  mkdirSync(join(extension, 'server'), { recursive: true });
  mkdirSync(dist);
  writeFileSync(join(jarDir, 'version.properties'),
    `gitSha=${options.sha ?? commit}\ngitDirty=${options.dirty ?? 'false'}\nversion=0.5.4\n`);
  const zip = (cwd, destination) => {
    const result = spawnSync('zip', ['-qr', destination, '.'], { cwd, encoding: 'utf8' });
    assert.equal(result.status, 0, result.stderr);
  };
  zip(jarDir, join(dist, 'gls-nightly.jar'));
  writeFileSync(join(extension, 'server', 'gls.jar'),
    options.embeddedJar ?? readFileSync(join(dist, 'gls-nightly.jar')));
  writeFileSync(join(extension, 'package.json'), JSON.stringify({
    version: options.version ?? version, engines: { vscode: '^1.144.0' },
  }));
  zip(join(root, 'vsix'), join(dist, 'gvy-nightly.vsix'));
  const run = (...args) => spawnSync(process.execPath, [cli, ...args], { encoding: 'utf8' });
  return { dist, run: () => run(dist, commit, version, 'https://github.com/albertocavalcante/gvy/actions/runs/123') };
}

test('records one clean commit, both artifact hashes and editor requirements', t => {
  const { dist, run } = fixture(t);
  const result = run();
  assert.equal(result.status, 0, result.stderr);
  const manifest = JSON.parse(readFileSync(join(dist, 'nightly.json'), 'utf8'));
  assert.equal(manifest.commit, commit);
  assert.equal(manifest.version, version);
  assert.equal(manifest.vscodeApi, '^1.144.0');
  for (const name of ['gls-nightly.jar', 'gvy-nightly.vsix']) {
    assert.equal(manifest.sha256[name], digest(readFileSync(join(dist, name))));
  }
  const checksums = readFileSync(join(dist, 'checksums.txt'), 'utf8');
  for (const name of ['gls-nightly.jar', 'gvy-nightly.vsix', 'nightly.json']) {
    assert.ok(checksums.includes(`${digest(readFileSync(join(dist, name)))}  ${name}\n`));
  }
  assert.match(readFileSync(join(dist, 'release-notes.md'), 'utf8'), /1\.144\.0/);
});

test('rejects a VSIX containing an old or different server', t => {
  const { run } = fixture(t, { embeddedJar: Buffer.from('old released server') });
  const result = run();
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /bundled server/i);
});

test('rejects server provenance from another commit', t => {
  const { run } = fixture(t, { sha: 'b'.repeat(40) });
  const result = run();
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /commit/i);
});

for (const dirty of ['true', 'unknown', '']) {
  test(`rejects unverified clean state (${dirty || 'absent'})`, t => {
    const { run } = fixture(t, { dirty });
    const result = run();
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /clean/i);
  });
}

test('rejects an extension with a different version', t => {
  const { run } = fixture(t, { version: '0.5.4' });
  const result = run();
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /version/i);
});

test('fails closed if a required artifact is absent', t => {
  const { dist, run } = fixture(t);
  rmSync(join(dist, 'gvy-nightly.vsix'));
  assert.notEqual(run().status, 0);
  assert.throws(() => readFileSync(join(dist, 'checksums.txt')), /ENOENT/);
});
