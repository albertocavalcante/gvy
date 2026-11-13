// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

// Temporary, fixed-input hosted worker. This file is not a general dispatch API.
import assert from "node:assert/strict";
import { spawn, spawnSync } from "node:child_process";
import { isUtf8 } from "node:buffer";
import { createHash } from "node:crypto";
import * as fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { performance } from "node:perf_hooks";
import { fileURLToPath } from "node:url";
import { gunzipSync } from "node:zlib";
import { readEvidenceBytes } from "./read-evidence.mjs";

export const PROFILE = Object.freeze({
  schemaVersion: 1,
  repository: "albertocavalcante/gvy",
  ref: "refs/heads/test/vscode-dependency-lock-worker",
  candidate: "7955436c366325af240284bf2eb9895777ff0ea1",
  candidateTree: "4f7588752f6b9121e84a8206feae1635c7917753",
  locks: ["editors/code/pnpm-lock.yaml", "MODULE.bazel.lock"],
  totalMs: 720_000,
  cleanupMs: 5_000,
  initialFreeBytes: 12 * 1024 ** 3,
  initialAvailableMemoryBytes: 3 * 1024 ** 3,
  freeBytesFloor: 5 * 1024 ** 3,
  availableMemoryFloor: 1024 ** 3,
  ownedBytesLimit: 4 * 1024 ** 3,
  artifactBytesLimit: 8 * 1024 ** 2,
  lockBytesLimit: 2 * 1024 ** 2,
  pnpm: {
    repository: "pnpm/pnpm",
    asset: 643957410,
    version: "12.14.0",
    bytes: 24713030,
    sha256: "3910c3852136bed5bc1511b9137a45fd4357ed6b3970d9d08893641e9005867e",
    timeoutMs: 60_000,
    captureBytes: 64 * 1024,
    archiveBytesLimit: 256 * 1024 ** 2,
    // Exact pinned release: root executable plus the unused node-gyp payload.
    // Lock generation disables scripts; only root pnpm is retained or executed.
    archiveEntriesLimit: 543,
    // Supply-chain verification needs registry metadata on a cold owned cache.
    // Keep that policy enabled, then verify the generated lock offline.
    updateNetwork: "online",
    frozenNetwork: "offline",
  },
  bazel: {
    repository: "bazelbuild/bazel",
    asset: 343421735,
    version: "9.0.0",
    bytes: 65821854,
    sha256: "c44a93f25398c68f904fa1d19b61d321de6c0d2f09dca375d7bc0dc9b9428403",
    timeoutMs: 240_000,
    captureBytes: 256 * 1024,
    heapMiB: 768,
    metaspaceMiB: 256,
    processors: 1,
    loadingPhaseThreads: 1,
  },
});

const SCRIPT = fileURLToPath(import.meta.url);
const sha256 = (bytes) => createHash("sha256").update(bytes).digest("hex");
const jsonBytes = (value) => Buffer.from(`${JSON.stringify(value, null, 2)}\n`);
const fail = (code) => {
  throw new Error(code);
};
const requireCondition = (value, code) => {
  if (!value) fail(code);
};
const writeExclusive = (target, bytes) =>
  fs.writeFileSync(target, bytes, { flag: "wx", mode: 0o600 });
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function childEnvironment(extra = {}) {
  const env = {};
  // Keep the hosted toolchain and ordinary public network routing. Authentication
  // is passed only to gh's fixed release-asset requests, never pnpm or Bazel.
  for (const key of [
    "PATH",
    "HOME",
    "USER",
    "LOGNAME",
    "JAVA_HOME",
    "TMPDIR",
    "LANG",
    "LC_ALL",
    "HTTP_PROXY",
    "HTTPS_PROXY",
    "NO_PROXY",
    "NODE_EXTRA_CA_CERTS",
  ]) {
    if (process.env[key] !== undefined) env[key] = process.env[key];
  }
  return {
    ...env,
    CI: "true",
    NO_COLOR: "1",
    TERM: "dumb",
    NODE_DISABLE_COMPILE_CACHE: "1",
    ...extra,
  };
}

function executableIdentity(name) {
  for (const directory of (process.env.PATH ?? "").split(path.delimiter)) {
    if (!path.isAbsolute(directory)) continue;
    const target = path.join(directory, name);
    let actual;
    try {
      fs.accessSync(target, fs.constants.X_OK);
      actual = fs.realpathSync(target);
    } catch {
      /* Continue the same PATH search used by spawn. */
      continue;
    }
    const bytes = readEvidenceBytes(actual, 256 * 1024 ** 2);
    return { sha256: sha256(bytes), bytes: bytes.length };
  }
  fail("utility-unavailable");
}

function safeDirectory(directory) {
  const info = fs.lstatSync(directory);
  requireCondition(
    info.isDirectory() &&
      !info.isSymbolicLink() &&
      fs.realpathSync(directory) === path.resolve(directory),
    "unsafe-directory",
  );
}

function ownedBytes(root) {
  let bytes = 0;
  let entries = 0;
  const pending = [root];
  while (pending.length) {
    const directory = pending.pop();
    for (const name of fs.readdirSync(directory)) {
      requireCondition(++entries <= 200_000, "owned-entry-limit");
      const target = path.join(directory, name);
      const info = fs.lstatSync(target);
      if (info.isDirectory()) pending.push(target);
      else bytes += info.size;
      requireCondition(bytes <= PROFILE.ownedBytesLimit, "owned-byte-limit");
    }
  }
  return bytes;
}

function resources(root) {
  const disk = fs.statfsSync(root);
  const freeBytes = disk.bavail * disk.bsize;
  const match = /^MemAvailable:\s+(\d+) kB$/m.exec(
    fs.readFileSync("/proc/meminfo", "utf8"),
  );
  requireCondition(match, "memory-observation-unavailable");
  return {
    freeBytes,
    availableMemoryBytes: Number(match[1]) * 1024,
    ownedBytes: ownedBytes(root),
  };
}

function groupAlive(pid) {
  try {
    process.kill(-pid, 0);
    return true;
  } catch (error) {
    if (error.code === "ESRCH") return false;
    return null; // Permission/inspection failure is not proof of disappearance.
  }
}

async function terminateGroup(pid) {
  if (!Number.isInteger(pid) || pid < 2) return false;
  try {
    process.kill(-pid, "SIGKILL");
  } catch (error) {
    if (error.code !== "ESRCH") return false;
  }
  const end = performance.now() + 2_000;
  while (groupAlive(pid) === true && performance.now() < end) await sleep(25);
  return groupAlive(pid) === false;
}

