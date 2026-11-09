// @ts-check

const fs = require("node:fs");
const path = require("node:path");
const os = require("node:os");
const crypto = require("node:crypto");
const { spawnSync } = require("node:child_process");
const { installerCommand, findInstaller } = require("./editor-command.js");
const { readZipEntry } = require("./zip.js");

/** @typedef {{phase: string, cmd: string, args: string[], cwd: string, env: NodeJS.ProcessEnv}} Step */
/** @typedef {{root: string, buildRoot: string, jar: string, vsix: string, init: string, steps: Step[]}} Plan */

/** @param {Buffer} bytes */
function sha256(bytes) {
  return crypto.createHash("sha256").update(bytes).digest("hex");
}

/** @param {string} source @param {string} dest */
function localJarMatches(source, dest) {
  return (
    fs.existsSync(dest) &&
    sha256(fs.readFileSync(source)) === sha256(fs.readFileSync(dest))
  );
}

/** Atomically replace a local jar after validating the staged copy.
 * @param {string} source @param {string} dest @param {(file: string) => void} validate
 */
function replaceLocalJar(source, dest, validate) {
  const temp = fs.mkdtempSync(path.join(path.dirname(dest), ".gls-copy-"));
  const candidate = path.join(temp, "gls.jar");
  try {
    fs.copyFileSync(source, candidate);
    validate(candidate);
    if (!localJarMatches(source, candidate))
      throw new Error("Local JAR changed while copying");
    fs.renameSync(candidate, dest);
  } finally {
    fs.rmSync(temp, { recursive: true, force: true });
  }
}

/** @param {NodeJS.ProcessEnv} inherited @param {string} jar */
function sourceEnvironment(inherited, jar) {
  const env = { ...inherited };
  for (const key of [
    "GLS_TAG",
    "GLS_CHANNEL",
    "GLS_URL",
    "GLS_CHECKSUM",
    "GLS_USE_PINNED",
    "USE_LATEST_GLS",
    "USE_LATEST_GROOVY_LSP",
  ])
    delete env[key];
  return {
    ...env,
    GLS_LOCAL_JAR: jar,
    FORCE_DOWNLOAD: "true",
    SKIP_PREPARE_SERVER: "false",
    GLS_ALLOW_PINNED_FALLBACK: "false",
  };
}

/** @param {string[]} args @param {NodeJS.ProcessEnv} env */
function pnpmInvocation(args, env) {
  if (env.npm_execpath && /pnpm\.c?js$/.test(path.basename(env.npm_execpath)))
    return { cmd: process.execPath, args: [env.npm_execpath, ...args] };
  return installerCommand("pnpm", args);
}

/** @param {string} root @param {string} buildRoot @param {NodeJS.ProcessEnv} env @returns {Plan} */
function createSourcePlan(root, buildRoot, env) {
  const jar = path.join(buildRoot, "build", "gls", "editor-server", "gls.jar");
  const vsix = path.join(buildRoot, "artifacts", "gvy-source.vsix");
  const init = path.join(buildRoot, "source-build.init.gradle");
  const extension = path.join(root, "editors", "code");
  const buildEnv = {
    ...env,
    GVY_BUILD_ROOT: buildRoot,
    GRADLE_USER_HOME: env.GRADLE_USER_HOME
      ? path.resolve(env.GRADLE_USER_HOME)
      : path.join(buildRoot, "gradle-cache"),
  };
  const sourceEnv = sourceEnvironment(buildEnv, jar);
  const pm = (
    /** @type {string} */ phase,
    /** @type {string[]} */ args,
    environment = sourceEnv,
  ) => ({
    phase,
    ...pnpmInvocation(args, environment),
    cwd: extension,
    env: environment,
  });
  const gradle = installerCommand(
    path.join(root, process.platform === "win32" ? "gradlew.bat" : "gradlew"),
    [
      ":gls:stageEditorServer",
      ":gls:smokeShadowJar",
      "--init-script",
      init,
      "--project-cache-dir",
      path.join(buildRoot, "project-cache"),
      "--console=plain",
    ],
  );
  return {
    root,
    buildRoot,
    jar,
    vsix,
    init,
    steps: [
      { phase: "server", ...gradle, cwd: root, env: buildEnv },
      pm("dependencies", ["install", "--frozen-lockfile"], {
        ...sourceEnv,
        SKIP_PREPARE_SERVER: "true",
      }),
      pm("tools", ["run", "check-tools"]),
      pm("tests", ["test"]),
      pm("package", [
        "exec",
        "vsce",
        "package",
        "--no-dependencies",
        "--out",
        vsix,
      ]),
    ],
  };
}

/** @param {Step} step @param {boolean} capture */
function runProcess(step, capture = false) {
  // Only platform launchers use a shell; their arguments were validated and quoted.
  const result = spawnSync(step.cmd, step.args, {
    cwd: step.cwd,
    env: step.env,
    encoding: "utf8",
    shell: process.platform === "win32" && step.cmd.startsWith('"'),
    stdio: capture ? "pipe" : ["inherit", 2, 2],
  });
  if (result.error || result.status !== 0)
    throw new Error(
      `${step.phase} failed (${result.status ?? result.error?.message}): ${capture ? result.stderr : "see command output"}`,
    );
  return result.stdout || "";
}

