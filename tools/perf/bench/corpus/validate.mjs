import { createHash } from 'node:crypto';
import { constants } from 'node:fs';
import fs from 'node:fs/promises';
import { join } from 'node:path';

const MAX_BYTES = 1024 * 1024;
const FAMILIES = ['class', 'script', 'gradle', 'jenkins', 'spock'];
const CATEGORIES = ['keyword', 'comment', 'string', 'number', 'operator', 'punctuation'];
const PROFILE = 'conversion-only-empty-external-classpath';
const REQUIRED_FIXTURES = [
  'groovy-cli-exception', 'spock-blocking-variables', 'groovy-grape-ivy', 'groovy-benchmark-driver',
  'ofbiz-filter-receipts', 'spock-gradle-build', 'geb-gradle-bootstrap', 'pipeline-parallel-list',
  'jenkins-docker-pipeline', 'spock-feature-methods', 'geb-browser-spec',
].sort();
const REQUIRED_SOURCES = new Map([
  ['groovy4', { repository: 'apache/groovy', spdx: 'Apache-2.0', files: ['LICENSE', 'NOTICE'] }],
  ['spock', { repository: 'spockframework/spock', spdx: 'Apache-2.0', files: ['LICENSE', 'NOTICE'] }],
  ['geb', { repository: 'geb/geb', spdx: 'Apache-2.0', files: ['LICENSE', 'NOTICE'] }],
  ['ofbiz', { repository: 'apache/ofbiz-framework', spdx: 'Apache-2.0', files: ['LICENSE', 'NOTICE'] }],
  ['pipeline-examples', { repository: 'jenkinsci/pipeline-examples', spdx: 'MIT', files: ['LICENSE.txt'] }],
  ['jenkins-docker', { repository: 'jenkinsci/docker', spdx: 'MIT', files: ['LICENSE.txt'] }],
]);

class CorpusError extends Error {}
const fail = code => { throw new CorpusError(code); };
const invalid = () => fail('CORPUS_INVALID_MANIFEST');
const hash = (bytes, algorithm = 'sha256') => createHash(algorithm).update(bytes).digest('hex');
const blobHash = bytes => createHash('sha1').update(`blob ${bytes.length}\0`).update(bytes).digest('hex');
const equal = (left, right) => JSON.stringify(left) === JSON.stringify(right);

function object(value, keys) {
  if (!value || typeof value !== 'object' || Array.isArray(value)
    || Object.getPrototypeOf(value) !== Object.prototype
    || !equal(Object.keys(value).sort(), [...keys].sort())) invalid();
}

function identifier(value) {
  if (typeof value !== 'string' || !/^[a-z][a-z0-9-]{0,63}$/.test(value)) invalid();
}

function relativePath(value, prefix = '') {
  if (typeof value !== 'string' || value.length > 256 || !value.startsWith(prefix)
    || !/^[a-zA-Z0-9._/-]+$/.test(value)
    || value.split('/').some(part => !part || part === '.' || part === '..')) invalid();
}

function digest(value, length) {
  if (typeof value !== 'string' || !new RegExp(`^[a-f0-9]{${length}}$`).test(value)) invalid();
}

function fileRecord(value, prefix, source = false) {
  object(value, source
    ? ['id', 'family', 'originKind', 'sourceId', 'path', 'upstreamPath', 'gitBlobSha1', 'sha256',
      'byteLength', 'lineCount', 'languageMode', 'compilerProfile', 'required']
    : ['path', 'upstreamPath', 'gitBlobSha1', 'sha256', 'byteLength']);
  relativePath(value.path, prefix);
  relativePath(value.upstreamPath);
  digest(value.gitBlobSha1, 40);
  digest(value.sha256, 64);
  if (!Number.isSafeInteger(value.byteLength) || value.byteLength <= 0 || value.byteLength > MAX_BYTES) invalid();
}