/** Every subprocess owns a detached POSIX group, including after leader close. */
export async function runCommand({
  executable,
  args,
  cwd,
  env,
  logRoot,
  label,
  timeoutMs,
  maxStdout,
  maxStderr = maxStdout,
  deadline,
  monitor = () => {},
}) {
  requireCondition(/^[a-z0-9-]+$/.test(label), "invalid-command-label");
  const stdoutPath = path.join(logRoot, `${label}.stdout`);
  const stderrPath = path.join(logRoot, `${label}.stderr`);
  const descriptors = [];
  const started = performance.now();
  const result = {
    label,
    exitCode: null,
    signal: null,
    reason: null,
    childError: false,
    drainComplete: false,
    drainTimedOut: false,
    cleanupComplete: false,
    elapsedMs: 0,
    stdout: { bytes: 0, retainedBytes: 0 },
    stderr: { bytes: 0, retainedBytes: 0 },
  };
  let child;
  let stopped = false;
  let timer;
  let guard;
  let drainTimer;
  let finish;
  let interrupted;
  let streamsEnded = 0;
  let captureWritable = true;
  try {
    descriptors.push(fs.openSync(stdoutPath, "wx", 0o600));
    descriptors.push(fs.openSync(stderrPath, "wx", 0o600));
    child = spawn(executable, args, {
      cwd,
      env,
      detached: true,
      stdio: ["ignore", "pipe", "pipe"],
    });
    child.stdout.once("end", () => streamsEnded++);
    child.stderr.once("end", () => streamsEnded++);
    const stop = (reason) => {
      result.reason ??= reason;
      if (stopped) return;
      stopped = true;
      try {
        process.kill(-child.pid, "SIGKILL");
      } catch {
        /* Verified below. */
      }
      // An escaped descendant could keep inherited pipes open. Bound waiting,
      // retain the failed capture and never infer cleanup from this timeout.
      drainTimer = setTimeout(() => {
        result.drainTimedOut = true;
        result.drainComplete = false;
        child.stdout.destroy();
        child.stderr.destroy();
        child.unref();
        finish();
      }, 2_000);
    };
    interrupted = () => stop("interrupted");
    process.once("SIGINT", interrupted);
    process.once("SIGTERM", interrupted);
    const capture = (stream, field, fd, cap) =>
      stream.on("data", (chunk) => {
        result[field].bytes += chunk.length;
        if (!captureWritable) return;
        const retained = chunk.subarray(
          0,
          Math.max(0, cap - result[field].retainedBytes),
        );
        try {
          const written = retained.length ? fs.writeSync(fd, retained) : 0;
          requireCondition(
            Number.isSafeInteger(written) &&
              written >= 0 &&
              written <= retained.length,
            "invalid-capture-write",
          );
          result[field].retainedBytes += written;
          if (written !== retained.length) {
            captureWritable = false;
            stop("capture-write-error");
          }
        } catch {
          captureWritable = false;
          stop("capture-write-error");
        }
        if (result[field].bytes > cap) stop("capture-limit");
      });
    capture(child.stdout, "stdout", descriptors[0], maxStdout);
    capture(child.stderr, "stderr", descriptors[1], maxStderr);
    const allowedMs = Math.max(
      1,
      Math.min(timeoutMs, deadline - performance.now()),
    );
    timer = setTimeout(() => stop("deadline"), allowedMs);
    guard = setInterval(() => {
      try {
        monitor();
      } catch {
        stop("resource-limit");
      }
    }, 1_000);
    await new Promise((resolve) => {
      finish = resolve;
      child.once("error", () => {
        result.childError = true;
        stop("spawn-error");
        resolve();
      });
      child.once("close", (code, signal) => {
        result.exitCode = code;
        result.signal = signal;
        resolve();
      });
    });
    // A failed or successful leader may leave descendants holding no stdio.
    result.cleanupComplete = await terminateGroup(child.pid);
    result.drainComplete = !result.drainTimedOut && streamsEnded === 2;
    if (!result.cleanupComplete && !result.reason)
      result.reason = "cleanup-incomplete";
  } finally {
    clearTimeout(timer);
    clearInterval(guard);
    clearTimeout(drainTimer);
    if (interrupted) {
      process.off("SIGINT", interrupted);
      process.off("SIGTERM", interrupted);
    }
    for (const fd of descriptors) fs.closeSync(fd);
    result.elapsedMs = Math.ceil(performance.now() - started);
  }
  result.stdout.sha256 = sha256(readEvidenceBytes(stdoutPath, maxStdout || 1));
  result.stderr.sha256 = sha256(readEvidenceBytes(stderrPath, maxStderr || 1));
  result.captureComplete =
    result.drainComplete &&
    captureWritable &&
    result.stdout.bytes === result.stdout.retainedBytes &&
    result.stderr.bytes === result.stderr.retainedBytes;
  return result;
}

function tarString(header, start, size) {
  const bytes = header.subarray(start, start + size);
  const end = bytes.indexOf(0);
  const value = bytes.subarray(0, end < 0 ? bytes.length : end);
  requireCondition(
    value.every((byte) => byte >= 32 && byte <= 126),
    "archive-header-encoding",
  );
  return value.toString("ascii");
}

function tarOctal(header, start, size) {
  const value = tarString(header, start, size).trim();
  requireCondition(/^[0-7]+$/.test(value), "archive-number");
  const number = Number.parseInt(value, 8);
  requireCondition(Number.isSafeInteger(number), "archive-number");
  return number;
}

export function pnpmInstallArguments(storeDirectory, phase) {
  requireCondition(phase === "update" || phase === "frozen", "pnpm-phase");
  const network = phase === "update"
    ? PROFILE.pnpm.updateNetwork
    : PROFILE.pnpm.frozenNetwork;
  requireCondition(
    network === "online" || network === "offline",
    "pnpm-network-policy",
  );
  return [
    "install",
    "--lockfile-only",
    ...(network === "offline" ? ["--offline"] : []),
    "--ignore-scripts",
    "--store-dir",
    storeDirectory,
    phase === "frozen" ? "--frozen-lockfile" : "--no-frozen-lockfile",
  ];
}

/** Extract only pnpm, never delegate archive path/link handling to tar. */
export function pnpmBinary(archive) {
  const bytes = gunzipSync(archive, {
    maxOutputLength: PROFILE.pnpm.archiveBytesLimit,
  });
  const names = new Set();
  const regularFiles = new Set();
  let executable;
  let count = 0;
  let offset = 0;
  while (offset + 512 <= bytes.length) {
    const header = bytes.subarray(offset, offset + 512);
    if (header.every((byte) => byte === 0)) {
      requireCondition(
        bytes.length - offset >= 1024 &&
          bytes.subarray(offset).every((byte) => byte === 0),
        "archive-trailing-data",
      );
      requireCondition(
        executable && executable.length > 0,
        "archive-missing-pnpm",
      );
      return executable;
    }
    requireCondition(
      ++count <= PROFILE.pnpm.archiveEntriesLimit,
      "archive-entry-limit",
    );
    const checksum = tarOctal(header, 148, 8);
    const actual = header.reduce(
      (sum, byte, index) => sum + (index >= 148 && index < 156 ? 32 : byte),
      0,
    );
    requireCondition(checksum === actual, "archive-checksum");
    const leaf = tarString(header, 0, 100);
    const prefix = tarString(header, 345, 155);
    const name = prefix ? `${prefix}/${leaf}` : leaf;
    const segments = name.replace(/\/$/, "").split("/");
    requireCondition(
      name &&
        !name.startsWith("/") &&
        !name.includes("\\") &&
        segments.every((part) => part && part !== "." && part !== "..") &&
        !names.has(name),
      "archive-path",
    );
    names.add(name);
    const kind = header[156];
    const size = tarOctal(header, 124, 12);
    const ignoredHardLink = kind === 49 && size === 0 && name.startsWith("dist/");
    requireCondition(
      kind === 0 || kind === 48 || (kind === 53 && size === 0) || ignoredHardLink,
      "archive-entry-kind",
    );
    if (ignoredHardLink) {
      // Validate ignored metadata; never create a filesystem link or follow it.
      const target = tarString(header, 157, 100);
      requireCondition(
        target.startsWith("dist/") && regularFiles.has(target),
        "archive-link-target",
      );
    }
    requireCondition(
      offset + 512 + size <= bytes.length,
      "archive-short-entry",
    );
    if (kind === 0 || kind === 48) regularFiles.add(name);
    if (name === "pnpm" && (kind === 0 || kind === 48)) {
      requireCondition(!executable, "archive-duplicate-pnpm");
      executable = bytes.subarray(offset + 512, offset + 512 + size);
    }
    offset += 512 + Math.ceil(size / 512) * 512;
  }
  fail("archive-missing-terminator");
}

