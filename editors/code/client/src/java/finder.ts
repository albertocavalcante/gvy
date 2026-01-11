import * as path from "path";
import * as fs from "fs";
import { exec } from "child_process";
import { promisify } from "util";
import { workspace } from "vscode";
import { findRuntimes, getRuntime, getSources, IJavaRuntime } from "jdk-utils";

const execAsync = promisify(exec);
const fsAccess = promisify(fs.access);
const fsReaddir = promisify(fs.readdir);

// Minimum supported Java version for Groovy Language Server
export const MINIMUM_JAVA_VERSION = 17;

export type JavaSource =
  | "setting"
  | "java_home"
  | "jdk_manager"
  | "system"
  | "login_shell";

export interface JavaResolution {
  path: string;
  version: number;
  source: JavaSource;
}

/**
 * Finds Java installation from multiple sources using jdk-utils.
 * Falls back to login shell for lazy-loading shell functions (SDKMAN, etc.)
 *
 * Search priority:
 * 1. groovy.java.home setting (user-configured path)
 * 2. jdk-utils detection (JAVA_HOME, PATH, SDKMAN, jEnv, jabba, asdf, common paths)
 * 3. Login shell fallback (handles lazy-loaded shell functions)
 *
 * @returns JavaResolution with path, version, and source, or null if no Java found
 */
export async function findJava(): Promise<JavaResolution | null> {
  // 1. Check groovy.java.home setting first (highest priority)
  const configuredHome = workspace
    .getConfiguration("groovy")
    .get<string>("java.home");
  if (configuredHome) {
    const expandedPath = expandHomeDir(configuredHome);
    const runtime = await getRuntime(expandedPath, { withVersion: true });
    if (
      runtime?.version?.major &&
      runtime.version.major >= MINIMUM_JAVA_VERSION
    ) {
      return {
        path: runtime.homedir,
        version: runtime.version.major,
        source: "setting",
      };
    }
    // Setting configured but invalid - we'll report this later
    if (runtime?.version?.major) {
      return {
        path: runtime.homedir,
        version: runtime.version.major,
        source: "setting",
      };
    }
  }

  // 2. Use jdk-utils to scan JAVA_HOME, PATH, SDKMAN, jEnv, jabba, asdf, common paths
  try {
    const runtimes = await findRuntimes({
      checkJavac: true,
      withVersion: true,
      withTags: true,
    });
    const validRuntimes = runtimes.filter(
      (r) => r.version?.major && r.version.major >= MINIMUM_JAVA_VERSION,
    );

    if (validRuntimes.length > 0) {
      // Sort by source priority (env vars > JDK managers > common paths)
      validRuntimes.sort((a, b) => getSourcePriority(a) - getSourcePriority(b));
      const best = validRuntimes[0];
      return {
        path: best.homedir,
        version: best.version!.major,
        source: categorizeSource(getSources(best)),
      };
    }

    // Also check if there's any Java at all (even if version is too low)
    if (runtimes.length > 0) {
      const anyRuntime = runtimes[0];
      if (anyRuntime.version?.major) {
        return {
          path: anyRuntime.homedir,
          version: anyRuntime.version.major,
          source: categorizeSource(getSources(anyRuntime)),
        };
      }
    }
  } catch {
    // jdk-utils failed, continue to login shell fallback
  }

  // 3. Login shell fallback for lazy-loading shell functions (SDKMAN lazy init, etc.)
  const loginShellResult = await tryLoginShell();
  if (loginShellResult) {
    return { ...loginShellResult, source: "login_shell" };
  }

  return null;
}

/**
 * Legacy function for backward compatibility - returns just the Java executable path.
 * Synchronously searches for Java in groovy.java.home setting, then JAVA_HOME, then PATH.
 *
 * @deprecated Use findJava() instead for comprehensive detection with version info
 * @returns Path to java executable (e.g., "/path/to/jdk/bin/java" or "java")
 */
export function findJavaSync(): string {
  const executableFile = process.platform === "win32" ? "java.exe" : "java";

  // 1. Check configuration setting first
  const javaHome = workspace
    .getConfiguration("groovy")
    .get<string>("java.home");
  if (javaHome) {
    const javaPath = path.join(expandHomeDir(javaHome), "bin", executableFile);
    return javaPath;
  }

  // 2. Check JAVA_HOME environment variable
  const envJavaHome = process.env.JAVA_HOME;
  if (envJavaHome) {
    return path.join(envJavaHome, "bin", executableFile);
  }

  // 3. Fallback to system PATH
  return "java";
}