export function validateManifest(manifest) {
  object(manifest, ['schemaVersion', 'id', 'spanContract', 'compilerProfiles', 'sources', 'fixtures']);
  if (manifest.schemaVersion !== 1 || manifest.id !== 'gvy-groovy-corpus-v1') invalid();
  object(manifest.spanContract, ['encoding', 'range', 'categories']);
  if (manifest.spanContract.encoding !== 'utf-8' || manifest.spanContract.range !== 'half-open'
    || !equal(manifest.spanContract.categories, CATEGORIES)) invalid();
  if (!Array.isArray(manifest.compilerProfiles) || manifest.compilerProfiles.length !== 1) invalid();
  const profile = manifest.compilerProfiles[0];
  object(profile, ['id', 'phase', 'externalClasspath', 'semanticAvailability', 'executeSource']);
  if (profile.id !== PROFILE || profile.phase !== 'CONVERSION' || !equal(profile.externalClasspath, [])
    || profile.semanticAvailability !== 'not-declared' || profile.executeSource !== false) invalid();
  if (!Array.isArray(manifest.sources) || manifest.sources.length !== REQUIRED_SOURCES.size
    || !Array.isArray(manifest.fixtures) || manifest.fixtures.length !== REQUIRED_FIXTURES.length) invalid();
  for (const fixture of manifest.fixtures) {
    if (!fixture || typeof fixture !== 'object' || Array.isArray(fixture)) invalid();
    identifier(fixture.id);
  }
  const sources = new Map();
  const paths = new Set();
  for (const source of manifest.sources) {
    object(source, ['id', 'repository', 'revision', 'license']);
    identifier(source.id);
    const requiredSource = REQUIRED_SOURCES.get(source.id);
    if (!requiredSource || source.repository !== requiredSource.repository) invalid();
    if (sources.has(source.id) || typeof source.repository !== 'string' || source.repository.length > 128
      || !/^[a-zA-Z0-9_.-]+\/[a-zA-Z0-9_.-]+$/.test(source.repository)) invalid();
    digest(source.revision, 40);
    object(source.license, ['spdx', 'files']);
    if (source.license.spdx !== requiredSource.spdx || !Array.isArray(source.license.files)
      || source.license.files.length < 1 || source.license.files.length > 3) invalid();
    for (const license of source.license.files) {
      fileRecord(license, `licenses/${source.id}/`);
      if (paths.has(license.path)) invalid();
      paths.add(license.path);
    }
    if (!equal(source.license.files.map(file => file.upstreamPath).sort(), [...requiredSource.files].sort())) invalid();
    sources.set(source.id, source);
  }
  if (!equal(manifest.fixtures.map(fixture => fixture.id).sort(), REQUIRED_FIXTURES)) invalid();
  const origins = new Map(FAMILIES.map(family => [family, new Set()]));
  for (const fixture of manifest.fixtures) {
    fileRecord(fixture, `fixtures/upstream/${fixture.id}/`, true);
    identifier(fixture.id);
    identifier(fixture.sourceId);
    if (!FAMILIES.includes(fixture.family) || !sources.has(fixture.sourceId) || paths.has(fixture.path)
      || !['library-source', 'tool-script', 'hosted-service-script', 'build-script', 'pipeline-example',
        'ci-pipeline', 'test-spec'].includes(fixture.originKind)
      || !Number.isSafeInteger(fixture.lineCount) || fixture.lineCount < 1
      || fixture.languageMode !== 'groovy-4' || fixture.compilerProfile !== PROFILE || fixture.required !== true) invalid();
    paths.add(fixture.path);
    origins.get(fixture.family).add(sources.get(fixture.sourceId).repository);
  }
  if ([...origins.values()].some(repositories => repositories.size < 2)) invalid();
}

