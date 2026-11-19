// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import { isAbsolute } from "node:path";
import { isIP } from "node:net";

function requireValue(condition, message) {
  if (!condition) throw new Error(message);
}
function absolute(value) {
  return (
    typeof value === "string" && isAbsolute(value) && !value.includes("\0")
  );
}

export function validateExecutionHost(host, probe) {
  requireValue(
    host.platform === "linux" && host.uid !== 0 && host.uid === host.euid,
    "Expected a non-root native Linux process with unchanged uid",
  );
  requireValue(
    host.elf && host.nodeMajor >= 20,
    "Invoke an explicit Linux ELF Node 20+ executable; Windows Node is unsupported",
  );
  const wsl = /microsoft|wsl/i.test(host.kernel);
  requireValue(
    probe || (!wsl && !host.interop),
    "Full qualification is refused on WSL or with an interoperability handler; use a native Linux fixture",
  );
  return {
    wsl,
    interop: host.interop,
    qualificationHost: !wsl && !host.interop,
  };
}

/** Trusted CLI input, never workspace-discovered configuration or shell text. */
export function validateManifest(value) {
  requireValue(value?.schemaVersion === 1, "Expected manifest schemaVersion 1");
  requireValue(
    value.exclusiveGradleLane === true,
    "An exclusive Gradle lane is required",
  );
  requireValue(
    Number.isInteger(value.deadlineSeconds) &&
      value.deadlineSeconds >= 5 &&
      value.deadlineSeconds <= 1800,
    "deadlineSeconds must be 5..1800",
  );
  for (const key of ["serverJar", "extensionVsix"]) {
    const artifact = value.artifacts?.[key];
    requireValue(
      absolute(artifact?.path) && /^[a-f0-9]{64}$/.test(artifact.sha256),
      `Invalid ${key} artifact identity`,
    );
  }
  requireValue(absolute(value.javaHome), "javaHome must be absolute");
  requireValue(
    absolute(value.gradle?.installation) &&
      /^\d+\.\d+(?:\.\d+)?(?:-[a-zA-Z0-9.-]+)?$/.test(value.gradle.version),
    "Invalid provisioned Gradle installation/version",
  );
  requireValue(
    Array.isArray(value.scenarios) &&
      value.scenarios.length > 0 &&
      value.scenarios.length <= 64,
    "Expected 1..64 scenarios",
  );
  const ids = new Set();
  for (const scenario of value.scenarios) {
    requireValue(
      /^[a-z][a-z0-9-]{0,63}$/.test(scenario.id) && !ids.has(scenario.id),
      "Invalid/duplicate scenario id",
    );
    ids.add(scenario.id);
    requireValue(absolute(scenario.cwd), "Scenario cwd must be absolute");
    requireValue(
      ["command", "lsp"].includes(scenario.kind),
      "Expected command or lsp scenario",
    );
    if (scenario.kind === "command") {
      requireValue(
        Array.isArray(scenario.argv) &&
          scenario.argv.length > 0 &&
          scenario.argv.length <= 128 &&
          absolute(scenario.argv[0]) &&
          scenario.argv.every(
            (arg) =>
              typeof arg === "string" &&
              !arg.includes("\0") &&
              arg.length < 8192,
          ),
        "Expected bounded argv array with absolute executable",
      );
    } else {
      requireValue(
        scenario.initializationOptions &&
          typeof scenario.initializationOptions === "object" &&
          !Array.isArray(scenario.initializationOptions),
        "Expected explicit LSP initializationOptions",
      );
      requireValue(
        Array.isArray(scenario.steps) && scenario.steps.length <= 128,
        "Expected bounded LSP steps",
      );
      for (const step of scenario.steps) {
        requireValue(
          ["request", "notify", "waitNotification"].includes(step.kind) &&
            typeof step.method === "string",
          "Invalid LSP step",
        );
        if (step.kind !== "notify")
          requireValue(
            typeof step.pointer === "string" && Object.hasOwn(step, "equals"),
            "LSP observation needs pointer and equals",
          );
      }
    }
  }
  return value;
}

export function loopback(address) {
  const text = address.toLowerCase();
  if (isIP(text) === 4) return text.startsWith("127.");
  if (isIP(text) !== 6) return false;
  // URL normalizes expanded/compressed IPv6 and IPv4-mapped IPv6 to one representation.
  const normalized = new URL(`http://[${text}]/`).hostname.slice(1, -1);
  if (normalized === "::1") return true;
  const mapped = normalized.match(/^::ffff:([a-f0-9]+):[a-f0-9]+$/);
  return Boolean(mapped && parseInt(mapped[1], 16) >>> 8 === 127);
}