/**
 * Tries to find Java via login shell (bash/zsh with -l flag).
 * This handles lazy-loading shell functions like SDKMAN's lazy init pattern.
 *
 * Some package managers (e.g., SDKMAN) use lazy-loading patterns where
 * the Java path is only available after shell config files (.bashrc, .zshrc)
 * are sourced. A login shell (-l) ensures these configs are loaded.
 *
 * @returns Object with path and version if found, null otherwise
 */
async function tryLoginShell(): Promise<{
  path: string;
  version: number;
} | null> {
  // Windows doesn't use login shells the same way
  if (process.platform === "win32") return null;

  const shell = process.env.SHELL || "/bin/bash";
  try {
    // Login shell (-l) loads user's config files (.zshrc, .bashrc, etc.)
    // This triggers lazy-loading shell functions like SDKMAN
    const { stdout } = await execAsync(
      `${shell} -l -c "which java 2>/dev/null"`,
      {
        timeout: 10000, // 10 second timeout
      },
    );
    const javaPath = stdout.trim();
    if (
      !javaPath ||
      javaPath.includes("not found") ||
      javaPath.includes("no java")
    ) {
      return null;
    }

    // Resolve symlinks and get the actual JAVA_HOME
    const { stdout: realPath } = await execAsync(
      `${shell} -l -c "readlink -f '${javaPath}' 2>/dev/null || realpath '${javaPath}' 2>/dev/null || echo '${javaPath}'"`,
      {
        timeout: 5000,
      },
    );
    const resolvedPath = realPath.trim();

    // Get JAVA_HOME (go up two directories from bin/java)
    const javaHome = path.dirname(path.dirname(resolvedPath));

    // Validate with jdk-utils
    const runtime = await getRuntime(javaHome, { withVersion: true });
    if (runtime?.version?.major) {
      return { path: runtime.homedir, version: runtime.version.major };
    }

    // Fallback: try to get version directly
    const { stdout: versionOut, stderr: versionErr } = await execAsync(
      `${shell} -l -c "java -version 2>&1"`,
      {
        timeout: 10000,
      },
    );
    const versionOutput = versionOut || versionErr;
    const versionMatch = versionOutput.match(/version "(\d+)(?:\.(\d+))?/);
    if (versionMatch) {
      const majorVersion = parseInt(versionMatch[1], 10);
      if (!isNaN(majorVersion)) {
        return { path: javaHome, version: majorVersion };
      }
    }
  } catch {
    // Login shell approach failed
  }
  return null;
}

/**
 * Expands tilde (~) to the user's home directory.
 * Handles both Unix-style (HOME) and Windows-style (USERPROFILE) environments.
 *
 * @param p Path potentially starting with ~
 * @returns Expanded absolute path
 */
function expandHomeDir(p: string): string {
  if (p.startsWith("~")) {
    return path.join(
      process.env.HOME || process.env.USERPROFILE || "",
      p.slice(1),
    );
  }
  return p;
}

/**
 * Returns priority score for JDK source (lower is better).
 * Environment variables are prioritized, followed by JDK managers, then common paths.
 *
 * @param runtime The runtime to evaluate
 * @returns Priority score (0 = highest priority)
 */
function getSourcePriority(runtime: IJavaRuntime): number {
  const sources = getSources(runtime);
  const envVars = ["JDK_HOME", "JAVA_HOME", "PATH"];
  const jdkManagers = ["SDKMAN", "jEnv", "jabba", "asdf"];

  // Check environment variables first (highest priority)
  for (let i = 0; i < envVars.length; i++) {
    if (sources.includes(envVars[i])) {
      return i;
    }
  }

  // JDK managers next
  if (sources.some((source) => jdkManagers.includes(source))) {
    return envVars.length + 1;
  }

  // Common system paths
  if (sources.length === 0) {
    return envVars.length + 2;
  }

  // Other sources
  return envVars.length + 3;
}

/**
 * Categorizes JDK source for display purposes.
 * Maps technical source names from jdk-utils to user-friendly categories.
 *
 * @param sources Array of source names from jdk-utils (e.g., ["JAVA_HOME", "PATH"])
 * @returns JavaSource category for display
 */
function categorizeSource(sources: string[]): JavaSource {
  if (sources.includes("JAVA_HOME") || sources.includes("JDK_HOME")) {
    return "java_home";
  }
  if (sources.some((s) => ["SDKMAN", "jEnv", "jabba", "asdf"].includes(s))) {
    return "jdk_manager";
  }
  if (sources.includes("PATH")) {
    return "system";
  }
  return "system";
}

/**
 * Extended Java resolution info including raw source names for display.
 */
export interface JavaResolutionExtended extends JavaResolution {
  /** Human-readable source description (e.g., "SDKMAN", "Homebrew", "JAVA_HOME") */
  sourceDescription: string;
}

// Debug logging helper - enable via "Developer: Toggle Developer Tools" in VS Code
const DEBUG_JDK_DETECTION =
  process.env.GROOVY_DEBUG_JDK === "true" ||
  process.env.NODE_ENV === "development";

function debugLog(message: string, ...args: unknown[]): void {
  if (DEBUG_JDK_DETECTION) {
    console.log(`[groovy-jdk] ${message}`, ...args);
  }
}

/**
 * Finds all Java installations on the system.
 * Returns all JDKs found, sorted by version (descending) and source priority.
 *
 * Use this for presenting a picker to the user when they need to select a JDK.
 *
 * Debug logging: Set GROOVY_DEBUG_JDK=true or NODE_ENV=development to see
 * detailed JDK detection logs in Developer Tools console.
 *
 * @param minVersion Optional minimum version filter (default: no filter)
 * @param preferredVersion Optional version to prioritize in sorting
 */
export async function findAllJdks(
  minVersion?: number,
  preferredVersion?: number,
): Promise<JavaResolutionExtended[]> {
  debugLog("Starting JDK detection", { minVersion, preferredVersion });
  const results: JavaResolutionExtended[] = [];

  // 1. Use jdk-utils to scan all sources
  try {
    debugLog("Scanning with jdk-utils...");
    const runtimes = await findRuntimes({
      checkJavac: true,
      withVersion: true,
      withTags: true,
    });
    debugLog(`jdk-utils found ${runtimes.length} runtimes`);

    for (const runtime of runtimes) {
      if (!runtime.version?.major) continue;
      if (minVersion && runtime.version.major < minVersion) continue;

      const sources = getSources(runtime);
      debugLog(`  Found: Java ${runtime.version.major} at ${runtime.homedir}`, {
        sources,
      });
      results.push({
        path: runtime.homedir,
        version: runtime.version.major,
        source: categorizeSource(sources),
        sourceDescription: formatSourceDescription(sources),
      });
    }
  } catch (error) {
    // jdk-utils failed, continue with login shell fallback
    console.warn(
      "Failed to find JDKs using jdk-utils, falling back to other methods. Error:",
      error,
    );
  }

  // Track discovered paths to avoid duplicates
  const existingPaths = new Set(results.map((r) => r.path));

  // 2. User-configured runtimes (highest priority for user intent)
  debugLog("Checking user-configured runtimes (groovy.configuration.runtimes)");
  const configuredRuntimes = await readConfiguredRuntimes(existingPaths);
  debugLog(`  Found ${configuredRuntimes.length} configured runtimes`);
  for (const jdk of configuredRuntimes) {
    if (!minVersion || jdk.version >= minVersion) {
      results.push(jdk);
    }
  }

  // 3. SDKMAN detection (Homebrew-installed SDKMAN not covered by jdk-utils)
  debugLog("Scanning SDKMAN (including Homebrew-installed)...");
  const sdkmanJdks = await scanSdkmanJdks(existingPaths);
  debugLog(`  Found ${sdkmanJdks.length} SDKMAN JDKs`);
  for (const jdk of sdkmanJdks) {
    debugLog(
      `    Java ${jdk.version} at ${jdk.path} (${jdk.sourceDescription})`,
    );
    if (!minVersion || jdk.version >= minVersion) {
      results.push(jdk);
    }
  }

  // 4. Try login shell to find additional JDKs (handles SDKMAN lazy init)
  debugLog("Trying login shell fallback...");
  const loginShellResult = await tryLoginShell();
  if (loginShellResult) {
    const alreadyFound = existingPaths.has(loginShellResult.path);
    debugLog(`  Login shell found Java ${loginShellResult.version}`, {
      path: loginShellResult.path,
      alreadyFound,
    });
    if (!alreadyFound) {
      if (!minVersion || loginShellResult.version >= minVersion) {
        results.push({
          ...loginShellResult,
          source: "login_shell",
          sourceDescription: "Shell (lazy-loaded)",
        });
        existingPaths.add(loginShellResult.path);
      }
    }
  } else {
    debugLog("  Login shell found no additional JDKs");
  }

  // 5. Sort results
  results.sort((a, b) => {
    // Preferred version first
    if (preferredVersion) {
      const aIsPreferred = a.version === preferredVersion;
      const bIsPreferred = b.version === preferredVersion;
      if (aIsPreferred && !bIsPreferred) return -1;
      if (bIsPreferred && !aIsPreferred) return 1;
    }

    // Then by version (descending - newer is better)
    if (a.version !== b.version) {
      return b.version - a.version;
    }

    // Then by source priority
    return (
      getSourcePriorityByCategory(a.source) -
      getSourcePriorityByCategory(b.source)
    );
  });

  // 6. Remove duplicates (same path)
  const seen = new Set<string>();
  const deduplicated = results.filter((r) => {
    if (seen.has(r.path)) return false;
    seen.add(r.path);
    return true;
  });

  debugLog(`JDK detection complete. Found ${deduplicated.length} unique JDKs:`);
  for (const jdk of deduplicated) {
    debugLog(`  - Java ${jdk.version} [${jdk.sourceDescription}] ${jdk.path}`);
  }

  return deduplicated;
}

/**
 * Formats source names for human-readable display.
 * Converts technical source names to friendly names and limits to 2 sources max.
 *
 * @param sources Array of source names from jdk-utils
 * @returns Formatted string (e.g., "JAVA_HOME, PATH" or "SDKMAN")
 */
function formatSourceDescription(sources: string[]): string {
  if (sources.length === 0) return "System";

  // Map technical names to friendly names
  const friendlyNames: Record<string, string> = {
    JAVA_HOME: "JAVA_HOME",
    JDK_HOME: "JDK_HOME",
    PATH: "PATH",
    SDKMAN: "SDKMAN",
    jEnv: "jEnv",
    jabba: "jabba",
    asdf: "asdf",
  };

  const displaySources = sources.map((s) => friendlyNames[s] || s).slice(0, 2); // Show at most 2 sources

  return displaySources.join(", ");
}

/**
 * Returns priority score for JavaSource category (lower is better).
 * Used for sorting JDKs when multiple are found.
 *
 * @param source The JavaSource category
 * @returns Priority score (0 = highest priority)
 */
function getSourcePriorityByCategory(source: JavaSource): number {
  switch (source) {
    case "setting":
      return 0;
    case "java_home":
      return 1;
    case "jdk_manager":
      return 2;
    case "system":
      return 3;
    case "login_shell":
      return 4;
    default:
      return 5;
  }
}

// =============================================================================
// SDKMAN Detection (Homebrew-installed)
// =============================================================================
//
// WORKAROUND(jdk-detection): Explicit Homebrew SDKMAN path scanning.
// When SDKMAN is installed via `brew install sdkman-cli`, JDKs are stored at:
//   /opt/homebrew/opt/sdkman-cli/libexec/candidates/java/ (ARM)
//   /usr/local/opt/sdkman-cli/libexec/candidates/java/ (Intel)
//
// This is a defensive workaround to ensure Homebrew-installed SDKMAN is detected.
// While jdk-utils@0.6.0+ may support Homebrew SDKMAN, this explicit scanning
// provides redundancy and ensures detection across different jdk-utils versions.
//
// TODO(#22): Verify jdk-utils@0.6.0+ Homebrew SDKMAN detection and potentially
//   remove this workaround if upstream support is confirmed reliable.
//   See: https://github.com/Eskibear/node-jdk-utils/issues/22
// =============================================================================

/**
 * Known locations for Homebrew-installed SDKMAN.
 * Standard ~/.sdkman is handled by jdk-utils, these are the Homebrew-specific paths.
 */
const HOMEBREW_SDKMAN_PATHS = {
  arm64: "/opt/homebrew/opt/sdkman-cli/libexec",
  x64: "/usr/local/opt/sdkman-cli/libexec",
} as const;

/**
 * Scans SDKMAN candidates directory for installed JDKs.
 *
 * Checks:
 * 1. SDKMAN_DIR environment variable (canonical, works for any installation)
 * 2. Homebrew-installed SDKMAN paths (not covered by jdk-utils)
 *
 * @param existingPaths Set of already discovered JDK paths (mutated: new paths are added)
 */
async function scanSdkmanJdks(
  existingPaths: Set<string>,
): Promise<JavaResolutionExtended[]> {
  const results: JavaResolutionExtended[] = [];

  // Build list of SDKMAN locations to check
  const sdkmanLocations: Array<{ dir: string; source: string }> = [];

  // 1. SDKMAN_DIR environment variable (most reliable - set by SDKMAN itself)
  const sdkmanDir = process.env.SDKMAN_DIR;
  if (sdkmanDir) {
    sdkmanLocations.push({ dir: sdkmanDir, source: "SDKMAN" });
  }

  // 2. Homebrew-installed SDKMAN (macOS only, not covered by jdk-utils)
  // HACK: These are hardcoded Homebrew paths. See comment block above.
  if (process.platform === "darwin") {
    const arch = process.arch === "arm64" ? "arm64" : "x64";
    const homebrewPath = HOMEBREW_SDKMAN_PATHS[arch];
    // Only add if different from SDKMAN_DIR (avoid duplicate scanning)
    if (homebrewPath !== sdkmanDir) {
      sdkmanLocations.push({ dir: homebrewPath, source: "SDKMAN (Homebrew)" });
    }
  }

  for (const { dir, source } of sdkmanLocations) {
    const candidatesDir = path.join(dir, "candidates", "java");
    try {
      await fsAccess(candidatesDir, fs.constants.R_OK);
      const entries = await fsReaddir(candidatesDir, { withFileTypes: true });

      for (const entry of entries) {
        if (!entry.isDirectory()) continue;
        // Skip the "current" symlink
        if (entry.name === "current") continue;

        const javaHome = path.join(candidatesDir, entry.name);

        // Skip if already discovered by jdk-utils
        if (existingPaths.has(javaHome)) continue;

        // Validate with jdk-utils to get version info
        try {
          const runtime = await getRuntime(javaHome, { withVersion: true });
          if (runtime?.version?.major) {
            results.push({
              path: runtime.homedir,
              version: runtime.version.major,
              source: "jdk_manager",
              sourceDescription: source,
            });
            existingPaths.add(runtime.homedir);
          }
        } catch (err) {
          debugLog(`Invalid JDK path in ${source}: ${javaHome}`, err);
        }
      }
    } catch (err) {
      debugLog(`Could not scan SDKMAN directory ${candidatesDir}:`, err);
    }
  }

  return results;
}

// =============================================================================
// User-Configured Runtimes
// =============================================================================

/**
 * Runtime configuration from groovy.configuration.runtimes setting.
 * Modeled after vscode-java's java.configuration.runtimes.
 */
interface RuntimeConfig {
  name: string; // e.g., "JDK-17", "JDK-21"
  path: string;
  /**
   * Reserved for future use and parity with vscode-java's java.configuration.runtimes.
   *
   * NOTE: This flag is currently not interpreted by the Groovy extension. It is kept
   * here for configuration compatibility and may be wired up in a future release.
   */
  default?: boolean;
}

/**
 * Reads user-configured JDK runtimes from groovy.configuration.runtimes setting.
 *
 * This allows users to explicitly specify JDK paths, which is the most reliable
 * method and works regardless of installation method.
 *
 * @param existingPaths Set of already discovered JDK paths (mutated: new paths are added)
 */
async function readConfiguredRuntimes(
  existingPaths: Set<string>,
): Promise<JavaResolutionExtended[]> {
  const results: JavaResolutionExtended[] = [];

  const runtimes = workspace
    .getConfiguration("groovy")
    .get<RuntimeConfig[]>("configuration.runtimes", []);

  // Validate that runtimes is actually an array to prevent "not iterable" errors
  if (!Array.isArray(runtimes)) {
    console.warn(
      "groovy.configuration.runtimes is not an array. Expected array, got:",
      typeof runtimes,
    );
    return results;
  }

  for (const config of runtimes) {
    if (!config.path) continue;

    const expandedPath = expandHomeDir(config.path);

    // Skip if already discovered
    if (existingPaths.has(expandedPath)) continue;

    try {
      const runtime = await getRuntime(expandedPath, { withVersion: true });
      if (runtime?.version?.major) {
        results.push({
          path: runtime.homedir,
          version: runtime.version.major,
          source: "setting",
          sourceDescription: `Configured (${config.name})`,
        });
        existingPaths.add(runtime.homedir);
      }
    } catch (err) {
      debugLog(
        `Invalid runtime path configured for ${config.name}: ${expandedPath}`,
        err,
      );
    }
  }

  return results;
}
