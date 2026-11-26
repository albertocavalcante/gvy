// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtemp, rm, symlink, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { inventory } from './artifacts.mjs';
import { loadArchive, validateRun } from './archive.mjs';
import { fileHash, hash, base, historicalHash, policy, versions } from './io.mjs';
import { serve } from './fixture.mjs';
import { historical, normalize } from './graph.mjs';
import { options, roots, verifyFixtures, verifyRow, verifySource } from './receipts.mjs';

const version = '2.1.26';
const ctx = { head: '1'.repeat(40), tree: '2'.repeat(40), run: '123', attempt: '2' };
const src = { ...ctx, base, sourceTreeWithoutProbeSha256: '6eb5f7df09de3e6ee5e55a5b93f7a546b5213a2d9afcf68e010ff21ee253afba',
  os: 'Linux', platform: 'linux', version, mode: 'strict', bazelPin: '9.3.2' };
const rawJson = value => JSON.stringify(value, null, 2) + '\n';

async function completeRow() {
  const files = new Map();
  const record = (name, value) => files.set(name, rawJson(value));
  const run = (dir, command, args, stdout = '', stderr = '') => {
    record(dir + '/command.json', { command, args });
    record(dir + '/exit.json', { code: 0, signal: null });
    files.set(dir + '/stdout.txt', stdout);
    files.set(dir + '/stderr.txt', stderr);
  };
  record('source.json', src);
  const sha = versions[version];
  record('release.json', { tag: 'v2.1.26', asset: 601941217, bytes: 54491865, digest: 'sha256:' + sha,
    url: 'https://github.com/coursier/coursier/releases/download/v2.1.26/coursier.jar' });
  record('resolver-binaries.json', { releaseSha256: sha, installedSha256: sha });
  run('download', 'gh', ['release', 'download', 'v2.1.26', '--repo', 'coursier/coursier', '--pattern', 'coursier.jar', '--output', '/tmp/test/coursier.jar']);
  run('java-version', 'java', ['-version'], '', 'openjdk version "21.0.1"');
  run('bazel-version', 'bazel', ['--version'], 'bazel 9.3.2\n');
  const labels = 'sh_binary rule @maven//:pin\n';
  run('query', 'bazel', ['query', '--repo_env=COURSIER_SHA256=' + sha, '--output=label_kind', '@maven//:*'], labels);
  files.set('resolver-input.patch', '-    fail_on_missing_checksum = False,\n+    fail_on_missing_checksum = True,\n');
  files.set('module-lock.patch', '');
  files.set('exclusion-file.txt', 'org.openrewrite:rewrite-groovy--org.codehaus.groovy:groovy');
  const repos = ['https://maven-central.storage-download.googleapis.com/maven2', 'https://repo1.maven.org/maven2',
    'https://plugins.gradle.org/m2/', 'https://repo.gradle.org/gradle/libs-releases/', 'https://maven.google.com'];
  const exclusions = ['groovy', 'groovy-ant', 'groovy-groovydoc', 'groovy-json', 'groovy-templates', 'groovy-xml'];
  const args = ['-jar', 'coursier', 'fetch', ...roots, '--no-default', '--local-exclude-file', 'exclusion-file.txt',
    '--checksum', policy.strict, ...repos.flatMap(url => ['--repository', url]),
    ...exclusions.flatMap(name => ['--exclude', 'org.codehaus.groovy:' + name]), ...options];
  files.set('java_argsfile', args.map(x => JSON.stringify(x)).join('\n') + '\n');
  const prefix = '/tmp/test/probe-output-base/external/rules_jvm_external++maven+maven/v1';
  const cache = [];
  const enriched = [];
  const lock = {};
  const nodes = roots.map((coord, index) => {
    const key = coord.split(':').slice(0, 2).join(':');
    const path = 'host/maven2/' + key.replace(':', '/') + '/artifact.jar';
    const sha256 = hash(coord);
    const file = 'https/' + path;
    cache.push({ path, sha256 });
    enriched.push({ coord, file: 'v1/' + file, sha256 });
    lock[key] = { version: coord.split(':').at(-1), shasums: { jar: sha256 } };
    const deps = index === 0 ? [roots[1]] : [];
    return { coord, file: prefix + '/' + file, directDependencies: deps, dependencies: deps };
  });
  const raw = { version: '0.1.0', conflict_resolution: {}, dependencies: nodes };
  record('dep-tree.json', raw);
  record('coursier-deps.json', { ...raw, dependencies: enriched });
  record('unsorted_deps.json', { version: '3', artifacts: lock, dependencies: { [roots[0]]: [roots[1]] }, repositories: { [repos[0]]: Object.keys(lock) } });
  record('cache-files.json', cache);
  const graph = await normalize(raw, prefix, path => cache.find(c => path.endsWith('/' + c.path)).sha256);
  record('graph.json', graph);
  const list = historical(cache.map(c => c.path));
  files.set('historical-file-list.txt', list);
  files.set('targets.txt', labels);
  files.set('BUILD', 'sh_binary(name = "pin")');
  record('graph-result.json', { nodes: nodes.length, targets: 1, metadataOnly: 0, graphSha256: hash(files.get('graph.json')),
    targetsSha256: hash(labels), lockSha256: hash(files.get('unsorted_deps.json')), historicalAlgorithmSha256: hash(list),
    historicalReferenceSha256: historicalHash, historicalMatch: false, resolverSha256: sha, checksum: policy.strict,
    variants: options.filter((_, i) => options[i - 1] === '--variant') });
  return files;
}

