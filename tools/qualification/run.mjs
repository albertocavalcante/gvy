#!/usr/bin/env node
// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import {
  mkdir,
  readlink,
  readdir,
  writeFile,
  stat,
  open,
  access,
} from "node:fs/promises";
import { join, dirname, isAbsolute } from "node:path";
import { fileURLToPath } from "node:url";
import { homedir, release, arch, endianness } from "node:os";
import { isDeepStrictEqual } from "node:util";
import {
  validateManifest,
  qualificationReceipt,
  validateExecutionHost,
} from "./core.mjs";
import {
  command,
  delay,
  executable,
  fileIdentity,
  readJson,
  writeJson,
  processesInNamespace,
  requireNoGradleDaemons,
  configurationSnapshot,
} from "./io.mjs";
import { ioUringFilter } from "./seccomp.mjs";

const source = dirname(fileURLToPath(import.meta.url));

function argumentsOf(argv) {
  const options = {};
  while (argv.length) {
    const key = argv.shift();
    if (key === "--probe") {
      if (options.probe) throw new Error("Duplicate --probe");
      options.probe = true;
    } else if (key === "--seccomp-helper-sha256") {
      if (options.helperHash || !/^[a-f0-9]{64}$/.test(argv[0] ?? ""))
        throw new Error("Expected unique seccomp helper SHA256");
      options.helperHash = argv.shift();
    } else if (["--manifest", "--output", "--seccomp-helper"].includes(key)) {
      if (options[key.slice(2)] || !isAbsolute(argv[0] ?? ""))
        throw new Error(`Expected unique absolute ${key} path`);
      options[key.slice(2)] = argv.shift();
    } else throw new Error(`Unknown option: ${key}`);
  }
  if (
    !options.output ||
    !options["seccomp-helper"] ||
    !options.helperHash ||
    Boolean(options.probe) === Boolean(options.manifest)
  )
    throw new Error(
      "Usage: run.mjs (--probe | --manifest /absolute/input.json) --output /new/private/directory --seccomp-helper /absolute/launcher --seccomp-helper-sha256 SHA256",
    );
  return options;
}

async function waitJson(path, milliseconds = 15000, ended = () => false) {
  const deadline = Date.now() + milliseconds;
  while (Date.now() < deadline) {
    try {
      return await readJson(path);
    } catch (error) {
      if (error.code !== "ENOENT") throw error;
    }
    if (ended()) throw new Error(`Namespace exited before writing ${path}`);
    await delay(50);
  }
  throw new Error(`Timed out waiting for ${path}`);
}

