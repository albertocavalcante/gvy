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
  cpSync(join(repository, 'Makefile'), join(root, 'Makefile'));
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
if (process.argv.includes('printVersion')) {
  console.log(['Base version: 0.5.4', 'Final version: 0.5.4-SNAPSHOT', 'GITHUB_REF_TYPE: not set', 'GITHUB_HEAD_REF: not set', 'Is release build: false'].join('\\n'));
}
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
  const environment = { ...process.env, PATH: `${root}/bin:${process.env.PATH}`, CI: 'true', FIXTURE_ROOT: root,
    FIXTURE_COMMIT: commit, FIXTURE_MODE: options.mode ?? '', GLS_USE_PINNED: 'true',
    GLS_CHANNEL: 'pinned', GLS_REQUIRE_FRESH: 'true', SKIP_PREPARE_SERVER: 'true' };
  const command = (program, args) => spawnSync(program, args, {
    // Allow shared-host I/O contention while bounding hung fixture commands.
    cwd: root, encoding: 'utf8', timeout: 60000, env: environment,
  });
  const run = (...extra) => command('bash', ['tools/release.sh', 'build', '--version', `v${version}`, ...extra]);
  return { root, editor, originalPackage, run, command,
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

test('dry run previews outputs without invoking tools or creating artifacts', t => {
  const f = fixture(t);
  const result = f.run('--dry-run');
  assert.equal(result.status, 0, result.stdout + result.stderr);
  assert.equal(existsSync(join(f.root, 'calls.jsonl')), false);
  assert.equal(existsSync(join(f.root, 'dist')), false);
  assert.match(result.stdout, /gls-0\.5\.4\.jar/);
  assert.match(result.stdout, /gvy-0\.5\.4\.vsix/);
  assert.equal(readFileSync(join(f.editor, 'package.json'), 'utf8'), f.originalPackage);
});

test('dry run preserves previously verified artifacts and their manifest', t => {
  const f = fixture(t);
  const dist = join(f.root, 'dist');
  mkdirSync(dist);
  const originals = { 'gls-0.5.4.jar': 'verified server', 'gvy-0.5.4.vsix': 'verified editor', 'checksums.txt': 'verified checksums' };
  for (const [name, content] of Object.entries(originals)) writeFileSync(join(dist, name), content);
  const result = f.run('--dry-run');
  assert.equal(result.status, 0, result.stdout + result.stderr);
  assert.deepEqual(readdirSync(dist).sort(), Object.keys(originals).sort());
  for (const [name, content] of Object.entries(originals)) assert.equal(readFileSync(join(dist, name), 'utf8'), content);
  assert.equal(existsSync(join(f.root, 'calls.jsonl')), false);
});

for (const command of ['checksums', 'validate', 'smoke-test']) {
  test(`dry-run rejects ${command} without changing files or running tools`, t => {
    const f = fixture(t);
    const dist = join(f.root, 'dist');
    mkdirSync(dist);
    writeFileSync(join(dist, 'gls-0.5.4.jar'), 'verified server');
    writeFileSync(join(dist, 'checksums.txt'), 'previous verified manifest\n');
    writeFileSync(join(f.root, 'bin/java'), '#!/bin/bash\ntouch java-called\n', { mode: 0o755 });
    const result = f.command('bash', ['tools/release.sh', command, '--version', 'v0.5.4', '--dry-run']);
    assert.notEqual(result.status, 0, result.stdout + result.stderr);
    assert.match(result.stdout + result.stderr, /--dry-run is supported only for build/);
    assert.equal(readFileSync(join(dist, 'checksums.txt'), 'utf8'), 'previous verified manifest\n');
    assert.deepEqual(readdirSync(dist).sort(), ['checksums.txt', 'gls-0.5.4.jar']);
    assert.equal(existsSync(join(f.root, 'java-called')), false);
    assert.equal(existsSync(join(f.root, 'calls.jsonl')), false);
  });
}

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

for (const condition of ['unavailable-git', 'unusable-gradle-wrapper']) {
  test(`build preflight preserves existing artifacts for ${condition}`, t => {
    const f = fixture(t);
    mkdirSync(join(f.root, 'dist'));
    writeFileSync(join(f.root, 'dist/previous-artifact'), 'keep previous artifacts');
    if (condition === 'unavailable-git') {
      writeFileSync(join(f.root, 'bin/git'), '#!/bin/bash\nexit 17\n', { mode: 0o755 });
    } else {
      chmodSync(join(f.root, 'gradlew'), 0o644);
    }
    assert.notEqual(f.run().status, 0);
    assert.equal(readFileSync(join(f.root, 'dist/previous-artifact'), 'utf8'), 'keep previous artifacts');
    assert.equal(existsSync(join(f.root, 'calls.jsonl')), false);
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

test('an absolute invocation from another directory preserves that directory artifacts', t => {
  const f = fixture(t);
  const caller = join(f.root, 'unrelated checkout');
  mkdirSync(join(caller, 'dist'), { recursive: true });
  writeFileSync(join(caller, 'dist/previous-artifact'), 'keep caller output');
  const result = spawnSync('bash', [join(f.root, 'tools/release.sh'), 'build', '--version', 'v0.5.4', '--dry-run'], {
    cwd: caller, encoding: 'utf8',
  });
  assert.equal(result.status, 0, result.stdout + result.stderr);
  assert.equal(readFileSync(join(caller, 'dist/previous-artifact'), 'utf8'), 'keep caller output');
  assert.equal(existsSync(join(f.root, 'dist')), false);
  assert.equal(existsSync(join(f.root, 'calls.jsonl')), false);
});

for (const input of ['', '../invalid']) {
  test(`make release-publish rejects ${input || 'an absent version'} before build tools run`, t => {
    const f = fixture(t);
    const result = f.command('make', ['release-publish', `RELEASE_VERSION=${input}`]);
    assert.notEqual(result.status, 0);
    assert.equal(existsSync(join(f.root, 'calls.jsonl')), false);
    assert.equal(existsSync(join(f.root, 'dist')), false);
    if (!input) assert.match(result.stdout + result.stderr, /RELEASE_VERSION/);
  });
}

test('make release-publish forwards an explicit tag without parsing human version output', t => {
  const f = fixture(t);
  const result = f.command('make', ['release-publish', 'RELEASE_VERSION=v0.5.4']);
  assert.equal(result.status, 0, result.stdout + result.stderr);
  const receipt = JSON.parse(readFileSync(join(f.root, 'dist/release.json'), 'utf8'));
  assert.equal(receipt.version, version);
  assert.equal(f.calls().some(call => call.args.includes('printVersion')), false);
});

test('make release-build previews and names the explicit version needed for a real build', t => {
  const f = fixture(t);
  const result = f.command('make', ['release-build']);
  assert.equal(result.status, 0, result.stdout + result.stderr);
  assert.match(result.stdout, /make release-publish RELEASE_VERSION=vX\.Y\.Z/);
  assert.equal(existsSync(join(f.root, 'dist')), false);
  assert.equal(existsSync(join(f.root, 'calls.jsonl')), false);
});
