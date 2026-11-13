// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import { spawn } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { setTimeout as delay } from 'node:timers/promises';
const [mode, ready] = process.argv.slice(2);
if (mode === 'descendant' || mode === 'ignoring-descendant') {
  if (mode === 'ignoring-descendant') process.on('SIGTERM', () => {});
  writeFileSync(ready, JSON.stringify({ pid: process.pid }), { flag: 'wx', mode: 0o600 });
  setInterval(() => {}, 1000);
} else if (mode === 'normal') {
  process.stdout.write(Buffer.from([255, 0, 128])); process.stderr.write(Buffer.from([254, 1])); process.exitCode = 7;
} else if (mode === 'flush') {
  process.stdout.write(Buffer.alloc(256 * 1024, 42)); process.stderr.write(Buffer.alloc(256 * 1024, 43)); process.exitCode = 13;
} else if (mode === 'timeout') {
  setInterval(() => {}, 1000);
} else if (mode === 'overflow') {
  process.stdout.write(Buffer.alloc(4096, 120)); setInterval(() => {}, 1000);
} else {
  if (mode === 'ignoring') process.on('SIGTERM', () => { writeFileSync(ready + '.leader-term.json', JSON.stringify({ pid: process.pid, received: 'SIGTERM' }), { flag: 'wx', mode: 0o600 }); process.exit(0); });
  const child = spawn(process.execPath, ['--max-old-space-size=32', import.meta.filename, mode === 'ignoring' ? 'ignoring-descendant' : 'descendant', ready],
    { detached: mode === 'detached' || mode === 'detached-closed', stdio: mode === 'detached' ? ['ignore', 'inherit', 'inherit'] : 'ignore' });
  child.unref();
  while (true) { try { readFileSync(ready); break; } catch (error) { if (error.code !== 'ENOENT') throw error; } await delay(5); }
  process.stdout.write('leader-output\n');
  if (mode !== 'ignoring') process.exit(0);
  else setInterval(() => {}, 1000);
}
