// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
// @ts-check

const fs = require("node:fs");
const path = require("node:path");
const os = require("node:os");
const crypto = require("node:crypto");
const zlib = require("node:zlib");
const { isDeepStrictEqual } = require("node:util");
const { spawn, spawnSync } = require("node:child_process");
const {
  provenanceFromProperties,
  parseVersionProperties,
} = require("./jar-provenance.js");
const { smokeServerCommand } = require("./smoke-server.js");

/** @typedef {{id: number, run_attempt: number, head_sha: string, head_branch: string, event: string, path: string, status: string, conclusion: string|null}} WorkflowRun */
/** @typedef {{id: number, name: string, expired: boolean, digest: string}} Artifact */
/** @typedef {{schemaVersion: number, repo: string, ref: string, sha: string, success: boolean, stage: string, runId?: number, runAttempt?: number, runStatus?: string, runConclusion?: string|null, hashes?: {jar: string, client: string, vsix: string}, jar?: string, vsix?: string, artifacts?: {id: number, name: string, digest: string}[], noop?: boolean, editorMayHaveChanged?: boolean, error?: string, installed?: boolean, createdAt?: string, verifiedAt?: string, runtimeVersion?: string, java?: string, command?: string, wrapper?: string, installer?: string, binDir?: string, resumeArgs?: string[]}} Receipt */
const LIMIT = 512 * 1024 * 1024;
/** @param {Buffer} b */
function hash(b) {
  return crypto.createHash("sha256").update(b).digest("hex");
}
/** Open once without following symlinks; validate and read that descriptor only.
 * @param {string} file @param {number} limit @param {boolean} [executable] */
function readRegularFile(file, limit, executable = false) {
  const fd = fs.openSync(
    file,
    fs.constants.O_RDONLY | fs.constants.O_NOFOLLOW | fs.constants.O_NONBLOCK,
  );
  try {
    const before = fs.fstatSync(fd);
    if (!before.isFile()) throw new Error(`Not a regular file: ${file}`);
    if (before.size > limit)
      throw new Error(`File exceeds size limit: ${file}`);
    if (executable && !(before.mode & 0o111))
      throw new Error(`Wrapper is not executable: ${file}`);
    const bytes = Buffer.alloc(before.size + 1);
    let offset = 0;
    while (offset < bytes.length) {
      const read = fs.readSync(fd, bytes, offset, bytes.length - offset, null);
      if (read === 0) break;
      offset += read;
    }
    const after = fs.fstatSync(fd);
    if (
      offset !== before.size ||
      before.size !== after.size ||
      before.mtimeMs !== after.mtimeMs ||
      before.ctimeMs !== after.ctimeMs
    )
      throw new Error(`File changed while reading: ${file}`);
    return bytes.subarray(0, offset);
  } finally {
    fs.closeSync(fd);
  }
}
/** Publish a private candidate only if the destination is absent; validate any existing file through its descriptor.
 * @param {string} candidate @param {string} destination @param {Buffer} expected @param {number} limit @param {boolean} [executable] */
function publishFile(
  candidate,
  destination,
  expected,
  limit,
  executable = false,
) {
  try {
    fs.linkSync(candidate, destination);
  } catch (error) {
    if (/** @type {NodeJS.ErrnoException} */ (error).code !== "EEXIST")
      throw error;
  }
  const actual = readRegularFile(destination, limit, executable);
  if (hash(actual) !== hash(expected))
    throw new Error(`Existing runtime differs: ${destination}`);
}
/** @param {string} file @param {object} data */
function writeJson(file, data) {
  const tmp = `${file}.${process.pid}.${crypto.randomBytes(6).toString("hex")}.tmp`;
  /** @type {Error | null} */ let failure = null;
  try {
    fs.writeFileSync(tmp, JSON.stringify(data, null, 2) + "\n", {
      mode: 0o600,
      flag: "wx",
    });
    fs.renameSync(tmp, file);
  } catch (error) {
    failure = error instanceof Error ? error : new Error(String(error));
    throw failure;
  } finally {
    try {
      fs.rmSync(tmp, { force: true });
    } catch (error) {
      const message = `Failed to clean ${tmp}: ${error instanceof Error ? error.message : String(error)}`;
      if (failure) Object.assign(failure, { cleanupError: message });
      // A cleanup failure after success must not disappear.
      // eslint-disable-next-line no-unsafe-finally
      else throw new Error(message, { cause: error });
    }
  }
}
/** @param {string[]} args @param {number} [timeoutMs] */
function gh(args, timeoutMs = 30000) {
  const r = spawnSync("gh", ["api", ...args], {
    encoding: "utf8",
    maxBuffer: 16 * 1024 * 1024,
    timeout: timeoutMs,
    killSignal: "SIGKILL",
  });
  if (r.error || r.status !== 0)
    throw new Error(`gh api failed: ${r.error?.message || r.stderr.trim()}`);
  return JSON.parse(r.stdout);
}
/** @param {WorkflowRun[]} runs @param {string} sha */
function selectRun(runs, sha) {
  return (
    runs
      .filter(
        (r) =>
          r.head_sha === sha &&
          r.head_branch === "main" &&
          r.event === "push" &&
          r.path === ".github/workflows/extension.yml",
      )
      .sort((a, b) => b.id - a.id)[0] || null
  );
}
/** @param {Artifact[]} artifacts */
function validateArtifacts(artifacts) {
  return ["current-editor-server", "vsix"].map((name) => {
    const matches = artifacts.filter((a) => a.name === name);
    if (matches.length !== 1)
      throw new Error(`Expected one ${name} artifact, found ${matches.length}`);
    const a = matches[0];
    if (
      a.expired ||
      !/^sha256:[a-f0-9]{64}$/.test(a.digest || "") ||
      !Number.isSafeInteger(a.id)
    )
      throw new Error(`Invalid or expired ${name} artifact`);
    return a;
  });
}
/** Read exactly one named entry, with bounds before decompression. Never writes archive paths.
 * @param {Buffer} zip @param {(name: string) => boolean} match @param {number} limit */