async function namespaceRun(mode, directory, input, forced = false) {
  for (const artifact of [
    input.ioUringEnforcement.helper,
    input.ioUringEnforcement.profile,
  ])
    if (!isDeepStrictEqual(await fileIdentity(artifact.path), artifact))
      throw new Error(
        "Enforcement artifact changed before namespace execution",
      );
  await mkdir(directory, { mode: 0o700 });
  const inputPath = join(directory, "input.json");
  await writeJson(inputPath, input);
  let processHandle;
  const execution = command(
    [
      input.tools.unshare,
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
      process.execPath,
      join(source, "namespace.mjs"),
      mode,
      directory,
      inputPath,
    ],
    {
      timeoutMs: ((input.deadlineSeconds ?? 15) + 5) * 1000,
      onSpawn: (child) => {
        processHandle = child;
      },
    },
  );
  // Attach rejection handling immediately; diagnostics are collected below even if readiness fails.
  let ended = false;
  const completed = execution
    .then(
      (value) => ({ value }),
      (error) => ({ error: error.message }),
    )
    .finally(() => {
      ended = true;
    });
  let namespace;
  let probe;
  let observed = [];
  let failure;
  try {
    ({ namespace } = await waitJson(
      join(directory, "namespace-ready.json"),
      15000,
      () => ended,
    ));
    observed = await processesInNamespace(namespace.pidNamespace);
    if (!observed.length)
      throw new Error(
        "Host cannot observe the owned PID namespace; cleanup proof unavailable",
      );
    await writeFile(join(directory, "host-admit"), "", {
      flag: "wx",
      mode: 0o600,
    });
    if (mode === "probe") {
      probe = await waitJson(
        join(directory, "probe-ready.json"),
        15000,
        () => ended,
      );
      observed = await processesInNamespace(namespace.pidNamespace);
      if (observed.length < 2)
        throw new Error(
          "Detached probe child was not observed in the namespace",
        );
      if (forced) processHandle.kill("SIGKILL");
      else
        await writeFile(join(directory, "probe-release"), "", {
          flag: "wx",
          mode: 0o600,
        });
    }
  } catch (error) {
    failure = error.message;
    processHandle?.kill("SIGKILL");
  }
  const outcome = await completed;
  let remaining = namespace
    ? await processesInNamespace(namespace.pidNamespace)
    : null;
  const drainDeadline = Date.now() + 5000;
  while (remaining?.length && Date.now() < drainDeadline) {
    await delay(50);
    remaining = await processesInNamespace(namespace.pidNamespace);
  }
  const cleanup = {
    verified: Boolean(namespace && observed.length && remaining.length === 0),
    observedHostPids: observed,
    remainingHostPids: remaining,
    forced,
  };
  let result;
  try {
    result = await readJson(join(directory, "namespace-result.json"));
  } catch (error) {
    if (error.code !== "ENOENT") throw error;
  }
  if (
    failure ||
    outcome.error ||
    (!forced && outcome.value?.code !== 0) ||
    !cleanup.verified ||
    (!forced && !result)
  ) {
    let diagnostic;
    try {
      diagnostic = await readJson(join(directory, "namespace-error.json"));
    } catch {
      /* startup may fail before diagnostics */
    }
    throw Object.assign(
      new Error(
        `Namespace run failed: ${diagnostic?.error ?? failure ?? outcome.error ?? JSON.stringify(outcome.value)}`,
      ),
      { cleanup, outcome, diagnostic },
    );
  }
  for (const artifact of [
    input.ioUringEnforcement.helper,
    input.ioUringEnforcement.profile,
  ])
    if (!isDeepStrictEqual(await fileIdentity(artifact.path), artifact))
      throw Object.assign(
        new Error("Enforcement artifact changed during namespace execution"),
        { cleanup },
      );
  return { ...result, probe, namespace, cleanup, outcome: outcome.value };
}

async function inputs(manifest) {
  await requireNoGradleDaemons(manifest.gradle.version);
  const artifacts = {};
  for (const key of ["serverJar", "extensionVsix"]) {
    artifacts[key] = await fileIdentity(manifest.artifacts[key].path);
    if (artifacts[key].sha256 !== manifest.artifacts[key].sha256)
      throw new Error(`${key} hash mismatch`);
  }
  const launcher = join(
    manifest.gradle.installation,
    "lib",
    `gradle-launcher-${manifest.gradle.version}.jar`,
  );
  const gradleFiles = [];
  let total = 0;
  async function inventory(directory) {
    for (const entry of await readdir(directory, { withFileTypes: true })) {
      const path = join(directory, entry.name);
      if (entry.isSymbolicLink())
        throw new Error(
          `Distribution contains a symlink; provide a real installation tree: ${path}`,
        );
      if (entry.isDirectory()) await inventory(path);
      else {
        const identity = await fileIdentity(path);
        gradleFiles.push(identity);
        total += identity.bytes;
        if (gradleFiles.length > 10000 || total > 1024 ** 3)
          throw new Error("Gradle installation inventory limit exceeded");
      }
    }
  }
  await fileIdentity(launcher);
  await inventory(manifest.gradle.installation);
  const java = await fileIdentity(join(manifest.javaHome, "bin/java"));
  const javaRelease = await fileIdentity(join(manifest.javaHome, "release"));
  const javaModules = await fileIdentity(
    join(manifest.javaHome, "lib/modules"),
  );
  const javaVersion = await command([java.path, "-Xmx64m", "-version"]);
  if (javaVersion.code !== 0)
    throw new Error("Selected JDK version check failed");
  const gradleUserHome =
    process.env.GRADLE_USER_HOME ?? join(homedir(), ".gradle");
  if (!isAbsolute(gradleUserHome))
    throw new Error("Effective Gradle user home must be absolute");
  for (const key of [
    "JAVA_TOOL_OPTIONS",
    "JDK_JAVA_OPTIONS",
    "JAVA_OPTS",
    "GRADLE_OPTS",
  ]) {
    if (/gradle\.user\.home/.test(process.env[key] ?? ""))
      throw new Error(
        `Explicit gradle.user.home in ${key} requires a separately reviewed effective-home resolver`,
      );
  }
  return {
    artifacts,
    gradle: {
      version: manifest.gradle.version,
      installation: manifest.gradle.installation,
      files: gradleFiles,
    },
    java: {
      executable: java,
      release: javaRelease,
      modules: javaModules,
      versionOutput: javaVersion.stderr + javaVersion.stdout,
    },
    gradleUserHome,
    gradleUserHomeWasExplicit: process.env.GRADLE_USER_HOME !== undefined,
    userConfiguration: await configurationSnapshot(
      gradleUserHome,
      join(homedir(), ".m2"),
    ),
  };
}

