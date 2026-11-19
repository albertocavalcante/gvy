// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import { spawn } from "node:child_process";
import {
  readlink,
  readFile,
  unlink,
  writeFile,
  access,
} from "node:fs/promises";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { command, readJson, writeJson, delay } from "./io.mjs";
import { classifyTrace } from "./core.mjs";
import { startFixtures, fixtureControlReceipt } from "./fixtures.mjs";
import { captureTrace } from "./trace.mjs";
import { retireNamespaceTracees } from "./processes.mjs";
import { filteredArgv, verifyCanary } from "./seccomp.mjs";

const source = dirname(fileURLToPath(import.meta.url));
const [mode, directory, inputPath] = process.argv.slice(2);
const input = await readJson(inputPath);
if (
  process.pid !== 1 ||
  process.getuid() === 0 ||
  process.getuid() !== input.uid
)
  throw new Error("Expected non-root PID namespace init at the original uid");
const networkNamespace = await readlink("/proc/self/ns/net");
const pidNamespace = await readlink("/proc/self/ns/pid");
const mountNamespace = await readlink("/proc/self/ns/mnt");
if (
  networkNamespace === input.parentNetworkNamespace ||
  pidNamespace === input.parentPidNamespace ||
  mountNamespace === input.parentMountNamespace
)
  throw new Error("Namespace isolation was not established");
const deadline = setTimeout(
  () => process.exit(124),
  (input.deadlineSeconds ?? 15) * 1000,
);
process.on("SIGTERM", () => process.exit(143));
process.on("SIGINT", () => process.exit(130));
let negativeControl;
let ioUringEnforcement;

async function runTrace(name, argv, resultPath, filtered = true) {
  const path = join(directory, `${name}.strace`);
  let processTeardown;
  // Node's pipe is a socketpair on Linux and cannot be reopened via /proc/self/fd/N.
  // strace writes directly to its existing stderr; workers capture candidate stderr themselves.
  const { result, rawTrace } = await captureTrace(
    [
      input.tools.strace,
      "-f",
      "-qq",
      "-yy",
      "-s",
      "0",
      "-e",
      "trace=%network,io_uring_setup,io_uring_enter,io_uring_register",
      ...(filtered
        ? filteredArgv(
            input.ioUringEnforcement.helper.path,
            input.ioUringEnforcement.profile.path,
            argv,
          )
        : argv),
    ],
    {
      tracePath: path,
      resultPath,
      deadlineMs: (input.deadlineSeconds ?? 15) * 1000,
      afterResult:
        name === "candidate"
          ? async (result, observer) => {
              processTeardown = await retireNamespaceTracees(
                result.worker,
                observer,
              );
            }
          : undefined,
    },
  );
  return {
    result,
    trace: classifyTrace(rawTrace),
    processTeardown,
    observer: { naturalExit: true, exitCode: 0, drained: true },
  };
}

