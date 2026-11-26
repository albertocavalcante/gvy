// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { historical, normalize } from './graph.mjs';
import { fixtureVerdict, serve } from './fixture.mjs';
import { base, hash, historicalHash, policy, versions } from './io.mjs';

const module = readFileSync(new URL('../../../MODULE.bazel', import.meta.url), 'utf8');
function list(field) {
  const start = '\n    ' + field + ' = [\n';
  assert.equal(module.split(start).length, 2);
  return [...module.split(start)[1].split('\n    ],')[0].matchAll(/^\s*"([^"]+)",/gm)].map(m => m[1]);
}
const extraRoots = [...module.matchAll(/^maven\.artifact\(\n([\s\S]*?)^\)/gm)].map(([, block]) => {
  const field = key => block.match(new RegExp('^    ' + key + ' = "([^"]+)",', 'm'))[1];
  return [field('group'), field('artifact'), field('version')].join(':');
});
export const roots = [...list('artifacts'), ...extraRoots];
export const options = list('additional_coursier_options');
const releases = { '2.1.25-M23': [337384143, 53192670], '2.1.26': [601941217, 54491865], '2.1.27': [764401554, 57879410] };
const kinds = ['valid', 'missing', 'malformed', 'mismatch', 'permissive-missing'];
const suffix = '/probe-output-base/external/rules_jvm_external++maven+maven/v1/https/';
const digest = value => assert.match(value, /^[a-f0-9]{64}$/);
const flag = (args, name) => args.flatMap((arg, i) => arg === name ? [args[i + 1]] : []);
const text = (files, name) => {
  assert(files.has(name), 'missing raw receipt: ' + name);
  return files.get(name);
};
const data = (files, name) => JSON.parse(text(files, name));
const lines = value => value.trimEnd().split('\n');

function invocation(files, dir, command) {
  const record = data(files, dir + '/command.json');
  assert.equal(record.command, command);
  assert(Array.isArray(record.args) && record.args.every(arg => typeof arg === 'string'));
  const exit = data(files, dir + '/exit.json');
  assert.equal(exit.signal, null);
  assert(Number.isInteger(exit.code));
  return { args: record.args, ...exit, stdout: text(files, dir + '/stdout.txt'), stderr: text(files, dir + '/stderr.txt') };
}

export function verifySource(source, ctx, os, version, mode) {
  for (const key of ['head', 'tree', 'run', 'attempt']) assert.equal(source[key], ctx[key], 'unexpected ' + key);
  assert.equal(source.base, base);
  assert.equal(source.sourceTreeWithoutProbeSha256, '6eb5f7df09de3e6ee5e55a5b93f7a546b5213a2d9afcf68e010ff21ee253afba');
  assert.equal(source.os, os);
  assert.equal(source.platform, os === 'Linux' ? 'linux' : 'darwin');
  assert.equal(source.version, version);
  assert.equal(source.mode, mode);
  assert.equal(source.bazelPin, '9.3.2');
}

