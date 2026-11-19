// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import assert from "node:assert/strict";
import test from "node:test";
import {
  parseProcessStat,
  waitForTraceeTeardown,
  retireNamespaceTracees,
} from "./processes.mjs";

const identity = (pid, startTime, state = "S") => ({ pid, startTime, state });
test("proc identities preserve start-time across awkward names and reject uncertainty", () => {
  assert.deepEqual(
    parseProcessStat(
      `123 (awkward ) process) R ${Array(18).fill("0").join(" ")} 9000 0`,
    ),
    identity(123, "9000", "R"),
  );
  assert.throws(() => parseProcessStat("123 (truncated) R 0"));
});

test("main-worker natural exit precedes descendant teardown and fork survivors are rescanned", async () => {
  let tick = 0;
  let processes = [identity(3, "30"), identity(4, "40")];
  const killed = [];
  const result = await waitForTraceeTeardown({
    worker: identity(3, "30"),
    observer: { pid: 2, isRunning: () => true },
    readProcesses: async () => processes,
    killProcess: async (pid) => {
      killed.push(pid);
      processes = pid === 4 ? [identity(5, "50")] : [];
    },
    now: () => tick,
    pause: async () => {
      tick += 10;
      if (tick === 10) processes = [identity(4, "40")];
    },
    timeoutMs: 100,
  });
  assert.deepEqual(killed, [4, 5]);
  assert.equal(result.verified, true);
  assert.deepEqual(result.livePidsAfter, []);
});

test("hanging main worker, observer death and inaccessible process state fail closed", async () => {
  let tick = 0;
  const options = {
    worker: identity(3, "30"),
    observer: { pid: 2, isRunning: () => true },
    readProcesses: async () => [identity(3, "30")],
    killProcess: async () =>
      assert.fail("must not kill a hanging worker and pass"),
    now: () => tick,
    pause: async () => {
      tick += 10;
    },
    timeoutMs: 20,
  };
  await assert.rejects(waitForTraceeTeardown(options), /natural exit|deadline/);
  await assert.rejects(
    waitForTraceeTeardown({
      ...options,
      observer: { pid: 2, isRunning: () => false },
    }),
    /observer/i,
  );
  await assert.rejects(
    waitForTraceeTeardown({
      ...options,
      readProcesses: async () => {
        throw Error("proc denied");
      },
    }),
    /proc denied/,
  );
  await assert.rejects(
    retireNamespaceTracees(identity(3, "30"), {
      pid: 2,
      isRunning: () => true,
    }),
    /PID1/,
  );
});

test("a reused worker pid is a new owned descendant, not proof the former worker is still running", async () => {
  let remaining = [identity(3, "31")];
  const killed = [];
  await waitForTraceeTeardown({
    worker: identity(3, "30"),
    observer: { pid: 2, isRunning: () => true },
    readProcesses: async () => remaining,
    killProcess: async (pid) => {
      killed.push(pid);
      remaining = [];
    },
    pause: async () => {},
    timeoutMs: 100,
  });
  assert.deepEqual(killed, [3]);
});
