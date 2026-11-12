import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { validateClasspathProfile, validateCorpus } from './validate.mjs';

const sourceRoot = fileURLToPath(new URL('.', import.meta.url));
const profile = { id: 'conversion-only-empty-external-classpath', phase: 'CONVERSION', externalClasspath: [] };

async function copyCorpus(t) {
  const directory = await fs.mkdtemp(join(tmpdir(), 'gvy-corpus-test-'));
  t.after(() => fs.rm(directory, { recursive: true, force: true }));
  await fs.cp(sourceRoot, directory, { recursive: true });
  return directory;
}

async function changeManifest(directory, change) {
  const path = join(directory, 'manifest.json');
  const manifest = JSON.parse(await fs.readFile(path, 'utf8'));
  change(manifest);
  await fs.writeFile(path, JSON.stringify(manifest));
  return manifest;
}

async function fixturePath(directory) {
  const manifest = JSON.parse(await fs.readFile(join(directory, 'manifest.json'), 'utf8'));
  return join(directory, manifest.fixtures[0].path);
}

test('the full frozen corpus verifies offline with all required families and origins', async () => {
  const report = await validateCorpus(sourceRoot);
  assert.equal(report.fixtures, 11);
  assert.equal(report.bytes, 79808);
  assert.equal(report.projects, 6);
  for (const family of ['class', 'script', 'gradle', 'jenkins', 'spock']) {
    assert.ok(report.families[family].files >= 2);
    assert.equal(report.families[family].origins, 2);
  }
  assert.equal(report.compatibility, 'not-executed');
  assert.match(report.manifestSha256, /^[a-f0-9]{64}$/);
});

test('a missing required source file fails rather than reducing the corpus', async t => {
  const directory = await copyCorpus(t);
  await fs.unlink(await fixturePath(directory));
  await assert.rejects(validateCorpus(directory), { message: 'CORPUS_MISSING_FILE' });
});

test('a same-length source mutation fails its content identity', async t => {
  const directory = await copyCorpus(t);
  const path = await fixturePath(directory);
  const bytes = await fs.readFile(path);
  bytes[0] ^= 1;
  await fs.writeFile(path, bytes);
  await assert.rejects(validateCorpus(directory), { message: 'CORPUS_HASH_MISMATCH' });
});

test('a removed manifest entry cannot silently change the required workload', async t => {
  const directory = await copyCorpus(t);
  await changeManifest(directory, manifest => { manifest.fixtures.pop(); });
  await assert.rejects(validateCorpus(directory), { message: 'CORPUS_INVALID_MANIFEST' });
});

test('missing license evidence fails even when all source fixtures exist', async t => {
  const directory = await copyCorpus(t);
  const manifest = JSON.parse(await fs.readFile(join(directory, 'manifest.json'), 'utf8'));
  await fs.unlink(join(directory, manifest.sources[0].license.files[0].path));
  await assert.rejects(validateCorpus(directory), { message: 'CORPUS_MISSING_FILE' });
});

for (const upstreamPath of ['LICENSE', 'NOTICE']) {
  test(`the required ${upstreamPath} declaration cannot disappear with its file`, async t => {
    const directory = await copyCorpus(t);
    const manifest = JSON.parse(await fs.readFile(join(directory, 'manifest.json'), 'utf8'));
    const source = manifest.sources.find(value => value.id === 'groovy4');
    const evidence = source.license.files.find(value => value.upstreamPath === upstreamPath);
    await fs.unlink(join(directory, evidence.path));
    await changeManifest(directory, value => {
      const entry = value.sources.find(item => item.id === 'groovy4');
      entry.license.files = entry.license.files.filter(item => item.upstreamPath !== upstreamPath);
    });
    await assert.rejects(validateCorpus(directory), { message: 'CORPUS_INVALID_MANIFEST' });
  });
}

test('version-one source and license identities cannot be relabeled', async t => {
  const directory = await copyCorpus(t);
  await changeManifest(directory, manifest => { manifest.sources[0].repository = 'example/replacement'; });
  await assert.rejects(validateCorpus(directory), { message: 'CORPUS_INVALID_MANIFEST' });
  await fs.cp(join(sourceRoot, 'manifest.json'), join(directory, 'manifest.json'));
  await changeManifest(directory, manifest => { manifest.sources[0].license.spdx = 'MIT'; });
  await assert.rejects(validateCorpus(directory), { message: 'CORPUS_INVALID_MANIFEST' });
  await fs.cp(join(sourceRoot, 'manifest.json'), join(directory, 'manifest.json'));
  await changeManifest(directory, manifest => { manifest.sources[0].license.files[1].upstreamPath = 'OTHER-NOTICE'; });
  await assert.rejects(validateCorpus(directory), { message: 'CORPUS_INVALID_MANIFEST' });
});

test('unused source declarations cannot inflate diversity after fixture reassignment', async t => {
  const directory = await copyCorpus(t);
  await changeManifest(directory, manifest => {
    manifest.fixtures.find(value => value.id === 'ofbiz-filter-receipts').sourceId = 'spock';
  });
  await assert.rejects(validateCorpus(directory), { message: 'CORPUS_INVALID_MANIFEST' });
});

test('retaining every source declaration does not permit swapping fixture provenance', async t => {
  const directory = await copyCorpus(t);
  await changeManifest(directory, manifest => {
    manifest.fixtures.find(value => value.id === 'ofbiz-filter-receipts').sourceId = 'spock';
    manifest.fixtures.find(value => value.id === 'spock-blocking-variables').sourceId = 'ofbiz';
  });
  await assert.rejects(validateCorpus(directory), { message: 'CORPUS_INVALID_MANIFEST' });
});