/** @param {Plan} plan @param {(step: Step) => unknown} run */
function executeBuild(plan, run = runProcess) {
  for (const step of plan.steps) run(step);
}

/** @param {string} vsix @param {string} jar @param {string} bundle */
function verifyVsix(vsix, jar, bundle) {
  const jarHash = sha256(fs.readFileSync(jar));
  const bundleHash = sha256(fs.readFileSync(bundle));
  if (sha256(readZipEntry(vsix, "extension/server/gls.jar")) !== jarHash)
    throw new Error("Packaged server does not match the source build");
  if (
    sha256(readZipEntry(vsix, "extension/client/out/extension.js")) !==
    bundleHash
  )
    throw new Error("Packaged client does not match the source build");
  return {
    jar: jarHash,
    bundle: bundleHash,
    vsix: sha256(fs.readFileSync(vsix)),
  };
}

/** @param {string} dir */
function checkSpace(dir) {
  fs.mkdirSync(dir, { recursive: true });
  fs.accessSync(dir, fs.constants.W_OK);
  const disk = fs.statfsSync(dir);
  if (disk.bavail * disk.bsize < 1024 ** 3)
    throw new Error(
      `Less than 1 GiB free at ${dir}; choose --build-root or GRADLE_USER_HOME on a drive with space`,
    );
}

/** @param {Plan} plan @param {string | null} requested @param {boolean} packageOnly */
function preflight(plan, requested, packageOnly) {
  if (Number(process.versions.node.split(".")[0]) < 24)
    throw new Error("Node 24+ is required");
  if (
    !fs.existsSync(path.join(plan.root, "settings.gradle.kts")) ||
    !fs.existsSync(path.join(plan.root, "gls", "build.gradle.kts"))
  )
    throw new Error("Source installation requires a gvy checkout");
  const env = plan.steps[0].env;
  const java = env.JAVA_HOME
    ? path.join(
        env.JAVA_HOME,
        "bin",
        process.platform === "win32" ? "java.exe" : "java",
      )
    : "java";
  const result = spawnSync(java, ["-version"], { encoding: "utf8", env });
  const javaVersion = /version "(\d+)/.exec(
    result.stderr || result.stdout || "",
  );
  if (result.status !== 0 || !javaVersion || Number(javaVersion[1]) < 21)
    throw new Error(
      "A working JDK 21+ is required; set JAVA_HOME or --java-home",
    );
  // Gradle's smoke task invokes java through PATH too.
  if (env.JAVA_HOME)
    for (const step of plan.steps)
      step.env.PATH =
        path.join(env.JAVA_HOME, "bin") + path.delimiter + (env.PATH || "");
  const pm = pnpmInvocation(["--version"], env);
  const pmVersion = runProcess(
    { phase: "pnpm", ...pm, cwd: plan.root, env },
    true,
  ).trim();
  if (!/^\d+\.\d+\.\d+/.test(pmVersion) || Number(pmVersion.split(".")[0]) < 12)
    throw new Error("pnpm 12+ is required");
  const installer = packageOnly ? null : findInstaller(requested || "code");
  if (installer) {
    const invocation = installerCommand(installer, ["--version"]);
    const version = runProcess(
      { phase: "editor", ...invocation, cwd: plan.root, env },
      true,
    ).match(/^(\d+)\.(\d+)\.(\d+)/m);
    const manifest = JSON.parse(
      fs.readFileSync(
        path.join(plan.root, "editors", "code", "package.json"),
        "utf8",
      ),
    );
    const minimum = /\^(\d+)\.(\d+)\.(\d+)/.exec(manifest.engines.vscode);
    if (
      !version ||
      !minimum ||
      Number(version[1]) < Number(minimum[1]) ||
      (version[1] === minimum[1] && Number(version[2]) < Number(minimum[2]))
    )
      throw new Error(`Editor must satisfy VS Code ${manifest.engines.vscode}`);
  }
  checkSpace(plan.buildRoot);
  checkSpace(env.GRADLE_USER_HOME || path.join(os.homedir(), ".gradle"));
  return installer;
}

