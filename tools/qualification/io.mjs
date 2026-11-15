// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import { createHash } from "node:crypto";
import { createReadStream } from "node:fs";
import {
  access,
  readFile,
  writeFile,
  rename,
  stat,
  readdir,
  readlink,
  realpath,
} from "node:fs/promises";
import { constants } from "node:fs";
import { join, delimiter } from "node:path";
import { spawn } from "node:child_process";

export const MAX_LOG_BYTES = 16 * 1024 * 1024;
export const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
export async function readJson(path) {
  const metadata = await stat(path);
  if (!metadata.isFile() || metadata.size > 1024 * 1024)
    throw new Error(`Invalid/oversized JSON input: ${path}`);
  return JSON.parse(await readFile(path, "utf8"));
}
export async function writeJson(path, value) {
  await writeFile(`${path}.pending`, `${JSON.stringify(value, null, 2)}\n`, {
    mode: 0o600,
    flag: "wx",
  });
  await rename(`${path}.pending`, path);
}
export async function fileIdentity(path) {
  const canonical = await realpath(path);
  const metadata = await stat(canonical);
  if (!metadata.isFile() || metadata.size > 1024 ** 3)
    throw new Error(`Invalid/oversized artifact: ${path}`);
  const hash = createHash("sha256");
  let bytes = 0;
  for await (const chunk of createReadStream(canonical)) {
    bytes += chunk.length;
    if (bytes > 1024 ** 3)
      throw new Error(`Artifact grew beyond byte limit: ${path}`);
    hash.update(chunk);
  }
  if (bytes !== metadata.size)
    throw new Error(`Artifact size changed while hashing: ${path}`);
  return { path: canonical, bytes: metadata.size, sha256: hash.digest("hex") };
}

/** Fingerprints only: never copy user repository credentials or init-script contents into receipts. */
export async function configurationSnapshot(gradleHome, mavenHome) {
  const files = [
    join(gradleHome, "gradle.properties"),
    join(gradleHome, "init.gradle"),
    join(gradleHome, "init.gradle.kts"),
    join(mavenHome, "settings.xml"),
    join(mavenHome, "settings-security.xml"),
  ];
  try {
    const scripts = (await readdir(join(gradleHome, "init.d"))).filter((name) =>
      /\.gradle(?:\.kts)?$/.test(name),
    );
    if (scripts.length > 128)
      throw new Error("Init script inventory limit exceeded");
    files.push(...scripts.map((name) => join(gradleHome, "init.d", name)));
  } catch (error) {
    if (error.code !== "ENOENT") throw error;
  }
  const result = [];
  for (const path of files.sort()) {
    try {
      if ((await stat(path)).size > 1024 * 1024)
        throw new Error(`Configuration metadata limit exceeded: ${path}`);
      result.push(await fileIdentity(path));
    } catch (error) {
      if (error.code === "ENOENT") result.push({ path, absent: true });
      else throw error;
    }
  }
  return result;
}
export async function executable(name) {
  for (const directory of (process.env.PATH ?? "").split(delimiter)) {
    if (!directory.startsWith("/")) continue;
    const path = join(directory, name);
    try {
      await access(path, constants.X_OK);
      return await realpath(path);
    } catch {
      /* next PATH directory */
    }
  }
  throw new Error(`Required preinstalled tool unavailable: ${name}`);
}

/** Bounded direct argv execution; never invokes a shell. Logs remain private to the receipt folder. */
export function command(
  argv,
  {
    cwd,
    env = process.env,
    timeoutMs = 10000,
    maxBytes = MAX_LOG_BYTES,
    onSpawn,
  } = {},
) {
  return new Promise((resolve, reject) => {
    const child = spawn(argv[0], argv.slice(1), {
      cwd,
      env,
      stdio: ["ignore", "pipe", "pipe"],
    });
    onSpawn?.(child);
    let bytes = 0;
    let failure;
    const stdout = [];
    const stderr = [];
    const stop = (error) => {
      failure ??= error;
      child.kill("SIGKILL");
      child.stdout.destroy();
      child.stderr.destroy();
    };
    const timer = setTimeout(
      () => stop(new Error(`Command deadline exceeded: ${argv[0]}`)),
      timeoutMs,
    );
    for (const [stream, buffers] of [
      [child.stdout, stdout],
      [child.stderr, stderr],
    ]) {
      stream.on("data", (chunk) => {
        bytes += chunk.length;
        if (bytes > maxBytes)
          stop(new Error(`Command output exceeds limit: ${argv[0]}`));
        else buffers.push(chunk);
      });
    }
    child.once("error", (error) => {
      failure ??= error;
    });
    child.once("close", (code, signal) => {
      clearTimeout(timer);
      if (failure) reject(failure);
      else
        resolve({
          code,
          signal,
          stdout: Buffer.concat(stdout).toString(),
          stderr: Buffer.concat(stderr).toString(),
        });
    });
  });
}

export async function processesInNamespace(namespace) {
  const found = [];
  for (const entry of await readdir("/proc")) {
    if (!/^\d+$/.test(entry)) continue;
    try {
      if ((await readlink(`/proc/${entry}/ns/pid`)) === namespace)
        found.push(Number(entry));
    } catch (error) {
      if (!["ENOENT", "ESRCH", "EACCES", "EPERM"].includes(error.code))
        throw error;
    }
  }
  return found;
}

/** Never stop a foreign daemon or substitute an isolated Gradle home. */
export async function requireNoGradleDaemons(version) {
  const found = [];
  for (const entry of await readdir("/proc")) {
    if (!/^\d+$/.test(entry)) continue;
    try {
      const args = (await readFile(`/proc/${entry}/cmdline`, "utf8")).split(
        "\0",
      );
      const marker = args.indexOf(
        "org.gradle.launcher.daemon.bootstrap.GradleDaemon",
      );
      if (marker >= 0 && args[marker + 1] === version)
        found.push(Number(entry));
    } catch (error) {
      if (!["ENOENT", "ESRCH"].includes(error.code)) throw error;
    }
  }
  if (found.length)
    throw new Error(
      `Foreign Gradle ${version} daemons must exit before qualification: ${found.join(", ")}`,
    );
}
