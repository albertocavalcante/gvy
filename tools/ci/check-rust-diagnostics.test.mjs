import assert from 'node:assert/strict';
import { appendFileSync, mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { classifyRustDiagnostics } from './check-rust-diagnostics.mjs';

test('captured rustup warnings fail even with successful surrounding output', () => {
  const excerpt = readFileSync(new URL('./fixtures/rustup-auto-install-warnings.txt', import.meta.url), 'utf8');
  const result = classifyRustDiagnostics(`info: installing\n${excerpt}Finished release\n`);
  assert.equal(result.ok, false);
  assert.equal(result.diagnostics.length, 5);
});

test('classifies only diagnostic prefixes and deduplicates normalized lines', () => {
  const result = classifyRustDiagnostics('info: warning: quoted text\ntest warning: no\n  WARN: found\nWARN: found\n\u001b[33mwarning[E1234]: issue\u001b[0m\r\n error[E0308]: failed\r\nwarning[clippy::foo]: issue\n');
  assert.equal(result.valid, true);
  assert.deepEqual(result.diagnostics, ['WARN: found', 'warning[E1234]: issue', 'error[E0308]: failed', 'warning[clippy::foo]: issue']);
  assert.equal(classifyRustDiagnostics('info: compiling\ntest result: ok. 1 passed\nFinished release\n').ok, true);
});

test('rejects empty, partial and control-bearing logs', () => {
  for (const text of ['', '\n', ' \t\n', '\u001b[33m\u001b[0m\n', 'unfinished', 'ok\0\n', 'bad\rline\n', 'bad\u001b[2K\n', 'bad\u0085\n']) {
    assert.equal(classifyRustDiagnostics(text).valid, false);
  }
});


test('CLI fails closed for missing, unsafe, malformed or incomplete evidence', () => {
  const dir = mkdtempSync(join(tmpdir(), 'rust-diag-'));
  const cli = fileURLToPath(new URL('./check-rust-diagnostics.mjs', import.meta.url));
  const run = path => spawnSync(process.execPath, [cli, path], { encoding: 'utf8' });
  try {
    const fixture = fileURLToPath(new URL('./fixtures/rustup-auto-install-warnings.txt', import.meta.url));
    assert.equal(run(fixture).status, 1);
    const path = join(dir, 'private-log');
    writeFileSync(path, 'info: ready\n');
    assert.equal(run(path).status, 0);
    writeFileSync(path, 'info: ready\nwarning[E1234]: broken\n');
    const warning = run(path);
    assert.equal(warning.status, 1);
    assert.match(warning.stderr, /warning\[E1234\]: broken/);
    const link = join(dir, 'link');
    symlinkSync(path, link);
    for (const invalidPath of [link, join(dir, 'missing')]) {
      const result = run(invalidPath);
      assert.equal(result.status, 1);
      assert.doesNotMatch(result.stderr, /private-log|rust-diag-|missing|link/);
    }
    for (const bytes of [Buffer.from([0xff, 0x0a]), Buffer.from('bad\0\n'), Buffer.from('bad\u001b[2K\n'), Buffer.from('partial'), Buffer.alloc(0)]) {
      writeFileSync(path, bytes);
      assert.equal(run(path).status, 1);
    }
    const limit = 16 * 1024 * 1024;
    const atLimit = Buffer.alloc(limit, 'x');
    atLimit[limit - 1] = 0x0a;
    writeFileSync(path, atLimit);
    assert.equal(run(path).status, 0);
    appendFileSync(path, 'x\n');
    const oversized = run(path);
    assert.equal(oversized.status, 1);
    assert.doesNotMatch(oversized.stderr, /private-log|rust-diag-/);
    writeFileSync(path, 'warning: private-canary\ninvalid\0\n');
    const invalid = run(path);
    assert.equal(invalid.status, 1);
    assert.doesNotMatch(invalid.stdout + invalid.stderr, /private-canary/);
    assert.equal(spawnSync(process.execPath, [cli], { encoding: 'utf8' }).status, 1);
    const extra = spawnSync(process.execPath, [cli, path, 'private-extra-canary'], { encoding: 'utf8' });
    assert.equal(extra.status, 1);
    assert.doesNotMatch(extra.stdout + extra.stderr, /private-extra-canary|private-log/);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('CLI runs through a symlinked program directory', () => {
  const dir = mkdtempSync(join(tmpdir(), 'rust-diag-alias-'));
  try {
    const alias = join(dir, 'alias');
    symlinkSync(fileURLToPath(new URL('.', import.meta.url)), alias);
    const cli = join(alias, 'check-rust-diagnostics.mjs');
    const fixture = fileURLToPath(new URL('./fixtures/rustup-auto-install-warnings.txt', import.meta.url));
    for (const flags of [[], ['--preserve-symlinks-main']]) {
      const result = spawnSync(process.execPath, [...flags, cli, fixture], { encoding: 'utf8' });
      assert.equal(result.status, 1);
      assert.equal((result.stderr.match(/^warn:/gm) ?? []).length, 5);
    }
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});


test('module imports remain pure and entry identification failures are private', () => {
  const moduleUrl = new URL('./check-rust-diagnostics.mjs', import.meta.url).href;
  const script = `import(${JSON.stringify(moduleUrl)}).then(() => console.log('imported'))`;
  const imported = spawnSync(process.execPath, ['-e', script], { encoding: 'utf8' });
  assert.equal(imported.status, 0);
  assert.equal(imported.stdout, 'imported\n');
  assert.equal(imported.stderr, '');
  const failed = spawnSync(process.execPath, ['-e', script, 'private-missing-canary'], { encoding: 'utf8' });
  assert.equal(failed.status, 1);
  assert.doesNotMatch(failed.stdout + failed.stderr, /private-missing-canary/);
});