/** Conservative strace classifier. Unknown/unfinished records prohibit a clean observation. */
export function classifyTrace(text) {
  const result = {
    socketCreations: 0,
    loopbackAttempts: 0,
    externalAttempts: 0,
    blockedExternalAttempts: 0,
    unixAttempts: 0,
    deniedIoUring: [],
    unclassified: [],
  };
  const pending = new Map();
  for (const raw of text.split("\n")) {
    if (!raw.trim()) continue;
    const prefix = raw.match(/^(?:\[pid\s+(\d+)\]|(\d+))\s+(.*)$/);
    // strace omits the main pid before the tracee creates its first thread/child.
    const pid = prefix ? (prefix[1] ?? prefix[2]) : "main";
    let line = prefix ? prefix[3] : raw;
    if (line.includes("<unfinished ...>")) {
      if (pending.has(pid)) result.unclassified.push(pending.get(pid));
      pending.set(pid, line.replace("<unfinished ...>", ""));
      continue;
    }
    if (line.startsWith("<... ")) {
      const previous = pending.get(pid);
      if (!previous) {
        result.unclassified.push(line);
        continue;
      }
      line = previous + line.replace(/^<\.\.\. \w+ resumed>/, "");
      pending.delete(pid);
    }
    if (/^(?:\+\+\+|---)/.test(line)) continue;
    const call = line.match(/^(\w+)\(/)?.[1];
    if (
      ["io_uring_setup", "io_uring_enter", "io_uring_register"].includes(call)
    ) {
      if (/ = -1 ENOSYS(?: \([^\n]*\))?$/.test(line))
        result.deniedIoUring.push({ syscall: call, errno: "ENOSYS" });
      else result.unclassified.push(line.slice(0, 512));
      continue;
    }
    if (call === "socket") {
      result.socketCreations++;
      if (/SOCK_RAW|AF_PACKET|AF_XDP/.test(line))
        result.unclassified.push(line.slice(0, 512));
      continue;
    }
    if (
      [
        "bind",
        "listen",
        "accept",
        "accept4",
        "getsockname",
        "getpeername",
        "getsockopt",
        "setsockopt",
        "shutdown",
        "recvfrom",
        "recvmsg",
        "recvmmsg",
        "socketpair",
      ].includes(call)
    )
      continue;
    if (!["connect", "sendto", "sendmsg", "sendmmsg"].includes(call)) {
      result.unclassified.push(line.slice(0, 512));
      continue;
    }
    if (line.includes("AF_UNIX")) {
      result.unixAttempts++;
      continue;
    }
    const addresses = [
      ...line.matchAll(/(?:inet_addr\("|inet_pton\(AF_INET6, ")([^"]+)"/g),
    ].map((match) => match[1]);
    if (!addresses.length) {
      // -yy supplies the peer of connected UDP/TCP sockets when msg_name is NULL.
      const peer = line.match(
        /<(?:UDP|TCP)(?:v6)?:\[[^\]]*->(?:\[([^\]]+)\]|([^:\]]+)):\d+\]>/,
      );
      if (peer) addresses.push(peer[1] ?? peer[2]);
    }
    if (!addresses.length || addresses.some((address) => !isIP(address))) {
      result.unclassified.push(line.slice(0, 512));
      continue;
    }
    if (addresses.every(loopback)) result.loopbackAttempts++;
    else {
      result.externalAttempts++;
      if (/ = -1 (?:ENETUNREACH|EHOSTUNREACH|EACCES|EPERM)\b/.test(line))
        result.blockedExternalAttempts++;
    }
  }
  for (const line of pending.values()) {
    // A daemon waiting to receive/accept at observer teardown has made no outbound attempt.
    if (!/^(?:recvfrom|recvmsg|recvmmsg|accept|accept4)\(/.test(line))
      result.unclassified.push(line);
  }
  return result;
}

export function qualificationReceipt(evidence) {
  const { trace } = evidence;
  return {
    schemaVersion: 1,
    ...evidence,
    networkObservationPassed: Boolean(
      evidence.namespace?.verified &&
      evidence.cleanup?.verified &&
      evidence.observer?.naturalExit &&
      evidence.observer?.exitCode === 0 &&
      evidence.observer?.drained &&
      evidence.processTeardown?.verified &&
      evidence.processTeardown?.workerExitedNaturally &&
      evidence.ioUringEnforcement?.verified &&
      evidence.lifetimeControl?.verified &&
      evidence.negativeControl?.verified &&
      trace &&
      trace.externalAttempts === 0 &&
      trace.unixAttempts === 0 &&
      trace.unclassified.length === 0,
    ),
    completeAcceptanceMatrixQualified: false,
    limits: [
      "Trusted fixture commands only; not a hostile-code sandbox",
      "Successful or unknown io_uring is unqualified; no privileged namespace escape coverage",
      "Host AF_UNIX services may bridge namespaces; attempts prohibit clean qualification",
      "Generic command success is not proof of editor/LSP semantic assertions",
      "Complete accepted matrix requires separately reviewed scenario evidence",
    ],
  };
}