/** @param {{root: string, buildRoot?: string | null, javaHome?: string | null, installer?: string | null, packageOnly?: boolean, doctor?: boolean}} options */
function installFromSource(options) {
  const root = path.resolve(options.root);
  const buildRoot = path.resolve(
    options.buildRoot ||
      process.env.GVY_BUILD_ROOT ||
      path.join(root, "editors", "code", "dist", "source"),
  );
  const env = { ...process.env };
  if (options.javaHome) env.JAVA_HOME = path.resolve(options.javaHome);
  const plan = createSourcePlan(root, buildRoot, env);
  let stage = "preflight";
  /** @type {string[]} */
  const locks = [];
  const receiptPath = path.join(buildRoot, "artifacts", "install-result.json");
  /** @type {Record<string, unknown>} */
  const receipt = {
    schemaVersion: 1,
    success: false,
    source: "checkout",
    root,
    buildRoot,
  };
  try {
    const installer = preflight(
      plan,
      options.installer || null,
      !!options.packageOnly,
    );
    if (options.doctor)
      return {
        schemaVersion: 1,
        success: true,
        stage: "preflight",
        buildRoot,
        installer,
      };
    for (const lock of [
      path.join(root, "editors", "code", ".source-install.lock"),
      path.join(buildRoot, ".source-install.lock"),
    ]) {
      try {
        fs.mkdirSync(lock);
      } catch (error) {
        throw new Error(
          `Source installation is already locked at ${lock}; inspect its owner.json before removing a stale lock`,
          { cause: error },
        );
      }
      locks.push(lock);
      fs.writeFileSync(
        path.join(lock, "owner.json"),
        JSON.stringify({ pid: process.pid, root, buildRoot }),
      );
    }
    fs.mkdirSync(path.dirname(plan.vsix), { recursive: true });
    fs.rmSync(plan.vsix, { force: true });
    fs.writeFileSync(
      plan.init,
      `gradle.beforeProject { p ->\n    p.layout.buildDirectory.set(new File(System.getenv('GVY_BUILD_ROOT'), 'build/' + (p.path == ':' ? 'root' : p.path.substring(1).replace(':', '/'))))\n}\n`,
    );
    const git = (/** @type {string[]} */ args) =>
      runProcess(
        { phase: "git", cmd: "git", args, cwd: root, env },
        true,
      ).trim();
    receipt.head = git(["rev-parse", "HEAD"]);
    receipt.dirty = git(["status", "--porcelain"]) !== "";
    receipt.extensionVersion = JSON.parse(
      fs.readFileSync(
        path.join(root, "editors", "code", "package.json"),
        "utf8",
      ),
    ).version;
    executeBuild(plan, (step) => {
      stage = step.phase;
      return runProcess(step);
    });
    stage = "verify";
    receipt.hashes = verifyVsix(
      plan.vsix,
      plan.jar,
      path.join(root, "editors", "code", "client", "out", "extension.js"),
    );
    if (git(["rev-parse", "HEAD"]) !== receipt.head)
      throw new Error(
        "Checkout HEAD changed during the build; rerun installation",
      );
    stage = "lsp-smoke";
    runProcess({
      phase: stage,
      cmd: process.execPath,
      args: [
        path.join(root, "editors", "code", "tools", "smoke-server.js"),
        plan.jar,
      ],
      cwd: root,
      env: plan.steps[0].env,
    });
    stage = "version";
    const java = plan.steps[0].env.JAVA_HOME
      ? path.join(
          plan.steps[0].env.JAVA_HOME,
          "bin",
          process.platform === "win32" ? "java.exe" : "java",
        )
      : "java";
    receipt.jarVersion =
      runProcess(
        {
          phase: "version",
          cmd: java,
          args: ["-jar", plan.jar, "version"],
          cwd: root,
          env: plan.steps[0].env,
        },
        true,
      ).match(/gls version ([^\s]+)/)?.[1] || "unknown";
    receipt.jar = plan.jar;
    receipt.vsix = plan.vsix;
    receipt.installer = installer;
    if (installer) {
      stage = "install";
      const invocation = installerCommand(installer, [
        "--install-extension",
        plan.vsix,
        "--force",
      ]);
      runProcess({ phase: stage, ...invocation, cwd: root, env });
    }
    stage = installer ? "installed" : "packaged";
    receipt.success = true;
    return receipt;
  } catch (error) {
    receipt.error = error instanceof Error ? error.message : String(error);
    throw Object.assign(new Error(String(receipt.error), { cause: error }), {
      stage,
      receipt: locks.length === 2 ? receiptPath : undefined,
    });
  } finally {
    if (!options.doctor && locks.length === 2) {
      receipt.stage = stage;
      finishReceipt(receiptPath, receipt, locks);
    } else {
      for (const lock of locks.reverse())
        fs.rmSync(lock, { recursive: true, force: true });
    }
  }
}

/** @param {string} file @param {Record<string, unknown>} receipt @param {string[]} locks
 * @param {(file: string, contents: string) => void} write
 */
function finishReceipt(
  file,
  receipt,
  locks,
  write = (file, contents) => {
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, contents);
  },
) {
  try {
    write(file, JSON.stringify(receipt, null, 2) + "\n");
  } catch (error) {
    if (receipt.error) console.error("Could not save failure receipt:", error);
    else
      throw Object.assign(
        new Error(
          `Source stage '${receipt.stage}' completed, but its receipt could not be saved`,
          { cause: error },
        ),
        { stage: receipt.stage, installed: receipt.stage === "installed" },
      );
  } finally {
    for (const lock of locks.reverse())
      fs.rmSync(lock, { recursive: true, force: true });
  }
}

module.exports = {
  createSourcePlan,
  sourceEnvironment,
  executeBuild,
  localJarMatches,
  replaceLocalJar,
  verifyVsix,
  installFromSource,
  finishReceipt,
};
