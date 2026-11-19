// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import { spawnSync } from 'node:child_process';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

// Inspect the running process, not a port parsed from configuration or a boot log.
// Reject all Internet sockets, including UDP and outbound TCP, at each checkpoint.
export function inspectSockets(pid) {
  if (!Number.isSafeInteger(pid) || pid <= 0) throw Error('invalid socket inspection PID');
  process.kill(pid, 0);
  const args = ['-nP', '-a', '-p', String(pid), '-i'];
  const result = spawnSync('lsof', args, { encoding: 'utf8', timeout: 5000, maxBuffer: 1024 * 1024 });
  if (result.error) throw Error('socket inspection unavailable: ' + result.error.message);
  if (result.signal || result.stderr.trim()) throw Error('socket inspection failed: ' + result.stderr.trim());
  // lsof returns 1 and empty output when the selection has no matching files.
  if (result.status !== 1 || result.stdout.trim()) throw Error('oracle process has Internet sockets: ' + result.stdout.trim());
  process.kill(pid, 0);
  return { pid, checkedAt: new Date().toISOString(), command: ['lsof', ...args], internetSockets: 0 };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try { console.log(JSON.stringify(inspectSockets(Number(process.argv[2])))); }
  catch (error) { console.error(error.message); process.exitCode = 1; }
}
