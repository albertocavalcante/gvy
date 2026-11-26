// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { copyFile, mkdir, readFile, readdir, realpath, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

import { fixtures } from './fixture.mjs';
import { historical, normalize } from './graph.mjs';
import { base, fileHash, hash, historicalHash, invoke, json, policy, versions } from './io.mjs';

const [stage, version, mode] = process.argv.slice(2);
assert(Object.hasOwn(versions, version) && Object.hasOwn(policy, mode));
assert.equal(process.env.GITHUB_ACTIONS, 'true', 'qualification runs on fresh hosted jobs');
const temp = await realpath(process.env.RUNNER_TEMP);
const out = join(temp, 'coursier-evidence');
const binary = join(temp, 'coursier.jar');
const git = (...args) => execFileSync('git', args, { encoding: 'utf8', maxBuffer: 8_000_000 });
await mkdir(out, { recursive: true });

async function source() {
  assert.equal(git('status', '--porcelain'), '');
  const files = git('ls-tree', '-rz', 'HEAD').split('\0').filter(line =>
    !line.endsWith('\t.github/workflows/gradle-compat.yml') && !line.includes('\ttools/ci/coursier-probe/')).join('\0');
  assert.equal(hash(files), '6eb5f7df09de3e6ee5e55a5b93f7a546b5213a2d9afcf68e010ff21ee253afba');
  const head = git('rev-parse', 'HEAD').trim();
  assert.equal(head, process.env.GITHUB_SHA);
  await json(join(out, 'source.json'), { base, head, tree: git('rev-parse', 'HEAD^{tree}').trim(),
    sourceTreeWithoutProbeSha256: hash(files), os: process.env.RUNNER_OS, arch: process.arch,
    platform: process.platform, release: process.env.ImageVersion, version, mode, run: process.env.GITHUB_RUN_ID,
    attempt: process.env.GITHUB_RUN_ATTEMPT, node: process.version,
    bazelPin: (await readFile('.bazelversion', 'utf8')).trim() });
  const result = await invoke(join(out, 'java-version'), 'java', ['-Xmx128m', '-version'], 30_000);
  assert.equal(result.code, 0);
}

async function download() {
  const metadata = JSON.parse(execFileSync('gh', ['api', 'repos/coursier/coursier/releases/tags/v' + version]));
  const assets = metadata.assets.filter(a => a.name === 'coursier.jar');
  assert.equal(assets.length, 1);
  const asset = assets[0];
  await json(join(out, 'release.json'), { tag: metadata.tag_name, asset: asset.id, digest: asset.digest, bytes: asset.size,
    url: asset.browser_download_url });
  assert.equal(metadata.tag_name, 'v' + version);
  assert.equal(asset.digest, 'sha256:' + versions[version]);
  const result = await invoke(join(out, 'download'), 'gh', ['release', 'download', 'v' + version,
    '--repo', 'coursier/coursier', '--pattern', 'coursier.jar', '--output', binary], 180_000);
  assert.equal(result.code, 0);
  assert.equal(await fileHash(binary), versions[version]);
}

const variants = [
  'org.gradle.category=library', 'org.gradle.usage=runtime', 'org.gradle.jvm.environment=standard-jvm',
  'org.jetbrains.kotlin.platform.type=jvm', 'org.gradle.libraryelements=jar',
  'org.gradle.dependency.bundling=external|shadowed', 'ui=awt',
];

