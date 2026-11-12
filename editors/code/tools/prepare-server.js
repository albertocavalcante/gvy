// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

/**
 * Prepares the Groovy Language Server JAR
 * Priority order:
 * 1. Use explicitly provided local JAR (--local/GLS_LOCAL_JAR)
 * 2. Use explicitly provided URL (--url/GLS_URL)
 * 3. Use existing server/gls.jar if present (unless FORCE_DOWNLOAD=true)
 * 4. Copy from local groovy-lsp build if available and PREFER_LOCAL=true
 * 5. Download from GitHub releases (pinned, latest, nightly, or explicit tag)
 */

const fs = require("node:fs");
const path = require("node:path");
const crypto = require("node:crypto");
const os = require("node:os");
const { execFileSync } = require("node:child_process");
const { localJarMatches, replaceLocalJar } = require("./source-install.js");
const { validateJarFile } = require("./validate-server.js");
const { extractOrCopyJar, isZipFile } = require("./zip.js");
const {
  readJarProvenance,
  buildVersionRecord,
  assessLocalJar,
  describeRecord,
  requireFresh,
} = require("./jar-provenance.js");
const { HttpError, downloadToFile } = require("./http.js");
const {
  getLatestReleaseInfo,
  getReleaseByTag,
  getLatestNightlyRelease,
  selectJarAsset,
  fetchChecksumForAsset,
} = require("./github-releases.js");
const {
  resolveGitHubToken,
  resolveGitHubArtifactDownload,
} = require("./github-token.js");

const SERVER_DIR = path.join(__dirname, "..", "server");
const CANONICAL_JAR_NAME = "gls.jar";
const JAR_PATH = path.join(SERVER_DIR, CANONICAL_JAR_NAME);
const VERSION_FILE = path.join(SERVER_DIR, ".gls-version");

// Pinned Groovy LSP release
const PINNED_RELEASE_TAG = "v0.4.8";
const PINNED_JAR_ASSET = "gls-0.4.8.jar";
// Ships a single universal JAR; use gls artifact for all platforms
const PINNED_DOWNLOAD_URL = `https://github.com/albertocavalcante/gvy/releases/download/${PINNED_RELEASE_TAG}/${PINNED_JAR_ASSET}`;
const PINNED_JAR_SHA256 =
  "552558bc588968c3127aa25114a86bb9137fa740572b39e7af44ff732a2802f2";

/**
 * Sanitizes user-controlled strings for safe logging
 * Prevents log injection attacks by removing:
 * - Newlines (\n, \r) - prevents fake log entries
 * - ANSI escape codes - prevents terminal manipulation
 * - Control characters - prevents binary/malicious content
 * @param {string} str - User-controlled string to sanitize
 * @param {number} maxLength - Maximum length before truncation (default: 500)
 * @returns {string} Sanitized string safe for logging
 */
