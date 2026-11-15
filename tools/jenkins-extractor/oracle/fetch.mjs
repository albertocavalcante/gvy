// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import { mkdir, open, rename, stat } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { directory, diskGuard, captureInput, parseLock, verifyArtifact, withHome } from './oracle.mjs';

export async function fetchArtifact(artifact, cache, download = fetch) {
  const destination = join(cache, artifact.file);
  try {
    await stat(destination);
    await verifyArtifact(destination, artifact.sha256);
    return;
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
  }
  await diskGuard(cache);
  await withHome(cache, async (temporary, signal) => {
    const partial = join(temporary, 'artifact');
    const response = await download(artifact.url, {
      signal: AbortSignal.any([signal, AbortSignal.timeout(180000)]),
    });
    if (!response.ok || !response.body) throw Error('download unavailable: ' + artifact.url);
    const output = await open(partial, 'wx', 0o600);
    try {
      let size = 0;
      for await (const chunk of response.body) {
        signal.throwIfAborted();
        size += chunk.length;
        if (size > 256 * 1024 * 1024) throw Error('artifact exceeds 256 MiB');
        await diskGuard(cache);
        await output.writeFile(chunk);
      }
    } finally { await output.close(); }
    await verifyArtifact(partial, artifact.sha256);
    signal.throwIfAborted();
    await rename(partial, destination);
  });
}
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const [profile, target] = process.argv.slice(2);
    if (!['current', 'legacy'].includes(profile) || !target) throw Error('usage: node fetch.mjs current|legacy CACHE');
    const cache = resolve(target);
    await mkdir(cache, { recursive: true, mode: 0o700 });
    const lock = parseLock((await captureInput(join(directory, 'locks', profile + '.json'))).bytes, profile);
    for (const artifact of [lock.core, ...lock.plugins]) await fetchArtifact(artifact, cache);
    console.log(JSON.stringify({ profile, artifacts: lock.plugins.length + 1, verified: true }));
  } catch (error) { console.error(error.message); process.exitCode = 1; }
}