function zipEntry(zip, match, limit) {
  if (zip.length > LIMIT || zip.length < 22)
    throw new Error("ZIP size outside limit");
  let end = -1;
  for (let i = zip.length - 22; i >= Math.max(0, zip.length - 65557); i--) {
    if (
      zip.readUInt32LE(i) === 0x06054b50 &&
      i + 22 + zip.readUInt16LE(i + 20) === zip.length
    ) {
      end = i;
      break;
    }
  }
  if (end < 0 || zip.readUInt16LE(end + 4) || zip.readUInt16LE(end + 6))
    throw new Error("Invalid ZIP directory");
  const count = zip.readUInt16LE(end + 10),
    size = zip.readUInt32LE(end + 12),
    start = zip.readUInt32LE(end + 16);
  if (
    count === 0xffff ||
    start + size !== end ||
    count !== zip.readUInt16LE(end + 8)
  )
    throw new Error("Unsupported ZIP directory");
  let pos = start;
  const matches = [];
  for (let i = 0; i < count; i++) {
    if (pos + 46 > end || zip.readUInt32LE(pos) !== 0x02014b50)
      throw new Error("Invalid ZIP entry");
    const nameLength = zip.readUInt16LE(pos + 28),
      extra = zip.readUInt16LE(pos + 30),
      comment = zip.readUInt16LE(pos + 32);
    const next = pos + 46 + nameLength + extra + comment;
    if (next > end) throw new Error("Invalid ZIP entry length");
    const name = zip.subarray(pos + 46, pos + 46 + nameLength).toString("utf8");
    if (match(name))
      matches.push({
        flags: zip.readUInt16LE(pos + 8),
        method: zip.readUInt16LE(pos + 10),
        compressed: zip.readUInt32LE(pos + 20),
        size: zip.readUInt32LE(pos + 24),
        offset: zip.readUInt32LE(pos + 42),
      });
    pos = next;
  }
  if (pos !== end || matches.length !== 1)
    throw new Error("Expected exactly one matching ZIP entry");
  const e = matches[0];
  if (
    e.flags & 1 ||
    e.size > limit ||
    e.compressed > LIMIT ||
    e.offset + 30 > start ||
    zip.readUInt32LE(e.offset) !== 0x04034b50
  )
    throw new Error("Invalid or oversized ZIP entry");
  const data =
    e.offset +
    30 +
    zip.readUInt16LE(e.offset + 26) +
    zip.readUInt16LE(e.offset + 28);
  if (data + e.compressed > start) throw new Error("ZIP entry outside archive");
  const compressed = zip.subarray(data, data + e.compressed);
  const result =
    e.method === 0
      ? compressed
      : e.method === 8
        ? zlib.inflateRawSync(compressed, { maxOutputLength: limit })
        : null;
  if (!result || result.length !== e.size)
    throw new Error("Invalid ZIP entry content");
  return result;
}
/** Download to a private file with a hard deadline and bounded output.
 * @param {string} endpoint @param {string} file @param {number} [timeoutMs] */
