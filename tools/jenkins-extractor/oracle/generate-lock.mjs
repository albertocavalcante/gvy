// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
// Explicit maintainer operation: generate a candidate lock from pinned roots and saved metadata.
import { readdir, readFile, writeFile, copyFile, mkdir } from 'node:fs/promises';
import { execFileSync } from 'node:child_process';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { assertOracleHost, archiveManifest, digest, directory, diskGuard, json, runProcess, verifyArtifact, verifyGraph, withHome } from './oracle.mjs';

async function generate(profile, cache, evidence, java) {
  assertOracleHost();
  const inputs = await json(join(directory, 'roots.json'));
  const target = inputs.profiles[profile];
  if (!target) throw Error('unknown profile');
  const core = { ...target.core, file: 'jenkins-' + target.core.version + '.war',
    url: 'https://get.jenkins.io/war-stable/' + target.core.version + '/jenkins.war' };
  await verifyArtifact(join(cache, core.file), core.sha256);
  await verifyArtifact(join(cache, inputs.tool.file), inputs.tool.sha256);
  await mkdir(evidence, { recursive: true, mode: 0o700 });
  await diskGuard(cache, evidence);
  const metadata = [];
  for (const [file, url] of [
    ['update-center.actual.json', 'https://updates.jenkins.io/update-center.actual.json'],
    ['plugin-versions.json', 'https://updates.jenkins.io/current/plugin-versions.json'],
  ]) metadata.push({ file, url, sha256: digest(await readFile(join(cache, file))) });
  return withHome(join(evidence, 'resolution'), async (work, signal) => {
    await mkdir(join(work, 'tmp'));
    const requested = [...target.roots, ...target.overrides];
    const pluginDir = join(work, 'plugins');
    for (const artifact of [core, inputs.tool]) {
      await copyFile(join(cache, artifact.file), join(work, artifact.file));
      await verifyArtifact(join(work, artifact.file), artifact.sha256);
    }
    for (const input of metadata) {
      await copyFile(join(cache, input.file), join(work, input.file));
      await verifyArtifact(join(work, input.file), input.sha256);
    }
    const versions = await json(join(work, 'plugin-versions.json'));
    const args = ['-Xmx512m', '-XX:MaxMetaspaceSize=256m',
      '-Duser.home=' + work, '-Djava.io.tmpdir=' + join(work, 'tmp'),
      '-XX:ErrorFile=' + join(evidence, profile + '-resolver-crash-%p.log'), '-XX:-CreateCoredumpOnCrash', '-jar', join(work, inputs.tool.file),
      '--war', join(work, core.file), '--plugin-download-directory', pluginDir,
      '--jenkins-update-center', pathToFileURL(join(work, metadata[0].file)).href,
      '--jenkins-plugin-info', pathToFileURL(join(work, metadata[1].file)).href,
      '--jenkins-update-center-download-url', 'https://updates.jenkins.io/download/plugins',
      '--latest=' + target.latest, '--list', '--plugins', ...requested.map(root => root.id + ':' + root.version)];
    await runProcess(java, args, { timeout: 600000, cwd: work, signal, guard: () => diskGuard(cache, evidence), log: join(evidence, profile + '-resolution.log'),
      env: { PATH: process.env.PATH, HOME: work, CACHE_DIR: join(work, 'metadata-cache'), LANG: 'en_US.UTF-8' } });
    const plugins = [];
    for (const name of (await readdir(pluginDir)).filter(name => /\.[hj]pi$/.test(name)).sort()) {
      signal.throwIfAborted();
      await diskGuard(cache, evidence);
      const file = join(pluginDir, name);
      const info = archiveManifest(file);
      const bytes = await readFile(file);
      const sha256 = digest(bytes);
      const advertised = versions.plugins[info.id]?.[info.version]?.sha256;
      if (!advertised || Buffer.from(advertised, 'base64').toString('hex') !== sha256) throw Error('unverified advertised artifact: ' + info.id);
      const cachedFile = info.id + '-' + info.version + '.hpi';
      await copyFile(file, join(cache, cachedFile));
      plugins.push({ ...info, file: cachedFile, sha256,
        url: 'https://updates.jenkins.io/download/plugins/' + info.id + '/' + info.version + '/' + info.id + '.hpi' });
    }
    verifyGraph(plugins);
    for (const root of requested) {
      const actual = plugins.find(plugin => plugin.id === root.id);
      if (actual?.version !== root.version || actual?.sha256 !== root.sha256) throw Error('root mismatch: ' + root.id);
    }
    // The WAR digest locks these too, including detached plugins which are not active in a fresh home.
    const packagedPlugins = [];
    const members = execFileSync('unzip', ['-Z1', join(work, core.file)]).toString().split('\n')
      .filter(member => /^WEB-INF\/(detached-)?plugins\/[^/]+\.[hj]pi$/.test(member));
    for (const member of members) {
      const bytes = execFileSync('unzip', ['-p', join(work, core.file), member], { maxBuffer: 64 * 1024 * 1024 });
      const temp = join(work, 'packaged.hpi');
      await writeFile(temp, bytes);
      packagedPlugins.push({ ...archiveManifest(temp), member, sha256: digest(bytes) });
    }
    const lock = { schemaVersion: 1, profile, core: { ...core, packagedPlugins },
      resolver: { ...inputs.tool, metadata, latest: target.latest, overrides: target.overrides, downloadBase: 'https://updates.jenkins.io/download/plugins' },
      converterRevision: target.converterRevision, roots: target.roots, plugins };
    await writeFile(join(directory, 'locks', profile + '.json'), JSON.stringify(lock, null, 2) + '\n');
    console.log(JSON.stringify({ profile, plugins: plugins.length, packagedPlugins: packagedPlugins.length }));
  });
}
const [profile, cache, evidence, java] = process.argv.slice(2);
try {
  if (!profile || !cache || !evidence || !java) throw Error('usage: node generate-lock.mjs PROFILE CACHE NEW-EVIDENCE-DIR JAVA');
  await generate(profile, resolve(cache), resolve(evidence), resolve(java));
} catch (error) { console.error(error.message); process.exitCode = 1; }