let directory;
const receiptContext = { startedAt: new Date().toISOString(), probes: [] };
try {
  const options = argumentsOf(process.argv.slice(2));
  const executableFile = await open(process.execPath, "r");
  const magic = Buffer.alloc(4);
  try {
    await executableFile.read(magic, 0, 4, 0);
  } finally {
    await executableFile.close();
  }
  let interop = false;
  try {
    await access("/proc/sys/fs/binfmt_misc/WSLInterop");
    interop = true;
  } catch (error) {
    if (error.code !== "ENOENT") throw error;
  }
  const hostBoundary = validateExecutionHost(
    {
      platform: process.platform,
      uid: process.getuid?.(),
      euid: process.geteuid?.(),
      elf: magic.equals(Buffer.from([0x7f, 0x45, 0x4c, 0x46])),
      nodeMajor: Number(process.versions.node.split(".")[0]),
      kernel: release(),
      interop,
    },
    options.probe,
  );
  directory = options.output;
  await mkdir(directory, { mode: 0o700 });
  if ((await stat(directory)).mode & 0o077)
    throw new Error("Receipt directory must be private");
  const helper = await fileIdentity(options["seccomp-helper"]);
  if (helper.sha256 !== options.helperHash)
    throw new Error("Seccomp helper hash mismatch");
  const helperFile = await open(helper.path, "r");
  const header = Buffer.alloc(20);
  try {
    await helperFile.read(header, 0, header.length, 0);
  } finally {
    await helperFile.close();
  }
  if (
    !header.subarray(0, 4).equals(Buffer.from([0x7f, 0x45, 0x4c, 0x46])) ||
    header[4] !== 2 ||
    header[5] !== 1 ||
    header.readUInt16LE(18) !== 62
  )
    throw new Error(
      "Seccomp helper must be an explicit x86_64 ELF64 little-endian binary",
    );
  const profilePath = join(directory, "io-uring-denial.bpf");
  await writeFile(profilePath, ioUringFilter(arch(), endianness()), {
    flag: "wx",
    mode: 0o600,
  });
  const ioUringEnforcement = {
    helper,
    profile: await fileIdentity(profilePath),
    abi: "linux-x86_64-lp64-le",
    deniedErrno: "ENOSYS",
    inheritedDescriptors: "reject ring stdio; close_range >=3 before exec",
    capabilityPolicy:
      "clear and verify ambient/effective/permitted/inheritable after namespace setup",
  };
  receiptContext.ioUringEnforcement = ioUringEnforcement;
  const tools = {};
  const versions = {};
  for (const name of ["unshare", "ip", "strace"]) {
    tools[name] = await executable(name);
    const version = await command([
      tools[name],
      name === "ip" ? "-Version" : "--version",
    ]);
    if (version.code !== 0) throw new Error(`${name} version probe failed`);
    versions[name] = {
      executable: await fileIdentity(tools[name]),
      output: (version.stdout + version.stderr).slice(0, 2048),
    };
  }
  const base = {
    uid: process.getuid(),
    tools,
    ioUringEnforcement,
    parentNetworkNamespace: await readlink("/proc/self/ns/net"),
    parentPidNamespace: await readlink("/proc/self/ns/pid"),
    parentMountNamespace: await readlink("/proc/self/ns/mnt"),
  };
  const harnessFiles = [];
  for (const name of [
    ...(await readdir(source))
      .filter((name) => /\.(?:mjs|c)$/.test(name))
      .sort(),
    "../logging/lsp-protocol.mjs",
  ]) {
    harnessFiles.push({ name, ...(await fileIdentity(join(source, name))) });
  }
  const host = {
    platform: process.platform,
    kernel: release(),
    architecture: arch(),
    uid: process.getuid(),
    node: process.version,
    nodeExecutable: await fileIdentity(process.execPath),
    tools: versions,
    harnessFiles,
    ...hostBoundary,
  };
  receiptContext.host = host;
  let manifest;
  let identities;
  const manifestPath = join(directory, "manifest.json");
  if (!options.probe) {
    manifest = validateManifest(await readJson(options.manifest));
    identities = await inputs(manifest);
    await writeJson(manifestPath, manifest);
    receiptContext.identities = identities;
    receiptContext.manifest = await fileIdentity(manifestPath);
  }
  // Validate artifact inputs before any namespace/negative-control side effects.
  // Both graceful and forced teardown are proved before candidate execution.
  const probes = receiptContext.probes;
  for (const forced of [false, true])
    probes.push(
      await namespaceRun(
        "probe",
        join(directory, forced ? "probe-forced" : "probe-normal"),
        base,
        forced,
      ),
    );
  const lifetimeDirectory = join(directory, "observer-lifetime");
  const lifetimeOutcome = await command(
    [
      process.execPath,
      join(source, "lifetime-control.mjs"),
      "host",
      lifetimeDirectory,
      ioUringEnforcement.helper.path,
      ioUringEnforcement.profile.path,
    ],
    { timeoutMs: 25000 },
  );
  let lifetimeHost;
  try {
    lifetimeHost = await readJson(join(lifetimeDirectory, "host-result.json"));
  } catch (error) {
    if (error.code !== "ENOENT") throw error;
  }
  if (lifetimeOutcome.code !== 0 || !lifetimeHost?.cleanup?.verified)
    throw Object.assign(
      new Error(
        `Observer lifetime control failed: ${lifetimeOutcome.stderr || "Missing successful host cleanup receipt"}`,
      ),
      { cleanup: lifetimeHost?.cleanup },
    );
  const lifetimeControl = {
    verified: true,
    ...(await readJson(join(lifetimeDirectory, "init-result.json"))),
    cleanup: lifetimeHost.cleanup,
  };
  receiptContext.lifetimeControl = lifetimeControl;
  if (options.probe)
    await writeJson(join(directory, "receipt.json"), {
      schemaVersion: 1,
      kind: "mechanism-probe",
      startedAt: receiptContext.startedAt,
      completedAt: new Date().toISOString(),
      host,
      probes,
      lifetimeControl,
      candidateQualified: false,
    });
  else {
    await requireNoGradleDaemons(manifest.gradle.version);
    const run = await namespaceRun("candidate", join(directory, "candidate"), {
      ...base,
      manifestPath,
      deadlineSeconds: manifest.deadlineSeconds,
    });
    const userConfigurationAfter = await configurationSnapshot(
      identities.gradleUserHome,
      join(homedir(), ".m2"),
    );
    const configurationPreserved = isDeepStrictEqual(
      identities.userConfiguration,
      userConfigurationAfter,
    );
    const artifactsAfter = {};
    for (const key of ["serverJar", "extensionVsix"])
      artifactsAfter[key] = await fileIdentity(manifest.artifacts[key].path);
    const artifactsPreserved = isDeepStrictEqual(
      identities.artifacts,
      artifactsAfter,
    );
    const receipt = qualificationReceipt({
      startedAt: receiptContext.startedAt,
      completedAt: new Date().toISOString(),
      host,
      identities,
      manifest: await fileIdentity(manifestPath),
      probes,
      lifetimeControl,
      ...run,
      userConfigurationAfter,
      configurationPreserved,
      artifactsAfter,
      artifactsPreserved,
    });
    await writeJson(join(directory, "receipt.json"), receipt);
    if (
      !receipt.networkObservationPassed ||
      !configurationPreserved ||
      !artifactsPreserved ||
      receipt.scenarios.length !== manifest.scenarios.length ||
      receipt.scenarios.some((scenario) => !scenario.passed)
    )
      process.exitCode = 1;
  }
} catch (error) {
  if (directory)
    await writeJson(join(directory, "failure.json"), {
      schemaVersion: 1,
      ...receiptContext,
      completedAt: new Date().toISOString(),
      error: error.message,
      cleanup: error.cleanup ?? null,
      outcome: error.outcome ?? null,
      diagnostic: error.diagnostic ?? null,
      completeAcceptanceMatrixQualified: false,
    }).catch(() => {});
  console.error(error.message);
  process.exitCode = 1;
}
