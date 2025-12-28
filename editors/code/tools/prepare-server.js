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
const { validateJarFile } = require("./validate-server.js");
const { extractOrCopyJar, isZipFile } = require("./zip.js");
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
 * Finds local Groovy LSP JAR file in common development locations
 */
function findLocalGroovyLspJar() {
  const searchPaths = [
    // 1. Environment variable override
    process.env.GLS_LOCAL_JAR,

    // 2. Sibling directory (common for development)
    path.join(__dirname, "..", "..", "groovy-lsp", "build", "libs"),

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
          console.log(
            `Found local JAR via GLS_LOCAL_JAR: ${searchPath}`,
          );
          return searchPath;
        }
        continue;
      }

      // Otherwise, search for JAR files in the directory
      if (fs.existsSync(searchPath) && fs.statSync(searchPath).isDirectory()) {
        const jarFiles = fs
          .readdirSync(searchPath)
          .filter(
            (file) => file.endsWith(".jar") && file.includes("groovy-lsp"),
          )
          .sort(); // Sort to get consistent results

        if (jarFiles.length > 0) {
          const foundJar = path.join(searchPath, jarFiles[0]); // Use first match
          console.log(`Found local JAR: ${foundJar}`);
          return foundJar;
        }
      }
    } catch (error) {
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
    throw new Error(`Source JAR validation failed: ${error.message}`);
  }

  let shouldCopy = true;

  if (!forceDownload && fs.existsSync(JAR_PATH)) {
    const localStat = fs.statSync(localJarPath);
    const existingStat = fs.statSync(JAR_PATH);

    if (localStat.mtime <= existingStat.mtime) {
      console.log(`✓ Using existing ${CANONICAL_JAR_NAME} (up to date)`);
      writeInstalledVersion("local");
      shouldCopy = false;
    }
  }

  if (shouldCopy) {
    console.log(`Copying from local build: ${localJarPath}`);
    fs.copyFileSync(localJarPath, JAR_PATH);

    // Validate destination JAR after copying
    try {
      validateJarFile(JAR_PATH);
    } catch (error) {
      // Clean up corrupt file
      try {
        fs.unlinkSync(JAR_PATH);
      } catch (cleanupError) {
        console.warn(
          `Warning: Failed to remove corrupt JAR ${JAR_PATH}: ${cleanupError.message}`,
        );
      }
      throw new Error(`Copied JAR validation failed: ${error.message}`);
    }

    writeInstalledVersion("local");
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
  const usePinned = process.env.GLS_USE_PINNED === "true" || channel === "pinned";

  // Priority: tag > nightly > pinned > latest (default)
  if (explicitTag) {
    return { type: "tag", tag: explicitTag };
  }

  if (useNightly) {
    return { type: "nightly" };
  }

  if (usePinned) {
    return { type: "pinned" };
  }

  return { type: "latest" };
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
    console.log(`${logMessage}: ${info.tag_name}`);
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
 * Reads version marker stored alongside the server JAR
 */
function readInstalledVersion() {
  try {
    const content = fs.readFileSync(VERSION_FILE, "utf8").trim();
    return content || null;
  } catch (error) {
    if (error.code !== "ENOENT") {
      console.warn(
        `Warning: Could not read version file at ${VERSION_FILE}: ${error.message}`,
      );
    }
    return null;
  }
}

/**
 * Writes version marker for the installed JAR
 */
function writeInstalledVersion(versionTag) {
  fs.writeFileSync(VERSION_FILE, `${versionTag}\n`, "utf8");
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
  if (!expectedHash) return;
  const actual = await sha256File(filePath);
  if (actual !== expectedHash) {
    throw new Error(
      `Checksum mismatch for ${filePath}. Expected ${expectedHash} but got ${actual}`,
    );
  }
}

/**
 * Downloads a JAR from a URL (handles ZIP extraction for GitHub artifacts)
 * @param {string} url - URL to download from
 * @param {string|null} expectedChecksum - Optional SHA256 checksum
 */
async function downloadFromUrl(url, expectedChecksum) {
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
  } else if (!authToken && downloadUrl.includes("api.github.com")) {
    console.warn(
      "⚠️  No GitHub token found. GitHub API downloads may be rate-limited.",
    );
  }

  // Create temp directory for download
  const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "groovy-lsp-"));
  const tempFile = path.join(tempDir, "download");

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
      extractOrCopyJar(tempFile, JAR_PATH);
    } else {
      // Direct JAR download
      console.log("Download is a direct JAR file...");
      fs.copyFileSync(tempFile, JAR_PATH);
    }

    // Validate extracted/copied JAR
    try {
      validateJarFile(JAR_PATH);
    } catch (error) {
      try {
        fs.unlinkSync(JAR_PATH);
      } catch {
        // ignore cleanup error
      }
      throw new Error(`Downloaded JAR validation failed: ${error.message}`);
    }

    // Verify checksum if provided
    if (expectedChecksum) {
      await verifyChecksum(JAR_PATH, expectedChecksum);
      console.log("✓ Checksum verified");
    } else {
      console.warn(
        "⚠️  No checksum provided for URL download; skipping verification.",
      );
    }

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
    writeInstalledVersion(`url:${markerId}`);

    console.log(`✓ Downloaded and saved as ${CANONICAL_JAR_NAME}`);
  } finally {
    // Clean up temp directory
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
  console.log(`Downloading Groovy LSP ${target.tag} (${target.assetName})...`);
  await downloadToFile(target.downloadUrl, JAR_PATH);

  // Validate downloaded JAR
  try {
    validateJarFile(JAR_PATH);
  } catch (error) {
    // Clean up corrupt download
    try {
      fs.unlinkSync(JAR_PATH);
    } catch (cleanupError) {
      console.warn(
        `Warning: Failed to remove corrupted download ${JAR_PATH}: ${cleanupError.message}`,
      );
    }
    throw new Error(`Downloaded JAR validation failed: ${error.message}`);
  }

  try {
    await verifyChecksum(JAR_PATH, target.checksum);
  } catch (error) {
    try {
      fs.unlinkSync(JAR_PATH);
    } catch (cleanupError) {
      console.warn(
        `Warning: Failed to remove corrupted download ${JAR_PATH}: ${cleanupError.message}`,
      );
    }
    throw error;
  }
  writeInstalledVersion(target.tag);
  console.log(`✓ Downloaded and saved as ${CANONICAL_JAR_NAME}`);
}

