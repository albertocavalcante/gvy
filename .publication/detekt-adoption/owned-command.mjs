// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import { spawn } from 'node:child_process';
import { randomBytes } from 'node:crypto';

// A private anchor retains the group's sole-parent-owned unreaped identity through cleanup.
const ANCHOR = String.raw`
const {spawn}=require('node:child_process');
const [token,command,...args]=process.argv.slice(1);
let actual=null,started=false;
const selfStop=()=>{try{process.kill(-process.pid,'SIGKILL');}catch{process.exit(1);}};
const send=message=>{if(process.connected)try{process.send({token,...message},error=>{if(error)selfStop();});}catch{selfStop();}};
process.on('SIGTERM',()=>{});process.on('SIGINT',()=>{});
process.on('disconnect',selfStop);
process.on('message',message=>{
 if(message?.token!==token)return;
 if(message.type==='start'&&!started){
  started=true;
  try{
   actual=spawn(command,args,{stdio:['ignore','inherit','inherit']});
   actual.once('spawn',()=>send({type:'spawned',pid:actual.pid}));
   actual.once('error',error=>send({type:'spawn-error',error:error.message.slice(0,512)}));
   actual.once('exit',(status,signal)=>send({type:'direct-exit',status,signal}));
  }catch(error){send({type:'spawn-error',error:error.message.slice(0,512)});}
 }
 if(message.type==='kill-command'){
  if(!started){started=true;send({type:'not-started'});}
  else if(actual&&actual.exitCode===null&&actual.signalCode===null)actual.kill('SIGKILL');
 }
});
setInterval(()=>{},1000);
send({type:'ready'});
`;

export function maySignalOwnedGroup(state) {
  return Number.isSafeInteger(state.pid) && state.pid > 1 && !state.anchorExit && state.anchorExitCode === null && state.anchorSignalCode === null && !state.groupKillSent && !state.groupAbsent && !state.settled;
}