function download(endpoint, file, timeoutMs = 300000) {
  return new Promise((resolve, reject) => {
    const child = spawn("gh", ["api", endpoint], {
      stdio: ["ignore", "pipe", "pipe"],
    });
    const output = fs.createWriteStream(file, { flags: "wx", mode: 0o600 });
    let size = 0,
      stderr = "",
      childDone = false,
      outputDone = false,
      settled = false;
    const timer = setTimeout(
      () => fail(new Error("Artifact download timed out")),
      timeoutMs,
    );
    /** @param {Error} error */
    function fail(error) {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      child.kill("SIGKILL");
      child.stdout.destroy();
      output.destroy();
      reject(error);
    }
    function complete() {
      if (!settled && childDone && outputDone) {
        settled = true;
        clearTimeout(timer);
        resolve(undefined);
      }
    }
    child.stderr.on("data", (c) => {
      stderr = (stderr + c.toString()).slice(-4096);
    });
    child.stdout.on("data", (c) => {
      size += c.length;
      if (size > LIMIT) fail(new Error("Artifact archive exceeds size limit"));
    });
    child.on("error", fail);
    child.stdout.on("error", fail);
    output.on("error", fail);
    output.on("finish", () => {
      outputDone = true;
      complete();
    });
    child.on("close", (code) => {
      if (code !== 0)
        return fail(new Error(`Artifact download failed: ${stderr}`));
      childDone = true;
      complete();
    });
    child.stdout.pipe(output);
  });
}
/** @param {string} command @param {string[]} args @param {number} [timeoutMs] */
function run(command, args, timeoutMs = 30000) {
  const r = spawnSync(command, args, {
    encoding: "utf8",
    timeout: timeoutMs,
    killSignal: "SIGKILL",
    maxBuffer: 1024 * 1024,
  });
  if (r.error || r.status !== 0)
    throw new Error(`${command} failed: ${r.error?.message || r.stderr}`);
  return r.stdout;
}
/** @param {string} engine @param {string} version */
function compatible(engine, version) {
  const m = /^\^(\d+)\.(\d+)\.(\d+)$/.exec(engine),
    v = /^(\d+)\.(\d+)\.(\d+)/.exec(version);
  if (!m || !v) return false;
  const a = m.slice(1).map(Number),
    b = v.slice(1).map(Number);
  return a[0] === b[0] && (b[1] > a[1] || (b[1] === a[1] && b[2] >= a[2]));
}
/** Resolve a command as a POSIX PATH search does: skip missing and non-executable files.
 * @param {string[]} directories @param {string} name @returns {string|null} */
