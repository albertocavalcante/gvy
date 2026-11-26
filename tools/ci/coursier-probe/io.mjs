// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { mkdir, open, writeFile } from 'node:fs/promises';
import { join } from 'node:path';

export const base = '3922fe11cade046f7553be3057361667d86f0af4';
export const versions = {
  '2.1.25-M23': '8b373a4aab15203c5bf43fcbe7475ae45e160a6c0f3b2560fc36a7d51d618dec',
  '2.1.26': 'd3135830850eddfb9998be66ab4c79478ee6cff56012bb5a177532b1df47c7cc',
  '2.1.27': '8d1e0d684a55c09828b7afda481139472d87a18e0544bb875e2e5e3e300f64b3',
};
export const policy = { strict: 'SHA-1,MD5', permissive: 'SHA-1,MD5,None' };
export const historicalHash = 'f5d6871547e7becfa069455bee63dbcec417d8e861c157ac71f9fb84a3097972';
export const json = (file, data) => writeFile(file, JSON.stringify(data, null, 2) + '\n');
export const hash = bytes => createHash('sha256').update(bytes).digest('hex');

export async function fileHash(file) {
  const digest = createHash('sha256');
  for await (const chunk of createReadStream(file)) digest.update(chunk);
  return digest.digest('hex');
}

// Raw output stays on disk. A killed command never qualifies as a behavioral failure.
export async function invoke(dir, command, args, timeout, env = process.env) {
  await mkdir(dir, { recursive: true });
  const stdout = await open(join(dir, 'stdout.txt'), 'wx');
  const stderr = await open(join(dir, 'stderr.txt'), 'wx');
  await json(join(dir, 'command.json'), { command, args, timeout, cwd: process.cwd() });
  try {
    const result = await new Promise((resolve, reject) => {
      const child = spawn(command, args, { env, timeout, killSignal: 'SIGKILL', stdio: ['ignore', stdout.fd, stderr.fd] });
      child.once('error', reject);
      child.once('close', (code, signal) => resolve({ code, signal }));
    });
    await json(join(dir, 'exit.json'), result);
    return result;
  } finally {
    await Promise.all([stdout.close(), stderr.close()]);
  }
}
