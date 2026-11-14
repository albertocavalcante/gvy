#!/usr/bin/env node
// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

// @ts-check

const fs = require("node:fs");
const path = require("node:path");
const { spawnSync } = require("node:child_process");

const REPO_ROOT = path.join(__dirname, "..");
const { installerCommand, findInstaller } = require("./editor-command.js");

/** @param {string[]} argv @param {number} index @param {string} flag */
function expectValue(argv, index, flag) {
  if (index >= argv.length || argv[index].startsWith("--")) {
    throw new Error(`Missing value for ${flag} option.`);
  }
  return argv[index];
}

/** @param {string[]} argv */
function parseArgs(argv = []) {
  const parsed = {
    tag: /** @type {string | null} */ (null),
    nightly: false,
    latest: false,
    local: /** @type {string | null} */ (null),
    url: /** @type {string | null} */ (null),
    checksum: /** @type {string | null} */ (null),
    channel: /** @type {string | null} */ (null),
    forceDownload: false,
    installer: /** @type {string | null} */ (null),
    packageOnly: false,
    vsix: /** @type {string | null} */ (null),
    help: false,
    fromSource: false,
    fromCi: false,
    ref: /** @type {string | null} */ (null),
    repo: /** @type {string | null} */ (null),
    stateRoot: /** @type {string | null} */ (null),
    binDir: /** @type {string | null} */ (null),
    wait: /** @type {string | null} */ (null),
    resume: /** @type {string | null} */ (null),
    doctor: false,
    json: false,
    buildRoot: /** @type {string | null} */ (null),
    javaHome: /** @type {string | null} */ (null),
    unknown: /** @type {string[]} */ ([]),
  };

  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    switch (arg) {
      case "--from-ci":
        parsed.fromCi = true;
        break;
      case "--ref":
        parsed.ref = expectValue(argv, ++i, arg);
        break;
      case "--repo":
        parsed.repo = expectValue(argv, ++i, arg);
        break;
      case "--state-root":
        parsed.stateRoot = expectValue(argv, ++i, arg);
        break;
      case "--bin-dir":
        parsed.binDir = expectValue(argv, ++i, arg);
        break;
      case "--wait":
        parsed.wait = expectValue(argv, ++i, arg);
        break;
      case "--resume":
        parsed.resume = expectValue(argv, ++i, arg);
        break;
      case "--from-source":
        parsed.fromSource = true;
        break;
      case "--doctor":
        parsed.doctor = true;
        break;
      case "--json":
        parsed.json = true;
        break;
      case "--build-root":
        parsed.buildRoot = expectValue(argv, ++i, arg);
        break;
      case "--java-home":
        parsed.javaHome = expectValue(argv, ++i, arg);
        break;
      case "--tag":
        parsed.tag = expectValue(argv, i + 1, "--tag");
        i += 1;
        break;
      case "--nightly":
        parsed.nightly = true;
        break;
      case "--latest":
        parsed.latest = true;
        break;
      case "--channel":
        parsed.channel = expectValue(argv, i + 1, "--channel");
        i += 1;
        break;
      case "--local":
        parsed.local = expectValue(argv, i + 1, "--local");
        i += 1;
        break;
      case "--url":
        parsed.url = expectValue(argv, i + 1, "--url");
        i += 1;
        break;
      case "--checksum":
        parsed.checksum = expectValue(argv, i + 1, "--checksum");
        i += 1;
        break;
      case "--force-download":
        parsed.forceDownload = true;
        break;
      case "--installer":
        parsed.installer = expectValue(argv, i + 1, "--installer");
        i += 1;
        break;
      case "--package-only":
        parsed.packageOnly = true;
        break;
      case "--vsix":
        parsed.vsix = expectValue(argv, i + 1, "--vsix");
        i += 1;
        break;
      case "--help":
      case "-h":
        parsed.help = true;
        break;
      default:
        parsed.unknown.push(arg);
    }
  }

  return parsed;
}

/** @param {string} cmd @param {string[]} args @param {import("node:child_process").SpawnSyncOptions} options */
function runCommand(cmd, args, options = {}) {
  const result = spawnSync(cmd, args, {
    stdio: "inherit",
    shell: false,
    ...options,
  });

  if (result.error) {
    throw result.error;
  }

  if (result.status !== 0) {
    throw new Error(`Command failed: ${cmd} ${args.join(" ")}`);
  }
}

