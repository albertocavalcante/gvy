// @ts-check
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const crypto = require("node:crypto");
const { execFileSync } = require("node:child_process");
const { downloadToFile } = require("../editors/code/tools/http.js");
/** @type {{version: string, targets: Record<string, {archive: string, sha256: string}>}} */
const toolchain = require("./just-toolchain.json");

/** @param {string} file @param {string} expected */
function verifyArchive(file, expected) {
  const actual = crypto
    .createHash("sha256")
    .update(fs.readFileSync(file))
    .digest("hex");
  if (!/^[a-f0-9]{64}$/.test(expected) || actual !== expected)
    throw new Error("Just archive checksum mismatch; nothing was installed");
}

/** @param {string} platform @param {string} arch */
function selectArtifact(platform, arch) {
  const target = toolchain.targets[`${platform}-${arch}`];
  if (!target)
    throw new Error(`Unsupported Just bootstrap target: ${platform}-${arch}`);
  return {
    url: `https://github.com/casey/just/releases/download/${toolchain.version}/just-${toolchain.version}-${target.archive}`,
    sha256: target.sha256,
  };
}

/** @param {string} binDir */
async function setupJust(binDir) {
  const artifact = selectArtifact(process.platform, process.arch);
  fs.mkdirSync(binDir, { recursive: true });
  const stage = fs.mkdtempSync(path.join(binDir, ".just-bootstrap-"));
  const archive = path.join(stage, "archive");
  const name = process.platform === "win32" ? "just.exe" : "just";
  try {
    await downloadToFile(artifact.url, archive);
    verifyArchive(archive, artifact.sha256);
    // Both Windows bsdtar and Unix tar extract these release formats.
    execFileSync("tar", ["-xf", archive, "-C", stage, name]);
    const candidate = path.join(stage, name);
    fs.chmodSync(candidate, 0o755);
    const version = execFileSync(candidate, ["--version"], {
      encoding: "utf8",
    }).trim();
    if (version !== `just ${toolchain.version}`)
      throw new Error(`Unexpected Just version: ${version}`);
    fs.renameSync(candidate, path.join(binDir, name));
    return binDir;
  } finally {
    fs.rmSync(stage, { recursive: true, force: true });
  }
}

/** @param {string[]} args */
function parseBootstrapArgs(args) {
  /** @type {string | null} */
  let binDir = null;
  let githubPath = false;
  for (let i = 0; i < args.length; i++) {
    if (args[i] === "--github-path") githubPath = true;
    else if (
      args[i] === "--bin-dir" &&
      args[i + 1] &&
      !args[i + 1].startsWith("--")
    )
      binDir = args[++i];
    else
      throw new Error(
        "Usage: node tools/setup-just.js [--bin-dir PATH] [--github-path (CI only)]",
      );
  }
  return { binDir: binDir ? path.resolve(binDir) : null, githubPath };
}

function createDefaultBinDir() {
  return fs.mkdtempSync(
    path.join(os.tmpdir(), `gvy-just-${toolchain.version}-`),
  );
}

module.exports = {
  selectArtifact,
  verifyArchive,
  setupJust,
  parseBootstrapArgs,
  createDefaultBinDir,
};
if (require.main === module) {
  try {
    const options = parseBootstrapArgs(process.argv.slice(2));
    const githubPath = options.githubPath ? process.env.GITHUB_PATH : null;
    if (options.githubPath && !githubPath)
      throw new Error("--github-path requires GITHUB_PATH from a CI runner");
    const binDir = options.binDir || createDefaultBinDir();
    setupJust(binDir)
      .then((dir) => {
        if (githubPath) fs.appendFileSync(githubPath, dir + "\n");
        console.log(`Verified Just ${toolchain.version} installed in ${dir}`);
      })
      .catch((error) => {
        if (!options.binDir)
          fs.rmSync(binDir, { recursive: true, force: true });
        console.error(error.message);
        process.exitCode = 1;
      });
  } catch (error) {
    console.error(error instanceof Error ? error.message : String(error));
    process.exitCode = 1;
  }
}
