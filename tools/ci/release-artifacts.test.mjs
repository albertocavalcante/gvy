import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { chmodSync, cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, realpathSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const require = createRequire(import.meta.url);
const { readZipEntry } = require('../../editors/code/tools/zip.js');
const repository = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const commit = '0123456789abcdef0123456789abcdef01234567';
const version = '0.5.4';

function crc32(bytes) {
  let value = 0xffffffff;
  for (const byte of bytes) {
    value ^= byte;
    for (let bit = 0; bit < 8; bit += 1) value = (value >>> 1) ^ ((value & 1) ? 0xedb88320 : 0);
  }
  return (value ^ 0xffffffff) >>> 0;
}

// Stored ZIP fixtures exercise the production ZIP reader without a dependency or JVM.
function zip(entries) {
  const local = [];
  const central = [];
  let offset = 0;
  for (const [name, contents] of entries) {
    const filename = Buffer.from(name);
    const bytes = Buffer.from(contents);
    const header = Buffer.alloc(30);
    header.writeUInt32LE(0x04034b50, 0);
    header.writeUInt16LE(20, 4);
    header.writeUInt32LE(crc32(bytes), 14);
    header.writeUInt32LE(bytes.length, 18);
    header.writeUInt32LE(bytes.length, 22);
    header.writeUInt16LE(filename.length, 26);
    const directory = Buffer.alloc(46);
    directory.writeUInt32LE(0x02014b50, 0);
    directory.writeUInt16LE(20, 4);
    directory.writeUInt16LE(20, 6);
    header.copy(directory, 8, 6, 28);
    directory.writeUInt32LE(offset, 42);
    local.push(header, filename, bytes);
    central.push(directory, filename);
    offset += header.length + filename.length + bytes.length;
  }
  const index = Buffer.concat(central);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(entries.length, 8);
  end.writeUInt16LE(entries.length, 10);
  end.writeUInt32LE(index.length, 12);
  end.writeUInt32LE(offset, 16);
  return Buffer.concat([...local, index, end]);
}

function server(serverVersion = version, sha = commit, dirty = false) {
  return zip([
    ['version.properties', `version=${serverVersion}\ndisplayVersion=${serverVersion}\ngitSha=${sha}\ngitDirty=${dirty}\n`],
    ['org/codehaus/groovy/ast/ClassNode.class', Buffer.alloc(5 * 1024 * 1024)],
  ]);
}

function fixture(t, options = {}) {
  const root = realpathSync(mkdtempSync(join(tmpdir(), 'gvy release test-')));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const editor = join(root, 'editors/code');
  mkdirSync(join(root, 'tools'), { recursive: true });
  mkdirSync(join(root, 'bin'));
  mkdirSync(join(root, 'gls/build/libs'), { recursive: true });
  cpSync(join(repository, 'tools/release.sh'), join(root, 'tools/release.sh'));
  cpSync(join(repository, 'tools/ci'), join(root, 'tools/ci'), { recursive: true });
  cpSync(join(repository, 'editors/code/tools'), join(editor, 'tools'), { recursive: true });
  const originalPackage = '{ "name": "gvy", "publisher": "fixture", "version": "0.1.0" }\n';
  writeFileSync(join(editor, 'package.json'), originalPackage);
  chmodSync(join(editor, 'package.json'), 0o644);
  writeFileSync(join(root, 'fresh.jar'), server(options.serverVersion ?? version, options.commit ?? commit, options.dirty ?? false));
  writeFileSync(join(root, 'pinned.jar'), server('0.4.8-SNAPSHOT'));
  writeFileSync(join(root, 'fixture-zip.cjs'), `${crc32.toString()}\n${zip.toString()}\nmodule.exports = zip;\n`);
  writeFileSync(join(root, 'bin/git'), '#!/bin/bash\nprintf "%s\\n" "$FIXTURE_COMMIT"\n', { mode: 0o755 });
  writeFileSync(join(root, 'gradlew'), `#!${process.execPath}
const fs = require('node:fs');
const path = require('node:path');
fs.appendFileSync('calls.jsonl', JSON.stringify({tool:'gradle',args:process.argv.slice(2)})+'\\n');
if (process.argv.some(a => /shadowJar|stageEditorServer/.test(a))) {
  fs.mkdirSync('gls/build/editor-server', {recursive:true});
  if (process.env.FIXTURE_MODE !== 'missing-server') fs.copyFileSync('fresh.jar', 'gls/build/editor-server/gls.jar');
  fs.copyFileSync('fresh.jar', 'gls/build/libs/gls-0.5.4-all.jar');
}
`, { mode: 0o755 });
  writeFileSync(join(root, 'bin/pnpm'), `#!${process.execPath}
const fs = require('node:fs');
const path = require('node:path');
const {spawnSync} = require('node:child_process');
const root = process.env.FIXTURE_ROOT;
const args = process.argv.slice(2);
fs.appendFileSync(path.join(root,'calls.jsonl'), JSON.stringify({tool:'pnpm',args,local:process.env.GLS_LOCAL_JAR,force:process.env.FORCE_DOWNLOAD,fallback:process.env.GLS_ALLOW_PINNED_FALLBACK,skip:process.env.SKIP_PREPARE_SERVER})+'\\n');
if (args[0] === 'install') fs.mkdirSync('node_modules', {recursive:true});
if (args[0] === 'install' || args[0] === 'run') {
  if (process.env.GLS_LOCAL_JAR) {
    const result = spawnSync(process.execPath, ['tools/prepare-server.js'], {stdio:'inherit',env:process.env});
    if (result.status !== 0) process.exit(result.status ?? 1);
  } else {
    fs.mkdirSync('server', {recursive:true});
    fs.copyFileSync(path.join(root,'pinned.jar'), 'server/gls.jar');
  }
}
if (args[0] === 'run' && args[1] === 'package') {
  if (process.env.FIXTURE_MODE === 'package-failure') process.exit(31);
  if (process.env.FIXTURE_MODE === 'missing-output') process.exit(0);
  const zip = require(path.join(root,'fixture-zip.cjs'));
  const outputIndex = args.indexOf('--out');
  const output = outputIndex < 0 ? 'gvy-0.5.4.vsix' : args[outputIndex+1];
  const pkg = JSON.parse(fs.readFileSync('package.json','utf8'));
  if (process.env.FIXTURE_MODE === 'wrong-version') pkg.version = '0.4.8';
  const jar = fs.readFileSync(process.env.FIXTURE_MODE === 'wrong-server' ? path.join(root,'pinned.jar') : 'server/gls.jar');
  fs.writeFileSync(output,zip([['extension/package.json',JSON.stringify(pkg)],['extension/server/gls.jar',jar]]));
}
`, { mode: 0o755 });
  if (options.stale) {
    writeFileSync(join(root, 'gls/build/libs/gls-0.1.0-all.jar'), server('0.1.0'));
    writeFileSync(join(editor, 'aaa-stale.vsix'), zip([['extension/package.json', '{}']]));
  }
  const run = (...extra) => spawnSync('bash', ['tools/release.sh', 'build', '--version', `v${version}`, ...extra], {
    cwd: root, encoding: 'utf8', timeout: 20000,
    env: { ...process.env, PATH: `${root}/bin:${process.env.PATH}`, CI: 'true', FIXTURE_ROOT: root,
      FIXTURE_COMMIT: commit, FIXTURE_MODE: options.mode ?? '', GLS_USE_PINNED: 'true',
      GLS_CHANNEL: 'pinned', GLS_REQUIRE_FRESH: 'true', SKIP_PREPARE_SERVER: 'true' },
  });
  return { root, editor, originalPackage, run,
    calls: () => readFileSync(join(root, 'calls.jsonl'), 'utf8').trim().split('\n').map(JSON.parse) };
}

test('release build prepares and packages the exact current server despite inherited pinned selection', t => {
  const f = fixture(t);
  const result = f.run();
  assert.equal(result.status, 0, result.stdout + result.stderr);
  const jar = readFileSync(join(f.root, 'dist/gls-0.5.4.jar'));
  assert.ok(readZipEntry(join(f.root, 'dist/gvy-0.5.4.vsix'), 'extension/server/gls.jar').equals(jar),
    'the embedded server must be byte-identical to the standalone artifact');
  for (const call of f.calls().filter(c => c.tool === 'pnpm')) {
    assert.equal(call.local, join(f.root, 'dist/gls-0.5.4.jar'));
    assert.equal(call.force, 'true');
    assert.equal(call.fallback, 'false');
    assert.equal(call.skip, 'false');
  }
  const receipt = JSON.parse(readFileSync(join(f.root, 'dist/release.json'), 'utf8'));
  assert.equal(receipt.commit, commit);
  assert.equal(receipt.server.version, version);
  assert.equal(receipt.server.dirty, false);
  assert.equal(readFileSync(join(f.editor, 'package.json'), 'utf8'), f.originalPackage);
});

test('stale JARs and VSIX files cannot be selected from a reused checkout', t => {
  const f = fixture(t, { stale: true });
  const result = f.run();
  assert.equal(result.status, 0, result.stdout + result.stderr);
  assert.ok(readFileSync(join(f.root, 'dist/gls-0.5.4.jar')).equals(readFileSync(join(f.root, 'fresh.jar'))));
  assert.deepEqual(readdirSync(join(f.root, 'dist')).sort(), ['gls-0.5.4.jar', 'gvy-0.5.4.vsix', 'release.json']);
});

for (const mode of ['missing-output', 'missing-server']) {
  test(`stale artifacts cannot satisfy ${mode}`, t => {
    const f = fixture(t, { stale: true, mode });
    assert.notEqual(f.run().status, 0);
    assert.equal(existsSync(join(f.root, 'dist/release.json')), false);
  });
}

for (const mode of ['wrong-server', 'wrong-version', 'missing-output', 'package-failure']) {
  test(`release build fails closed for ${mode} and restores package.json`, t => {
    const f = fixture(t, { mode });
    const result = f.run();
    assert.notEqual(result.status, 0, result.stdout + result.stderr);
    assert.equal(readFileSync(join(f.editor, 'package.json'), 'utf8'), f.originalPackage);
    assert.equal(existsSync(join(f.root, 'dist/release.json')), false);
  });
}

for (const options of [{ serverVersion: '0.4.8' }, { commit: 'a'.repeat(40) }, { dirty: true }]) {
  test(`release build rejects incorrect server provenance ${JSON.stringify(options)}`, t => {
    const f = fixture(t, options);
    assert.notEqual(f.run().status, 0);
    assert.equal(readFileSync(join(f.editor, 'package.json'), 'utf8'), f.originalPackage);
  });
}

test('dry run creates placeholders without invoking Gradle or pnpm and preserves package.json', t => {
  const f = fixture(t);
  const result = f.run('--dry-run');
  assert.equal(result.status, 0, result.stdout + result.stderr);
  assert.equal(existsSync(join(f.root, 'calls.jsonl')), false);
  assert.equal(existsSync(join(f.root, 'dist/gls-0.5.4.jar')), true);
  assert.equal(existsSync(join(f.root, 'dist/gvy-0.5.4.vsix')), true);
  assert.equal(readFileSync(join(f.editor, 'package.json'), 'utf8'), f.originalPackage);
});

test('an unrelated package.json backup is preserved', t => {
  const f = fixture(t);
  const backup = join(f.editor, 'package.json.bak');
  writeFileSync(backup, 'unrelated recovery file');
  assert.equal(f.run().status, 0);
  assert.equal(readFileSync(backup, 'utf8'), 'unrelated recovery file');
});

test('malformed versions fail before touching existing artifacts', t => {
  const f = fixture(t);
  mkdirSync(join(f.root, 'dist'));
  writeFileSync(join(f.root, 'dist/previous-artifact'), 'keep');
  const result = f.run('--version', '../invalid');
  assert.notEqual(result.status, 0);
  assert.equal(readFileSync(join(f.root, 'dist/previous-artifact'), 'utf8'), 'keep');
  assert.equal(existsSync(join(f.root, 'calls.jsonl')), false);
});

for (const mode of ['', 'package-failure']) {
  test(`package.json permissions survive ${mode || 'successful packaging'}`, t => {
    const f = fixture(t, { mode });
    const result = f.run();
    assert.equal(result.status, mode ? 31 : 0, result.stdout + result.stderr);
    assert.equal(statSync(join(f.editor, 'package.json')).mode & 0o777, 0o644);
    assert.equal(readFileSync(join(f.editor, 'package.json'), 'utf8'), f.originalPackage);
  });
}

test('a prerelease extension can truthfully bundle its clean base-version server', t => {
  const f = fixture(t);
  const result = f.run('--version', 'v0.5.4-rc.1');
  assert.equal(result.status, 0, result.stdout + result.stderr);
  const receipt = JSON.parse(readFileSync(join(f.root, 'dist/release.json'), 'utf8'));
  assert.equal(receipt.version, '0.5.4-rc.1');
  assert.equal(receipt.server.version, '0.5.4');
  const vsix = join(f.root, 'dist/gvy-0.5.4-rc.1.vsix');
  const extension = JSON.parse(readZipEntry(vsix, 'extension/package.json').toString('utf8'));
  assert.equal(extension.version, '0.5.4-rc.1');
  assert.ok(readZipEntry(vsix, 'extension/server/gls.jar').equals(readFileSync(join(f.root, 'dist/gls-0.5.4-rc.1.jar'))));
});

test('checksum regeneration never hashes its own manifest or temporary output', t => {
  const f = fixture(t);
  const dist = join(f.root, 'dist');
  mkdirSync(dist);
  writeFileSync(join(dist, 'gls.jar'), 'server');
  writeFileSync(join(dist, '.checksums.abandoned'), 'incomplete old output');
  const run = () => spawnSync('bash', ['tools/release.sh', 'checksums'], { cwd: f.root, encoding: 'utf8' });
  assert.equal(run().status, 0);
  const first = readFileSync(join(dist, 'checksums.txt'), 'utf8');
  assert.equal(run().status, 0);
  assert.equal(readFileSync(join(dist, 'checksums.txt'), 'utf8'), first);
  const verification = spawnSync('shasum', ['-a', '256', '-c', 'checksums.txt'], { cwd: dist, encoding: 'utf8' });
  assert.equal(verification.status, 0, verification.stdout + verification.stderr);
  assert.match(first, /gls.jar/);
  assert.doesNotMatch(first, /checksums/);
});

test('a failed checksum command preserves the last manifest and its nonzero exit', t => {
  const f = fixture(t);
  const dist = join(f.root, 'dist');
  mkdirSync(dist);
  writeFileSync(join(dist, 'gls.jar'), 'server');
  writeFileSync(join(dist, 'checksums.txt'), 'previous verified manifest\n');
  writeFileSync(join(f.root, 'bin/shasum'), '#!/bin/bash\nprintf "partial output\\n"\nexit 7\n', { mode: 0o755 });
  const result = spawnSync('bash', ['tools/release.sh', 'checksums'], {
    cwd: f.root, encoding: 'utf8', env: { ...process.env, PATH: `${f.root}/bin:${process.env.PATH}` },
  });
  assert.equal(result.status, 7);
  assert.equal(readFileSync(join(dist, 'checksums.txt'), 'utf8'), 'previous verified manifest\n');
  assert.doesNotMatch(result.stdout, /Checksums generated/);
});