function tooling(files, version, mode) {
  const sha = versions[version];
  const release = data(files, 'release.json');
  assert.deepEqual(release, { tag: 'v' + version, asset: releases[version][0], bytes: releases[version][1],
    digest: 'sha256:' + sha, url: 'https://github.com/coursier/coursier/releases/download/v' + version + '/coursier.jar' });
  assert.deepEqual(data(files, 'resolver-binaries.json'), { releaseSha256: sha, installedSha256: sha });
  const download = invocation(files, 'download', 'gh');
  assert.equal(download.code, 0);
  assert.deepEqual(download.args.slice(0, -1), ['release', 'download', 'v' + version, '--repo', 'coursier/coursier', '--pattern', 'coursier.jar', '--output']);
  const java = invocation(files, 'java-version', 'java');
  assert.equal(java.code, 0);
  assert.match(java.stderr + java.stdout, / version "21[."]/);
  const bazel = invocation(files, 'bazel-version', 'bazel');
  assert.equal(bazel.code, 0);
  assert.equal(bazel.stdout.trim(), 'bazel 9.3.2');
  const patch = text(files, 'resolver-input.patch');
  const changes = lines(patch).filter(s => /^[+-][^+-]/.test(s));
  assert.deepEqual(changes, mode === 'strict' ? ['-    fail_on_missing_checksum = False,', '+    fail_on_missing_checksum = True,'] : []);
  text(files, 'module-lock.patch');
  const query = invocation(files, 'query', 'bazel');
  assert.equal(query.code, 0);
  assert.deepEqual(query.args.slice(-2), ['--output=label_kind', '@maven//:*']);
  assert(query.args.includes('query') && query.args.includes('--repo_env=COURSIER_SHA256=' + sha));
  const args = lines(text(files, 'java_argsfile')).map(line => JSON.parse(line));
  assert.deepEqual(flag(args, '--checksum'), [policy[mode]]);
  assert.deepEqual(args.slice(-options.length), options);
  for (const repo of list('repositories')) assert(flag(args, '--repository').includes(repo));
  for (const exc of list('excluded_artifacts')) assert(flag(args, '--exclude').includes(exc));
  assert(args.includes('--no-default') && args.includes('--local-exclude-file'));
  const start = args.indexOf('fetch');
  assert(start > 0);
  const coordinates = args.slice(start + 1, args.findIndex((arg, i) => i > start && arg.startsWith('--')));
  for (const root of roots) assert(coordinates.includes(root), 'missing declared resolver root: ' + root);
  assert.match(text(files, 'exclusion-file.txt'), /org\.openrewrite:rewrite-groovy--org\.codehaus\.groovy:groovy/);
  // The archived inputs also include the bzlmod contributions from protobuf.
  // Preserve the complete ordered fetch argument vector, masking only the
  // intentional policy switch. Include the local exclusion file contents.
  const fetch = args.slice(start);
  fetch[fetch.indexOf('--checksum') + 1] = '<selected-checksum-policy>';
  const inputs = { fetch, exclusions: text(files, 'exclusion-file.txt') };
  return { query, coordinates, inputs, binary: download.args.at(-1) };
}

export function verifyFixtures(files, binary) {
  const reported = data(files, 'fixtures/results.json');
  assert.deepEqual(reported.map(c => c.kind), kinds);
  const caches = new Set();
  for (const [index, kind] of kinds.entries()) {
    const dir = 'fixtures/' + kind;
    const run = invocation(files, dir, 'java');
    const policyValue = kind === 'permissive-missing' ? policy.permissive : policy.strict;
    const repo = flag(run.args, '--repository')[0];
    const cache = flag(run.args, '--cache')[0];
    assert.match(repo, /^http:\/\/127\.0\.0\.1:[1-9]\d*$/);
    assert(cache.endsWith('/fixture-' + kind) && !caches.has(cache));
    caches.add(cache);
    assert.deepEqual(run.args, ['-Xmx512m', '-jar', binary, 'fetch', '--no-default', '--repository', repo, '--cache', cache, '--checksum', policyValue, 'fixture:probe:1']);
    const requests = data(files, dir + '/requests.json');
    for (const req of requests) {
      assert(['GET', 'HEAD'].includes(req.method));
      const body = serve(req.path, kind);
      assert.equal(req.status, body === null ? 404 : 200);
      assert.equal(req.sha256, body && hash(body));
    }
    const observed = data(files, dir + '/output-file.json');
    let bytesMatch = false;
    if (observed !== null) {
      const path = run.stdout.trim();
      assert.equal(run.code, 0);
      assert(path.startsWith(cache + '/') && path.endsWith('/fixture/probe/1/probe-1.jar') && !path.includes('\n'));
      assert.equal(observed.path, path);
      digest(observed.sha256);
      bytesMatch = observed.sha256 === hash(serve('/fixture/probe/1/probe-1.jar', kind));
    }
    const verdict = fixtureVerdict(kind, run, requests, bytesMatch);
    assert.deepEqual(reported[index], { kind, checksum: policyValue, code: run.code, signal: run.signal, bytesMatch, verdict });
    assert.equal(verdict, 'pass', kind + ' checksum control ' + verdict);
  }
}

