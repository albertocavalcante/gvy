// @ts-check
const path = require("node:path");
const { spawnSync } = require("node:child_process");
const { installerCommand } = require("../editors/code/tools/editor-command.js");
const root = path.resolve(__dirname, "..");

/** @param {string} cmd @param {string[]} args @param {{cwd?: string, env?: NodeJS.ProcessEnv}} options */
function command(cmd, args, options = {}) {
  const invocation = installerCommand(cmd, args);
  const result = spawnSync(invocation.cmd, invocation.args, {
    cwd: root,
    env: process.env,
    ...options,
    shell: invocation.shell,
    stdio: "inherit",
  });
  if (result.error) throw result.error;
  if (result.status !== 0)
    throw new Error(`${cmd} exited ${result.status ?? result.signal}`);
}

/** @param {string[]} args */
function gradle(args) {
  command(
    path.join(root, process.platform === "win32" ? "gradlew.bat" : "gradlew"),
    args,
  );
}

/** @param {string[]} args @param {NodeJS.ProcessEnv} overrides */
function extension(args, overrides = {}) {
  const env = { ...process.env, ...overrides };
  const cwd = path.join(root, "editors", "code");
  if (env.npm_execpath && /pnpm\.c?js$/.test(path.basename(env.npm_execpath)))
    command(process.execPath, [env.npm_execpath, ...args], { cwd, env });
  else command("pnpm", args, { cwd, env });
}

/** @param {string[]} args */
function installSource(args) {
  command(process.execPath, [
    path.join(root, "editors", "code", "tools", "install-extension.js"),
    ...args,
  ]);
}

module.exports = { command, gradle, extension, installSource };