/**
 * Detects if we're running inside the monorepo
 * @returns {boolean} True if groovy-lsp sibling directory exists
 */
function detectMonorepoEnvironment() {
  const toolsDir = __dirname; // /path/to/editors/code/tools
  const extensionRoot = path.join(toolsDir, "..");
  const monorepoRoot = path.join(extensionRoot, "..", "..");
  const glsBuildFile = path.join(monorepoRoot, "groovy-lsp", "build.gradle.kts");

  try {
    return fs.existsSync(glsBuildFile);
  } catch {
    return false;
  }
}

/**
 * Main function to prepare the server JAR
 */
async function prepareServer(runtimeOptions = {}) {
  let requestedSelection = null;
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
    const installedVersion = readInstalledVersion();

    // Auto-detect monorepo and prefer local build
    const isMonorepo = detectMonorepoEnvironment();
    const autoPreferLocal = isMonorepo &&
                            !explicitUrl &&
                            !cliOptions.tag &&
                            !cliOptions.nightly;

    const effectivePreferLocal = preferLocal || autoPreferLocal;

    if (autoPreferLocal) {
      console.log("📦 Monorepo detected - using local build if available");
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
            console.log(`Found JAR in build directory: ${jarToUse}`);
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
            console.log(`Found JAR in directory: ${jarToUse}`);
          } else {
            throw new Error(
              `No JAR files found in directory: ${resolvedLocal}`,
            );
          }
        }
      }

      console.log(`Using explicitly provided local JAR: ${jarToUse}`);
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
    if (effectivePreferLocal) {
      const localJarPath = findLocalGroovyLspJar();

      if (localJarPath) {
        copyLocalJar(localJarPath, { forceDownload });
        return;
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
    if (!target.checksum) {
      console.warn(
        `⚠️  No checksum available for ${target.assetName}; proceeding without verification.`,
      );
    }

    const jarExists = fs.existsSync(JAR_PATH);
    const canVerify = !!target.checksum;

    if (!forceDownload && jarExists) {
      if (installedVersion === target.tag) {
        if (canVerify) {
          try {
            await verifyChecksum(JAR_PATH, target.checksum);
            console.log(
              `✓ Using existing ${CANONICAL_JAR_NAME} for ${target.tag}`,
            );
            return;
          } catch (checksumError) {
            console.warn(
              `Checksum mismatch for existing ${CANONICAL_JAR_NAME}: ${checksumError.message}`,
            );
            console.warn("Re-downloading Groovy LSP...");
            try {
              fs.unlinkSync(JAR_PATH);
            } catch (cleanupError) {
              console.warn(
                `Warning: Failed to remove corrupted JAR ${JAR_PATH}: ${cleanupError.message}`,
              );
            }
          }
        } else {
          console.log(
            `✓ Using existing ${CANONICAL_JAR_NAME} for ${target.tag} (checksum unavailable)`,
          );
          return;
        }
      } else {
        if (canVerify) {
          try {
            await verifyChecksum(JAR_PATH, target.checksum);
            writeInstalledVersion(target.tag);
            console.log(
              `✓ Using existing ${CANONICAL_JAR_NAME} for ${target.tag} (version marker refreshed)`,
            );
            return;
          } catch (checksumError) {
            console.warn(
              `Existing ${CANONICAL_JAR_NAME} failed checksum: ${checksumError.message}`,
            );
            console.warn("Re-downloading Groovy LSP...");
            try {
              fs.unlinkSync(JAR_PATH);
            } catch (cleanupError) {
              console.warn(
                `Warning: Failed to remove corrupted JAR ${JAR_PATH}: ${cleanupError.message}`,
              );
            }
          }
        } else {
          console.log(
            `Existing ${CANONICAL_JAR_NAME} does not match requested version (${target.tag}); downloading fresh copy...`,
          );
        }
      }
    }

    // Download from GitHub releases
    console.log(`Downloading Groovy LSP release (${target.tag})...`);
    await downloadRelease(target);

    // Final validation - ensure JAR is valid before completing
    if (fs.existsSync(JAR_PATH)) {
      try {
        validateJarFile(JAR_PATH);
        console.log(`\u2713 Final validation: ${CANONICAL_JAR_NAME} is valid`);
      } catch (error) {
        console.error(`\u274c Final validation failed: ${error.message}`);
        throw new Error(
          `Server JAR validation failed at completion: ${error.message}`,
        );
      }
    } else {
      throw new Error(`Server JAR not found after preparation: ${JAR_PATH}`);
    }
  } catch (error) {
    console.error("❌ Error preparing Groovy Language Server:");
    console.error(error.message);

    if (error instanceof HttpError && error.isGitHubRateLimit) {
      const reset = error.rateLimit?.reset
        ? new Date(Number(error.rateLimit.reset) * 1000).toISOString()
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
      if (requestedSelection?.type === "latest" && process.env.GLS_ALLOW_PINNED_FALLBACK === "true") {
        console.warn("");
        console.warn(`⚠️  Attempting fallback to pinned version (${PINNED_RELEASE_TAG})...`);
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
          console.error("❌ Fallback also failed:", fallbackError.message);
        }
      }
    }

    console.error("");
    if (requestedSelection) {
      const selectionLabel =
        requestedSelection.type === "tag"
          ? `tag:${requestedSelection.tag}`
          : requestedSelection.type;
      console.error(`Requested selection: ${selectionLabel}`);
    }
    console.error(`Pinned fallback available: ${PINNED_RELEASE_TAG}`);
    console.error(`Release page: https://github.com/albertocavalcante/gvy/releases/tag/${PINNED_RELEASE_TAG}`);
    console.error("To use pinned version: GLS_USE_PINNED=true or GLS_CHANNEL=pinned");
    console.error("To enable auto-fallback on network errors: GLS_ALLOW_PINNED_FALLBACK=true");
    console.error(
      "To try the latest nightly: GLS_CHANNEL=nightly or pass --nightly",
    );
    console.error("");
    console.error("You can also manually copy a JAR file to:");
    console.error(`  ${JAR_PATH}`);

    const requireBundle = process.env.REQUIRE_SERVER_BUNDLE === "true";
    const ignoreFailure = process.env.IGNORE_DOWNLOAD_FAILURE === "true";
    const isCI = !!(process.env.CI || process.env.GITHUB_ACTIONS);

    // Allow CI runs (except when REQUIRE_SERVER_BUNDLE=true) to continue without failing installation
    if (!requireBundle && (ignoreFailure || isCI)) {
      console.warn(
        "\n⚠️ WARNING: Server JAR download failed, but ignoring failure (CI/GITHUB_ACTIONS/IGNORE_DOWNLOAD_FAILURE).",
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
  --latest               Download the latest stable release (now the default)
  --channel <name>       Select channel: nightly | release | pinned
  --local <path>         Use a specific local groovy-lsp JAR (skips download)
  --url <url>            Download from a URL (supports GitHub Actions artifacts)
  --checksum <sha256>    Optional SHA256 checksum for URL downloads
  --prefer-local         Prefer local groovy-lsp builds from common paths
  --force-download       Always download/copy even if a JAR already exists
  --print-release-tag    Print the pinned release tag and exit
  -h, --help             Show this help message

Notes:
  Precedence: --local > --url > existing bundled JAR > --prefer-local > GitHub download
  Default: Downloads latest stable release from GitHub
  Monorepo: Auto-detected - local build used automatically

Environment Variables:
  Version Selection:
    GLS_TAG=<version>         Use specific version (e.g., v0.4.8)
    GLS_CHANNEL=nightly       Use latest nightly build
    GLS_CHANNEL=release       Use latest stable release (default)
    GLS_CHANNEL=pinned        Use pinned version (v0.4.8)
    GLS_USE_PINNED=true       Alternative to GLS_CHANNEL=pinned

  Download Behavior:
    PREFER_LOCAL=true         Use local build from ../groovy-lsp/build/libs/
    FORCE_DOWNLOAD=true       Re-download even if JAR exists
    GLS_ALLOW_PINNED_FALLBACK=true  Fall back to pinned on network failure
    SKIP_PREPARE_SERVER=true  Skip server preparation entirely

  Advanced:
    GLS_LOCAL_JAR=<path>      Use JAR from specific path
    GLS_URL=<url>             Download from custom URL
    GLS_CHECKSUM=<sha256>     Verify custom download
    GITHUB_TOKEN / GH_TOKEN   GitHub API authentication

Token resolution (for GitHub API requests):
  GH_TOKEN > GITHUB_TOKEN > gh auth token
`.trim();

  console.log(help);
}

module.exports = {
  PINNED_RELEASE_TAG,
};