function sanitizeForLog(str, maxLength = 500) {
  if (typeof str !== "string") {
    return String(str);
  }

  // Remove ANSI escape codes (e.g., \x1b[31m for colors)
  // eslint-disable-next-line no-control-regex -- intentionally strips terminal escapes
  let sanitized = str.replace(/\x1b\[[0-9;]*m/g, "");

  // Remove all control characters (ASCII 0-31 except tab) including newlines
  // eslint-disable-next-line no-control-regex -- intentionally strips control bytes
  sanitized = sanitized.replace(/[\x00-\x08\x0B-\x1F\x7F]/g, "");

  // Truncate if too long
  if (sanitized.length > maxLength) {
    sanitized = sanitized.substring(0, maxLength) + "... (truncated)";
  }

  return sanitized;
}

function expectValue(argv, index, flag) {
  if (index >= argv.length || argv[index].startsWith("--")) {
    throw new Error(`Missing value for ${flag} option.`);
  }
  return argv[index];
}

function parseArgs(argv = []) {
  const parsed = {
    printReleaseTag: false,
    nightly: false,
    latest: false,
    tag: null,
    local: null,
    url: null,
    checksum: null,
    forceDownload: false,
    channel: null,
    preferLocal: false,
    buildLocal: false,
    help: false,
    unknown: [],
  };

  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    switch (arg) {
      case "--print-release-tag":
        parsed.printReleaseTag = true;
        break;
      case "--nightly":
        parsed.nightly = true;
        break;
      case "--latest":
        parsed.latest = true;
        break;
      case "--tag":
        parsed.tag = expectValue(argv, i + 1, "--tag");
        i += 1;
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
      case "--prefer-local":
        parsed.preferLocal = true;
        break;
      case "--build-local":
        parsed.buildLocal = true;
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

/**
 * Walks up the directory tree to find the monorepo root.
 * Looks for groovy-lsp/build.gradle.kts as a marker.
 * This approach is robust to directory structure changes.
 * @returns {string|null} Path to monorepo root, or null if not found
 */
function findMonorepoRoot() {
  let current = __dirname;
  const root = path.parse(current).root;

  while (current !== root) {
    const marker = path.join(current, "gls", "build.gradle.kts");
    if (fs.existsSync(marker)) {
      return current;
    }
    current = path.dirname(current);
  }
  return null;
}

/**
 * Finds local Groovy LSP JAR file in common development locations
 * @returns {string|null} Path to JAR file, or null if not found
 */
function findLocalGroovyLspJar() {
  // Find monorepo root by walking up directory tree (robust to refactoring)
  const monorepoRoot = findMonorepoRoot();

  const searchPaths = [
    // 1. Environment variable override
    process.env.GLS_LOCAL_JAR,

    // 2. Monorepo sibling directory (most common for development)
    monorepoRoot
      ? path.join(monorepoRoot, "gls", "build", "editor-server")
      : null,
    monorepoRoot ? path.join(monorepoRoot, "gls", "build", "libs") : null,

    // 3. Common workspace patterns
    path.join(
      process.env.HOME || "",
      "dev",
      "workspace",
      "groovy-lsp",
      "build",
      "libs",
    ),
    path.join(
      process.env.HOME || "",
      "workspace",
      "groovy-lsp",
      "build",
      "libs",
    ),
    path.join(
      process.env.HOME || "",
      "projects",
      "groovy-lsp",
      "build",
      "libs",
    ),
  ].filter(Boolean); // Remove null/undefined paths

  for (const searchPath of searchPaths) {
    try {
      // If it's a direct file path (from env var), check if it exists
      if (searchPath === process.env.GLS_LOCAL_JAR) {
        if (fs.existsSync(searchPath) && searchPath.endsWith(".jar")) {
          console.log(`Found local JAR via GLS_LOCAL_JAR: ${searchPath}`);
          return searchPath;
        }
        continue;
      }

      // Otherwise, search for JAR files in the directory
      if (fs.existsSync(searchPath) && fs.statSync(searchPath).isDirectory()) {
        const jarFiles = fs
          .readdirSync(searchPath)
          .filter(
            (file) =>
              file.endsWith(".jar") &&
              (file === "gls.jar" ||
                file.includes("groovy-lsp") ||
                file.startsWith("gls-")),
          )
          .sort((a, b) => {
            // Priority 1: Files ending in -all.jar (Shadow JARs)
            const aIsAll = a.endsWith("-all.jar");
            const bIsAll = b.endsWith("-all.jar");
            if (aIsAll && !bIsAll) return -1;
            if (!aIsAll && bIsAll) return 1;

            // Priority 2: Alphabetical
            return a.localeCompare(b);
          });

        if (jarFiles.length > 0) {
          const foundJar = path.join(searchPath, jarFiles[0]); // Use first match
          console.log(`Found local JAR: ${foundJar}`);
          return foundJar;
        }
      }
    } catch {
      // Ignore errors for individual paths
      continue;
    }
  }

  console.log("No local Groovy LSP JAR found in common locations");
  return null;
}

function copyLocalJar(localJarPath, { forceDownload }) {
  // Validate source JAR before copying
  try {
    validateJarFile(localJarPath);
  } catch (error) {
    throw new Error(`Source JAR validation failed: ${error.message}`, {
      cause: error,
    });
  }

  let shouldCopy = true;

  if (!forceDownload && fs.existsSync(JAR_PATH)) {
    if (localJarMatches(localJarPath, JAR_PATH)) {
      console.log(`✓ Using existing ${CANONICAL_JAR_NAME} (up to date)`);
      writeInstalledVersion("local", "local");
      shouldCopy = false;
    }
  }

  if (shouldCopy) {
    console.log(`Copying from local build: ${sanitizeForLog(localJarPath)}`);
    replaceLocalJar(localJarPath, JAR_PATH, validateJarFile);

    writeInstalledVersion("local", "local");
    console.log(`✓ Copied to ${CANONICAL_JAR_NAME}`);
  }
}

function deriveSelection(cliOptions) {
  const explicitTag = cliOptions.tag || process.env.GLS_TAG || null;
  const channel = (
    cliOptions.channel ||
    process.env.GLS_CHANNEL ||
    ""
  ).toLowerCase();
  const useNightly = cliOptions.nightly || channel === "nightly";
  const usePinned =
    process.env.GLS_USE_PINNED === "true" || channel === "pinned";

  // Priority: explicit tag > nightly > pinned > latest > pinned default.
  if (explicitTag) {
    return { type: "tag", tag: explicitTag };
  }

  if (useNightly) {
    return { type: "nightly" };
  }

  if (usePinned) {
    return { type: "pinned" };
  }

  if (
    cliOptions.latest ||
    channel === "release" ||
    process.env.USE_LATEST_GLS === "true" ||
    process.env.USE_LATEST_GROOVY_LSP === "true"
  ) {
    return { type: "latest" };
  }

  return { type: "pinned" };
}

function hasExplicitVersionSelection(cliOptions) {
  return Boolean(
    cliOptions.tag ||
    cliOptions.nightly ||
    cliOptions.latest ||
    cliOptions.channel ||
    process.env.GLS_TAG ||
    process.env.GLS_CHANNEL ||
    process.env.GLS_USE_PINNED === "true" ||
    process.env.USE_LATEST_GLS === "true" ||
    process.env.USE_LATEST_GROOVY_LSP === "true",
  );
}

/**
 * Returns the normalized (lower-case) SHA-256 digest or throws: every
 * downloaded server JAR must be verified before it is bundled.
 */
function requireChecksum(value, artifact) {
  if (typeof value !== "string" || !/^[a-f0-9]{64}$/i.test(value.trim())) {
    throw new Error(`SHA-256 checksum required for ${artifact}`);
  }
  return value.trim().toLowerCase();
}

async function resolveTarget(selection, { authToken } = {}) {
  const buildTargetFromReleaseInfo = async (
    info,
    { noJarError, logMessage },
  ) => {
    if (!info) {
      throw new Error("Release information not found.");
    }
    const jarAsset = selectJarAsset(info.assets);
    if (!jarAsset) {
      throw new Error(noJarError(info));
    }
    const checksum = await fetchChecksumForAsset(info.assets, jarAsset.name, {
      authToken,
    });
    console.log(`${logMessage}: ${sanitizeForLog(info.tag_name)}`);
    return {
      tag: info.tag_name,
      assetName: jarAsset.name,
      downloadUrl: jarAsset.browser_download_url,
      checksum,
    };
  };

  if (selection.type === "tag") {
    const info = await getReleaseByTag(selection.tag, { authToken });
    return await buildTargetFromReleaseInfo(info, {
      noJarError: (i) => `No JAR file found in release ${i.tag_name}`,
      logMessage: "Selected Groovy LSP release tag",
    });
  }

  if (selection.type === "nightly") {
    const info = await getLatestNightlyRelease({ authToken });
    if (!info) {
      throw new Error("No nightly Groovy LSP release found");
    }
    return await buildTargetFromReleaseInfo(info, {
      noJarError: (i) => `No JAR file found in nightly release ${i.tag_name}`,
      logMessage: "Selected latest nightly Groovy LSP",
    });
  }

  if (selection.type === "latest") {
    const info = await getLatestReleaseInfo({ authToken });
    return await buildTargetFromReleaseInfo(info, {
      noJarError: () => "No JAR file found in the latest release",
      logMessage: "Selected latest Groovy LSP release",
    });
  }

  // Pinned release fallback
  console.log(`Selected pinned Groovy LSP release: ${PINNED_RELEASE_TAG}`);
  return {
    tag: PINNED_RELEASE_TAG,
    assetName: PINNED_JAR_ASSET,
    downloadUrl: PINNED_DOWNLOAD_URL,
    checksum: PINNED_JAR_SHA256,
  };
}

/**
 * Records what is installed: the marker plus the provenance stamped inside the
 * JAR (version, commit, dirty state) and how it got here. Logs the result so a
 * stale or dirty jar is visible in the install log.
 * @param {string} versionTag
 * @param {"local"|"release"|"reused"} source
 */
function writeInstalledVersion(versionTag, source) {
  const record = buildVersionRecord({
    tag: versionTag,
    source,
    provenance: readJarProvenance(JAR_PATH),
  });
  fs.writeFileSync(
    VERSION_FILE,
    `${JSON.stringify(record, null, 2)}\n`,
    "utf8",
  );
  console.log(`Server JAR: ${describeRecord(record)}`);
  if (record.gitSha === "unknown") {
    console.warn(
      `⚠️  ${CANONICAL_JAR_NAME} carries no git provenance (release or pre-provenance build).`,
    );
  }
}

/**
 * Computes SHA-256 for a file path (streaming to avoid large buffers)
 */
function sha256File(filePath) {
  return new Promise((resolve, reject) => {
    const hash = crypto.createHash("sha256");
    const stream = fs.createReadStream(filePath);
    stream.on("data", (chunk) => hash.update(chunk));
    stream.on("end", () => resolve(hash.digest("hex")));
    stream.on("error", reject);
  });
}

/**
 * Validates the checksum of the JAR if expected hash is available
 */
async function verifyChecksum(filePath, expectedHash) {
  expectedHash = requireChecksum(expectedHash, filePath);
  const actual = await sha256File(filePath);
  if (actual !== expectedHash) {
    throw new Error(
      `Checksum mismatch for ${filePath}. Expected ${expectedHash} but got ${actual}`,
    );
  }
}

/** Temp path next to the final JAR so the install is a same-volume rename. */
function downloadTempPath() {
  return path.join(SERVER_DIR, `.gls-download-${process.pid}.jar`);
}

/**
 * Copies `sourcePath` into a private snapshot, verifies and validates that
 * snapshot, then atomically replaces `destPath` with it.
 * Aliases, including hardlinks, and metadata lookup errors leave both paths
 * untouched. A missing destination is allowed. After this precondition check,
 * failures preserve the previous JAR and remove the source,
 * so an interrupted or tampered download can never become server/gls.jar.
 * The destination directory and private staging directory are trusted; the
 * caller's source pathname may be replaced at any point during validation.
 */
async function installVerifiedJar(
  sourcePath,
  destPath,
  expectedChecksum,
  validate = validateJarFile,
) {
  const sourceStat = fs.statSync(sourcePath, { bigint: true });
  let destinationStat;
  try {
    destinationStat = fs.statSync(destPath, { bigint: true });
  } catch (error) {
    if (error.code !== "ENOENT") throw error;
  }
  if (
    destinationStat &&
    sourceStat.dev === destinationStat.dev &&
    sourceStat.ino === destinationStat.ino
  ) {
    throw new Error("Staged JAR source must be distinct from destination");
  }

  let snapshotDir;
  let sourceRemoved = false;
  try {
    expectedChecksum = requireChecksum(expectedChecksum, sourcePath);
    snapshotDir = fs.mkdtempSync(
      path.join(path.dirname(destPath), ".gls-verified-"),
    );
    const snapshot = path.join(snapshotDir, path.basename(sourcePath));
    fs.copyFileSync(sourcePath, snapshot, fs.constants.COPYFILE_EXCL);
    await verifyChecksum(snapshot, expectedChecksum);
    validate(snapshot);
    fs.rmSync(sourcePath, { force: true });
    sourceRemoved = true;
    fs.renameSync(snapshot, destPath);
  } finally {
    try {
      if (!sourceRemoved) fs.rmSync(sourcePath, { force: true });
    } finally {
      if (snapshotDir) fs.rmSync(snapshotDir, { recursive: true, force: true });
    }
  }
}

/**
 * Downloads a JAR from a URL (handles ZIP extraction for GitHub artifacts)
 * @param {string} url - URL to download from
 * @param {string} expectedChecksum - Required SHA256 checksum
 */
async function downloadFromUrl(url, expectedChecksum) {
  expectedChecksum = requireChecksum(expectedChecksum, "URL download");
  const artifact = resolveGitHubArtifactDownload(url);
  const downloadUrl = artifact.downloadUrl;
  const isArtifactZip = artifact.isArtifactZip;

  if (artifact.kind === "browser") {
    console.log("Detected GitHub Actions artifact URL, transforming...");
    console.log(`API URL: ${downloadUrl}`);
  }

  // Resolve auth token (required for GitHub Actions artifact downloads)
  const authToken = resolveGitHubToken();

  if (isArtifactZip && !authToken) {
    throw new Error(
      [
        "GitHub Actions artifact downloads require authentication.",
        "Set GH_TOKEN or GITHUB_TOKEN, or run 'gh auth login' first.",
        "Token must have permission to read Actions artifacts for the repository.",
      ].join(" "),
    );
  } else if (!authToken && new URL(downloadUrl).hostname === "api.github.com") {
    console.warn(
      "⚠️  No GitHub token found. GitHub API downloads may be rate-limited.",
    );
  }

  // Create temp directory for download
  const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "groovy-lsp-"));
  const tempFile = path.join(tempDir, "download");
  const stagedJar = downloadTempPath();

  try {
    console.log(`Downloading from URL: ${downloadUrl}`);
    const { contentType } = await downloadToFile(downloadUrl, tempFile, {
      authToken,
    });

    // Determine if we need to extract based on artifact flag, content-type (if present), or file inspection
    const isZip =
      isArtifactZip ||
      contentType?.includes("application/zip") ||
      contentType?.includes("application/x-zip") ||
      isZipFile(tempFile);

    if (isZip) {
      console.log("Download is a ZIP archive, extracting JAR...");
      extractOrCopyJar(tempFile, stagedJar);
    } else {
      // Direct JAR download
      console.log("Download is a direct JAR file...");
      fs.copyFileSync(tempFile, stagedJar);
    }

    await installVerifiedJar(stagedJar, JAR_PATH, expectedChecksum);
    console.log("✓ Checksum verified");

    // Write version marker
    const urlObj = new URL(url);
    const pathSegments = urlObj.pathname.split("/").filter(Boolean);
    let markerId =
      pathSegments.length > 0
        ? pathSegments[pathSegments.length - 1]
        : urlObj.hostname || "unknown";
    if (markerId === "zip" && pathSegments.length >= 2) {
      markerId = pathSegments[pathSegments.length - 2];
    }
    writeInstalledVersion(`url:${markerId}`, "release");

    console.log(`✓ Downloaded and saved as ${CANONICAL_JAR_NAME}`);
  } finally {
    // Clean up the temp directory and a staged JAR left by a failed extract.
    fs.rmSync(stagedJar, { force: true });
    try {
      fs.rmSync(tempDir, { recursive: true, force: true });
    } catch {
      // ignore cleanup error
    }
  }
}

/**
 * Downloads the target release JAR from GitHub
 */
async function downloadRelease(target) {
  requireChecksum(target.checksum, target.assetName);
  console.log(
    `Downloading Groovy LSP ${sanitizeForLog(target.tag)} (${sanitizeForLog(target.assetName)})...`,
  );
  const checksum = requireChecksum(target.checksum, target.assetName);
  const stagedJar = downloadTempPath();
  try {
    await downloadToFile(target.downloadUrl, stagedJar);
  } catch (error) {
    fs.rmSync(stagedJar, { force: true });
    throw error;
  }
  await installVerifiedJar(stagedJar, JAR_PATH, checksum);
  writeInstalledVersion(target.tag, "release");
  console.log(`✓ Downloaded and saved as ${CANONICAL_JAR_NAME}`);
}

/**
 * Detects if we're running inside the monorepo
 * @returns {boolean} True if groovy-lsp sibling directory exists
 */
function detectMonorepoEnvironment() {
  return findMonorepoRoot() !== null;
}

/**
 * Gets the monorepo root directory
 * @returns {string|null} Path to monorepo root, or null if not in monorepo
 */
function getMonorepoRoot() {
  return findMonorepoRoot();
}

/**
 * Builds the local JAR by invoking './gradlew :gls:stageEditorServer' in the monorepo root.
 * Only works in a monorepo environment.
 * @throws {Error} If not in monorepo or build fails
 */
function buildLocalJar() {
  if (!detectMonorepoEnvironment()) {
    throw new Error(
      "--build-local requires a monorepo environment (gls/build.gradle.kts not found)",
    );
  }

  const monorepoRoot = getMonorepoRoot();
  console.log(
    "🔨 Building local JAR via './gradlew :gls:stageEditorServer'...",
  );
  console.log(`   Working directory: ${monorepoRoot}`);

  try {
    execFileSync(
      process.platform === "win32"
        ? "cmd.exe"
        : path.join(monorepoRoot, "gradlew"),
      process.platform === "win32"
        ? ["/d", "/s", "/c", "gradlew.bat :gls:stageEditorServer"]
        : [":gls:stageEditorServer"],
      {
        cwd: monorepoRoot,
        stdio: "inherit",
        env: { ...process.env },
      },
    );
    console.log("✓ Local JAR build completed");
  } catch (error) {
    throw new Error(
      `Failed to build local JAR: ${error.message || "./gradlew :gls:stageEditorServer failed"}`,
      { cause: error },
    );
  }
}

/**
 * HEAD of the monorepo checkout, or null when it cannot be read.
 */
function readCheckoutHead() {
  return runCheckoutGit(["rev-parse", "HEAD"]);
}

/**
 * Whether the monorepo checkout has uncommitted changes (ignored files do not
 * count), or null when it cannot be read.
 */
function readCheckoutDirty() {
  const status = runCheckoutGit(["status", "--porcelain"]);
  return status === null ? null : status.length > 0;
}

/**
 * Runs git in the monorepo root only. Outside a monorepo there is no checkout
 * to compare with, and the process's cwd repo would be the wrong one.
 */
function runCheckoutGit(args) {
  const root = getMonorepoRoot();
  if (!root) return null;
  try {
    return execFileSync("git", args, {
      cwd: root,
      encoding: "utf8",
      stdio: ["ignore", "pipe", "ignore"],
    }).trim();
  } catch {
    return null;
  }
}

/**
 * Checks a local build against the checkout before it is bundled. A jar built
 * from an old commit is the stale-jar hazard: warn loudly by default; under
 * GLS_REQUIRE_FRESH rebuild when allowed, otherwise fail. All IO is injected.
 * @returns the jar to use (possibly rebuilt)
 */
function ensureFreshLocalJar(jarPath, deps) {
  const {
    readProvenance,
    getHead,
    getCheckoutDirty = () => null,
    isExplicit = () => false,
    strict,
    rebuild,
    findJar,
    warn,
  } = deps;
  // A jar the user named (--local, GLS_LOCAL_JAR) is theirs to judge.
  if (isExplicit(jarPath)) {
    return jarPath;
  }
  const assess = (jar) =>
    assessLocalJar(readProvenance(jar), getHead(), getCheckoutDirty());
  const first = assess(jarPath);
  if (first.state === "fresh" || first.state === "unverifiable") {
    return jarPath;
  }
  warn(
    `⚠️  Local server JAR may be out of date: ${sanitizeForLog(first.reason)} (${sanitizeForLog(jarPath)}).`,
  );
  if (first.state === "dirty" || !strict) {
    return jarPath;
  }
  if (!rebuild) {
    throw new Error(
      `GLS_REQUIRE_FRESH is set and the local JAR is stale: ${sanitizeForLog(first.reason)}. Rebuild with ./gradlew :gls:stageEditorServer (or BUILD_LOCAL=true).`,
    );
  }
  rebuild();
  const rebuilt = findJar();
  const second = rebuilt ? assess(rebuilt) : null;
  if (!rebuilt || second.state === "stale") {
    throw new Error(
      `GLS_REQUIRE_FRESH is set and the rebuilt local JAR is still stale: ${sanitizeForLog(second?.reason ?? "no JAR found")}`,
    );
  }
  return rebuilt;
}

/**
 * Main function to prepare the server JAR
 */
async function prepareServer(runtimeOptions = {}) {
  let requestedSelection = null;
  // Set when the bundled JAR failed verification: it is kept only until a
  // verified download replaces it, and must not survive a failed download.
  let existingJarRejected = false;
  try {
    if (process.env.SKIP_PREPARE_SERVER === "true") {
      console.log("SKIP_PREPARE_SERVER=true, skipping server preparation.");
      return;
    }

    // Ensure server directory exists
    if (!fs.existsSync(SERVER_DIR)) {
      fs.mkdirSync(SERVER_DIR, { recursive: true });
      console.log("Created server directory");
    }

    const cliOptions = parseArgs(runtimeOptions.argv || []);
    if (cliOptions.help) {
      printHelp();
      return;
    }

    const forceDownload =
      runtimeOptions.forceDownload ??
      (cliOptions.forceDownload || process.env.FORCE_DOWNLOAD === "true");
    const preferLocal =
      runtimeOptions.preferLocal ??
      (cliOptions.preferLocal || process.env.PREFER_LOCAL === "true");
    const buildLocal =
      runtimeOptions.buildLocal ??
      (cliOptions.buildLocal || process.env.BUILD_LOCAL === "true");
    const explicitLocalJar =
      runtimeOptions.local ??
      cliOptions.local ??
      process.env.GLS_LOCAL_JAR ??
      null;
    const explicitUrl =
      runtimeOptions.url ?? cliOptions.url ?? process.env.GLS_URL ?? null;
    const explicitChecksum =
      runtimeOptions.checksum ??
      cliOptions.checksum ??
      process.env.GLS_CHECKSUM ??
      null;

    // Auto-detect monorepo and prefer local build
    // Only activate if no explicit version/channel selection is made
    const isMonorepo = detectMonorepoEnvironment();
    const autoPreferLocal =
      isMonorepo && !explicitUrl && !hasExplicitVersionSelection(cliOptions);

    const effectivePreferLocal = preferLocal || autoPreferLocal;

    // In monorepo, auto-build local JAR if not found
    // Disabled in CI environments (CI=true) and when BUILD_LOCAL=false
    const isCI = process.env.CI === "true";
    const autoBuildLocal =
      autoPreferLocal && !isCI && process.env.BUILD_LOCAL !== "false";
    const effectiveBuildLocal = buildLocal || autoBuildLocal;

    if (autoPreferLocal) {
      const buildStatus = autoBuildLocal
        ? " (auto-build enabled)"
        : isCI
          ? " (auto-build disabled in CI)"
          : "";
      console.log(`📦 Monorepo detected - will use local build${buildStatus}`);
    }

    // Hard local override (highest precedence)
    if (explicitLocalJar) {
      const resolvedLocal = path.resolve(explicitLocalJar);

      if (!fs.existsSync(resolvedLocal)) {
        throw new Error(`Local JAR path not found: ${resolvedLocal}`);
      }

      // Check if it's a directory - if so, search for JAR files within it
      const stat = fs.statSync(resolvedLocal);
      let jarToUse = resolvedLocal;

      if (stat.isDirectory()) {
        console.log(
          `Provided path is a directory, searching for JAR files: ${resolvedLocal}`,
        );

        // Search for groovy-lsp JAR in the directory and subdirectories
        const buildLibs = path.join(
          resolvedLocal,
          "groovy-lsp",
          "build",
          "libs",
        );
        if (fs.existsSync(buildLibs) && fs.statSync(buildLibs).isDirectory()) {
          const jarFiles = fs
            .readdirSync(buildLibs)
            .filter(
              (file) =>
                file.endsWith(".jar") &&
                file.includes("groovy-lsp") &&
                file.includes("-all"),
            )
            .sort();

          if (jarFiles.length > 0) {
            jarToUse = path.join(buildLibs, jarFiles[0]);
            console.log(
              `Found JAR in build directory: ${sanitizeForLog(jarToUse)}`,
            );
          } else {
            throw new Error(
              `No groovy-lsp JAR found in ${buildLibs}. Did you run the build?`,
            );
          }
        } else {
          // Try to find any JAR in the provided directory
          const jarFiles = fs
            .readdirSync(resolvedLocal)
            .filter((file) => file.endsWith(".jar"))
            .sort();

          if (jarFiles.length > 0) {
            jarToUse = path.join(resolvedLocal, jarFiles[0]);
            console.log(`Found JAR in directory: ${sanitizeForLog(jarToUse)}`);
          } else {
            throw new Error(
              `No JAR files found in directory: ${resolvedLocal}`,
            );
          }
        }
      }

      console.log(
        `Using explicitly provided local JAR: ${sanitizeForLog(jarToUse)}`,
      );
      copyLocalJar(jarToUse, { forceDownload });
      return;
    }

    // URL override (second highest precedence)
    if (explicitUrl) {
      console.log("Using explicitly provided URL for download...");
      await downloadFromUrl(explicitUrl, explicitChecksum);
      return;
    }

    // Try local build first if preferred
    if (effectivePreferLocal || effectiveBuildLocal) {
      let localJarPath = findLocalGroovyLspJar();

      // If no local JAR found and buildLocal is enabled, try to build it
      if (!localJarPath && effectiveBuildLocal) {
        console.log(
          "No local JAR found, building via './gradlew :gls:stageEditorServer'...",
        );
        buildLocalJar();
        // After building, try to find the JAR again
        localJarPath = findLocalGroovyLspJar();
      }

      if (localJarPath) {
        localJarPath = ensureFreshLocalJar(localJarPath, {
          readProvenance: readJarProvenance,
          getHead: readCheckoutHead,
          getCheckoutDirty: readCheckoutDirty,
          isExplicit: (jar) =>
            [explicitLocalJar, process.env.GLS_LOCAL_JAR].some(
              (explicit) =>
                explicit && path.resolve(explicit) === path.resolve(jar),
            ),
          strict: requireFresh(process.env),
          rebuild: effectiveBuildLocal ? buildLocalJar : null,
          findJar: findLocalGroovyLspJar,
          warn: (message) => console.warn(message),
        });
        copyLocalJar(localJarPath, { forceDownload });
        return;
      } else if (effectiveBuildLocal) {
        // If buildLocal was enabled but we still don't have a JAR, that's an error
        throw new Error(
          "Build completed but no JAR found. Check './gradlew :gls:stageEditorServer' output for errors.",
        );
      } else {
        console.log(
          "PREFER_LOCAL=true but no local JAR found, falling back to download...",
        );
      }
    }

    requestedSelection = deriveSelection({
      ...cliOptions,
      tag: runtimeOptions.tag ?? cliOptions.tag,
      nightly: runtimeOptions.nightly ?? cliOptions.nightly,
      latest: runtimeOptions.latest ?? cliOptions.latest,
      channel: runtimeOptions.channel ?? cliOptions.channel,
    });

    const selectionLabel =
      requestedSelection.type === "tag"
        ? `tag:${requestedSelection.tag}`
        : requestedSelection.type;

    let authToken = null;
    if (requestedSelection.type !== "pinned") {
      authToken = resolveGitHubToken();
      if (!authToken) {
        console.warn(
          [
            "⚠️  No GitHub token found. GitHub API requests may be rate-limited.",
            "Tip: run 'gh auth login' or set GH_TOKEN/GITHUB_TOKEN to avoid 403 rate limits.",
          ].join("\n"),
        );
      }
    }

    console.log(`Requested Groovy LSP selection: ${selectionLabel}`);
    const target = await resolveTarget(requestedSelection, { authToken });
    target.checksum = requireChecksum(target.checksum, target.assetName);

    // Reuse the existing JAR only when it matches the release digest; the
    // version marker alone does not prove the file is complete.
    if (!forceDownload && fs.existsSync(JAR_PATH)) {
      let matchesRelease = false;
      try {
        await verifyChecksum(JAR_PATH, target.checksum);
        matchesRelease = true;
      } catch (checksumError) {
        console.warn(
          `Existing ${CANONICAL_JAR_NAME} does not match ${target.tag}: ${checksumError.message}`,
        );
        console.warn("Re-downloading Groovy LSP...");
        existingJarRejected = true;
      }
      if (matchesRelease) {
        console.warn(
          `⚠️  REUSING existing ${CANONICAL_JAR_NAME} for ${target.tag} (checksum matches; no download). (provably the requested release; set FORCE_DOWNLOAD=true to refresh anyway).`,
        );
        writeInstalledVersion(target.tag, "reused");
        return;
      }
    }

    // Download from GitHub releases
    console.log(
      `Downloading Groovy LSP release (${sanitizeForLog(target.tag)})...`,
    );
    await downloadRelease(target);

    // Final validation - ensure JAR is valid before completing
    if (fs.existsSync(JAR_PATH)) {
      try {
        validateJarFile(JAR_PATH);
        console.log(`\u2713 Final validation: ${CANONICAL_JAR_NAME} is valid`);
      } catch (error) {
        console.error(
          `\u274c Final validation failed: ${sanitizeForLog(error.message)}`,
        );
        throw new Error(
          `Server JAR validation failed at completion: ${sanitizeForLog(error.message)}`,
          { cause: error },
        );
      }
    } else {
      throw new Error(`Server JAR not found after preparation: ${JAR_PATH}`);
    }
  } catch (error) {
    console.error("❌ Error preparing Groovy Language Server:");
    console.error(sanitizeForLog(error.message));

    if (existingJarRejected) {
      fs.rmSync(JAR_PATH, { force: true });
      fs.rmSync(VERSION_FILE, { force: true });
      console.error(
        `Removed ${CANONICAL_JAR_NAME}: it failed verification and no replacement was installed.`,
      );
    }

    const httpError =
      error instanceof HttpError
        ? error
        : error?.cause instanceof HttpError
          ? error.cause
          : null;
    if (httpError?.isGitHubRateLimit) {
      const reset = httpError.rateLimit?.reset
        ? new Date(Number(httpError.rateLimit.reset) * 1000).toISOString()
        : null;
      console.error("");
      console.error("GitHub API rate limit exceeded.");
      console.error(
        "Authenticate to get a higher limit: run 'gh auth login' or set GH_TOKEN/GITHUB_TOKEN.",
      );
      if (reset) {
        console.error(`Rate limit resets at: ${reset}`);
      }
    }

    // Provide helpful error messages
    if (
      error.message.includes("ENOTFOUND") ||
      error.message.includes("timeout")
    ) {
      console.error("");
      console.error(
        "Network error. Please check your internet connection or try again later.",
      );

      // Offer pinned fallback as escape hatch
      if (
        requestedSelection?.type === "latest" &&
        process.env.GLS_ALLOW_PINNED_FALLBACK === "true"
      ) {
        console.warn("");
        console.warn(
          `⚠️  Attempting fallback to pinned version (${PINNED_RELEASE_TAG})...`,
        );
        try {
          await downloadRelease({
            tag: PINNED_RELEASE_TAG,
            assetName: PINNED_JAR_ASSET,
            downloadUrl: PINNED_DOWNLOAD_URL,
            checksum: PINNED_JAR_SHA256,
          });
          console.log("✅ Fallback successful!");
          return;
        } catch (fallbackError) {
          console.error(
            "❌ Fallback also failed:",
            sanitizeForLog(fallbackError.message),
          );
        }
      }
    }

    console.error("");
    if (requestedSelection) {
      const selectionLabel =
        requestedSelection.type === "tag"
          ? `tag:${sanitizeForLog(requestedSelection.tag)}`
          : requestedSelection.type;
      console.error(`Requested selection: ${selectionLabel}`);
    }
    console.error(`Pinned fallback available: ${PINNED_RELEASE_TAG}`);
    console.error(
      `Release page: https://github.com/albertocavalcante/gvy/releases/tag/${PINNED_RELEASE_TAG}`,
    );
    console.error(
      "To use pinned version: GLS_USE_PINNED=true or GLS_CHANNEL=pinned",
    );
    console.error(
      "To enable auto-fallback on network errors: GLS_ALLOW_PINNED_FALLBACK=true",
    );
    console.error(
      "To try the latest nightly: GLS_CHANNEL=nightly or pass --nightly",
    );
    console.error("");
    console.error("You can also manually copy a JAR file to:");
    console.error(`  ${JAR_PATH}`);

    const ignoreFailure = process.env.IGNORE_DOWNLOAD_FAILURE === "true";

    // Only ignore failure if explicitly requested via IGNORE_DOWNLOAD_FAILURE=true
    // CI should NOT silently ignore failures - tests need the JAR
    if (ignoreFailure) {
      console.warn(
        "\n⚠️ WARNING: Server JAR preparation failed, but ignoring (IGNORE_DOWNLOAD_FAILURE=true).",
      );
      console.warn(
        "The extension will NOT work without the server JAR unless a custom path is configured.",
      );
      process.exit(0);
    }

    process.exit(1);
  }
}

// Run the script
if (require.main === module) {
  async function run() {
    const cliOptions = parseArgs(process.argv.slice(2));

    if (cliOptions.printReleaseTag) {
      process.stdout.write(PINNED_RELEASE_TAG);
      return;
    }

    if (cliOptions.unknown.length > 0) {
      console.warn(
        `Ignoring unknown options: ${cliOptions.unknown.join(", ")}`,
      );
    }

    if (cliOptions.help) {
      printHelp();
      return;
    }

    console.log("Preparing Groovy Language Server...");
    await prepareServer({ argv: process.argv.slice(2) });
    console.log("✅ Server preparation complete!");
  }

  run().catch((error) => {
    console.error("Failed to prepare Groovy Language Server:", error);
    process.exit(1);
  }); // NOSONAR: top-level await is not available in this CommonJS entrypoint
}

function printHelp() {
  const help = `
Usage: node tools/prepare-server.js [options]

Options:
  --tag <tag>            Download a specific Groovy LSP release tag (e.g. v0.4.8, nightly-*)
  --nightly              Download the latest nightly/prerelease build
  --latest               Download the latest stable release
  --channel <name>       Select channel: nightly | release | pinned
  --local <path>         Use a specific local groovy-lsp JAR (skips download)
  --url <url>            Download from a URL (supports GitHub Actions artifacts)
  --checksum <sha256>    Required SHA256 checksum for URL downloads
  --prefer-local         Prefer local groovy-lsp builds from common paths
  --build-local          Build local JAR via './gradlew :gls:stageEditorServer' if not found (monorepo only)
                         NOTE: Auto-enabled in monorepo (disabled in CI); use BUILD_LOCAL=false to disable
  --force-download       Always download/copy even if a JAR already exists
  --print-release-tag    Print the pinned release tag and exit
  -h, --help             Show this help message

Notes:
  Precedence: --local > --url > existing bundled JAR > --prefer-local > GitHub download
  Default: Downloads the pinned, checksum-verified release from GitHub
  Monorepo: Auto-detected - local build used automatically

Environment Variables:
  Version Selection:
    GLS_TAG=<version>         Use specific version (e.g., v0.4.8)
    GLS_CHANNEL=nightly       Use latest nightly build
    GLS_CHANNEL=release       Use latest stable release
    GLS_CHANNEL=pinned        Use pinned version (v0.4.8, default)
    GLS_USE_PINNED=true       Alternative to GLS_CHANNEL=pinned

  Download Behavior:
    PREFER_LOCAL=true         Use local build from ../groovy-lsp/build/libs/
    BUILD_LOCAL=true          Build local JAR via './gradlew :gls:stageEditorServer' if not found (monorepo only)
    FORCE_DOWNLOAD=true       Re-download even if JAR exists
    GLS_REQUIRE_FRESH=1       Rebuild (or fail) when the local build is from another commit than HEAD
    GLS_ALLOW_PINNED_FALLBACK=true  Fall back to pinned on network failure
    SKIP_PREPARE_SERVER=true  Skip server preparation entirely

  Advanced:
    GLS_LOCAL_JAR=<path>      Use JAR from specific path
    GLS_URL=<url>             Download from custom URL
    GLS_CHECKSUM=<sha256>     Required for custom download
    GITHUB_TOKEN / GH_TOKEN   GitHub API authentication

Token resolution (for GitHub API requests):
  GH_TOKEN > GITHUB_TOKEN > gh auth token
`.trim();

  console.log(help);
}

module.exports = {
  PINNED_RELEASE_TAG,
  downloadFromUrl,
  deriveSelection,
  hasExplicitVersionSelection,
  installVerifiedJar,
  requireChecksum,
  ensureFreshLocalJar,
};
