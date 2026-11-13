// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict';
import { readFileSync, writeFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';
import * as helper from './owned-command.mjs';
import { createHash } from 'node:crypto';
const [mode, directory] = process.argv.slice(2);
const ready = join(directory, 'ready.json');
const listeners = { SIGTERM: process.listenerCount('SIGTERM'), SIGINT: process.listenerCount('SIGINT') };
const scope = helper.createOwnedCommandScope({ timeoutMs: mode === 'ignoring' ? 2500 : 2000, maxOutputBytes: mode === 'overflow' ? 64 : mode === 'flush' ? 1024 * 1024 : 4096, signalGraceMs: 150, cleanupTimeoutMs: 500, pipeDrainMs: 100 });
const alive = pid => { try { process.kill(pid, 0); return true; } catch (e) { if (e.code === 'ESRCH') return false; throw e; } };
const report = { mode, helperResult: null, passed: false };
try {
  if (mode === 'reuse') {
    assert.equal(typeof helper.maySignalOwnedGroup, 'function');
    const identity = { pid: 4321, anchorExit: false, anchorExitCode: null, anchorSignalCode: null, groupKillSent: false, groupAbsent: false, settled: false };
    assert.equal(helper.maySignalOwnedGroup(identity), true);
    for (const changed of [{ anchorExit: true }, { anchorExitCode: 0 }, { anchorSignalCode: 'SIGKILL' }, { groupKillSent: true }, { groupAbsent: true }, { settled: true }]) assert.equal(helper.maySignalOwnedGroup({ ...identity, ...changed }), false, 'No signal to a potentially reused group');
  } else {
    const command = mode === 'spawn-error' ? '/nonexistent-owned-probe-command' : process.execPath;
    const args = mode === 'spawn-error' ? [] : ['--max-old-space-size=32', join(dirname(fileURLToPath(import.meta.url)), 'probe-fixture.mjs'), mode === 'between' ? 'normal' : mode, ready];
    const promise = scope.run(command, args, mode === 'anchor-spawn-error' ? { cwd: join(directory, 'absent-directory') } : {});
    if (mode === 'ignoring') {
      while (true) { try { readFileSync(ready); break; } catch (e) { if (e.code !== 'ENOENT') throw e; } await delay(5); }
      process.kill(process.pid, 'SIGTERM');
    }
    const result = await promise;
    report.helperResult = { ...result, stdoutBytes: result.stdoutBytes.length <= 4096 ? result.stdoutBytes.toString('base64') : null, stderrBytes: result.stderrBytes.length <= 4096 ? result.stderrBytes.toString('base64') : null,
      stdoutLength: result.stdoutBytes.length, stderrLength: result.stderrBytes.length, stdoutSha256: createHash('sha256').update(result.stdoutBytes).digest('hex'), stderrSha256: createHash('sha256').update(result.stderrBytes).digest('hex') };
    if (mode === 'normal' || mode === 'between') { assert.equal(result.status, 7); assert.equal(result.signal, null); assert.equal(result.error, null); assert.deepEqual([...result.stdoutBytes], [255, 0, 128]); assert.deepEqual([...result.stderrBytes], [254, 1]); }
    if (mode === 'flush') { assert.equal(result.status,13);assert.equal(result.error,null);assert.deepEqual(result.stdoutBytes,Buffer.alloc(256*1024,42));assert.deepEqual(result.stderrBytes,Buffer.alloc(256*1024,43));assert.equal(result.lifecycle.rawComplete,true); }
    if (mode === 'same' || mode === 'ignoring') { const pid = JSON.parse(readFileSync(ready)).pid; report.descendantAliveAfterRun = alive(pid); assert.equal(report.descendantAliveAfterRun, false); }
    if (mode === 'same') { assert.equal(result.error, null); assert.equal(result.lifecycle?.cleanupComplete, true); }
    if (mode === 'ignoring') { assert.ok(result.error); assert.equal(scope.interruptedSignal, 'SIGTERM'); assert.equal(result.status,0); assert.equal(result.signal,null); assert.equal(result.lifecycle?.cleanupComplete, true); const marker=JSON.parse(readFileSync(ready+'.leader-term.json'));assert.equal(marker.received,'SIGTERM');assert.equal(marker.pid,result.lifecycle.commandPid);report.termTriggeredLeaderExit=true; }
    if (mode === 'detached') { const pid = JSON.parse(readFileSync(ready)).pid; report.detachedAliveForOuterCleanup = alive(pid); assert.ok(result.error); assert.equal(result.status, 0); assert.equal(result.lifecycle?.cleanupComplete, false); assert.equal(result.lifecycle?.pipeForcedClosed, true); assert.equal(report.detachedAliveForOuterCleanup, true); }
    if (mode === 'detached-closed') { const pid=JSON.parse(readFileSync(ready)).pid;report.unsupportedEscapeObserved=alive(pid);assert.equal(report.unsupportedEscapeObserved,true);assert.equal(result.lifecycle.detachedDescendantsVerified,false);assert.equal(result.lifecycle.cleanupComplete,true);report.requiresCallerContainment=true; }
    if (mode === 'timeout') { assert.match(result.error, /exceeded/); assert.equal(result.signal, 'SIGKILL'); }
    if (mode === 'overflow') { assert.match(result.error, /output exceeded/); assert.equal(result.stdoutBytes.length + result.stderrBytes.length, 64); }
    if (mode === 'spawn-error' || mode === 'anchor-spawn-error') { assert.ok(result.error); assert.equal(result.status, null); }
    if (mode === 'between') { process.emit('SIGTERM'); await assert.rejects(scope.run(process.execPath, ['-e', 'process.exit(0)']), /refused/); }
  }
  report.passed = true;
} catch (error) { report.error = error.message; process.exitCode = 1; }
finally { try { scope.dispose(); report.disposeSucceeded = true; for (const [name,count] of Object.entries(listeners)) assert.equal(process.listenerCount(name),count); report.listenersRestored = true; } catch (error) { report.disposeError = error.message; process.exitCode = 1; } writeFileSync(join(directory, 'worker-result.json'), JSON.stringify(report, null, 2) + '\n', { flag: 'wx', mode: 0o600 }); }
