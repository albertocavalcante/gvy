// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import * as fs from 'node:fs';
import { setTimeout as pause } from 'node:timers/promises';
import { performance } from 'node:perf_hooks';
import { join } from 'node:path';

// Linux-only, read-only nonce observation. Never signal a PID or read non-Java environments.
// Same-user unreadable process metadata is uncertainty, not an empty observation.
export function observeJava(nonce, javaExecutables, io = fs, uid = process.getuid()) {
  if (!/^[a-f0-9]{32}$/.test(nonce)) throw Error('Invalid owned Java nonce');
  const owned = [], uncertain = []; let entries = 0;
  const read = (file, limit) => {
    const fd = io.openSync(file, fs.constants.O_RDONLY | fs.constants.O_NOFOLLOW | fs.constants.O_NONBLOCK);
    try {
      const st = io.fstatSync(fd);
      if (!st.isFile() || st.uid !== uid) throw Error('Foreign/nonregular proc record');
      const bytes = Buffer.alloc(limit + 1); let size = 0;
      while (size < bytes.length) { const n = io.readSync(fd, bytes, size, bytes.length - size, null); if (!n) break; size += n; }
      if (size > limit) throw Error('Proc read bound'); return bytes.subarray(0, size);
    } finally { io.closeSync(fd); }
  };
  const start = directory => {
    const text = read(join(directory, 'stat'), 8192).toString();
    const fields = text.slice(text.lastIndexOf(')') + 2).trim().split(/\s+/);
    if (!/^\d+$/.test(fields[19] ?? '')) throw Error('Malformed process start identity'); return fields[19];
  };
  const directory = io.opendirSync('/proc');
  try {
    for (;;) {
      const entry = directory.readSync(); if (!entry) break;
      if (++entries > 16384) throw Error('Proc entry bound');
      if (!/^\d+$/.test(entry.name)) continue;
      const pid = Number(entry.name), proc = '/proc/' + entry.name;
      try {
        const st = io.statSync(proc); if (st.uid !== uid) continue;
        const comm = read(join(proc, 'comm'), 256).toString().trim(); if (comm !== 'java') continue;
        const identity = start(proc), executable = io.readlinkSync(join(proc, 'exe'));
        if (!javaExecutables.includes(executable)) { uncertain.push({ pid, reason: 'Java executable outside declared JDKs; ownership not inspected' }); continue; }
        const argv = read(join(proc, 'cmdline'), 1024 * 1024).toString().split('\0');
        const env = read(join(proc, 'environ'), 1024 * 1024).toString().split('\0');
        const matchedBy = argv.includes('-Dgvy.hostedProofNonce=' + nonce) ? 'nonce-argument' : env.includes('GVY_HOSTED_PROOF_NONCE=' + nonce) ? 'inherited-nonce-environment' : null;
        if (io.statSync(proc).uid !== uid || start(proc) !== identity || io.readlinkSync(join(proc, 'exe')) !== executable) throw Error('Process identity changed');
        if (matchedBy) owned.push({ pid, startTime: identity, uid, executable, matchedBy });
      } catch (error) { if (!['ENOENT', 'ESRCH'].includes(error.code)) uncertain.push({ pid, reason: String(error.message ?? error) }); }
    }
  } finally { directory.closeSync(); }
  return { owned, uncertain, complete: uncertain.length === 0, signalsSent: false,
    scope: 'Declared JDK Java processes visible to same UID with inherited exact nonce; arbitrary escaped/nonce-removing descendants are not contained' };
}
export async function waitForJava(nonce, executables, remaining, checkpoint, observe = observeJava, sleep = pause, clock = () => performance.now()) {
  const stop = clock() + Math.min(10_000, remaining()); const observations = [];
  for (;;) {
    await checkpoint(); const result = observe(nonce, executables); observations.push(result);
    if (!result.complete || result.owned.length === 0) return { ok: result.complete && result.owned.length === 0, observations, signalsSent: false };
    const budget = Math.min(stop - clock(), remaining());
    if (budget <= 0) return { ok: false, observations, signalsSent: false };
    await sleep(Math.min(200, budget));
  }
}