function gitRead(args, cwd, maxBuffer = 2 * 1024 ** 2, timeoutMs = 10_000) {
  const result = spawnSync("git", args, {
    cwd,
    env: childEnvironment(),
    encoding: "buffer",
    timeout: timeoutMs,
    killSignal: "SIGKILL",
    maxBuffer,
  });
  requireCondition(!result.error && result.status === 0, "git-source-read");
  return result.stdout;
}

function certifyBeforeDeadline(result, deadline) {
  requireCondition(performance.now() < deadline, "total-deadline");
  result.ok = true;
}

function sourceEntries(workspace) {
  const entries = new Map();
  for (const record of gitRead(
    ["ls-tree", "-rz", "--full-tree", PROFILE.candidate],
    workspace,
  )
    .toString("utf8")
    .split("\0")
    .filter(Boolean)) {
    const match = /^(100644|100755|120000) blob ([0-9a-f]{40})\t(.+)$/.exec(
      record,
    );
    requireCondition(
      match &&
        !path.isAbsolute(match[3]) &&
        match[3]
          .split("/")
          .every((part) => part && part !== ".." && part !== "."),
      "unsupported-source-entry",
    );
    entries.set(match[3], { mode: match[1], oid: match[2] });
  }
  return entries;
}

export function verifyPayload(root, entries, mutable = [], frozen = new Map()) {
  safeDirectory(root);
  const remaining = new Map(entries);
  const expectedDirectories = new Set();
  for (const name of entries.keys()) {
    let directory = path.posix.dirname(name);
    while (directory !== ".") {
      expectedDirectories.add(directory);
      directory = path.posix.dirname(directory);
    }
  }
  const pending = [root];
  while (pending.length) {
    const directory = pending.pop();
    for (const leaf of fs.readdirSync(directory)) {
      requireCondition(leaf !== "node_modules", "unexpected-node-modules");
      const target = path.join(directory, leaf);
      const relative = path.relative(root, target).split(path.sep).join("/");
      const info = fs.lstatSync(target);
      if (info.isDirectory()) {
        requireCondition(
          expectedDirectories.has(relative),
          "unexpected-source-directory",
        );
        pending.push(target);
        continue;
      }
      const expected = remaining.get(relative);
      requireCondition(expected, "unexpected-source-file");
      remaining.delete(relative);
      const link = expected.mode === "120000";
      requireCondition(
        link
          ? info.isSymbolicLink()
          : info.isFile() && !info.isSymbolicLink() && info.nlink === 1,
        "source-entry-type",
      );
      if (mutable.includes(relative)) {
        requireCondition(
          !link && info.size <= PROFILE.lockBytesLimit,
          "unsafe-mutable-lock",
        );
        if (frozen.has(relative))
          requireCondition(
            frozen
              .get(relative)
              .equals(readEvidenceBytes(target, PROFILE.lockBytesLimit)),
            "frozen-lock-changed",
          );
        continue;
      }
      const bytes = link
        ? Buffer.from(fs.readlinkSync(target))
        : readEvidenceBytes(target, 32 * 1024 ** 2);
      const oid = createHash("sha1")
        .update(`blob ${bytes.length}\0`)
        .update(bytes)
        .digest("hex");
      requireCondition(
        oid === expected.oid &&
          (link || Boolean(info.mode & 0o111) === (expected.mode === "100755")),
        "source-content-changed",
      );
    }
  }
  requireCondition(remaining.size === 0, "missing-source-file");
}

function artifactAllowed(bytes, token) {
  if (!isUtf8(bytes) || bytes.includes(0)) return false;
  const text = bytes.toString("utf8");
  return (
    !(token && text.includes(token)) &&
    !/authorization\s*[:=]|(?:https?:\/\/)[^\s/]+@|[?&](?:token|signature|sig|credential|x-amz-[a-z-]+)=/i.test(
      text,
    )
  );
}

export function auditPublic(directory) {
  safeDirectory(directory);
  let total = 0;
  for (const name of fs.readdirSync(directory)) {
    requireCondition(
      /^(?:result|profile)\.json$|^(?:before|after)-(?:pnpm|bazel)\.lock$|^(?:pnpm|bazel)\.diff$|^[a-z0-9-]+\.(?:stdout|stderr)$/.test(
        name,
      ),
      "unexpected-artifact-name",
    );
    const target = path.join(directory, name);
    const info = fs.lstatSync(target);
    requireCondition(
      info.isFile() &&
        !info.isSymbolicLink() &&
        info.nlink === 1 &&
        fs.realpathSync(target) === path.resolve(target),
      "unsafe-artifact",
    );
    requireCondition(
      info.size <= PROFILE.lockBytesLimit,
      "artifact-file-limit",
    );
    total += info.size;
    requireCondition(
      total <= PROFILE.artifactBytesLimit,
      "artifact-total-limit",
    );
  }
  return total;
}

export function validateContext(env, host) {
  const context = {
    repository: env.GITHUB_REPOSITORY,
    ref: env.GITHUB_REF,
    source: env.GITHUB_SHA,
    workflowSource: env.GITHUB_WORKFLOW_SHA,
    runId: env.GITHUB_RUN_ID,
    attempt: env.GITHUB_RUN_ATTEMPT,
    jobKey: env.GITHUB_JOB,
  };
  requireCondition(
    host.platform === "linux" && host.arch === "x64" && host.nodeMajor === 24,
    "host-profile-mismatch",
  );
  requireCondition(
    context.repository === PROFILE.repository &&
      context.ref === PROFILE.ref &&
      env.GITHUB_EVENT_NAME === "workflow_dispatch" &&
      context.jobKey === "verify",
    "context-mismatch",
  );
  requireCondition(
    /^[0-9a-f]{40}$/.test(env.EXPECTED_WORKER_SHA ?? "") &&
      context.source === env.EXPECTED_WORKER_SHA &&
      context.workflowSource === context.source,
    "worker-source-mismatch",
  );
  requireCondition(
    /^[1-9][0-9]{0,19}$/.test(context.runId ?? "") &&
      /^[1-9][0-9]{0,5}$/.test(context.attempt ?? ""),
    "invalid-run-context",
  );
  requireCondition(
    env.GITHUB_WORKFLOW_REF ===
      `${PROFILE.repository}/.github/workflows/dependency-maintenance.yml@${PROFILE.ref}`,
    "workflow-ref-mismatch",
  );
  return context;
}