function findOnPath(directories, name) {
  for (const directory of directories) {
    const file = path.join(directory, name);
    try {
      if (!fs.statSync(file).isFile()) continue;
      fs.accessSync(file, fs.constants.X_OK);
      return file;
    } catch (error) {
      const code = /** @type {NodeJS.ErrnoException} */ (error).code;
      if (code !== "ENOENT" && code !== "EACCES" && code !== "ENOTDIR")
        throw error;
    }
  }
  return null;
}
/** @param {string} binDir */
function preflightCommandPath(binDir) {
  const directories = (process.env.PATH || "")
    .split(path.delimiter)
    .map((p) => path.resolve(p || "."));
  const index = directories.indexOf(binDir);
  if (index < 0) throw new Error(`Command directory is not on PATH: ${binDir}`);
  const shadow = findOnPath(directories.slice(0, index), "gvy");
  if (shadow)
    throw new Error(
      `Another gvy shadows ${path.join(binDir, "gvy")}: ${shadow}`,
    );
}
/** @param {string} link */
function verifyCommandPath(link) {
  const directories = (process.env.PATH || "")
    .split(path.delimiter)
    .map((p) => path.resolve(p || "."));
  if (findOnPath(directories, "gvy") !== link)
    throw new Error(
      `Installed command is not executable or is shadowed on PATH: ${link}`,
    );
}
/** @param {string} bin @param {string} target */
function checkLink(bin, target) {
  try {
    const s = fs.lstatSync(bin);
    if (!s.isSymbolicLink()) throw new Error(`Refusing to replace ${bin}`);
    const old = fs.readlinkSync(bin);
    if (path.resolve(path.dirname(bin), old) !== target)
      throw new Error(`Refusing to replace unrelated link ${bin}`);
    return true;
  } catch (e) {
    if (/** @type {NodeJS.ErrnoException} */ (e).code === "ENOENT")
      return false;
    throw e;
  }
}
/** @param {string} home @param {string} jarHash @param {string} clientHash @param {object} expectedManifest */
function installedMatches(home, jarHash, clientHash, expectedManifest) {
  try {
    /** @type {{identifier?: {id?: string}, location?: {scheme?: string, path: string}}[]} */
    const records = JSON.parse(
      fs.readFileSync(
        path.join(home, ".vscode/extensions/extensions.json"),
        "utf8",
      ),
    );
    const matches = records.filter(
      (r) => r.identifier?.id?.toLowerCase() === "albertocavalcante.gvy",
    );
    if (matches.length !== 1 || matches[0].location?.scheme !== "file")
      return false;
    const root = matches[0].location.path;
    const manifest = JSON.parse(
      fs.readFileSync(path.join(root, "package.json"), "utf8"),
    );
    if (!manifest || typeof manifest !== "object" || Array.isArray(manifest))
      return false;
    delete manifest.__metadata;
    return (
      isDeepStrictEqual(manifest, expectedManifest) &&
      hash(fs.readFileSync(path.join(root, "server/gls.jar"))) === jarHash &&
      hash(fs.readFileSync(path.join(root, "client/out/extension.js"))) ===
        clientHash
    );
  } catch {
    return false;
  }
}
/** @param {string} java @param {string} jar */
function wrapperText(java, jar) {
  const quote = (/** @type {string} */ s) => `'${s.replace(/'/g, "'\\''")}'`;
  return `#!/bin/sh\nexec ${quote(java)} -Xmx2g -jar ${quote(jar)} "$@"\n`;
}
/** @param {string} stable @param {string} state */
function checkStable(stable, state) {
  try {
    const prior = fs.lstatSync(stable);
    const target = path.resolve(path.dirname(stable), fs.readlinkSync(stable));
    const relative = path.relative(state, target);
    if (
      !prior.isSymbolicLink() ||
      !/^[a-f0-9]{40}\/gvy(?:-[a-f0-9]{64})?$/.test(relative)
    )
      throw new Error(`Refusing to replace unrelated ${stable}`);
  } catch (e) {
    if (/** @type {NodeJS.ErrnoException} */ (e).code !== "ENOENT") throw e;
  }
}
/** @param {Receipt} receipt @param {string} runtime @param {string} state @param {Options} options @param {string} java @param {string} editorVersion */
function cachedInstallMatches(
  receipt,
  runtime,
  state,
  options,
  java,
  editorVersion,
) {
  try {
    if (!receipt.hashes) return false;
    const jar = path.join(runtime, "gls.jar"),
      vsix = path.join(runtime, "gvy.vsix"),
      wrapper = receipt.wrapper || path.join(runtime, "gvy");
    if (
      path.dirname(wrapper) !== runtime ||
      !/^gvy(?:-[a-f0-9]{64})?$/.test(path.basename(wrapper))
    )
      return false;
    const jarBytes = readRegularFile(jar, LIMIT),
      vsixBytes = readRegularFile(vsix, LIMIT),
      wrapperBytes = readRegularFile(wrapper, 65536, true);
    const manifest = JSON.parse(
      zipEntry(
        vsixBytes,
        (n) => n === "extension/package.json",
        1024 * 1024,
      ).toString("utf8"),
    );
    if (!compatible(manifest.engines?.vscode, editorVersion))
      throw new Error(`Editor does not satisfy ${manifest.engines?.vscode}`);
    if (
      hash(jarBytes) !== receipt.hashes.jar ||
      hash(vsixBytes) !== receipt.hashes.vsix ||
      hash(
        zipEntry(vsixBytes, (n) => n === "extension/server/gls.jar", LIMIT),
      ) !== receipt.hashes.jar ||
      hash(
        zipEntry(
          vsixBytes,
          (n) => n === "extension/client/out/extension.js",
          16 * 1024 * 1024,
        ),
      ) !== receipt.hashes.client ||
      wrapperBytes.toString("utf8") !== wrapperText(java, jar) ||
      !installedMatches(
        os.homedir(),
        receipt.hashes.jar,
        receipt.hashes.client,
        manifest,
      )
    )
      return false;
    const stable = path.join(state, "gvy"),
      link = path.join(
        path.resolve(options.binDir || path.join(os.homedir(), ".local/bin")),
        "gvy",
      );
    if (!checkLink(stable, wrapper) || !checkLink(link, stable)) return false;
    verifyCommandPath(link);
    return true;
  } catch {
    return false;
  }
}
/** @typedef {{ref?: string|null, repo?: string|null, stateRoot?: string|null, binDir?: string|null, javaHome?: string|null, installer?: string|null, wait?: string|null, resume?: string|null, packageOnly?: boolean}} Options */
/** @param {Options} options */
async function installFromCi(options) {
  if (!["darwin", "linux"].includes(process.platform))
    throw new Error("CI installation supports macOS and Linux only");
  const state = path.resolve(
    options.stateRoot ||
      (process.platform === "darwin"
        ? path.join(
            os.homedir(),
            "Library/Application Support/gvy/source-installs",
          )
        : path.join(
            process.env.XDG_STATE_HOME ||
              path.join(os.homedir(), ".local/state"),
            "gvy/source-installs",
          )),
    "ci-v1",
  );
  const repo = options.repo || "albertocavalcante/gvy";
  let ref = options.ref || "main";
  const validRef = (/** @type {string} */ value) =>
    value === "main" || /^[a-f0-9]{40}$/.test(value);
  if (repo !== "albertocavalcante/gvy" || !validRef(ref))
    throw new Error(
      "Only albertocavalcante/gvy main or a full commit SHA is supported",
    );
  const wait = Number(options.wait || 0);
  if (!Number.isInteger(wait) || wait < 0 || wait > 3600)
    throw new Error("--wait must be an integer from 0 to 3600 seconds");
  fs.mkdirSync(state, { recursive: true, mode: 0o700 });
  const lock = path.join(state, ".lock");
  try {
    fs.mkdirSync(lock, { mode: 0o700 });
  } catch (e) {
    throw new Error(`CI install locked at ${lock}; inspect before removing`, {
      cause: e,
    });
  }
  let receiptPath = "",
    stage = "preflight";
  /** @type {Receipt | null} */ let receipt = null;
  /** @type {Receipt | null} */ let priorReceipt = null;
  /** @type {(Error & {stage?: string, installed?: boolean, receipt?: string, receiptError?: string, cleanupError?: string}) | null} */
  let failure = null;
  try {
    fs.writeFileSync(
      path.join(lock, "owner.json"),
      JSON.stringify({ pid: process.pid }),
    );
    const editor = options.installer || "code";
    if (editor !== "code") throw new Error("Only the code editor is supported");
    const editorVersion = run(editor, ["--version"]);
    const java = options.javaHome
      ? path.resolve(options.javaHome, "bin/java")
      : process.env.JAVA_HOME
        ? path.resolve(process.env.JAVA_HOME, "bin/java")
        : null;
    if (!java) throw new Error("Set --java-home or JAVA_HOME to a JDK 21+");
    const versionProbe = spawnSync(java, ["-Xmx64m", "-version"], {
      encoding: "utf8",
      timeout: 10000,
      killSignal: "SIGKILL",
    });
    const jv = /version "(\d+)/.exec(
      versionProbe.stderr || versionProbe.stdout || "",
    );
    if (versionProbe.status !== 0 || !jv || Number(jv[1]) < 21)
      throw new Error("JDK 21+ required");
    const binDir = path.resolve(
      options.binDir || path.join(os.homedir(), ".local/bin"),
    );
    const link = path.join(binDir, "gvy"),
      stable = path.join(state, "gvy");
    if (!options.packageOnly) {
      checkLink(link, stable);
      checkStable(stable, state);
      preflightCommandPath(binDir);
    }
    stage = "resolve";
    if (options.resume) {
      receiptPath = path.resolve(options.resume);
      if (path.dirname(receiptPath) !== state)
        throw new Error(
          "Resume receipt must belong to the selected state root",
        );
      receipt = JSON.parse(fs.readFileSync(receiptPath, "utf8"));
      if (receipt && !options.ref) ref = receipt.ref;
      if (
        !receipt ||
        !validRef(ref) ||
        receipt.schemaVersion !== 1 ||
        receipt.repo !== repo ||
        receipt.ref !== ref ||
        !/^[a-f0-9]{40}$/.test(receipt.sha)
      ) {
        receipt = null;
        throw new Error("Invalid resume receipt");
      }
    } else {
      const commit = gh([`repos/${repo}/commits/${encodeURIComponent(ref)}`]);
      if (
        !/^[a-f0-9]{40}$/.test(commit.sha) ||
        (ref !== "main" && commit.sha !== ref)
      )
        throw new Error("Invalid resolved commit SHA");
      receiptPath = path.join(state, `${commit.sha}.json`);
      if (fs.existsSync(receiptPath)) {
        const previous = JSON.parse(fs.readFileSync(receiptPath, "utf8"));
        if (
          previous?.schemaVersion !== 1 ||
          previous.repo !== repo ||
          previous.sha !== commit.sha
        )
          throw new Error(
            `Refusing to overwrite unknown receipt ${receiptPath}`,
          );
        priorReceipt = previous;
      }
      receipt = {
        schemaVersion: 1,
        repo,
        ref,
        sha: commit.sha,
        success: false,
        stage,
        createdAt: new Date().toISOString(),
      };
      writeJson(receiptPath, receipt);
    }
    if (!receipt) throw new Error("Missing selection receipt");
    if (options.resume) priorReceipt = { ...receipt };
    const pinned = receipt;
    receipt.java = java;
    receipt.installer = editor;
    receipt.binDir = binDir;
    receipt.resumeArgs = [
      "--from-ci",
      "--state-root",
      path.dirname(state),
      "--resume",
      receiptPath,
      "--java-home",
      path.dirname(path.dirname(java)),
      "--installer",
      editor,
      "--bin-dir",
      binDir,
      ...(options.packageOnly ? ["--package-only"] : []),
    ];
    receipt.success = false;
    delete receipt.error;
    delete receipt.installed;
    receipt.stage = stage = "select-run";
    writeJson(receiptPath, receipt);
    const deadline = Date.now() + wait * 1000;
    let selected;
    do {
      /** @type {{workflow_runs: WorkflowRun[]}} */
      const result = gh(
        [
          `repos/${repo}/actions/workflows/extension.yml/runs?event=push&branch=main&head_sha=${receipt.sha}&per_page=100`,
        ],
        wait ? Math.max(1, Math.min(30000, deadline - Date.now())) : 30000,
      );
      selected = receipt.runId
        ? result.workflow_runs.find(
            (r) => r.id === pinned.runId && r.run_attempt === pinned.runAttempt,
          )
        : selectRun(result.workflow_runs, receipt.sha);
      if (!selected && pinned.runId) {
        const rerun = result.workflow_runs.find((r) => r.id === pinned.runId);
        if (rerun && rerun.run_attempt !== pinned.runAttempt)
          throw new Error(
            `Pinned run attempt ${pinned.runAttempt} changed to ${rerun.run_attempt}; original artifacts cannot be resumed`,
          );
      }
      if (selected && !selectRun([selected], receipt.sha))
        throw new Error("Selected run no longer matches fixed commit");
      if (selected) {
        receipt.runId = selected.id;
        receipt.runAttempt = selected.run_attempt;
        receipt.runStatus = selected.status;
        receipt.runConclusion = selected.conclusion;
        writeJson(receiptPath, receipt);
      }
      if (selected?.status === "completed") break;
      if (Date.now() >= deadline) break;
      await new Promise((resolve) =>
        setTimeout(resolve, Math.min(15000, deadline - Date.now())),
      );
    } while (selected?.status !== "completed");
    if (
      !selected ||
      selected.status !== "completed" ||
      selected.conclusion !== "success"
    )
      throw new Error(
        `Exact-commit Extension CI ${selected ? `${selected.status}/${selected.conclusion || "pending"}` : "not found"}; resume with --resume ${receiptPath}`,
      );
    const runtime = path.join(state, receipt.sha);
    // A completed receipt is useful only after rehashing the actual runtime and
    // installed files, and rechecking both command links and the Java wrapper.
    if (
      priorReceipt &&
      priorReceipt.success &&
      priorReceipt.stage === "installed" &&
      priorReceipt.runId === selected.id &&
      priorReceipt.runAttempt === selected.run_attempt &&
      !options.packageOnly &&
      cachedInstallMatches(
        priorReceipt,
        runtime,
        state,
        options,
        java,
        editorVersion,
      )
    ) {
      receipt = {
        ...priorReceipt,
        error: undefined,
        installed: undefined,
        ref,
        success: true,
        noop: true,
        verifiedAt: new Date().toISOString(),
        resumeArgs: receipt.resumeArgs,
      };
      stage = "installed";
      writeJson(receiptPath, receipt);
      return { ...receipt, receipt: receiptPath };
    }
    stage = "artifacts";
    receipt.stage = stage;
    writeJson(receiptPath, receipt);
    const artifacts = validateArtifacts(
      gh([`repos/${repo}/actions/runs/${selected.id}/artifacts?per_page=100`])
        .artifacts,
    );
    fs.mkdirSync(runtime, { recursive: true, mode: 0o700 });
    const temp = fs.mkdtempSync(path.join(state, ".download-"));
    /** @type {Error | null} */ let tempFailure = null;
    try {
      /** @type {Buffer[]} */
      const contents = [];
      const reusable =
        priorReceipt &&
        priorReceipt.runId === selected.id &&
        priorReceipt.runAttempt === selected.run_attempt &&
        priorReceipt.hashes &&
        priorReceipt.artifacts &&
        artifacts.every((a) =>
          priorReceipt?.artifacts?.some(
            (p) => p.id === a.id && p.name === a.name && p.digest === a.digest,
          ),
        );
      if (reusable && priorReceipt?.hashes) {
        const cachedJar = path.join(runtime, "gls.jar"),
          cachedVsix = path.join(runtime, "gvy.vsix");
        try {
          const jar = readRegularFile(cachedJar, LIMIT),
            vsix = readRegularFile(cachedVsix, LIMIT);
          if (
            hash(jar) !== priorReceipt.hashes.jar ||
            hash(vsix) !== priorReceipt.hashes.vsix
          )
            throw new Error(
              "Cached runtime hash mismatch; inspect before retrying",
            );
          contents.push(jar, vsix);
        } catch (error) {
          if (/** @type {NodeJS.ErrnoException} */ (error).code !== "ENOENT")
            throw error;
        }
      }
      for (const a of contents.length ? [] : artifacts) {
        const archive = path.join(temp, `${a.name}.zip`);
        await download(`repos/${repo}/actions/artifacts/${a.id}/zip`, archive);
        const bytes = fs.readFileSync(archive);
        if (`sha256:${hash(bytes)}` !== a.digest)
          throw new Error(`${a.name} artifact digest mismatch`);
        contents.push(
          zipEntry(
            bytes,
            a.name === "vsix"
              ? (n) => /^gvy-[^/]+\.vsix$/.test(n)
              : (n) => n === "gls.jar",
            LIMIT,
          ),
        );
      }
      const [jar, vsix] = contents;
      const bundled = zipEntry(
        vsix,
        (n) => n === "extension/server/gls.jar",
        LIMIT,
      );
      const client = zipEntry(
        vsix,
        (n) => n === "extension/client/out/extension.js",
        16 * 1024 * 1024,
      );
      const manifest = JSON.parse(
        zipEntry(
          vsix,
          (n) => n === "extension/package.json",
          1024 * 1024,
        ).toString("utf8"),
      );
      if (hash(jar) !== hash(bundled))
        throw new Error("Bundled JAR differs from CI server");
      const provenance = provenanceFromProperties(
        parseVersionProperties(
          zipEntry(
            jar,
            (n) => n === "version.properties",
            1024 * 1024,
          ).toString("utf8"),
        ),
      );
      if (provenance.gitSha !== receipt.sha || provenance.dirty !== false)
        throw new Error("JAR provenance does not match clean selected commit");
      if (`${manifest.publisher}.${manifest.name}` !== "albertocavalcante.gvy")
        throw new Error("Unexpected extension identity");
      if (!compatible(manifest.engines?.vscode, editorVersion))
        throw new Error(`Editor does not satisfy ${manifest.engines?.vscode}`);
      const jarPath = path.join(runtime, "gls.jar"),
        vsixPath = path.join(runtime, "gvy.vsix");
      fs.writeFileSync(path.join(temp, "gls.jar"), jar, { mode: 0o600 });
      fs.writeFileSync(path.join(temp, "gvy.vsix"), vsix, { mode: 0o600 });
      stage = "smoke";
      receipt.stage = stage;
      writeJson(receiptPath, receipt);
      await smokeServerCommand(
        java,
        ["-Xmx2g", "-jar", path.join(temp, "gls.jar")],
        30000,
        process.env,
        {
          uri: "file:///gvy-smoke.groovy",
          text: "class GvySmoke {}\n",
          verifySymbols: (symbols) => {
            if (!symbols.some((s) => "name" in s && s.name === "GvySmoke"))
              throw new Error("Missing smoke document symbol");
          },
        },
      );
      // A commit directory may already be serving a previous verified install.
      // Never replace it with different bytes, even for a rerun of the same SHA.
      publishFile(path.join(temp, "gls.jar"), jarPath, jar, LIMIT);
      publishFile(path.join(temp, "gvy.vsix"), vsixPath, vsix, LIMIT);
      receipt.runtimeVersion = provenance.version;
      receipt.hashes = {
        jar: hash(jar),
        client: hash(client),
        vsix: hash(vsix),
      };
      receipt.jar = jarPath;
      receipt.vsix = vsixPath;
      receipt.artifacts = artifacts.map((a) => ({
        id: a.id,
        name: a.name,
        digest: a.digest,
      }));
      if (!options.packageOnly) {
        fs.mkdirSync(binDir, { recursive: true });
        const wrapperContents = wrapperText(java, jarPath);
        const wrapper = path.join(
          runtime,
          `gvy-${hash(Buffer.from(wrapperContents))}`,
        );
        receipt.wrapper = wrapper;
        receipt.command = link;
        const wrapperCandidate = path.join(temp, "gvy");
        const wrapperBytes = Buffer.from(wrapperContents);
        fs.writeFileSync(wrapperCandidate, wrapperBytes, {
          mode: 0o700,
          flag: "wx",
        });
        publishFile(wrapperCandidate, wrapper, wrapperBytes, 65536, true);
        const extensionMatches = installedMatches(
          os.homedir(),
          receipt.hashes.jar,
          receipt.hashes.client,
          manifest,
        );
        const noop =
          extensionMatches &&
          fs.existsSync(stable) &&
          fs.realpathSync(stable) === fs.realpathSync(wrapper) &&
          checkLink(link, stable);
        receipt.noop = noop;
        if (!extensionMatches) {
          stage = "editor-install";
          receipt.stage = stage;
          receipt.editorMayHaveChanged = true;
          writeJson(receiptPath, receipt);
          run(editor, ["--install-extension", vsixPath, "--force"]);
          if (
            !installedMatches(
              os.homedir(),
              receipt.hashes.jar,
              receipt.hashes.client,
              manifest,
            )
          )
            throw new Error("Installed extension hashes do not match");
        }
        if (!noop) {
          stage = "launcher-activation";
          const tmpLink = path.join(state, `.gvy-${process.pid}`);
          /** @type {Error | null} */ let linkFailure = null;
          try {
            fs.symlinkSync(wrapper, tmpLink);
            fs.renameSync(tmpLink, stable);
          } catch (error) {
            linkFailure =
              error instanceof Error ? error : new Error(String(error));
            throw linkFailure;
          } finally {
            try {
              fs.rmSync(tmpLink, { force: true });
            } catch (error) {
              const message = `Failed to clean ${tmpLink}: ${error instanceof Error ? error.message : String(error)}`;
              if (linkFailure)
                Object.assign(linkFailure, { cleanupError: message });
              // Cleanup failure must be reported even after successful activation.
              // eslint-disable-next-line no-unsafe-finally
              else throw new Error(message, { cause: error });
            }
          }
          if (!checkLink(link, stable)) fs.symlinkSync(stable, link);
        }
        verifyCommandPath(link);
      }
      receipt.stage = stage = options.packageOnly ? "packaged" : "installed";
      receipt.success = true;
      receipt.verifiedAt = new Date().toISOString();
      writeJson(receiptPath, receipt);
      return { ...receipt, receipt: receiptPath };
    } catch (error) {
      tempFailure = error instanceof Error ? error : new Error(String(error));
      throw tempFailure;
    } finally {
      try {
        fs.rmSync(temp, { recursive: true, force: true });
      } catch (error) {
        const message = `Failed to clean ${temp}: ${error instanceof Error ? error.message : String(error)}`;
        if (tempFailure) {
          const previous = /** @type {Error & {cleanupError?: string}} */ (
            tempFailure
          ).cleanupError;
          Object.assign(tempFailure, {
            cleanupError: [previous, message].filter(Boolean).join("; "),
          });
        }
        // A cleanup failure after success must not disappear.
        // eslint-disable-next-line no-unsafe-finally
        else throw new Error(message, { cause: error });
      }
    }
  } catch (e) {
    failure = Object.assign(e instanceof Error ? e : new Error(String(e)), {
      stage,
      installed: stage === "installed",
      receipt: receiptPath || undefined,
    });
    if (receipt && receiptPath) {
      receipt.success = false;
      receipt.installed = stage === "installed";
      receipt.stage = stage;
      receipt.error = failure.message;
      try {
        writeJson(receiptPath, receipt);
      } catch (receiptError) {
        failure.receiptError =
          receiptError instanceof Error
            ? receiptError.message
            : String(receiptError);
        const receiptCleanup = /** @type {{cleanupError?: string}} */ (
          receiptError
        )?.cleanupError;
        if (receiptCleanup)
          failure.cleanupError = [failure.cleanupError, receiptCleanup]
            .filter(Boolean)
            .join("; ");
      }
    }
    throw failure;
  } finally {
    try {
      fs.rmSync(lock, { recursive: true, force: true });
    } catch (cleanupError) {
      const message =
        cleanupError instanceof Error
          ? cleanupError.message
          : String(cleanupError);
      if (failure)
        failure.cleanupError = [
          failure.cleanupError,
          `Failed to clean ${lock}: ${message}`,
        ]
          .filter(Boolean)
          .join("; ");
      else {
        const error = Object.assign(
          new Error(`CI install lock cleanup failed: ${message}`, {
            cause: cleanupError,
          }),
          {
            stage,
            installed: stage === "installed",
            receipt: receiptPath || undefined,
            cleanupError: `Failed to clean ${lock}: ${message}`,
            receiptError: /** @type {string | undefined} */ (undefined),
          },
        );
        if (receipt && receiptPath) {
          receipt.success = false;
          receipt.installed = stage === "installed";
          receipt.error = error.message;
          try {
            writeJson(receiptPath, receipt);
          } catch (writeError) {
            error.receiptError =
              writeError instanceof Error
                ? writeError.message
                : String(writeError);
            const receiptCleanup = /** @type {{cleanupError?: string}} */ (
              writeError
            )?.cleanupError;
            if (receiptCleanup) error.cleanupError += `; ${receiptCleanup}`;
          }
        }
        // A cleanup failure after success must not disappear.
        // eslint-disable-next-line no-unsafe-finally
        throw error;
      }
    }
  }
}
module.exports = {
  installFromCi,
  selectRun,
  validateArtifacts,
  zipEntry,
  compatible,
  installedMatches,
  wrapperText,
  readRegularFile,
  publishFile,
  download,
  run,
};