/** @param {string | null} explicitPath */
function findVsix(explicitPath) {
  if (explicitPath) {
    const resolved = path.resolve(explicitPath);
    if (!fs.existsSync(resolved)) {
      throw new Error(`VSIX not found: ${resolved}`);
    }
    return resolved;
  }

  const entries = fs
    .readdirSync(REPO_ROOT)
    .filter((file) => file.endsWith(".vsix"))
    .map((file) => {
      const fullPath = path.join(REPO_ROOT, file);
      const stat = fs.statSync(fullPath);
      return { fullPath, mtime: stat.mtimeMs };
    })
    .sort((a, b) => b.mtime - a.mtime);

  if (entries.length === 0) {
    throw new Error(
      "No VSIX found in the repository root. Run pnpm run package first.",
    );
  }

  return entries[0].fullPath;
}

// Selection variables read by tools/prepare-server.js.
const SELECTION_VARS = ["GLS_TAG", "GLS_CHANNEL", "GLS_LOCAL_JAR", "GLS_URL"];

/** @param {Partial<ReturnType<typeof parseArgs>>} args */
function buildEnv(args) {
  const env = { ...process.env };
  const select = (/** @type {string} */ name, /** @type {string} */ value) => {
    for (const key of SELECTION_VARS) delete env[key];
    delete env.USE_LATEST_GLS;
    delete env.GLS_USE_PINNED;
    if (name) env[name] = value;
  };

  if (args.tag) {
    select("GLS_TAG", args.tag);
  } else if (args.nightly || (args.channel || "").toLowerCase() === "nightly") {
    select("GLS_CHANNEL", "nightly");
  } else if (args.latest || (args.channel || "").toLowerCase() === "release") {
    select("GLS_CHANNEL", "release");
  }

  if (args.local) {
    select("GLS_LOCAL_JAR", path.resolve(args.local));
  }

  if (args.url) {
    select("GLS_URL", args.url);
  }

  if (args.checksum) {
    env.GLS_CHECKSUM = args.checksum;
  }

  if (args.forceDownload) {
    env.FORCE_DOWNLOAD = "true";
  }

  return env;
}

/**
 * Command that runs `pnpm run package`. Prefer the pnpm that launched this
 * script (npm_execpath) so no .cmd shim is spawned; Node refuses to spawn
 * .cmd files without a shell on Windows (CVE-2024-27980).
 */
/** @param {NodeJS.ProcessEnv} env @param {NodeJS.Platform} platform */
function packageCommand(env = process.env, platform = process.platform) {
  const args = ["run", "package"];
  if (env.npm_execpath) {
    return {
      cmd: process.execPath,
      args: [env.npm_execpath, ...args],
      shell: false,
    };
  }
  return { cmd: "pnpm", args, shell: platform === "win32" };
}

function printHelp() {
  const help = `
Usage: node tools/install-extension.js [options]

Options:
  --from-ci             Install exact main commit artifacts from Extension CI
  --ref <ref>            CI ref (main or full commit SHA)
  --repo <owner/repo>    CI repository (albertocavalcante/gvy only)
  --state-root <path>    CI installation state root
  --bin-dir <path>       Directory for standalone gvy link
  --wait <seconds>       Wait for exact-commit CI (0–3600)
  --resume <receipt>     Resume a previously pinned selection
  --from-source         Build, verify, and install this checkout (no release fallback)
  --doctor              Check source-build prerequisites without building
  --build-root <path>   Place Gradle cache, build outputs and artifacts here
  --java-home <path>    JDK 21+ for the source build
  --json                Emit a JSON source/CI result; logs go to stderr
  --tag <tag>            Bundle a specific Groovy LSP release tag
  --nightly              Bundle the latest nightly/prerelease Groovy LSP
  --latest               Bundle the latest stable Groovy LSP release
  --channel <name>       Channel shortcut: nightly | release
  --local <path>         Use a specific local Groovy LSP JAR (skips download)
  --url <url>            Download from URL (supports GitHub Actions artifacts)
  --checksum <sha256>    Optional SHA256 checksum for URL downloads
  --force-download       Force download/copy of the selected JAR
  --installer <cmd>      Installer binary to use (code, agy, cursor, etc.)
  --package-only         Verify the package without installing
  --vsix <path>          Install a specific VSIX path (skip auto-detect)
  -h, --help             Show this help message

Token resolution (for GitHub artifact downloads):
  GH_TOKEN > GITHUB_TOKEN > gh auth token
`.trim();

  console.log(help);
}