try {
  const up = await command([input.tools.ip, "link", "set", "lo", "up"]);
  if (up.code !== 0) throw new Error(`Loopback setup failed: ${up.stderr}`);
  const interfaces = JSON.parse(
    (await command([input.tools.ip, "-j", "link", "show"])).stdout,
  );
  const ipv4Routes = JSON.parse(
    (
      await command([
        input.tools.ip,
        "-j",
        "-4",
        "route",
        "show",
        "table",
        "all",
      ])
    ).stdout,
  );
  const ipv6Routes = JSON.parse(
    (
      await command([
        input.tools.ip,
        "-j",
        "-6",
        "route",
        "show",
        "table",
        "all",
      ])
    ).stdout,
  );
  if (
    interfaces.some((item) => item.ifname !== "lo") ||
    [...ipv4Routes, ...ipv6Routes].some((route) => route.dev !== "lo")
  )
    throw new Error("Unexpected external interface or route");
  const namespace = {
    uid: process.getuid(),
    pid: process.pid,
    uidMap: await readFile("/proc/self/uid_map", "utf8"),
    gidMap: await readFile("/proc/self/gid_map", "utf8"),
    networkNamespace,
    pidNamespace,
    mountNamespace,
    interfaces,
    ipv4Routes,
    ipv6Routes,
    verified: true,
  };
  await writeJson(join(directory, "namespace-ready.json"), { namespace });
  while (true) {
    try {
      await readFile(join(directory, "host-admit"));
      break;
    } catch (error) {
      if (error.code !== "ENOENT") throw error;
    }
    await delay(50);
  }
  const helperPath = input.ioUringEnforcement.helper.path;
  const baselinePath = join(directory, "uring-baseline.json");
  const baseline = await runTrace(
    "uring-baseline",
    [helperPath, "--canary", baselinePath],
    baselinePath,
    false,
  );
  const mutantPath = join(directory, "uring-mutant.bpf");
  const mutant = await readFile(input.ioUringEnforcement.profile.path);
  mutant[0] ^= 1;
  await writeFile(mutantPath, mutant, { flag: "wx", mode: 0o600 });
  const unexpectedPath = join(directory, "mutant-must-not-execute.json");
  const rejected = await command(
    filteredArgv(helperPath, mutantPath, [
      helperPath,
      "--canary",
      unexpectedPath,
    ]),
  );
  if (
    rejected.code !== 125 ||
    !rejected.stderr.includes("differs from fixed reviewed denial program")
  )
    throw new Error("Altered seccomp profile did not fail before execution");
  try {
    await access(unexpectedPath);
    throw new Error("Altered profile executed its target");
  } catch (error) {
    if (error.code !== "ENOENT") throw error;
  }
  const canaryPath = join(directory, "uring-filtered.json");
  const filtered = await runTrace(
    "uring-filtered",
    [helperPath, "--canary", canaryPath],
    canaryPath,
  );
  ioUringEnforcement = {
    ...input.ioUringEnforcement,
    baseline,
    filtered,
    alteredProfileRejected: true,
    baselineDemonstratesDifferentErrno: baseline.result.calls.some(
      (call) => call.errno !== 38,
    ),
    verified:
      verifyCanary(filtered.result, input.uid) &&
      filtered.trace.deniedIoUring.length === 3 &&
      filtered.trace.unclassified.length === 0 &&
      filtered.trace.externalAttempts === 0 &&
      filtered.trace.unixAttempts === 0,
  };
  if (!ioUringEnforcement.verified)
    throw new Error("io_uring denial/inheritance canary failed");
  const control = await runTrace(
    "negative-control",
    [
      process.execPath,
      join(source, "negative-control.mjs"),
      join(directory, "control-result.json"),
    ],
    join(directory, "control-result.json"),
  );
  negativeControl = {
    ...control,
    verified:
      ["ENETUNREACH", "EHOSTUNREACH"].includes(control.result.tcp) &&
      ["ENETUNREACH", "EHOSTUNREACH"].includes(control.result.udp) &&
      control.result.ipv6Loopback &&
      control.trace.externalAttempts >= 2 &&
      control.trace.blockedExternalAttempts ===
        control.trace.externalAttempts &&
      control.trace.loopbackAttempts > 0 &&
      control.trace.unclassified.length === 0,
  };
  if (!negativeControl.verified)
    throw new Error(
      `Intentional negative control did not prove prevention AND observer coverage: ${JSON.stringify(negativeControl)}`,
    );
  if (mode === "probe") {
    const fixtures = await startFixtures();
    let fixtureControl;
    try {
      const unauthenticated = await fetch(`${fixtures.origins.allowed}/auth`);
      const authenticated = await fetch(`${fixtures.origins.allowed}/auth`, {
        headers: { authorization: fixtures.authorization },
      });
      const redirect = await fetch(
        `${fixtures.origins.allowed}/redirect/denied`,
        { redirect: "manual" },
      );
      fixtureControl = fixtureControlReceipt(
        unauthenticated.status,
        authenticated.status,
        redirect.status,
        fixtures.requests,
      );
    } finally {
      await fixtures.close();
    }
    // detached=true creates a new session; PID namespace teardown must still reap it.
    const child = spawn(
      process.execPath,
      ["-e", "setInterval(() => {}, 1000)"],
      { detached: true, stdio: "ignore" },
    );
    child.unref();
    await writeJson(join(directory, "probe-ready.json"), {
      namespace,
      ioUringEnforcement,
      negativeControl,
      fixtureControl,
      detachedChildPid: child.pid,
    });
    while (true) {
      try {
        await readFile(join(directory, "probe-release"));
        break;
      } catch (error) {
        if (error.code !== "ENOENT") throw error;
      }
      await delay(50);
    }
    await writeJson(join(directory, "namespace-result.json"), {
      namespace,
      ioUringEnforcement,
      negativeControl,
      fixtureControl,
    });
  } else {
    const fixtures = await startFixtures();
    const fixturesPath = join(directory, "fixture-access.json");
    try {
      await writeJson(fixturesPath, {
        origins: fixtures.origins,
        authorization: fixtures.authorization,
      });
      const candidate = await runTrace(
        "candidate",
        [
          process.execPath,
          join(source, "worker.mjs"),
          input.manifestPath,
          directory,
          fixturesPath,
        ],
        join(directory, "worker-result.json"),
      );
      await writeJson(join(directory, "namespace-result.json"), {
        namespace,
        ioUringEnforcement,
        negativeControl,
        trace: candidate.trace,
        observer: candidate.observer,
        processTeardown: candidate.processTeardown,
        scenarios: candidate.result.scenarios,
        fixtureRequests: fixtures.requests,
        fixtureOrigins: fixtures.origins,
      });
    } finally {
      await fixtures.close();
      await unlink(fixturesPath).catch(() => {});
    }
  }
} catch (error) {
  await writeJson(join(directory, "namespace-error.json"), {
    error: error.message,
    networkNamespace,
    pidNamespace,
    mountNamespace,
    negativeControl,
    ioUringEnforcement,
  });
  process.exitCode = 1;
} finally {
  clearTimeout(deadline);
}
// Linux kills every remaining member when PID1 exits, including detached/session-leader daemons.
// The host independently checks namespace membership after this exit before confirming cleanup.
process.exit(process.exitCode ?? 0);
