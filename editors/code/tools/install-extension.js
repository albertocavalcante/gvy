#!/usr/bin/env node

const fs = require("node:fs");
const path = require("node:path");
const { spawnSync } = require("node:child_process");

const REPO_ROOT = path.join(__dirname, "..");
const DEFAULT_INSTALLERS = [
  "code",
  "code-insiders",
  "agy",
  "cursor",
  "cursor-insiders",
];

function expectValue(argv, index, flag) {
  if (index >= argv.length || argv[index].startsWith("--")) {
    throw new Error(`Missing value for ${flag} option.`);
  }
  return argv[index];
}

function parseArgs(argv = []) {
  const parsed = {
    tag: null,
    nightly: false,
    latest: false,
    local: null,
    url: null,
    checksum: null,
    channel: null,
    forceDownload: false,
    installer: null,
    packageOnly: false,
    vsix: null,
    help: false,
    unknown: [],
  };

  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    switch (arg) {
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

function commandExists(cmd) {
  try {
    const result = spawnSync(cmd, ["--version"], { stdio: "ignore" });
    if (result.error && result.error.code === "ENOENT") {
      return false;
    }
    return result.status === 0 || result.status === 1; // some CLIs return 1 for --version
  } catch (error) {
    if (error.code === "ENOENT") return false;
    return false;
  }
}

function findInstaller(preferred) {
  const candidates = preferred
    ? [preferred, ...DEFAULT_INSTALLERS.filter((c) => c !== preferred)]
    : DEFAULT_INSTALLERS;
  for (const candidate of candidates) {
    if (commandExists(candidate)) return candidate;
  }
  return null;
}

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
      "No VSIX found in the repository root. Run npm run package first.",
    );
  }

  return entries[0].fullPath;
}

function buildEnv(args) {
  const env = { ...process.env };

  if (args.tag) {
    env.GROOVY_LSP_TAG = args.tag;
    delete env.USE_LATEST_GROOVY_LSP;
    delete env.GROOVY_LSP_CHANNEL;
  } else if (args.nightly || (args.channel || "").toLowerCase() === "nightly") {
    env.GROOVY_LSP_CHANNEL = "nightly";
    delete env.USE_LATEST_GROOVY_LSP;
  } else if (args.latest || (args.channel || "").toLowerCase() === "release") {
    env.USE_LATEST_GROOVY_LSP = "true";
    delete env.GROOVY_LSP_CHANNEL;
  }

  if (args.local) {
    env.GROOVY_LSP_LOCAL_JAR = path.resolve(args.local);
    delete env.GROOVY_LSP_TAG;
    delete env.USE_LATEST_GROOVY_LSP;
    delete env.GROOVY_LSP_CHANNEL;
    delete env.GROOVY_LSP_URL;
  }

  if (args.url) {
    env.GROOVY_LSP_URL = args.url;
    delete env.GROOVY_LSP_TAG;
    delete env.USE_LATEST_GROOVY_LSP;
    delete env.GROOVY_LSP_CHANNEL;
    delete env.GROOVY_LSP_LOCAL_JAR;
  }

  if (args.checksum) {
    env.GROOVY_LSP_CHECKSUM = args.checksum;
  }

  if (args.forceDownload) {
    env.FORCE_DOWNLOAD = "true";
  }

  env.REQUIRE_SERVER_BUNDLE = "true";
  return env;
}

function printHelp() {
  const help = `
Usage: node tools/install-extension.js [options]

Options:
  --tag <tag>            Bundle a specific Groovy LSP release tag
  --nightly              Bundle the latest nightly/prerelease Groovy LSP
  --latest               Bundle the latest stable Groovy LSP release
  --channel <name>       Channel shortcut: nightly | release
  --local <path>         Use a specific local Groovy LSP JAR (skips download)
  --url <url>            Download from URL (supports GitHub Actions artifacts)
  --checksum <sha256>    Optional SHA256 checksum for URL downloads
  --force-download       Force download/copy of the selected JAR
  --installer <cmd>      Installer binary to use (code, agy, cursor, etc.)
  --package-only         Only build the VSIX; do not install
  --vsix <path>          Install a specific VSIX path (skip auto-detect)
  -h, --help             Show this help message

Token resolution (for GitHub artifact downloads):
  GH_TOKEN > GITHUB_TOKEN > gh auth token
`.trim();

  console.log(help);
}

function main() {
  const args = parseArgs(process.argv.slice(2));

  if (args.help) {
    printHelp();
    return;
  }

  if (args.unknown.length > 0) {
    console.warn(`Ignoring unknown options: ${args.unknown.join(", ")}`);
  }

  const env = buildEnv(args);
  const npmCmd = process.platform === "win32" ? "npm.cmd" : "npm";

  console.log("Packaging VSIX with requested Groovy LSP selection...");
  runCommand(npmCmd, ["run", "package"], { cwd: REPO_ROOT, env });

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
  runCommand(installer, ["--install-extension", vsixPath], { cwd: REPO_ROOT });
  console.log("✅ Extension installed");
}

if (require.main === module) {
  try {
    main();
  } catch (error) {
    console.error(`Failed to install extension: ${error.message}`);
    process.exit(1);
  }
}
