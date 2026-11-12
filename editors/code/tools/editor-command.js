// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

// @ts-check

// Characters cmd.exe interprets even inside double quotes, or that end
// them. Parentheses are safe when quoted ("Program Files (x86)").
const { spawnSync } = require("node:child_process");
const DEFAULT_INSTALLERS = [
  "code",
  "code-insiders",
  "agy",
  "cursor",
  "cursor-insiders",
];

const WINDOWS_SHELL_META = /[\r\n\0"%!^&|<>]/u;

/**
 * How to spawn an editor CLI. On Windows these are .cmd launchers (code.cmd,
 * cursor.cmd): Node finds them only through cmd.exe's PATHEXT lookup and
 * refuses to spawn .cmd files without a shell (CVE-2024-27980), so they run
 * through cmd with every argument quoted.
 */
/** @param {string} cmd @param {string[]} args @param {NodeJS.Platform} platform */
function installerCommand(cmd, args, platform = process.platform) {
  if (platform !== "win32") {
    return { cmd, args, shell: false };
  }
  for (const value of [cmd, ...args]) {
    if (WINDOWS_SHELL_META.test(value)) {
      throw new Error(
        `Argument is unsafe for the Windows command shell: ${value}`,
      );
    }
  }
  return {
    cmd: `"${cmd}"`,
    args: args.map((arg) => `"${arg}"`),
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