async function main() {
  const started = performance.now();
  const workspace = fs.realpathSync(process.cwd());
  const context = validateContext(process.env, {
    platform: process.platform,
    arch: process.arch,
    nodeMajor: Number(process.versions.node.split(".")[0]),
  });
  requireCondition(
    gitRead(["rev-parse", "HEAD"], workspace).toString().trim() ===
      context.source &&
      gitRead(["status", "--porcelain=v1", "--untracked-files=all"], workspace)
        .length === 0,
    "worker-checkout-not-clean",
  );
  gitRead(
    ["merge-base", "--is-ancestor", PROFILE.candidate, context.source],
    workspace,
  );
  requireCondition(
    gitRead(["rev-parse", `${PROFILE.candidate}^{tree}`], workspace)
      .toString()
      .trim() === PROFILE.candidateTree,
    "candidate-tree-mismatch",
  );
  const changed = gitRead(
    ["diff", "--name-only", PROFILE.candidate, context.source],
    workspace,
  )
    .toString("utf8")
    .trim()
    .split("\n")
    .sort();
  requireCondition(
    JSON.stringify(changed) ===
      JSON.stringify([
        ".github/workflows/dependency-maintenance.yml",
        "tools/ci/generate-vscode-dependency-locks.mjs",
      ]),
    "worker-scope-mismatch",
  );
  const temporary = fs.realpathSync(process.env.RUNNER_TEMP);
  safeDirectory(temporary);
  const root = path.join(
    temporary,
    `gvy-sdk-lock-worker-${context.runId}-${context.attempt}`,
  );
  fs.mkdirSync(root, { mode: 0o700 });
  const locations = Object.fromEntries(
    ["public", "private", "payload", "tools", "cache", "logs"].map((name) => [
      name,
      path.join(root, name),
    ]),
  );
  for (const directory of Object.values(locations))
    fs.mkdirSync(directory, { mode: 0o700 });
  const deadline = started + PROFILE.totalMs - PROFILE.cleanupMs;
  const result = {
    schemaVersion: 1,
    ok: false,
    context,
    candidate: PROFILE.candidate,
    candidateTree: PROFILE.candidateTree,
    profileSha256: sha256(jsonBytes(PROFILE)),
    scriptSha256: sha256(readEvidenceBytes(SCRIPT, 256 * 1024)),
    runtime: {
      node: process.version,
      platform: process.platform,
      arch: process.arch,
      nodeSha256: sha256(readEvidenceBytes(process.execPath, 256 * 1024 ** 2)),
      git: executableIdentity("git"),
      gh: executableIdentity("gh"),
    },
    commands: [],
    files: [],
    error: null,
    resources: { initial: null, highWaterOwnedBytes: 0 },
    limits: {
      ownedBytesAreSampled: true,
      generatedLocksAreNotProductValidation: true,
    },
  };
  const retained = new Map();
  const retain = (name, target) => retained.set(name, target);
  const monitor = () => {
    const current = resources(root);
    result.resources.highWaterOwnedBytes = Math.max(
      result.resources.highWaterOwnedBytes,
      current.ownedBytes,
    );
    requireCondition(
      current.freeBytes >= PROFILE.freeBytesFloor &&
        current.availableMemoryBytes >= PROFILE.availableMemoryFloor,
      "resource-limit",
    );
  };
  const command = async (
    label,
    executable,
    args,
    cwd = locations.payload,
    options = {},
  ) => {
    monitor();
    requireCondition(performance.now() < deadline, "total-deadline");
    const item = await runCommand({
      executable,
      args,
      cwd,
      env: childEnvironment(options.env),
      label,
      logRoot: locations.logs,
      deadline,
      monitor,
      timeoutMs: options.timeoutMs ?? 10_000,
      maxStdout: options.maxStdout ?? 64 * 1024,
      maxStderr: options.maxStderr ?? 64 * 1024,
    });
    result.commands.push(item);
    if (options.retainStdout !== false) {
      retain(`${label}.stdout`, path.join(locations.logs, `${label}.stdout`));
    }
    retain(`${label}.stderr`, path.join(locations.logs, `${label}.stderr`));
    requireCondition(
      item.cleanupComplete &&
        item.captureComplete &&
        !item.reason &&
        (options.allowedExitCodes ?? [0]).includes(item.exitCode),
      `command-failed-${label}`,
    );
    return item;
  };
  const snapshot = (phase) =>
    PROFILE.locks.forEach((relative, index) => {
      const name = `${phase}-${index === 0 ? "pnpm" : "bazel"}.lock`;
      if (retained.has(name)) return;
      const target = path.join(locations.private, name);
      writeExclusive(
        target,
        readEvidenceBytes(
          path.join(locations.payload, relative),
          PROFILE.lockBytesLimit,
        ),
      );
      retain(name, target);
    });
  let materialized = false;
  let entries;
  try {
    result.resources.initial = resources(root);
    requireCondition(
      result.resources.initial.freeBytes >= PROFILE.initialFreeBytes &&
        result.resources.initial.availableMemoryBytes >=
          PROFILE.initialAvailableMemoryBytes,
      "initial-resources-insufficient",
    );
    entries = sourceEntries(workspace);
    const indexPath = path.join(locations.private, "candidate.index");
    await command(
      "source-read-tree",
      "git",
      ["read-tree", PROFILE.candidate],
      workspace,
      { env: { GIT_INDEX_FILE: indexPath } },
    );
    await command(
      "source-checkout",
      "git",
      ["checkout-index", "--all", `--prefix=${locations.payload}/`],
      workspace,
      { env: { GIT_INDEX_FILE: indexPath } },
    );
    verifyPayload(locations.payload, entries);
    materialized = true;
    snapshot("before");
    await command("git-version", "git", ["--version"]);
    await command("gh-version", "gh", ["--version"]);
    for (const [name, tool] of [
      ["pnpm", PROFILE.pnpm],
      ["bazel", PROFILE.bazel],
    ]) {
      requireCondition(process.env.GH_TOKEN, "missing-asset-token");
      await command(
        `download-${name}`,
        "gh",
        [
          "api",
          "--allow-escape-sequences",
          `repos/${tool.repository}/releases/assets/${tool.asset}`,
          "--header",
          "Accept: application/octet-stream",
        ],
        workspace,
        {
          env: { GH_TOKEN: process.env.GH_TOKEN },
          timeoutMs: 60_000,
          maxStdout: tool.bytes,
          retainStdout: false,
        },
      );
      const bytes = readEvidenceBytes(
        path.join(locations.logs, `download-${name}.stdout`),
        tool.bytes,
      );
      requireCondition(
        bytes.length === tool.bytes && sha256(bytes) === tool.sha256,
        `asset-mismatch-${name}`,
      );
      const binary = name === "pnpm" ? pnpmBinary(bytes) : bytes;
      const target = path.join(locations.tools, name);
      writeExclusive(target, binary);
      fs.chmodSync(target, 0o700);
      result[name] = { ...tool, binarySha256: sha256(binary) };
      await command(`version-${name}`, target, ["--version"]);
      const version = readEvidenceBytes(
        path.join(locations.logs, `version-${name}.stdout`),
        64 * 1024,
      )
        .toString("utf8")
        .trim();
      requireCondition(
        version === (name === "bazel" ? `bazel ${tool.version}` : tool.version),
        `tool-version-mismatch-${name}`,
      );
    }
    const pnpm = path.join(locations.tools, "pnpm");
    const pnpmStore = path.join(locations.cache, "pnpm-store");
    const pnpmEnv = {
      XDG_CACHE_HOME: path.join(locations.cache, "xdg"),
      npm_config_cache: path.join(locations.cache, "npm"),
      PNPM_HOME: path.join(locations.tools, "pnpm-home"),
    };
    await command(
      "pnpm-update",
      pnpm,
      pnpmInstallArguments(pnpmStore, "update"),
      path.join(locations.payload, "editors/code"),
      {
        env: pnpmEnv,
        timeoutMs: PROFILE.pnpm.timeoutMs,
        maxStdout: PROFILE.pnpm.captureBytes,
      },
    );
    verifyPayload(locations.payload, entries, [PROFILE.locks[0]]);
    const generatedPnpm = readEvidenceBytes(
      path.join(locations.payload, PROFILE.locks[0]),
      PROFILE.lockBytesLimit,
    );
    await command(
      "pnpm-frozen",
      pnpm,
      pnpmInstallArguments(pnpmStore, "frozen"),
      path.join(locations.payload, "editors/code"),
      {
        env: pnpmEnv,
        timeoutMs: PROFILE.pnpm.timeoutMs,
        maxStdout: PROFILE.pnpm.captureBytes,
      },
    );
    requireCondition(
      generatedPnpm.equals(
        readEvidenceBytes(
          path.join(locations.payload, PROFILE.locks[0]),
          PROFILE.lockBytesLimit,
        ),
      ),
      "pnpm-frozen-mutated-lock",
    );
    verifyPayload(locations.payload, entries, [PROFILE.locks[0]]);
    const bazel = path.join(locations.tools, "bazel");
    const frozenPnpm = new Map([[PROFILE.locks[0], generatedPnpm]]);
    const bazelBase = [
      "--batch",
      "--nosystem_rc",
      "--nohome_rc",
      `--output_user_root=${path.join(locations.cache, "bazel-output")}`,
      "--host_jvm_args=-Xmx768m",
      "--host_jvm_args=-XX:MaxMetaspaceSize=256m",
      "--host_jvm_args=-XX:ActiveProcessorCount=1",
    ];
    const bazelEnv = {
      JDK_JAVA_OPTIONS:
        "-Xmx512m -XX:MaxMetaspaceSize=256m -XX:ActiveProcessorCount=1",
      COURSIER_CACHE: path.join(locations.cache, "coursier"),
    };
    const bazelCommand = (mode) => [
      ...bazelBase,
      "mod",
      "deps",
      "--loading_phase_threads=1",
      `--lockfile_mode=${mode}`,
      `--repository_cache=${path.join(locations.cache, "bazel-repository")}`,
    ];
    await command(
      "bazel-update",
      bazel,
      bazelCommand("update"),
      locations.payload,
      {
        env: bazelEnv,
        timeoutMs: PROFILE.bazel.timeoutMs,
        maxStdout: PROFILE.bazel.captureBytes,
        maxStderr: PROFILE.bazel.captureBytes,
      },
    );
    verifyPayload(locations.payload, entries, PROFILE.locks, frozenPnpm);
    const generatedBazel = readEvidenceBytes(
      path.join(locations.payload, PROFILE.locks[1]),
      PROFILE.lockBytesLimit,
    );
    await command(
      "bazel-error",
      bazel,
      bazelCommand("error"),
      locations.payload,
      {
        env: bazelEnv,
        timeoutMs: PROFILE.bazel.timeoutMs,
        maxStdout: PROFILE.bazel.captureBytes,
        maxStderr: PROFILE.bazel.captureBytes,
      },
    );
    requireCondition(
      generatedBazel.equals(
        readEvidenceBytes(
          path.join(locations.payload, PROFILE.locks[1]),
          PROFILE.lockBytesLimit,
        ),
      ),
      "bazel-error-mutated-lock",
    );
    verifyPayload(locations.payload, entries, PROFILE.locks, frozenPnpm);
    snapshot("after");
    for (const name of ["pnpm", "bazel"]) {
      const comparison = await command(
        `diff-${name}`,
        "git",
        [
          "diff",
          "--no-index",
          "--no-ext-diff",
          "--no-color",
          `before-${name}.lock`,
          `after-${name}.lock`,
        ],
        locations.private,
        { maxStdout: 512 * 1024, allowedExitCodes: [0, 1] },
      );
      requireCondition(
        comparison.exitCode === 1,
        `expected-lock-change-${name}`,
      );
      retain(`${name}.diff`, path.join(locations.logs, `diff-${name}.stdout`));
    }
    requireCondition(
      gitRead(["status", "--porcelain=v1", "--untracked-files=all"], workspace)
        .length === 0 &&
        gitRead(["rev-parse", "HEAD"], workspace).toString().trim() ===
          context.source,
      "worker-source-mutated",
    );
    certifyBeforeDeadline(result, deadline);
  } catch (error) {
    result.error = /^[a-z0-9-]{1,100}$/.test(error.message ?? "")
      ? error.message
      : "worker-failure";
    result.errorSha256 = sha256(Buffer.from(String(error.stack ?? error)));
    // After a failed command, invoke no more dependency tools. Preserve partial
    // locks only when the complete inventory still proves their regular-file
    // locations; otherwise make the capture gap explicit.
    if (materialized) {
      try {
        verifyPayload(locations.payload, entries, PROFILE.locks);
        snapshot("after");
        result.failureLockCapture = "canonical-partial-locks-retained";
      } catch {
        result.failureLockCapture =
          "unsafe-or-unreadable-payload-not-published";
      }
    }
  }
  result.elapsedMs = Math.ceil(performance.now() - started);
  // Publication is a separate allowlist boundary. Unsafe raw output is retained
  // privately and represented by byte/hash identity, never silently sanitized.
  try {
    let publishedBytes = 0;
    for (const [name, target] of retained) {
      const bytes = readEvidenceBytes(target, PROFILE.lockBytesLimit);
      const privateOutput = !artifactAllowed(bytes, process.env.GH_TOKEN);
      const overBudget =
        publishedBytes + bytes.length > PROFILE.artifactBytesLimit - 128 * 1024;
      const safe = !privateOutput && !overBudget;
      result.files.push({
        name,
        bytes: bytes.length,
        sha256: sha256(bytes),
        published: safe,
        omissionReason: privateOutput
          ? "unsafe-public-output"
          : overBudget
            ? "artifact-total-limit"
            : null,
      });
      if (safe) {
        writeExclusive(path.join(locations.public, name), bytes);
        publishedBytes += bytes.length;
      } else {
        result.ok = false;
        result.error ??= privateOutput
          ? "unsafe-public-output"
          : "artifact-total-limit";
      }
    }
    writeExclusive(
      path.join(locations.public, "profile.json"),
      jsonBytes(PROFILE),
    );
    const bytes = jsonBytes(result);
    requireCondition(bytes.length <= 64 * 1024, "result-byte-limit");
    writeExclusive(path.join(locations.public, "result.json"), bytes);
    auditPublic(locations.public);
    fs.appendFileSync(process.env.GITHUB_OUTPUT, "safe=true\n");
  } catch {
    result.ok = false;
    result.error ??= "artifact-audit-failed";
  }
  console.log(
    `SDK lock worker ${result.ok ? "PASS" : "FAIL"}: ${result.error ?? "two locks generated and verified"}; candidate ${PROFILE.candidate}; profile ${result.profileSha256}`,
  );
  process.exitCode = result.ok ? 0 : 1;
}