test('mutated license evidence fails its recorded identity', async t => {
  const directory = await copyCorpus(t);
  const manifest = JSON.parse(await fs.readFile(join(directory, 'manifest.json'), 'utf8'));
  const path = join(directory, manifest.sources[0].license.files[0].path);
  const bytes = await fs.readFile(path);
  bytes[0] ^= 1;
  await fs.writeFile(path, bytes);
  await assert.rejects(validateCorpus(directory), { message: 'CORPUS_HASH_MISMATCH' });
});

test('source size and line metadata are checked independently of the digest', async t => {
  const directory = await copyCorpus(t);
  await changeManifest(directory, manifest => { manifest.fixtures[0].byteLength += 1; });
  await assert.rejects(validateCorpus(directory), { message: 'CORPUS_SIZE_MISMATCH' });
  await changeManifest(directory, manifest => {
    manifest.fixtures[0].byteLength -= 1;
    manifest.fixtures[0].lineCount += 1;
  });
  await assert.rejects(validateCorpus(directory), { message: 'CORPUS_LINE_COUNT_MISMATCH' });
});

test('unknown fields, moving revisions and traversal paths are rejected', async t => {
  const directory = await copyCorpus(t);
  await changeManifest(directory, manifest => { manifest.extra = 'do-not-export-this'; });
  await assert.rejects(validateCorpus(directory), { message: 'CORPUS_INVALID_MANIFEST' });
  await changeManifest(directory, manifest => {
    delete manifest.extra;
    manifest.sources[0].revision = 'main';
  });
  await assert.rejects(validateCorpus(directory), { message: 'CORPUS_INVALID_MANIFEST' });
  await fs.cp(join(sourceRoot, 'manifest.json'), join(directory, 'manifest.json'));
  await changeManifest(directory, manifest => { manifest.fixtures[0].path = '../outside.groovy'; });
  await assert.rejects(validateCorpus(directory), { message: 'CORPUS_INVALID_MANIFEST' });
});

test('an intermediary directory link cannot redirect corpus reads', async t => {
  const directory = await copyCorpus(t);
  const external = await fs.mkdtemp(join(tmpdir(), 'gvy-corpus-link-'));
  t.after(() => fs.rm(external, { recursive: true, force: true }));
  await fs.cp(join(directory, 'fixtures'), join(external, 'fixtures'), { recursive: true });
  await fs.rm(join(directory, 'fixtures'), { recursive: true });
  await fs.symlink(join(external, 'fixtures'), join(directory, 'fixtures'), 'junction');
  await assert.rejects(validateCorpus(directory), { message: 'CORPUS_SYMLINK' });
});

test('a directory in place of a fixture is not treated as a source file', async t => {
  const directory = await copyCorpus(t);
  const path = await fixturePath(directory);
  await fs.unlink(path);
  await fs.mkdir(path);
  await assert.rejects(validateCorpus(directory), { message: 'CORPUS_NOT_REGULAR' });
});

test('oversized manifest input is bounded before parsing', async t => {
  const directory = await copyCorpus(t);
  await fs.truncate(join(directory, 'manifest.json'), 1024 * 1024 + 1);
  await assert.rejects(validateCorpus(directory), { message: 'CORPUS_SIZE_LIMIT' });
});

test('invalid UTF-8 is rejected even with internally matching content hashes', async t => {
  const directory = await copyCorpus(t);
  const path = await fixturePath(directory);
  const bytes = await fs.readFile(path);
  bytes[0] = 0xff;
  await fs.writeFile(path, bytes);
  await changeManifest(directory, manifest => {
    manifest.fixtures[0].sha256 = createHash('sha256').update(bytes).digest('hex');
    manifest.fixtures[0].gitBlobSha1 = createHash('sha1')
      .update(Buffer.from(`blob ${bytes.length}\0`)).update(bytes).digest('hex');
  });
  await assert.rejects(validateCorpus(directory), { message: 'CORPUS_INVALID_UTF8' });
});

test('classpath declarations cannot silently promote a syntax-only workload', async () => {
  const manifest = JSON.parse(await fs.readFile(join(sourceRoot, 'manifest.json'), 'utf8'));
  validateClasspathProfile(manifest, profile);
  assert.throws(() => validateClasspathProfile(manifest, { ...profile, phase: 'CANONICALIZATION' }),
    { message: 'CORPUS_PROFILE_MISMATCH' });
  assert.throws(() => validateClasspathProfile(manifest, { ...profile, externalClasspath: ['ambient.jar'] }),
    { message: 'CORPUS_PROFILE_MISMATCH' });
  assert.throws(() => validateClasspathProfile(manifest, { phase: 'CONVERSION', externalClasspath: [] }),
    { message: 'CORPUS_PROFILE_MISMATCH' });
});

test('the command emits a bounded report or a fixed error without manifest values', async t => {
  const directory = await copyCorpus(t);
  const checker = fileURLToPath(new URL('check.mjs', import.meta.url));
  const command = () => spawnSync(process.execPath, [checker, directory], {
    encoding: 'utf8', timeout: 5000, maxBuffer: 128 * 1024,
  });
  const success = command();
  assert.equal(success.status, 0);
  assert.equal(JSON.parse(success.stdout).fixtures, 11);
  assert.equal(success.stderr, '');
  await changeManifest(directory, manifest => { manifest.secretCanary = 'corpus-private-canary-8539'; });
  const failure = command();
  assert.equal(failure.status, 1);
  assert.equal(failure.stdout, '');
  assert.equal(failure.stderr.trim(), 'CORPUS_INVALID_MANIFEST');
});
