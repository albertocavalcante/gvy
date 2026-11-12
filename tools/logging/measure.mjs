// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

/** Count pipe bytes and newline-delimited records without retaining records. */
import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { once } from 'node:events';
import { fileURLToPath, pathToFileURL } from 'node:url';

export async function measureChild(mode) {
  // Validate the fixed fixture name before launching a child.
  if (!['burst', 'no-newline', 'unicode'].includes(mode)) throw new Error('Unknown child fixture');
  const child = spawn(process.execPath, [fileURLToPath(new URL('./fixtures.mjs', import.meta.url)), mode], {
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  const finished = once(child, 'close');
  const hash = createHash('sha256');
  let bytes = 0;
  let records = 0;
  let readableChunks = 0;
  let lastByte;
  let stats = '';
  child.stderr.setEncoding('utf8');
  child.stderr.on('data', (chunk) => { stats += chunk; });
  for await (const chunk of child.stdout) {
    readableChunks += 1;
    bytes += chunk.length;
    hash.update(chunk);
    for (const byte of chunk) if (byte === 10) records += 1;
    lastByte = chunk.at(-1);
  }
  const [code, signal] = await finished;
  if (code !== 0) throw new Error(`Fixture child failed: ${code ?? signal}`);
  const terminated = lastByte === 10;
  if (bytes > 0 && !terminated) records += 1;
  const producer = JSON.parse(stats);
  return { mode, bytes, records, terminated, sha256: hash.digest('hex'), producerWrites: producer.writes,
    flushCalls: producer.flushCalls, readableChunks };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const modes = ['burst', 'no-newline', 'unicode'];
  const results = [];
  for (const mode of modes) results.push(await measureChild(mode));
  console.log(JSON.stringify({ node: process.version, platform: process.platform, arch: process.arch, results }, null, 2));
}