async function captureGraph(repo) {
  for (const file of ['dep-tree.json', 'coursier-deps.json', 'unsorted_deps.json', 'BUILD', 'exclusion-file.txt', 'java_argsfile']) {
    await copyFile(join(repo, file), join(out, file));
  }
  const args = (await readFile(join(out, 'java_argsfile'), 'utf8')).trim().split('\n').map(s => JSON.parse(s));
  const values = flag => args.flatMap((arg, i) => arg === flag ? [args[i + 1]] : []);
  assert.deepEqual(values('--checksum'), [policy[mode]]);
  assert.deepEqual(values('--variant'), variants);
  assert(args.includes('--enable-modules') && args.includes('--no-default') && args.includes('fetch'));
  assert.equal(await fileHash(join(repo, 'coursier')), versions[version]);
  const lock = JSON.parse(await readFile(join(out, 'unsorted_deps.json')));
  assert.equal(lock.version, '3');
  for (const key of ['artifacts', 'dependencies', 'repositories']) assert(Object.keys(lock[key]).length);
  const cache = await realpath(join(repo, 'v1'));
  const raw = JSON.parse(await readFile(join(out, 'dep-tree.json')));
  // macOS can report /private/var/... for a /var/... working directory.
  for (const node of raw.dependencies) if (node.file != null) node.file = await realpath(node.file);
  const normalized = await normalize(raw, cache, fileHash);
  assert(normalized.dependencies.some(node => node.direct.length && node.file));
  await json(join(out, 'graph.json'), normalized);

  const hostRoot = join(cache, 'https');
  const paths = [];
  async function walk(prefix) {
    for (const item of await readdir(join(hostRoot, prefix), { withFileTypes: true })) {
      const path = join(prefix, item.name);
      if (item.isDirectory()) await walk(path);
      else if (item.isFile()) paths.push(path);
    }
  }
  await walk('');
  const list = historical(paths);
  assert(list.length);
  await writeFile(join(out, 'historical-file-list.txt'), list);
  const cacheEntries = [];
  for (const path of paths.sort()) {
    cacheEntries.push({ path, sha256: await fileHash(join(hostRoot, path)) });
  }
  await json(join(out, 'cache-files.json'), cacheEntries);
  await json(join(out, 'resolver-binaries.json'), {
    releaseSha256: await fileHash(binary), installedSha256: await fileHash(join(repo, 'coursier')),
  });
  const kinds = (await readFile(join(out, 'query', 'stdout.txt'), 'utf8')).trim().split('\n').filter(line => line.includes(' rule ')).sort();
  assert(kinds.length && kinds.some(line => line.includes('//:pin')));
  await writeFile(join(out, 'targets.txt'), kinds.join('\n') + '\n');
  const result = { nodes: normalized.dependencies.length, targets: kinds.length,
    metadataOnly: normalized.dependencies.filter(node => !node.file).length,
    graphSha256: await fileHash(join(out, 'graph.json')), targetsSha256: await fileHash(join(out, 'targets.txt')),
    lockSha256: await fileHash(join(out, 'unsorted_deps.json')), historicalAlgorithmSha256: hash(list),
    historicalReferenceSha256: historicalHash, historicalMatch: hash(list) === historicalHash,
    resolverSha256: versions[version], checksum: policy[mode], variants };
  await json(join(out, 'graph-result.json'), result);
}

async function graph() {
  assert.equal(await fileHash(binary), versions[version]);
  const check = await invoke(join(out, 'bazel-version'), 'bazel', ['--version'], 180_000);
  assert.equal(check.code, 0);
  const actual = (await readFile(join(out, 'bazel-version', 'stdout.txt'), 'utf8')).trim();
  assert.equal(actual, 'bazel ' + (await readFile('.bazelversion', 'utf8')).trim());
  const output = join(temp, 'probe-output-base');
  await mkdir(output);
  const module = await readFile('MODULE.bazel', 'utf8');
  const field = '    fail_on_missing_checksum = False,';
  assert.equal(module.split(field).length, 2);
  if (mode === 'strict') await writeFile('MODULE.bazel', module.replace(field, field.replace('False', 'True')));
  await writeFile(join(out, 'resolver-input.patch'), git('diff', '--binary', '--', 'MODULE.bazel'));
  const startup = ['--batch', '--host_jvm_args=-Xmx2g', '--output_base=' + output];
  const options = ['--lockfile_mode=update', '--repository_cache=' + join(temp, 'probe-repository-cache'),
    '--repo_env=COURSIER_URL=' + pathToFileURL(binary), '--repo_env=COURSIER_SHA256=' + versions[version],
    '--repo_env=COURSIER_OPTS=-Xmx2g', '--repo_env=JAVA_TOOL_OPTIONS=-Xmx2g', '--repo_env=RJE_VERBOSE=1'];
  const result = await invoke(join(out, 'query'), 'bazel', [...startup, 'query', ...options, '--output=label_kind', '@maven//:*'], 1_200_000);
  await writeFile(join(out, 'module-lock.patch'), git('diff', '--binary', '--', 'MODULE.bazel.lock'));
  const repo = join(output, 'external', 'rules_jvm_external++maven+maven');
  if (result.code !== 0 || result.signal) {
    // Bazel may delete an unsuccessful repository. Preserve any genuine partial
    // resolver receipts that still exist, independently of complete graph gates.
    for (const name of ['dep-tree.json', 'java_argsfile', 'exclusion-file.txt', 'coursier-deps.json', 'unsorted_deps.json']) {
      try {
        await copyFile(join(repo, name), join(out, name));
      } catch (error) {
        if (error.code !== 'ENOENT') throw error;
      }
    }
    throw Error('Bazel @maven evaluation failed or was interrupted. Inspect query/stderr.txt and exit.json.');
  }
  await captureGraph(repo);
}

try {
  if (stage === 'source') await source();
  else if (stage === 'download') await download();
  else if (stage === 'graph') await graph();
  else if (stage === 'fixtures') {
    assert.equal(await fileHash(binary), versions[version]);
    await fixtures(binary, join(out, 'fixtures'), temp);
  } else throw Error('unknown stage');
} catch (error) {
  await json(join(out, stage + '-error.json'), { message: String(error), stack: error.stack });
  throw error;
}