test('reconstructs a schema-complete synthetic row and independently detects historical drift', async () => {
  const row = await verifyRow(await completeRow(), ctx, 'Linux', version, 'strict');
  assert.equal(row.result.nodes, roots.length);
  assert.equal(row.result.historicalMatch, false);
});

test('missing raw files, truncated graphs and fabricated summaries cannot qualify', async () => {
  const original = await completeRow();
  const mutations = [
    files => files.delete('unsorted_deps.json'),
    files => files.delete('query/exit.json'),
    files => files.set('dep-tree.json', rawJson({ ...JSON.parse(files.get('dep-tree.json')), dependencies: [] })),
    files => files.set('dep-tree.json', rawJson({ ...JSON.parse(files.get('dep-tree.json')), dependencies: JSON.parse(files.get('dep-tree.json')).dependencies.slice(1) })),
    files => files.set('cache-files.json', files.get('cache-files.json').replace(/[a-f0-9]{64}/, 'forged')),
    files => files.set('cache-files.json', files.get('cache-files.json').replace(/[a-f0-9]{64}/, '9'.repeat(64))),
    files => files.set('graph-result.json', files.get('graph-result.json').replace('"historicalMatch": false', '"historicalMatch": true')),
    files => files.set('targets.txt', 'some-equal-placeholder\n'),
    files => files.set('source.json', rawJson({ ...src, attempt: '1' })),
  ];
  for (const alter of mutations) {
    const files = new Map(original);
    alter(files);
    await assert.rejects(verifyRow(files, ctx, 'Linux', version, 'strict'));
  }
});

test('the previous self-reported one-node receipt is insufficient even with the correct identity', async () => {
  const files = new Map([['source.json', rawJson(src)], ['graph.json', rawJson({ dependencies: [{ coord: 'demo:a:1', file: null }], conflicts: {} })],
    ['graph-result.json', rawJson({ historicalMatch: true, targetsSha256: 'targets', lockSha256: 'lock' })]]);
  await assert.rejects(verifyRow(files, ctx, 'Linux', version, 'strict'), /missing raw receipt/);
});

test('binds GitHub workflow and archive row identities to independently selected values', () => {
  const repository = { full_name: 'albertocavalcante/gvy' };
  const record = { id: 123, run_attempt: 2, repository, head_repository: repository, head_sha: ctx.head,
    path: '.github/workflows/gradle-compat.yml', event: 'workflow_dispatch', status: 'completed' };
  validateRun(record, ctx.head, ctx.run, ctx.attempt);
  for (const changed of [{ head_sha: '3'.repeat(40) }, { id: 999 }, { run_attempt: 1 }, { event: 'push' }])
    assert.throws(() => validateRun({ ...record, ...changed }, ctx.head, ctx.run, ctx.attempt));
  for (const changed of [{ head: 'synthetic' }, { tree: '9'.repeat(40) }, { os: 'macOS' }, { mode: 'permissive' }, { version: '2.1.27' }])
    assert.throws(() => verifySource({ ...src, ...changed }, ctx, 'Linux', version, 'strict'));
});