function tarFixture(
  name = "pnpm",
  kind = 48,
  content = Buffer.from("executable"),
  linkName = "",
) {
  const header = Buffer.alloc(512);
  header.write(name, 0, 100, "ascii");
  header.write("0000700\0", 100, 8, "ascii");
  header.write(
    `${content.length.toString(8).padStart(11, "0")}\0`,
    124,
    12,
    "ascii",
  );
  header.fill(32, 148, 156);
  header[156] = kind;
  header.write(linkName, 157, 100, "ascii");
  header.write(
    `${header
      .reduce((sum, byte) => sum + byte, 0)
      .toString(8)
      .padStart(6, "0")}\0 `,
    148,
    8,
    "ascii",
  );
  return Buffer.concat([
    header,
    content,
    Buffer.alloc((512 - (content.length % 512)) % 512),
  ]);
}

async function selfTest() {
  const { gzipSync } = await import("node:zlib");
  const { test } = await import("node:test");
  const { syncBuiltinESMExports } = await import("node:module");
  const fsDefault = (await import("node:fs")).default;
  const childProcessDefault = (await import("node:child_process")).default;
  await test("a short or zero capture write cannot certify missing diagnostic bytes", async () => {
    const root = fs.realpathSync(
      fs.mkdtempSync(path.join(os.tmpdir(), "gvy-lock-short-write-")),
    );
    const original = fsDefault.writeSync;
    try {
      for (const count of [2, 0]) {
        fsDefault.writeSync = (fd, bytes, ...args) =>
          Buffer.isBuffer(bytes) && bytes.equals(Buffer.from("hello"))
            ? original(fd, bytes.subarray(0, count))
            : original(fd, bytes, ...args);
        syncBuiltinESMExports();
        const result = await runCommand({
          executable: process.execPath,
          args: ["-e", "process.stdout.write('hello')"],
          cwd: root,
          env: childEnvironment(),
          logRoot: root,
          label: `short-${count}`,
          timeoutMs: 1_000,
          maxStdout: 5,
          deadline: performance.now() + 3_000,
        });
        assert.equal(result.stdout.retainedBytes, count);
        assert.equal(
          fs.statSync(path.join(root, `short-${count}.stdout`)).size,
          count,
        );
        assert.equal(result.reason, "capture-write-error");
        assert.equal(result.captureComplete, false);
      }
    } finally {
      fsDefault.writeSync = original;
      syncBuiltinESMExports();
      fs.rmSync(root, { recursive: true });
    }
  });
  await test("capture overflow retains its primary cause when an owned escaped pipe exceeds the drain window", async () => {
    const root = fs.realpathSync(
      fs.mkdtempSync(path.join(os.tmpdir(), "gvy-lock-drain-")),
    );
    let escaped;
    try {
      const source =
        "const child=require('node:child_process').spawn(process.execPath,['-e','setInterval(()=>{},1000)'],{detached:true,stdio:['ignore','inherit','inherit']});require('node:fs').writeFileSync(process.argv[1],String(child.pid));child.unref();process.stdout.write('overflow')";
      const result = await runCommand({
        executable: process.execPath,
        args: ["-e", source, path.join(root, "escaped.pid")],
        cwd: root,
        env: childEnvironment(),
        logRoot: root,
        label: "held-pipe",
        timeoutMs: 1_000,
        maxStdout: 1,
        deadline: performance.now() + 5_000,
      });
      escaped = Number(fs.readFileSync(path.join(root, "escaped.pid"), "utf8"));
      assert.ok(Number.isSafeInteger(escaped) && escaped > 1);
      assert.equal(result.reason, "capture-limit");
      assert.equal(result.drainComplete, false);
      assert.equal(result.drainTimedOut, true);
      assert.equal(result.captureComplete, false);
    } finally {
      if (!escaped) {
        try {
          escaped = Number(
            fs.readFileSync(path.join(root, "escaped.pid"), "utf8"),
          );
        } catch {
          /* No fixture was launched. */
        }
      }
      if (Number.isSafeInteger(escaped) && escaped > 1)
        await terminateGroup(escaped);
      fs.rmSync(root, { recursive: true });
    }
  });
  await test("synchronous Git reads kill a handled-TERM fixture at their timeout", () => {
    const root = fs.realpathSync(
      fs.mkdtempSync(path.join(os.tmpdir(), "gvy-lock-sync-git-")),
    );
    const original = childProcessDefault.spawnSync;
    try {
      const source =
        "process.on('SIGTERM',()=>{});require('node:fs').writeFileSync(process.argv[1],'ready');setTimeout(()=>process.exit(0),1000)";
      childProcessDefault.spawnSync = (_executable, _args, options) =>
        original(
          process.execPath,
          ["-e", source, path.join(root, "ready")],
          options,
        );
      syncBuiltinESMExports();
      const started = performance.now();
      assert.throws(
        () => gitRead([path.join(root, "ready")], root, 1024, 250),
        /git-source-read/,
      );
      const elapsed = performance.now() - started;
      assert.equal(fs.readFileSync(path.join(root, "ready"), "utf8"), "ready");
      assert.ok(
        elapsed < 750,
        `handled TERM exceeded the ordinary timeout allowance: ${elapsed}`,
      );
    } finally {
      childProcessDefault.spawnSync = original;
      syncBuiltinESMExports();
      fs.rmSync(root, { recursive: true });
    }
  });
  await test("a later child error cannot replace the capture failure that stopped it", async () => {
    const root = fs.realpathSync(
      fs.mkdtempSync(path.join(os.tmpdir(), "gvy-lock-late-error-")),
    );
    const original = childProcessDefault.spawn;
    try {
      childProcessDefault.spawn = (...args) => {
        const child = original(...args);
        child.stdout.once("data", () =>
          queueMicrotask(() =>
            child.emit("error", new Error("controlled late child error")),
          ),
        );
        return child;
      };
      syncBuiltinESMExports();
      const result = await runCommand({
        executable: process.execPath,
        args: ["-e", "process.stdout.write('overflow')"],
        cwd: root,
        env: childEnvironment(),
        logRoot: root,
        label: "late-error",
        timeoutMs: 1_000,
        maxStdout: 1,
        deadline: performance.now() + 3_000,
      });
      assert.equal(result.reason, "capture-limit");
      assert.equal(result.childError, true);
    } finally {
      childProcessDefault.spawn = original;
      syncBuiltinESMExports();
      fs.rmSync(root, { recursive: true });
    }
  });
  await test("later phases cannot change the pnpm bytes accepted by frozen verification", () => {
    const root = fs.realpathSync(
      fs.mkdtempSync(path.join(os.tmpdir(), "gvy-lock-late-pnpm-")),
    );
    try {
      const name = "pnpm-lock.yaml";
      const bytes = Buffer.from("verified");
      fs.writeFileSync(path.join(root, name), bytes);
      const oid = createHash("sha1")
        .update(`blob ${bytes.length}\0`)
        .update(bytes)
        .digest("hex");
      const entries = new Map([[name, { mode: "100644", oid }]]);
      const frozen = new Map([[name, bytes]]);
      verifyPayload(root, entries, [name], frozen);
      fs.writeFileSync(path.join(root, name), "late change");
      assert.throws(
        () => verifyPayload(root, entries, [name], frozen),
        /frozen-lock-changed/,
      );
    } finally {
      fs.rmSync(root, { recursive: true });
    }
  });
  await test("final source reads cannot certify success after the shared deadline", () => {
    const expired = { ok: false };
    assert.throws(
      () => certifyBeforeDeadline(expired, performance.now() - 1),
      /total-deadline/,
    );
    assert.equal(expired.ok, false);
    const current = { ok: false };
    certifyBeforeDeadline(current, performance.now() + 10_000);
    assert.equal(current.ok, true);
  });
  await test("dispatch requires the exact repository, ref, source, workflow and run attempt", () => {
    const sha = "a".repeat(40);
    const env = {
      GITHUB_REPOSITORY: PROFILE.repository,
      GITHUB_REF: PROFILE.ref,
      GITHUB_EVENT_NAME: "workflow_dispatch",
      GITHUB_JOB: "verify",
      GITHUB_SHA: sha,
      GITHUB_WORKFLOW_SHA: sha,
      EXPECTED_WORKER_SHA: sha,
      GITHUB_RUN_ID: "123",
      GITHUB_RUN_ATTEMPT: "1",
      GITHUB_WORKFLOW_REF: `${PROFILE.repository}/.github/workflows/dependency-maintenance.yml@${PROFILE.ref}`,
    };
    const host = { platform: "linux", arch: "x64", nodeMajor: 24 };
    assert.equal(validateContext(env, host).source, sha);
    for (const [key, value] of Object.entries({
      GITHUB_REPOSITORY: "other/repository",
      GITHUB_REF: "refs/heads/main",
      GITHUB_EVENT_NAME: "pull_request",
      GITHUB_JOB: "other",
      GITHUB_SHA: "b".repeat(40),
      GITHUB_WORKFLOW_SHA: "b".repeat(40),
      EXPECTED_WORKER_SHA: "main",
      GITHUB_RUN_ID: "123/456",
      GITHUB_RUN_ATTEMPT: "0",
      GITHUB_WORKFLOW_REF: "other/workflow",
    }))
      assert.throws(() => validateContext({ ...env, [key]: value }, host));
    assert.throws(() => validateContext(env, { ...host, platform: "darwin" }));
    assert.throws(() => validateContext(env, { ...host, arch: "arm64" }));
    assert.throws(() => validateContext(env, { ...host, nodeMajor: 26 }));
  });
  const archive = (...entries) =>
    gzipSync(Buffer.concat([...entries, Buffer.alloc(1024)]));
  await test("cold supply-chain metadata can warm during update and frozen verification stays offline", () => {
    const store = "/fixed-owned-cache";
    let metadataAvailable = false;
    const checkPolicy = (args) => {
      assert.ok(args.includes("--lockfile-only"));
      assert.ok(args.includes("--ignore-scripts"));
      assert.equal(args[args.indexOf("--store-dir") + 1], store);
      if (args.includes("--offline") && !metadataAvailable)
        throw new Error("ERR_PNPM_NO_OFFLINE_META");
      metadataAvailable = true;
    };
    const frozen = pnpmInstallArguments(store, "frozen");
    assert.ok(frozen.includes("--offline"));
    assert.ok(frozen.includes("--frozen-lockfile"));
    assert.ok(!frozen.includes("--no-frozen-lockfile"));
    assert.throws(() => checkPolicy(frozen), /ERR_PNPM_NO_OFFLINE_META/);
    const update = pnpmInstallArguments(store, "update");
    checkPolicy(update);
    assert.ok(!update.includes("--offline"));
    assert.ok(update.includes("--no-frozen-lockfile"));
    assert.ok(!update.includes("--frozen-lockfile"));
    assert.doesNotThrow(() => checkPolicy(frozen));
    const allowed = new Set([
      "install",
      "--lockfile-only",
      "--ignore-scripts",
      "--store-dir",
      store,
      "--offline",
      "--frozen-lockfile",
      "--no-frozen-lockfile",
    ]);
    assert.ok([...update, ...frozen].every((arg) => allowed.has(arg)));
  });
  await test("an unknown pnpm phase cannot select a network policy", () => {
    assert.throws(
      () => pnpmInstallArguments("/fixed-owned-cache", "other"),
      /pnpm-phase/,
    );
  });
  await test("archive extracts only one regular pnpm member", () =>
    assert.equal(pnpmBinary(archive(tarFixture())).toString(), "executable"));
  await test("the pinned release's 543-entry layout retains only the root executable", () => {
    const entries = Array.from({ length: 542 }, (_, i) =>
      tarFixture(`dist/file-${i}`, 48, Buffer.alloc(0)),
    );
    assert.equal(
      pnpmBinary(archive(tarFixture(), ...entries)).toString(),
      "executable",
    );
  });
  await test("ignored node-gyp payload hard links are validated without materializing them", () => {
    assert.equal(
      pnpmBinary(archive(
        tarFixture(),
        tarFixture("dist/source", 48, Buffer.from("payload")),
        tarFixture("dist/alias", 49, Buffer.alloc(0), "dist/source"),
      )).toString(),
      "executable",
    );
  });
  await test("an archive cannot substitute a nested executable for root pnpm", () => {
    assert.throws(
      () => pnpmBinary(archive(tarFixture("dist/pnpm"))),
      /archive-missing-pnpm/,
    );
  });
  await test("the release entry bound still rejects one additional member", () => {
    const entries = Array.from({ length: 543 }, (_, i) =>
      tarFixture(`dist/file-${i}`, 48, Buffer.alloc(0)),
    );
    assert.throws(
      () => pnpmBinary(archive(tarFixture(), ...entries)),
      /archive-entry-limit/,
    );
  });
  for (const [label, name, target, content] of [
    ["traversing target", "dist/alias", "../pnpm", Buffer.alloc(0)],
    ["executable target", "dist/alias", "pnpm", Buffer.alloc(0)],
    ["missing target", "dist/alias", "dist/missing", Buffer.alloc(0)],
    ["outside payload", "alias", "dist/source", Buffer.alloc(0)],
    ["nonempty hard link", "dist/alias", "dist/source", Buffer.from("x")],
  ]) {
    await test(`ignored hard links reject ${label}`, () => {
      assert.throws(() => pnpmBinary(archive(
        tarFixture(),
        tarFixture("dist/source", 48, Buffer.from("payload")),
        tarFixture(name, 49, content, target),
      )));
    });
  }
  for (const [name, bytes] of [
    ["traversal", archive(tarFixture("../pnpm"))],
    ["absolute path", archive(tarFixture("/pnpm"))],
    ["symbolic link", archive(tarFixture("pnpm", 50))],
    ["hard link", archive(tarFixture("pnpm", 49))],
    ["duplicate", archive(tarFixture(), tarFixture())],
    ["missing terminator", gzipSync(tarFixture())],
    [
      "checksum",
      (() => {
        const value = tarFixture();
        value[0] ^= 1;
        return archive(value);
      })(),
    ],
  ])
    await test(`archive rejects ${name}`, () =>
      assert.throws(() => pnpmBinary(bytes)));
  await test("public audit rejects root replacement, links and oversized totals", () => {
    const root = fs.realpathSync(
      fs.mkdtempSync(path.join(os.tmpdir(), "gvy-lock-audit-")),
    );
    try {
      fs.writeFileSync(path.join(root, "result.json"), "{}");
      assert.equal(auditPublic(root), 2);
      fs.symlinkSync(
        path.join(root, "result.json"),
        path.join(root, "profile.json"),
      );
      assert.throws(() => auditPublic(root));
      fs.unlinkSync(path.join(root, "profile.json"));
      fs.linkSync(
        path.join(root, "result.json"),
        path.join(root, "profile.json"),
      );
      assert.throws(() => auditPublic(root));
      fs.unlinkSync(path.join(root, "profile.json"));
      assert.throws(() => auditPublic(path.join(root, "result.json")));
      for (let i = 0; i < 5; i++)
        fs.writeFileSync(
          path.join(root, `control-${i}.stdout`),
          Buffer.alloc(PROFILE.lockBytesLimit),
        );
      assert.throws(() => auditPublic(root));
    } finally {
      fs.rmSync(root, { recursive: true });
    }
  });
  await test("payload accepts only exact source except two canonical regular locks", () => {
    const root = fs.realpathSync(
      fs.mkdtempSync(path.join(os.tmpdir(), "gvy-lock-payload-")),
    );
    try {
      const target = path.join(root, "lock");
      fs.writeFileSync(target, "before");
      const oid = createHash("sha1").update("blob 6\0before").digest("hex");
      const entries = new Map([["lock", { mode: "100644", oid }]]);
      verifyPayload(root, entries);
      fs.writeFileSync(target, "after");
      assert.throws(() => verifyPayload(root, entries));
      verifyPayload(root, entries, ["lock"]);
      fs.unlinkSync(target);
      fs.symlinkSync("missing", target);
      assert.throws(() => verifyPayload(root, entries, ["lock"]));
    } finally {
      fs.rmSync(root, { recursive: true });
    }
  });
  await test("private credential output cannot enter public evidence", () => {
    assert.equal(
      artifactAllowed(
        Buffer.from("ordinary registry URL https://registry.npmjs.org/"),
        "private",
      ),
      true,
    );
    for (const text of [
      "private",
      "Authorization: Bearer x",
      "https://user:pass@example.org/",
      "https://example.org/?token=x",
    ])
      assert.equal(artifactAllowed(Buffer.from(text), "private"), false);
    assert.equal(artifactAllowed(Buffer.from([0xff]), "private"), false);
    assert.equal(artifactAllowed(Buffer.from([0]), "private"), false);
  });
  await test("subprocess captures exact output and bounds overflow and dripping time", async () => {
    const root = fs.realpathSync(
      fs.mkdtempSync(path.join(os.tmpdir(), "gvy-lock-process-")),
    );
    const run = (label, source, timeoutMs, cap) =>
      runCommand({
        executable: process.execPath,
        args: ["-e", source],
        cwd: root,
        env: childEnvironment(),
        logRoot: root,
        label,
        timeoutMs,
        maxStdout: cap,
        maxStderr: cap,
        deadline: performance.now() + 5_000,
      });
    try {
      const good = await run("good", "process.stdout.write('hello')", 1_000, 5);
      assert.equal(good.exitCode, 0);
      assert.equal(good.captureComplete, true);
      assert.equal(good.cleanupComplete, true);
      const overflow = await run(
        "overflow",
        "process.stdout.write('123456')",
        1_000,
        5,
      );
      assert.equal(overflow.reason, "capture-limit");
      assert.equal(overflow.stdout.retainedBytes, 5);
      assert.equal(overflow.captureComplete, false);
      const drip = await run(
        "drip",
        "setInterval(()=>process.stdout.write('.'),10)",
        100,
        100,
      );
      assert.equal(drip.reason, "deadline");
      assert.equal(drip.cleanupComplete, true);
      const deadline = await runCommand({
        executable: process.execPath,
        args: ["-e", "setInterval(()=>{},1000)"],
        cwd: root,
        env: childEnvironment(),
        logRoot: root,
        label: "shared-deadline",
        timeoutMs: 5_000,
        maxStdout: 100,
        deadline: performance.now() + 100,
      });
      assert.equal(deadline.reason, "deadline");
      assert.ok(deadline.elapsedMs < 3_000);
    } finally {
      fs.rmSync(root, { recursive: true });
    }
  });
  await test("early failed leader still loses its owned grandchild and preserves an unrelated sibling", async () => {
    const root = fs.realpathSync(
      fs.mkdtempSync(path.join(os.tmpdir(), "gvy-lock-tree-")),
    );
    const sibling = spawn(
      process.execPath,
      ["-e", "setInterval(()=>{},1000)"],
      { detached: true, stdio: "ignore" },
    );
    const siblingClosed = new Promise((resolve) =>
      sibling.once("close", resolve),
    );
    let grandchild;
    try {
      // Paths are argv data. Neither executable fixture interpolates input into
      // source, shell syntax or another program's command string.
      const source =
        "const child=require('node:child_process').spawn(process.execPath,['-e','setInterval(()=>{},1000)'],{stdio:'ignore'});require('node:fs').writeFileSync(process.argv[1],String(child.pid));child.unref();process.exit(3)";
      const result = await runCommand({
        executable: process.execPath,
        args: ["-e", source, path.join(root, "grandchild.pid")],
        cwd: root,
        env: childEnvironment(),
        logRoot: root,
        label: "early-exit",
        timeoutMs: 2_000,
        maxStdout: 100,
        deadline: performance.now() + 5_000,
      });
      grandchild = Number(
        fs.readFileSync(path.join(root, "grandchild.pid"), "utf8"),
      );
      assert.ok(Number.isSafeInteger(grandchild) && grandchild > 1);
      assert.equal(result.exitCode, 3);
      const state = spawnSync("ps", ["-p", String(grandchild), "-o", "stat="], {
        encoding: "utf8",
        timeout: 1_000,
        maxBuffer: 1024,
      });
      assert.ok(
        state.status === 1 || /^\s*Z/.test(state.stdout),
        "owned grandchild must be absent or stopped zombie, never running",
      );
      assert.equal(process.kill(sibling.pid, 0), true);
    } finally {
      if (grandchild) {
        try {
          process.kill(grandchild, "SIGKILL");
        } catch (error) {
          if (error.code !== "ESRCH") throw error;
        }
      }
      await terminateGroup(sibling.pid);
      await siblingClosed;
      fs.rmSync(root, { recursive: true });
    }
  });
}

if (process.argv[1] && path.resolve(process.argv[1]) === SCRIPT) {
  if (process.argv.length === 3 && process.argv[2] === "--self-test")
    await selfTest();
  else if (process.argv.length === 2)
    await main().catch((error) => {
      console.error(
        `SDK lock worker rejected before publication: ${sha256(Buffer.from(String(error.stack ?? error)))}`,
      );
      process.exitCode = 1;
    });
  else {
    console.error("Unsupported arguments");
    process.exitCode = 1;
  }
}