/** Validates declarations only; this does not observe a compiler's real classpath or phase. */
export function validateClasspathProfile(manifest, observed) {
  validateManifest(manifest);
  try {
    object(observed, ['id', 'phase', 'externalClasspath']);
  } catch {
    fail('CORPUS_PROFILE_MISMATCH');
  }
  if (observed.id !== PROFILE || observed.phase !== 'CONVERSION' || !equal(observed.externalClasspath, [])) {
    fail('CORPUS_PROFILE_MISMATCH');
  }
}

async function readRegular(root, relative) {
  let handle;
  try {
    const parts = relative.split('/');
    let path = root;
    for (let index = 0; index < parts.length; index += 1) {
      path = join(path, parts[index]);
      const info = await fs.lstat(path);
      if (info.isSymbolicLink()) fail('CORPUS_SYMLINK');
      if (index < parts.length - 1 && !info.isDirectory()) fail('CORPUS_NOT_REGULAR');
      if (index === parts.length - 1 && !info.isFile()) fail('CORPUS_NOT_REGULAR');
    }
    handle = await fs.open(path, constants.O_RDONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK);
    const info = await handle.stat();
    if (!info.isFile()) fail('CORPUS_NOT_REGULAR');
    if (info.size > MAX_BYTES) fail('CORPUS_SIZE_LIMIT');
    const buffer = Buffer.alloc(MAX_BYTES + 1);
    let length = 0;
    while (length < buffer.length) {
      const { bytesRead } = await handle.read(buffer, length, buffer.length - length, null);
      if (!bytesRead) break;
      length += bytesRead;
    }
    if (length > MAX_BYTES) fail('CORPUS_SIZE_LIMIT');
    return buffer.subarray(0, length);
  } catch (error) {
    if (error instanceof CorpusError) throw error;
    fail(error?.code === 'ENOENT' ? 'CORPUS_MISSING_FILE' : 'CORPUS_READ_FAILED');
  } finally {
    if (handle) await handle.close().catch(() => fail('CORPUS_READ_FAILED'));
  }
}

function utf8(bytes) {
  try {
    return new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(bytes);
  } catch {
    fail('CORPUS_INVALID_UTF8');
  }
}

async function verifyFile(root, file) {
  const bytes = await readRegular(root, file.path);
  if (bytes.length !== file.byteLength) fail('CORPUS_SIZE_MISMATCH');
  if (hash(bytes) !== file.sha256 || blobHash(bytes) !== file.gitBlobSha1) fail('CORPUS_HASH_MISMATCH');
  const text = utf8(bytes);
  if (file.lineCount !== undefined) {
    const lines = text.split('\n').length - (text.endsWith('\n') ? 1 : 0);
    if (lines !== file.lineCount) fail('CORPUS_LINE_COUNT_MISMATCH');
  }
}

/** Offline integrity check of a trusted corpus root; no source is evaluated or fetched. */
export async function validateCorpus(directory) {
  const manifestBytes = await readRegular(directory, 'manifest.json');
  let manifest;
  try {
    manifest = JSON.parse(utf8(manifestBytes));
  } catch (error) {
    if (error instanceof CorpusError) throw error;
    invalid();
  }
  validateManifest(manifest);
  for (const source of manifest.sources) {
    for (const license of source.license.files) await verifyFile(directory, license);
  }
  for (const fixture of manifest.fixtures) await verifyFile(directory, fixture);
  const sourceById = new Map(manifest.sources.map(source => [source.id, source]));
  const families = Object.fromEntries(FAMILIES.map(family => {
    const fixtures = manifest.fixtures.filter(fixture => fixture.family === family);
    return [family, { files: fixtures.length,
      origins: new Set(fixtures.map(fixture => sourceById.get(fixture.sourceId).repository)).size }];
  }));
  return {
    schemaVersion: 1, corpusId: manifest.id, manifestSha256: hash(manifestBytes),
    fixtures: manifest.fixtures.length, bytes: manifest.fixtures.reduce((sum, fixture) => sum + fixture.byteLength, 0),
    projects: new Set(manifest.sources.map(source => source.repository)).size, families,
    compatibility: 'not-executed',
  };
}
