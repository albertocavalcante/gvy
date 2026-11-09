// @ts-check

// Characters cmd.exe interprets even inside double quotes, or that end
// them. Parentheses are safe when quoted ("Program Files (x86)").
const fs = require("node:fs");
const path = require("node:path");
const { spawnSync } = require("node:child_process");
const DEFAULT_INSTALLERS = [
  "code",
  "code-insiders",
  "agy",
  "cursor",
  "cursor-insiders",
];

const WINDOWS_SHELL_META = /[\r\n\0"%!^&|<>]/u;

/** @param {string} cmd @param {NodeJS.ProcessEnv} env @param {string} cwd */
function resolveWindowsCommand(cmd, env, cwd) {
  const variable = (/** @type {string} */ name) =>
    env[Object.keys(env).find((key) => key.toUpperCase() === name) || name];
  const extensions = (variable("PATHEXT") || ".COM;.EXE;.BAT;.CMD").split(";");
  const directories = /[\\/]/u.test(cmd)
    ? [cwd]
    : [cwd, ...(variable("PATH") || "").split(";")];
  for (const directory of directories) {
    const candidate = path.resolve(directory.replace(/^"|"$/gu, ""), cmd);
    const suffixes = /\.(?:com|exe|bat|cmd)$/iu.test(cmd) ? [""] : extensions;
    for (const suffix of suffixes) {
      const file = candidate + suffix;
      try {
        if (fs.statSync(file).isFile()) return file;
      } catch {
        // Keep searching PATH; ignore extensionless POSIX shims on Windows.
      }
    }
  }
  return cmd; // Let the shell report a missing command normally.
}

/**
 * Resolve Windows shims before quoting: a quoted bare name can select pnpm's
 * extensionless POSIX shim. Native executables need no shell. Batch launchers
 * use Node's cmd.exe /d /s /c handling with validated, CRT-quoted arguments.
 * @param {string} cmd @param {string[]} args @param {NodeJS.Platform} platform
 * @param {{env?: NodeJS.ProcessEnv, cwd?: string}} options
 */
function installerCommand(
  cmd,
  args,
  platform = process.platform,
  options = {},
) {
  if (platform !== "win32") return { cmd, args, shell: false };
  const resolved = resolveWindowsCommand(
    cmd,
    options.env || process.env,
    options.cwd || process.cwd(),
  );
  if (/\.(?:exe|com)$/iu.test(resolved))
    return { cmd: resolved, args, shell: false };
  for (const value of [resolved, ...args]) {
    if (WINDOWS_SHELL_META.test(value)) {
      throw new Error(
        `Argument is unsafe for the Windows command shell: ${value}`,
      );
    }
  }
  return {
    cmd: `"${resolved}"`,
    // Double terminal backslashes so the final quote is not consumed by the
    // Windows argv decoder when a batch shim forwards %* to Node/Java.
    args: args.map((arg) => '"' + arg.replace(/(\\+)$/u, "$1$1") + '"'),
    shell: true,
  };
}

/** @param {string} cmd @param {NodeJS.Platform} platform @param {typeof spawnSync} spawn */
function commandExists(cmd, platform = process.platform, spawn = spawnSync) {
  // Throws for a name cmd cannot run safely.
  const invocation = installerCommand(cmd, ["--version"], platform);
  const result = spawn(invocation.cmd, invocation.args, {
    stdio: "ignore",
    shell: invocation.shell,
  });
  if (result.error) {
    return false;
  }
  if (invocation.shell) {
    // cmd itself always starts; a missing command exits non-zero.
    return result.status === 0;
  }
  return result.status === 0 || result.status === 1; // some CLIs return 1 for --version
}

/** @param {string | null} preferred @param {NodeJS.Platform} platform @param {typeof spawnSync} spawn */
function findInstaller(
  preferred,
  platform = process.platform,
  spawn = spawnSync,
) {
  // An explicitly requested installer that cannot be run safely is an error,
  // not a reason to silently pick another one.
  if (preferred) {
    if (!commandExists(preferred, platform, spawn)) {
      throw new Error(`Requested installer is unavailable: ${preferred}`);
    }
    return preferred;
  }
  for (const candidate of DEFAULT_INSTALLERS) {
    if (candidate === preferred) continue;
    try {
      if (commandExists(candidate, platform, spawn)) return candidate;
    } catch {
      // not runnable through cmd; try the next one
    }
  }
  return null;
}

module.exports = { installerCommand, findInstaller };