/** @param {string[]} argv */
function main(argv = process.argv.slice(2)) {
  const args = parseArgs(argv);

  if (args.help) {
    printHelp();
    return;
  }

  if (args.unknown.length > 0)
    throw new Error(`Unknown options: ${args.unknown.join(", ")}`);
  const selectors = [
    args.tag,
    args.nightly,
    args.latest,
    args.channel,
    args.local,
    args.url,
    args.vsix,
    args.fromSource || args.doctor,
    args.fromCi,
  ].filter(Boolean);
  if (selectors.length > 1)
    throw new Error(
      "Choose exactly one source: --from-ci, --from-source, --local, --vsix, --tag, --latest, --nightly, --channel, or --url",
    );
  if (args.fromCi) {
    if (args.buildRoot)
      throw new Error("--build-root is not supported with --from-ci");
    const { installFromCi } = require("./ci-install.js");
    return installFromCi(args).then((result) => {
      if (args.json) console.log(JSON.stringify(result));
      else
        console.log(
          `CI ${args.packageOnly ? "package" : "installation"} verified: ${result.receipt}`,
        );
    });
  }
  if (
    args.ref ||
    args.repo ||
    args.stateRoot ||
    args.binDir ||
    args.wait ||
    args.resume
  )
    throw new Error("CI options require --from-ci");
  if (args.fromSource || args.doctor) {
    const { installFromSource } = require("./source-install.js");
    const result = installFromSource({
      root: path.resolve(REPO_ROOT, "..", ".."),
      ...args,
    });
    if (args.json) console.log(JSON.stringify(result));
    else
      console.log(
        `Source ${args.doctor ? "preflight passed" : args.packageOnly ? "package verified" : "installation verified"}. Build root: ${result.buildRoot}`,
      );
    return;
  }
  if (args.buildRoot || args.javaHome)
    throw new Error(
      "--build-root and --java-home require --from-source or --doctor",
    );
  if (args.vsix && args.packageOnly)
    throw new Error("--package-only cannot be combined with --vsix");
  if (args.vsix) {
    const vsix = findVsix(args.vsix);
    const installer = findInstaller(args.installer);
    if (!installer) throw new Error("No supported editor installer found");
    const install = installerCommand(installer, [
      "--install-extension",
      vsix,
      "--force",
    ]);
    runCommand(install.cmd, install.args, { shell: install.shell });
    return;
  }

  const env = buildEnv(args);
  const pkg = packageCommand(env);

  console.log("Packaging VSIX with requested Groovy LSP selection...");
  runCommand(pkg.cmd, pkg.args, { cwd: REPO_ROOT, env, shell: pkg.shell });

  const vsixPath = findVsix(args.vsix);
  console.log(`VSIX ready: ${vsixPath}`);

  if (args.packageOnly) {
    console.log("package-only requested; skipping installation.");
    return;
  }

  const installer = findInstaller(args.installer);
  if (!installer) {
    throw new Error(
      "No supported installer found (tried code, code-insiders, agy, cursor, cursor-insiders). Provide --installer to override.",
    );
  }

  console.log(`Installing extension via ${installer}...`);
  const install = installerCommand(installer, [
    "--install-extension",
    vsixPath,
    "--force",
  ]);
  runCommand(install.cmd, install.args, {
    cwd: REPO_ROOT,
    shell: install.shell,
  });
  console.log("✅ Extension installed");
}

module.exports = {
  parseArgs,
  main,
  reportError,
  buildEnv,
  packageCommand,
  installerCommand,
  findInstaller,
};

if (require.main === module) {
  try {
    Promise.resolve(main()).catch(reportError);
  } catch (caught) {
    reportError(caught);
  }
}

/** @param {unknown} caught */
function reportError(caught) {
  const error =
    /** @type {Error & {stage?: string, installed?: boolean, receipt?: string}} */ (
      caught
    );
  if (process.argv.includes("--json"))
    console.log(
      JSON.stringify({
        success: false,
        error: error.message,
        stage: error.stage,
        installed: error.installed,
        receipt: error.receipt,
      }),
    );
  console.error(`Failed to install extension: ${error.message}`);
  process.exitCode = 1;
}
