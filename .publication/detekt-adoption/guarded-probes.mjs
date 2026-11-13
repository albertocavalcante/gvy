// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import { mkdirSync, readFileSync, writeFileSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { runGuardedNode } from './probe-guard.mjs';
import { createHash } from 'node:crypto';
const directory = dirname(fileURLToPath(import.meta.url));
const [tag, ...requested] = process.argv.slice(2);
if (!/^[a-z0-9-]{1,40}$/.test(tag ?? '')) throw Error('Expected fresh probe tag');
const modes = requested.length ? requested : ['normal','flush','same','ignoring','detached','detached-closed','timeout','overflow','between','spawn-error','anchor-spawn-error','reuse'];
const output = join(directory, 'probes-' + tag); mkdirSync(output, { mode: 0o700 });
const results = [];
for (const mode of modes) {
  if (!['normal','flush','same','ignoring','detached','detached-closed','timeout','overflow','between','spawn-error','anchor-spawn-error','reuse'].includes(mode)) throw Error('Unknown mode');
  const owned = join(output, mode); mkdirSync(owned, { mode: 0o700 });
  const guarded = await runGuardedNode([join(directory, 'probe-worker.mjs'), mode, owned], owned);
  const resultPath = join(owned, 'worker-result.json');
  results.push({ mode, ...guarded, report: existsSync(resultPath) ? JSON.parse(readFileSync(resultPath)) : null });
}
const sourceHashes = Object.fromEntries(['owned-command.mjs','guarded-probes.mjs','probe-guard.mjs','probe-register.cjs','probe-worker.mjs','probe-fixture.mjs'].map(name=>[name,createHash('sha256').update(readFileSync(join(directory,name))).digest('hex')]));
const result = { scope: 'OWNED_NODE_ONLY', sourceHashes, results, noRemote: true, noJava: true, noResourceQuery: true, guard: 'Independent per-case 5000ms hard stop + bounded1500ms identity cleanup; direct worker ChildProcess kill/local-pipe close; registered own-UID PID/start/token only; no group/name scan', allPassed: results.every(r => r.status === 0 && !r.hardStopFired && !r.cleanupAmbiguous && !r.outputOverflow && r.remainingOwned === 0 && r.report?.passed) };
writeFileSync(join(output, 'result.json'), JSON.stringify(result, null, 2) + '\n', { flag: 'wx', mode: 0o600 });
console.log(JSON.stringify(result)); process.exitCode = result.allPassed ? 0 : 1;
