// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import { readFile, readdir } from "node:fs/promises";
import { performance } from "node:perf_hooks";
import { delay } from "./io.mjs";

export function parseProcessStat(text) {
  const pid = Number(text.slice(0, text.indexOf(" ")));
  const fields = text
    .slice(text.lastIndexOf(")") + 2)
    .trim()
    .split(/\s+/);
  const [state] = fields;
  const startTime = fields[19];
  if (
    !Number.isSafeInteger(pid) ||
    pid <= 0 ||
    !/^[A-Za-z]$/.test(state) ||
    !/^\d+$/.test(startTime)
  )
    throw new Error("Uncertain /proc process identity");
  return { pid, state, startTime };
}

export async function readProcessIdentity(pid) {
  const identity = parseProcessStat(
    await readFile(`/proc/${pid}/stat`, "utf8"),
  );
  if (identity.pid !== pid)
    throw new Error("Process identity changed while reading /proc");
  return identity;
}

/** Control algorithm is injectable for deterministic fork/exit/observer-death tests. */
export async function waitForTraceeTeardown({
  worker,
  observer,
  readProcesses,
  killProcess,
  pause = () => delay(10),
  now = () => performance.now(),
  timeoutMs = 3000,
}) {
  if (
    !Number.isSafeInteger(worker?.pid) ||
    worker.pid <= 1 ||
    worker.pid === observer.pid ||
    !/^\d+$/.test(worker.startTime)
  )
    throw new Error("Invalid main-worker process identity");
  const deadline = now() + timeoutMs;
  const signals = new Map();
  while (now() < deadline) {
    const snapshot = await readProcesses();
    if (snapshot.length > 4096)
      throw new Error("Process inventory limit exceeded");
    const live = snapshot.filter(
      (item) =>
        item.pid !== 1 && item.pid !== observer.pid && item.state !== "Z",
    );
    if (live.length && !observer.isRunning())
      throw new Error(
        "Observer exited while candidate processes were still live",
      );
    const main = live.find(
      (item) => item.pid === worker.pid && item.startTime === worker.startTime,
    );
    if (main) {
      await pause();
      continue;
    }
    if (!live.length)
      return {
        verified: true,
        workerExitedNaturally: true,
        signalAttempts: [...signals.values()],
        livePidsAfter: [],
      };
    for (const child of live) {
      if (!observer.isRunning())
        throw new Error("Observer exited during descendant teardown");
      // All visible processes are owned by this private PID namespace. PID reuse can change
      // which owned descendant receives a signal; only the final aggregate drain is certified.
      await killProcess(child.pid);
      signals.set(`${child.pid}:${child.startTime}`, {
        pid: child.pid,
        startTime: child.startTime,
      });
      if (signals.size > 4096)
        throw new Error("Descendant signal inventory limit exceeded");
    }
    await pause();
  }
  throw new Error(
    "Deadline waiting for main-worker natural exit or descendant teardown",
  );
}

export async function retireNamespaceTracees(worker, observer) {
  if (process.pid !== 1 || process.getuid() === 0)
    throw new Error(
      "Descendant teardown is restricted to non-root namespace PID1",
    );
  return waitForTraceeTeardown({
    worker,
    observer,
    readProcesses: async () => {
      const result = [];
      for (const entry of await readdir("/proc")) {
        if (!/^\d+$/.test(entry)) continue;
        try {
          result.push(await readProcessIdentity(Number(entry)));
        } catch (error) {
          if (!["ENOENT", "ESRCH"].includes(error.code)) throw error;
        }
      }
      return result;
    },
    killProcess: async (pid) => {
      try {
        process.kill(pid, "SIGKILL");
      } catch (error) {
        if (error.code !== "ESRCH") throw error;
      }
    },
  });
}