// Group contract only: detached/escaped descendants need reviewed caller containment.
export function createOwnedCommandScope({ timeoutMs = 15_000, maxOutputBytes = 4 * 1024 * 1024, signalGraceMs = 1000, cleanupTimeoutMs = 1000, pipeDrainMs = 250 } = {}) {
  if (!['darwin', 'linux'].includes(process.platform)) throw Error('POSIX controller required');
  for (const [name, value, maximum] of [['timeoutMs', timeoutMs, 2_147_000_000], ['signalGraceMs', signalGraceMs, 60_000], ['cleanupTimeoutMs', cleanupTimeoutMs, 60_000], ['pipeDrainMs', pipeDrainMs, 60_000], ['maxOutputBytes', maxOutputBytes, 128 * 1024 * 1024]]) {
    if (!Number.isSafeInteger(value) || value < (name === 'signalGraceMs' || name === 'pipeDrainMs' ? 0 : 1) || value > maximum) throw Error('Invalid bounded scope option: ' + name);
  }
  let active = null, interruptedSignal = null, disposed = false;
  const cleanupReserveMs = 2 * signalGraceMs + cleanupTimeoutMs + pipeDrainMs;
  const executionBudgetMs = timeoutMs - cleanupReserveMs;
  const budget = Object.freeze({ totalMs: timeoutMs, executionMs: Math.max(0, executionBudgetMs), cleanupReserveMs, signalGraceMs, cleanupTimeoutMs, pipeDrainMs });
  const forward = signal => { interruptedSignal ??= signal; active?.interrupt(signal); };
  const onInterrupt = () => forward('SIGINT'), onTerminate = () => forward('SIGTERM');
  process.on('SIGINT', onInterrupt); process.on('SIGTERM', onTerminate);
  return {
    get interruptedSignal() { return interruptedSignal; },
    get budget() { return budget; },
    get activeIdentity() { return active?.identity() ?? null; },
    async run(command, args = [], options = {}) {
      const started = performance.now();
      if (disposed) throw Error('Owned scope is disposed');
      if (active) throw Error('An owned child is already active');
      if (interruptedSignal) throw Error(`Child launch refused after ${interruptedSignal}`);
      if (typeof command !== 'string' || !command || command.includes('\0') || !Array.isArray(args) || !args.every(arg => typeof arg === 'string' && !arg.includes('\0'))) throw Error('Expected command and string arguments');
      if (!options || Object.keys(options).some(key => !['cwd', 'env'].includes(key))) throw Error('Only cwd/env spawn options are supported by the owned-group contract');
      if (executionBudgetMs <= 0 || performance.now() - started >= executionBudgetMs) return { stdoutBytes: Buffer.alloc(0), stderrBytes: Buffer.alloc(0), status: null, signal: null, error: 'Insufficient total operation budget for owned cleanup reserve',
        lifecycle: { contract: 'posix-owned-group-v2', budget, elapsedMs: Math.ceil(performance.now() - started), refusedBeforeLaunch: true, directExitObserved: false, anchorPid: null, groupId: null, commandPid: null, cleanupComplete: true, ambiguous: false, rawComplete: true, detachedDescendantsVerified: false, containment: 'No command launched' } };
      return await new Promise(resolve => {
        const token = randomBytes(16).toString('hex');
        const stdout = [], stderr = [], faults = [], timers = new Set();
        let child, bytes = 0, error = null, status = null, signal = null, commandPid = null;
        let ready = false, actualExit = false, spawnFailed = false, anchorExit = false, anchorClose = false, groupAbsent = false, groupKillSent = false, groupProbeUncertainSeen = false, cleanupStarted = false, pipeForcedClosed = false, settled = false, pendingStop = false;
        let escalation = null, forceCleanup = null, probe = null, runTimer = null;
        const fault = message => { if (settled) return; message = String(message).slice(0, 512); error ??= message; if (faults.length < 12 && !faults.includes(message)) faults.push(message); };
        const later = (fn, ms) => { if (settled) return null; const timer = setTimeout(() => { timers.delete(timer); if (!settled) fn(); }, ms); timers.add(timer); return timer; };
        const send = message => {
          if (!child?.connected || settled) return;
          try { child.send({ token, ...message }, failure => { if (failure && !settled) { fault('Anchor IPC failed: ' + failure.message); beginCleanup(); } }); }
          catch (failure) { fault('Anchor IPC failed: ' + failure.message); beginCleanup(); }
        };
        const identity = () => ({ pid: child?.pid, anchorExit, anchorExitCode: child?.exitCode ?? null, anchorSignalCode: child?.signalCode ?? null, groupKillSent, groupAbsent, settled });
        const groupSignal = requested => {
          if (!maySignalOwnedGroup(identity())) return false;
          if (requested === 'SIGKILL') groupKillSent = true;
          try { process.kill(-child.pid, requested); return true; }
          catch (failure) { if (failure.code === 'ESRCH') groupAbsent = true; else fault('Owned group signal failed: ' + failure.message); return false; }
        };
        const observeGroup = () => {
          if (settled) return;
          if (groupAbsent || !child?.pid) { groupAbsent = true; return; }
          try { process.kill(-child.pid, 0); }
          catch (failure) { if (failure.code === 'ESRCH') groupAbsent = true; else if (failure.code === 'EPERM') groupProbeUncertainSeen = true; else fault('Owned group observation failed: ' + failure.message); }
        };
        const finish = forced => {
          if (settled) return;
          observeGroup();
          if (forced) {
            fault('Owned lifecycle cleanup deadline expired; caller containment required');
            groupSignal('SIGKILL');
            if (!anchorClose) { pipeForcedClosed = true; child.stdout?.destroy(); child.stderr?.destroy(); }
          }
          if (!forced && !(anchorExit && anchorClose && groupAbsent)) return;
          if (!actualExit && !spawnFailed) fault('Original direct-command exit status was not observed');
          if (!groupAbsent) fault('Owned group remains present or may have been reused; no further signal is safe');
          if (pipeForcedClosed) fault('Inherited pipe remained open outside observed group cleanup; caller containment required');
          const stdoutBytes = Buffer.concat(stdout), stderrBytes = Buffer.concat(stderr);
          const elapsedMs = Math.ceil(performance.now() - started);
          const budgetOverrun = elapsedMs > timeoutMs;
          if (budgetOverrun) fault('Total operation budget elapsed before receipt materialization');
          settled = true;
          for (const timer of timers) clearTimeout(timer);
          if (probe) clearInterval(probe);
          if (child.connected) { try { child.disconnect(); } catch {} }
          child.unref(); active = null;
          const cleanupComplete = anchorExit && anchorClose && groupAbsent && !pipeForcedClosed;
          resolve({ stdoutBytes, stderrBytes, status, signal, error,
            lifecycle: { contract: 'posix-owned-group-v2', budget, elapsedMs, budgetOverrun, refusedBeforeLaunch: false, anchorPid: child.pid ?? null, groupId: child.pid ?? null, commandPid, ownerToken: token,
              directExitObserved: actualExit, directSpawnFailed: spawnFailed, anchorExitObserved: anchorExit, anchorCloseObserved: anchorClose, anchorStatus: child.exitCode, anchorSignal: child.signalCode,
              groupKillSent, groupQuiescentObserved: groupAbsent, groupProbeUncertainSeen, pipeForcedClosed, rawComplete: anchorClose && !pipeForcedClosed,
              cleanupComplete, ambiguous: !cleanupComplete || (!actualExit && !spawnFailed), detachedDescendantsVerified: false,
              containment: 'process-group-only; detached/escaped descendants require reviewed caller containment', faults: [...faults] } });
        };
        const beginCleanup = () => {
          if (cleanupStarted || settled) return;
          cleanupStarted = true;
          clearTimeout(runTimer); timers.delete(runTimer);
          // Our unreaped anchor pins this PID/PGID. Seal it before the final group kill.
          groupSignal('SIGKILL');
          probe = setInterval(() => { observeGroup(); finish(false); }, 10);
          later(() => finish(true), cleanupTimeoutMs); finish(false);
        };
        const stopDirect = () => {
          pendingStop = true; send({ type: 'kill-command' });
          if (!forceCleanup) forceCleanup = later(beginCleanup, signalGraceMs);
        };
        const interrupt = requested => {
          fault('Owned scope interrupted by ' + requested); pendingStop = true;
          if (ready) groupSignal(requested);
          if (!escalation) escalation = later(stopDirect, signalGraceMs); else stopDirect();
        };
        child = spawn(process.execPath, ['--max-old-space-size=32', '-e', ANCHOR, '--', token, command, ...args], { ...options, detached: true, stdio: ['ignore', 'pipe', 'pipe', 'ipc'] });
        active = { interrupt, identity: () => ({ anchorPid: child.pid ?? null, groupId: child.pid ?? null, commandPid, ownerToken: token }) };
        const capture = chunks => chunk => {
          if (settled) return;
          const remaining = maxOutputBytes - bytes;
          if (remaining > 0) chunks.push(chunk.subarray(0, remaining));
          bytes += chunk.length;
          if (bytes > maxOutputBytes && !pendingStop) { fault(`Owned child output exceeded ${maxOutputBytes} bytes`); stopDirect(); }
        };
        child.stdout?.on('data', capture(stdout)); child.stderr?.on('data', capture(stderr));
        child.on('message', message => {
          if (settled || message?.token !== token) return;
          if (message.type === 'ready') { ready = true; send({ type: pendingStop || interruptedSignal ? 'kill-command' : 'start' }); }
          else if (message.type === 'spawned') commandPid = message.pid;
          else if (message.type === 'spawn-error') { spawnFailed = true; fault(message.error); beginCleanup(); }
          else if (message.type === 'not-started') { fault('Direct command launch cancelled before anchor start'); beginCleanup(); }
          else if (message.type === 'direct-exit') { actualExit = true; status = message.status; signal = message.signal; beginCleanup(); }
        });
        child.on('error', failure => { if (settled) return; fault(failure.message); spawnFailed = true; if (!child.pid) { groupAbsent = true; anchorExit = true; } beginCleanup(); });
        child.on('exit', () => {
          if (settled) return;
          anchorExit = true;
          if (!groupKillSent) fault('Owned anchor exited unexpectedly; group identity is no longer signal-safe');
          if (!cleanupStarted) beginCleanup();
          later(() => { if (!anchorClose && !settled) { pipeForcedClosed = true; fault('Inherited pipe close deadline expired'); child.stdout?.destroy(); child.stderr?.destroy(); } }, pipeDrainMs);
          finish(false);
        });
        child.on('close', () => { if (settled) return; anchorClose = true; finish(false); });
        runTimer = later(() => { fault(`Owned child exceeded execution budget ${executionBudgetMs} ms (${timeoutMs} ms total)`); stopDirect(); }, Math.max(0, executionBudgetMs - (performance.now() - started)));
        later(() => { fault('Owned operation total deadline expired'); finish(true); }, Math.max(0, timeoutMs - (performance.now() - started)));
      });
    },
    dispose() {
      if (active) throw Error('Await the bounded owned run before disposing the scope');
      if (disposed) return;
      disposed = true; process.off('SIGINT', onInterrupt); process.off('SIGTERM', onTerminate);
    },
  };
}