export async function rebuildGraph(files, coordinates) {
  const entries = data(files, 'cache-files.json');
  const cache = new Map();
  for (const entry of entries) {
    assert(typeof entry.path === 'string' && /^[^/]+\/.+/.test(entry.path) && !entry.path.split('/').includes('..'));
    digest(entry.sha256);
    assert(!cache.has(entry.path));
    cache.set(entry.path, entry.sha256);
  }
  const raw = data(files, 'dep-tree.json');
  for (const node of raw.dependencies) if (node.file != null) {
    const parts = node.file.split(suffix);
    assert.equal(parts.length, 2, 'raw resolver path outside expected output base');
    node.file = '/v1/https/' + parts[1];
  }
  const graph = await normalize(raw, '/v1', path => cache.get(path.slice('/v1/https/'.length)));
  assert(graph.dependencies.some(n => n.file && n.direct.length));
  const resolved = new Set(graph.dependencies.map(n => n.coord.split(':').slice(0, 2).join(':')));
  for (const coord of coordinates) {
    const key = coord.split(':').slice(0, 2).join(':');
    assert(resolved.has(key), 'resolver input missing from raw graph: ' + key);
  }
  const enriched = data(files, 'coursier-deps.json');
  assert.equal(enriched.version, '0.1.0');
  const rawCoords = new Set(graph.dependencies.map(n => n.coord));
  const indexed = new Set();
  for (const node of enriched.dependencies) {
    assert(rawCoords.has(node.coord), 'enriched coordinate absent from raw report: ' + node.coord);
    if (!node.file) continue;
    assert(node.file.startsWith('v1/https/'));
    digest(node.sha256);
    assert.equal(cache.get(node.file.slice('v1/https/'.length)), node.sha256, 'RJE index hash mismatch');
    indexed.add(node.sha256);
  }
  const lock = data(files, 'unsorted_deps.json');
  assert.equal(lock.version, '3');
  for (const key of ['dependencies', 'repositories', 'artifacts']) assert(Object.keys(lock[key]).length);
  let represented = 0;
  for (const [coordinate, artifact] of Object.entries(lock.artifacts)) {
    assert(resolved.has(coordinate.split(':').slice(0, 2).join(':')));
    for (const sha of Object.values(artifact.shasums)) {
      if (sha === null) continue; // A metadata-only lock entry carries no content digest.
      digest(sha);
      assert(indexed.has(sha), 'lock artifact hash absent from resolver index');
      represented++;
    }
  }
  assert(represented > 0);
  assert.deepEqual(graph, data(files, 'graph.json'));
  const list = historical([...cache.keys()]);
  assert(list.length && list === text(files, 'historical-file-list.txt'));
  return { graph, list };
}

export async function verifyRow(files, ctx, os, version, mode) {
  verifySource(data(files, 'source.json'), ctx, os, version, mode);
  const { query, coordinates, binary, inputs } = tooling(files, version, mode);
  if (mode === 'permissive') verifyFixtures(files, binary);
  const { graph, list } = await rebuildGraph(files, coordinates);
  const labels = lines(query.stdout).filter(line => line.includes(' rule ')).sort();
  assert(labels.some(label => label.endsWith('//:pin')));
  assert(text(files, 'BUILD').includes('name = "pin"'));
  assert.equal(labels.join('\n') + '\n', text(files, 'targets.txt'));
  const result = {
    nodes: graph.dependencies.length, targets: labels.length, metadataOnly: graph.dependencies.filter(n => n.file === null).length,
    graphSha256: hash(text(files, 'graph.json')), targetsSha256: hash(text(files, 'targets.txt')), lockSha256: hash(text(files, 'unsorted_deps.json')),
    historicalAlgorithmSha256: hash(list), historicalReferenceSha256: historicalHash, historicalMatch: hash(list) === historicalHash,
    resolverSha256: versions[version], checksum: policy[mode], variants: flag(options, '--variant'),
  };
  assert.deepEqual(result, data(files, 'graph-result.json'), 'worker summary disagrees with reconstructed evidence');
  return { graph, result, inputs };
}