test('artifact admission rejects binary, symbolic links and signed access URLs', async t => {
  const dir = await mkdtemp(join(tmpdir(), 'coursier-audit-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const file = join(dir, 'log.txt');
  await writeFile(file, Buffer.from([0]));
  await assert.rejects(inventory(dir), /binary/);
  await writeFile(file, 'https://example.test/path?sig=test');
  await assert.rejects(inventory(dir), /credential/);
  await writeFile(file, 'ordinary evidence');
  assert.equal((await inventory(dir)).length, 1);
  await symlink(file, join(dir, 'copy.txt'));
  await assert.rejects(inventory(dir), /unexpected link/);
});

test('archive verification checks independent GitHub ZIP digest and embedded manifest', async t => {
  const dir = await mkdtemp(join(tmpdir(), 'coursier-zip-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  await writeFile(join(dir, 'log.txt'), 'hello');
  await writeFile(join(dir, 'manifest.json'), rawJson(await inventory(dir)));
  const name = 'row';
  execFileSync('zip', ['-q', name + '.zip', 'log.txt', 'manifest.json'], { cwd: dir });
  const id = 11;
  const digest = 'sha256:' + await fileHash(join(dir, name + '.zip'));
  const artifact = { id, name, digest, expired: false, workflow_run: { id: 123, head_sha: ctx.head } };
  const validated = await loadArchive(dir, { ...ctx, artifacts: [artifact] }, name);
  assert.equal(validated.files.get('log.txt'), 'hello');
  assert.equal(validated.digest, digest);
  await assert.rejects(loadArchive(dir, { ...ctx, artifacts: [{ ...artifact, digest: 'sha256:' + '0'.repeat(64) }] }, name));
  await assert.rejects(loadArchive(dir, { ...ctx, artifacts: [artifact, artifact] }, name));
});

test('regrades controls from command, exit, requests, diagnostic and returned byte identities', () => {
  const files = new Map();
  const rows = [];
  const jar = '/fixture/probe/1/probe-1.jar';
  const binary = '/tmp/check/coursier.jar';
  for (const kind of ['valid', 'missing', 'malformed', 'mismatch', 'permissive-missing']) {
    const dir = 'fixtures/' + kind + '/';
    const success = kind === 'valid' || kind === 'permissive-missing';
    const code = success ? 0 : 1;
    const cache = '/tmp/check/fixture-' + kind;
    const checksum = kind === 'permissive-missing' ? policy.permissive : policy.strict;
    const args = ['-Xmx512m', '-jar', binary, 'fetch', '--no-default', '--repository', 'http://127.0.0.1:8000',
      '--cache', cache, '--checksum', checksum, 'fixture:probe:1'];
    files.set(dir + 'command.json', rawJson({ command: 'java', args }));
    files.set(dir + 'exit.json', rawJson({ code, signal: null }));
    files.set(dir + 'stdout.txt', success ? cache + jar + '\n' : '');
    const message = { missing: 'checksum errors', malformed: 'checksum format error', mismatch: 'wrong checksum' }[kind];
    files.set(dir + 'stderr.txt', success ? '' : jar + ': ' + message);
    const requests = [jar, jar + '.sha1', jar + '.md5'].map(path => {
      const body = serve(path, kind);
      return { method: 'GET', path, status: body === null ? 404 : 200, sha256: body && hash(body) };
    });
    files.set(dir + 'requests.json', rawJson(requests));
    files.set(dir + 'output-file.json', rawJson(success ? { path: cache + jar, sha256: hash(serve(jar, kind)) } : null));
    rows.push({ kind, checksum, code, signal: null, bytesMatch: success, verdict: 'pass' });
  }
  files.set('fixtures/results.json', rawJson(rows));
  verifyFixtures(files, binary);
  const broken = new Map(files);
  broken.delete('fixtures/missing/requests.json');
  assert.throws(() => verifyFixtures(broken, binary));
  const wrong = new Map(files);
  wrong.set('fixtures/mismatch/stderr.txt', 'downloaded probe-1.jar\nwrong checksum: probe-1.pom');
  assert.throws(() => verifyFixtures(wrong, binary));
});
