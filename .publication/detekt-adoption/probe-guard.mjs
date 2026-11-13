// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import { spawn, execFileSync } from 'node:child_process';
import { closeSync, constants, fstatSync, openSync, readSync, writeFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';
const directory = dirname(fileURLToPath(import.meta.url));
// Independent fixed-fixture supervisor: no group or process-name signals.
export async function runGuardedNode(args, owned, hardStopMs = 5000, cleanupBudgetMs = 1500) {
  if (!Number.isSafeInteger(hardStopMs) || hardStopMs < 1 || hardStopMs > 15000 || !Number.isSafeInteger(cleanupBudgetMs) || cleanupBudgetMs < 1 || cleanupBudgetMs > 1500) throw Error('Invalid bounded supervisor budget');
  const registry = join(owned, 'registry.jsonl'); writeFileSync(registry, '', { flag: 'wx', mode: 0o600 });
  const known = new Map(); let registryError = null;
  const records = () => {
    let fd;
    try {
      fd = openSync(registry, constants.O_RDONLY | constants.O_NONBLOCK | constants.O_NOFOLLOW);
      const info = fstatSync(fd); if (!info.isFile() || info.uid !== process.getuid() || info.size > 1024 * 1024) throw Error('Unsafe/oversized fixture registry');
      const bytes = Buffer.alloc(1024 * 1024 + 1); let used = 0;
      while (used < bytes.length) { const n = readSync(fd, bytes, used, bytes.length-used, null); if (!n) break; used += n; }
      if (used > 1024 * 1024) throw Error('Fixture registry grew beyond its bound');
      const text = bytes.subarray(0,used).toString('utf8');
      for (const line of text.slice(0,text.lastIndexOf('\n')+1).split('\n').filter(Boolean)) {
        const row=JSON.parse(line);if(!Number.isSafeInteger(row.pid)||row.pid<=1||row.uid!==process.getuid()||typeof row.start!=='string'||row.start.length>64||!/^owned-node-probe-[0-9a-f]{32}$/.test(row.token))throw Error('Invalid registered fixture identity');
        known.set(row.pid+':'+row.token,row); if(known.size>256)throw Error('Fixture identity bound exceeded');
      }
    } catch(error) {registryError ??= error.message;}
    finally {if(fd!==undefined)try{closeSync(fd);}catch(error){registryError ??= error.message;}}
    return [...known.values()];
  };
  const safeCleanup = () => cleanup().catch(error => { registryError ??= error.message; return records(); });
  const identity = (row, until) => {
    if (performance.now()>=until) return {possible:true,signalable:false};
    try {
      const run = fields => execFileSync('/bin/ps',['-ww','-p',String(row.pid),...fields],{encoding:'utf8',timeout:Math.max(1,Math.min(250,Math.ceil(until-performance.now()))),maxBuffer:16*1024,env:{...process.env,LC_ALL:'C',TZ:'UTC'}}).trim();
      const start=run(['-o','lstart=']);if(start!==row.start)return{possible:false,signalable:false};
      if(performance.now()>=until)return{possible:true,signalable:false};
      const match=/^(\d+)\s+([\s\S]*)$/.exec(run(['-o','uid=','-o','command=']));
      if(Number(match?.[1])!==row.uid)return{possible:false,signalable:false};
      return{possible:true,signalable:match[2].includes(row.token)};
    } catch(error) {return error.status===1?{possible:false,signalable:false}:{possible:true,signalable:false};}
  };
  const cleanup = async () => {
    const until=performance.now()+cleanupBudgetMs;const pending=new Map();const gone=new Set();
    do {
      for(const row of records())if(!gone.has(row.pid+':'+row.token))pending.set(row.pid+':'+row.token,row);
      for(const[key,row]of pending){
        if(performance.now()>=until)break;
        const observed=identity(row,until);
        if(!observed.possible){pending.delete(key);gone.add(key);continue;}
        if(observed.signalable)try{process.kill(row.pid,'SIGKILL');}catch(error){if(error.code==='ESRCH'){pending.delete(key);gone.add(key);}}
      }
      if(!pending.size)break;
      if(performance.now()<until)await delay(Math.min(20,Math.max(1,until-performance.now())));
    }while(performance.now()<until);
    return [...pending.values()];
  };
  let child, workerCloseObserved=false,status=null,signal=null,spawnError=null;
  let hardStopFired=false,outputOverflow=false,cleanupPromise,resolveStopped;
  const stopped=new Promise(resolve=>{resolveStopped=resolve});
  const stop=()=>{
    if(hardStopFired)return;hardStopFired=true;
    // Sole-parent ChildProcess identity remains safe even if its registration was never completed.
    if(child&&child.exitCode===null&&child.signalCode===null)try{child.kill('SIGKILL');}catch{}
    child?.stdout?.destroy();child?.stderr?.destroy();child?.unref();
    cleanupPromise=safeCleanup();resolveStopped();
  };
  const hardStop=setTimeout(stop,hardStopMs);
  let stdout=Buffer.alloc(0),stderr=Buffer.alloc(0),used=0;
  const capture=name=>bytes=>{const remaining=1024*1024-used;if(remaining>0){if(name==='stdout')stdout=Buffer.concat([stdout,bytes.subarray(0,remaining)]);else stderr=Buffer.concat([stderr,bytes.subarray(0,remaining)]);}used+=bytes.length;if(used>1024*1024){outputOverflow=true;stop();}};
  let resolveClosed;const closed=new Promise(resolve=>{resolveClosed=resolve});
  try {
    child=spawn(process.execPath,['--max-old-space-size=96',...args],{stdio:['ignore','pipe','pipe'],env:{...process.env,NODE_OPTIONS:'--require='+join(directory,'probe-register.cjs'),OWNED_NODE_REGISTRY:registry}});
    child.stdout?.on('data',capture('stdout'));child.stderr?.on('data',capture('stderr'));
    child.on('error',error=>{spawnError=error.message});
    child.once('close',(code,terminatedBy)=>{workerCloseObserved=true;status=code;signal=terminatedBy;resolveClosed();});
    await Promise.race([closed,stopped]);
  } catch(error){spawnError=error.message;stop();}
  finally{clearTimeout(hardStop);}
  const remaining=cleanupPromise?await cleanupPromise:await safeCleanup();
  const cleanupAmbiguous=!workerCloseObserved||remaining.length>0||registryError!==null;
  return{status,signal,spawnError,stdout:stdout.toString('utf8'),stderr:stderr.toString('utf8'),hardStopFired,outputOverflow,workerCloseObserved,unconfirmedWorkerPid:workerCloseObserved?null:child?.pid??null,cleanupAmbiguous,registryError,remainingOwned:remaining.length,remainingIdentities:remaining,registeredNodes:known.size};
}
