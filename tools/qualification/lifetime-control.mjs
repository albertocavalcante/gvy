// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
// Native mechanism control; never a candidate qualification receipt.
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { createConnection } from "node:net";
import { mkdir, readFile, readlink } from "node:fs/promises";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import {
  command,
  delay,
  readJson,
  writeJson,
  processesInNamespace,
  executable,
} from "./io.mjs";
import { captureTrace } from "./trace.mjs";
import { classifyTrace } from "./core.mjs";
import { readProcessIdentity, retireNamespaceTracees } from "./processes.mjs";
import { filteredArgv } from "./seccomp.mjs";
import { validateLifetimeInvocation } from "./lifetime-core.mjs";
const [role, directory, helper, profile] = process.argv.slice(2);
validateLifetimeInvocation({
  role,
  directory,
  helper,
  profile,
  platform: process.platform,
  uid: process.getuid?.(),
  euid: process.geteuid?.(),
});
const tools = {
  ip: await executable("ip"),
  strace: await executable("strace"),
  unshare: await executable("unshare"),
};
if (role !== "host")
  assert.equal(
    process.getuid(),
    (await readJson(join(directory, "parent.json"))).uid,
  );
async function waitJson(path, milliseconds = 5000) {
  const deadline = Date.now() + milliseconds;
  while (Date.now() < deadline) {
    try {
      return await readJson(path);
    } catch (error) {
      if (error.code !== "ENOENT") throw error;
    }
    await delay(10);
  }
  throw Error(`Timed out waiting for ${path}`);
}
function invocation(mode) {
  return [
    process.execPath,
    fileURLToPath(import.meta.url),
    mode,
    directory,
    helper,
    profile,
  ];
}
if (role === "descendant") {
  await writeJson(
    join(directory, "descendant.json"),
    await readProcessIdentity(process.pid),
  );
  setTimeout(() => {}, 15000); // Finite backup; namespace ownership is the primary cleanup boundary.
} else if (role === "worker") {
  const child = spawn(process.execPath, invocation("descendant").slice(1), {
    detached: true,
    stdio: "ignore",
  });
  child.unref();
  const descendant = await waitJson(join(directory, "descendant.json"));
  await writeJson(join(directory, "worker-result.json"), {
    worker: await readProcessIdentity(process.pid),
    descendant,
  });
  await waitJson(join(directory, "marker-observed.json"));
  const tcp = await new Promise((resolve) => {
    const socket = createConnection({ host: "198.51.100.1", port: 9 });
    socket.setTimeout(1000, () => {
      socket.destroy();
      resolve("TIMEOUT");
    });
    socket.once("connect", () => {
      socket.destroy();
      resolve("UNEXPECTED_SUCCESS");
    });
    socket.once("error", (error) => resolve(error.code));
  });
  await writeJson(join(directory, "late-network.json"), {
    tcp,
    afterMarkerObserved: true,
  });
} else if (role === "init") {
  assert.equal(process.pid, 1);
  const timer = setTimeout(() => process.exit(124), 12000);
  try {
    const parent = await readJson(join(directory, "parent.json"));
    const namespace = {
      uid: process.getuid(),
      pid: process.pid,
      pidNamespace: await readlink("/proc/self/ns/pid"),
      networkNamespace: await readlink("/proc/self/ns/net"),
      mountNamespace: await readlink("/proc/self/ns/mnt"),
      uidMap: await readFile("/proc/self/uid_map", "utf8"),
    };
    for (const key of ["pidNamespace", "networkNamespace", "mountNamespace"])
      assert.notEqual(namespace[key], parent[key]);
    assert.equal(
      (await command([tools.ip, "link", "set", "lo", "up"])).code,
      0,
    );
    const interfaces = JSON.parse(
      (await command([tools.ip, "-j", "link", "show"])).stdout,
    );
    const routes4 = JSON.parse(
      (await command([tools.ip, "-j", "-4", "route", "show", "table", "all"]))
        .stdout,
    );
    const routes6 = JSON.parse(
      (await command([tools.ip, "-j", "-6", "route", "show", "table", "all"]))
        .stdout,
    );
    assert.ok(interfaces.every((item) => item.ifname === "lo"));
    assert.ok([...routes4, ...routes6].every((route) => route.dev === "lo"));
    await writeJson(join(directory, "ready.json"), namespace);
    await waitJson(join(directory, "host-admit.json"));
    let teardown;
    const result = await captureTrace(
      [
        tools.strace,
        "-f",
        "-qq",
        "-yy",
        "-s",
        "0",
        "-e",
        "trace=%network,io_uring_setup,io_uring_enter,io_uring_register",
        ...filteredArgv(helper, profile, invocation("worker")),
      ],
      {
        tracePath: join(directory, "lifetime.strace"),
        resultPath: join(directory, "worker-result.json"),
        deadlineMs: 8000,
        afterResult: async (marker, observer) => {
          await writeJson(join(directory, "marker-observed.json"), {
            observed: true,
          });
          teardown = await retireNamespaceTracees(marker.worker, observer);
        },
      },
    );
    const trace = classifyTrace(
      await readFile(join(directory, "lifetime.strace"), "utf8"),
    );
    const late = await readJson(join(directory, "late-network.json"));
    assert.equal(late.afterMarkerObserved, true);
    assert.ok(["ENETUNREACH", "EHOSTUNREACH"].includes(late.tcp));
    assert.equal(trace.externalAttempts, 1);
    assert.equal(trace.blockedExternalAttempts, 1);
    assert.equal(trace.unclassified.length, 0);
    assert.equal(trace.unixAttempts, 0);
    assert.equal(teardown.workerExitedNaturally, true);
    assert.equal(teardown.verified, true);
    assert.ok(
      teardown.signalAttempts.some(
        (item) =>
          item.pid === result.descendant.pid &&
          item.startTime === result.descendant.startTime,
      ),
    );
    await writeJson(join(directory, "init-result.json"), {
      kind: "mechanism-only-observer-lifetime",
      namespace,
      interfaces,
      routes4,
      routes6,
      result,
      teardown,
      trace,
      late,
      observer: { naturalExit: true, exitCode: 0, drained: true },
      candidateQualified: false,
    });
  } catch (error) {
    await writeJson(join(directory, "init-error.json"), { error: error.stack });
    process.exitCode = 1;
  } finally {
    clearTimeout(timer);
  }
  process.exit(process.exitCode ?? 0);
} else if (role === "host") {
  await mkdir(directory, { mode: 0o700 });
  await writeJson(join(directory, "parent.json"), {
    uid: process.getuid(),
    pidNamespace: await readlink("/proc/self/ns/pid"),
    networkNamespace: await readlink("/proc/self/ns/net"),
    mountNamespace: await readlink("/proc/self/ns/mnt"),
  });
  let child;
  const execution = command(
    [
      tools.unshare,
      "--keep-caps",
      "--user",
      "--map-current-user",
      "--net",
      "--mount",
      "--propagation",
      "private",
      "--pid",
      "--fork",
      "--kill-child=KILL",
      "--mount-proc",
      ...invocation("init"),
    ],
    {
      timeoutMs: 16000,
      onSpawn: (value) => {
        child = value;
      },
    },
  ).then(
    (value) => ({ value }),
    (error) => ({ error: error.message }),
  );
  let namespace,
    observed = [],
    failure;
  try {
    namespace = await waitJson(join(directory, "ready.json"));
    observed = await processesInNamespace(namespace.pidNamespace);
    assert.ok(observed.length > 0);
    await writeJson(join(directory, "host-admit.json"), { admitted: true });
  } catch (error) {
    failure = error.stack;
    child?.kill("SIGKILL");
  }
  const outcome = await execution;
  const deadline = Date.now() + 3000;
  let remaining = namespace
    ? await processesInNamespace(namespace.pidNamespace)
    : null;
  while (remaining?.length && Date.now() < deadline) {
    await delay(25);
    remaining = await processesInNamespace(namespace.pidNamespace);
  }
  const cleanup = {
    verified: Boolean(namespace && observed.length && remaining?.length === 0),
    observed,
    remaining,
  };
  await writeJson(join(directory, "host-result.json"), {
    namespace,
    cleanup,
    outcome,
    failure,
    candidateQualified: false,
  });
  assert.equal(failure, undefined);
  assert.equal(outcome.error, undefined);
  assert.equal(outcome.value?.code, 0);
  assert.equal(cleanup.verified, true);
  const result = await readJson(join(directory, "init-result.json"));
  console.log(JSON.stringify({ result, cleanup }));
} else throw Error("Unknown lifetime control role");
