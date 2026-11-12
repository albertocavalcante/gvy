// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

const assert = require("node:assert/strict");
const { spawn, spawnSync } = require("node:child_process");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { after, test } = require("node:test");

const isWindows = process.platform === "win32";
const javaName = isWindows ? "java.exe" : "java";
const launcherName = isWindows ? "gvy.exe" : "gvy";
const projectJavaHome = isWindows ? "C:\\project\\java" : "/project/java";

function buildWindowsProbe() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "gvy-java-probe-"));
  after(() => fs.rmSync(root, { recursive: true, force: true }));
  const executable = path.join(root, "java.exe");
  const result = spawnSync(
    process.env.GVY_PROBE_CC || "gcc",
    [
      "-std=c11",
      "-municode",
      "-static",
      "-Wall",
      "-Wextra",
      "-Werror",
      path.join(__dirname, "windows-java-probe.c"),
      "-o",
      executable,
    ],
    { encoding: "utf8", timeout: 30000 },
  );
  assert.ifError(result.error);
  assert.equal(result.status, 0, result.stdout + result.stderr);
  return executable;
}

const windowsProbe = isWindows ? buildWindowsProbe() : undefined;

const launcher = process.env.GVY_LAUNCHER;
assert.ok(
  launcher && path.isAbsolute(launcher),
  "Set GVY_LAUNCHER to the absolute native executable path",
);
fs.accessSync(launcher, fs.constants.X_OK);

function fixture(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "gvy launcher λ-"));
  const processes = new Map();
  t.after(async () => {
    const closing = [];
    for (const [pid, child] of processes) {
      if (child) {
        closing.push(
          new Promise((resolve, reject) => {
            const timeout = setTimeout(
              () => reject(new Error(`Child ${pid} did not close`)),
              5000,
            );
            child.once("close", () => {
              clearTimeout(timeout);
              resolve();
            });
          }),
        );
        if (child.exitCode === null && child.signalCode === null)
          child.kill("SIGKILL");
      } else {
        try {
          process.kill(pid, "SIGKILL");
        } catch (error) {
          if (error.code !== "ESRCH") throw error;
        }
      }
    }
    await Promise.all(closing);
    fs.rmSync(root, {
      recursive: true,
      force: true,
      maxRetries: 20,
      retryDelay: 50,
    });
  });
  const javaHome = path.join(root, "Java Home");
  fs.mkdirSync(path.join(javaHome, "bin"), { recursive: true });
  const jar = path.join(root, "my server.jar");
  fs.writeFileSync(jar, "probe payload");
  const env = { ...process.env };
  for (const key of Object.keys(env)) {
    if (
      /^(gvy_jar|gvy_java_home|java_home|path|jdk_java_options|java_tool_options|_java_options|gvy_probe_mode|gvy_probe_exit|gvy_console_.*)$/i.test(
        key,
      )
    ) {
      delete env[key];
    }
  }
  Object.assign(env, {
    PATH: process.env.PATH || process.env.Path,
    GVY_JAR: jar,
    GVY_JAVA_HOME: javaHome,
    JAVA_HOME: projectJavaHome,
  });
  const java = path.join(javaHome, "bin", javaName);
  if (isWindows) {
    fs.copyFileSync(windowsProbe, java);
  } else {
    fs.writeFileSync(
      java,
      '#!/bin/sh\nprintf "%s\\0" "$@"\ncat\nprintf "stderr:%s" "$JAVA_HOME" >&2\nexit 37\n',
      { mode: 0o755 },
    );
  }
  return { root, jar, java, javaHome, env, processes };
}

function run(args, env, options = {}) {
  return spawnSync(launcher, args, {
    env,
    timeout: 10000,
    maxBuffer: 1024 * 1024,
    ...options,
  });
}

function processLine(child) {
  return new Promise((resolve, reject) => {
    let output = "";
    const timeout = setTimeout(
      () => finish(new Error("Probe did not report its PID")),
      3000,
    );
    function finish(error, line) {
      clearTimeout(timeout);
      child.off("error", failed);
      child.off("exit", exited);
      child.stdout.off("data", data);
      if (error) reject(error);
      else resolve(line);
    }
    function failed(error) {
      finish(error);
    }
    function exited() {
      finish(new Error("Probe exited before reporting its PID"));
    }
    function data(chunk) {
      output += chunk.toString();
      if (output.includes("\n"))
        finish(null, output.slice(0, output.indexOf("\n")));
      else if (output.length > 1024)
        finish(new Error("Probe PID output exceeded its bound"));
    }
    child.once("error", failed);
    child.once("exit", exited);
    child.stdout.on("data", data);
  });
}

function trackProcess(child, processes) {
  if (child.pid) {
    processes.set(child.pid, child);
    child.once("close", () => processes.delete(child.pid));
  }
  return child;
}

function processAlive(pid) {
  try {
    process.kill(pid, 0);
    return true;
  } catch (error) {
    if (error.code === "ESRCH") return false;
    throw error;
  }
}

async function waitForStopped(pids) {
  const deadline = Date.now() + 3000;
  while (pids.some(processAlive) && Date.now() < deadline) {
    await new Promise((resolve) => setTimeout(resolve, 25));
  }
  for (const pid of pids)
    assert.equal(
      processAlive(pid),
      false,
      `Process ${pid} survived launcher cancellation`,
    );
}

test("preserves argv boundaries, Unicode, stdin, independent stderr, environment and exit status", (t) => {
  const { jar, env } = fixture(t);
  const args = [
    "check",
    "",
    "a b",
    "groovy-λ-世界-🦜",
    'a"b\\c',
    "$(touch sentinel);*",
    "C:\\path with spaces\\",
    'one\\"two',
    'one\\\\"two',
    '"quoted"',
    "& echo should-not-run > sentinel.txt",
  ];
  const input = Buffer.from([0, 1, 2, 10, 255]);
  const result = run(args, env, { input });
  assert.ifError(result.error);
  assert.equal(result.status, 37);
  assert.deepEqual(
    result.stdout,
    Buffer.concat([
      Buffer.from(["-Xmx2g", "-jar", jar, ...args].join("\0") + "\0"),
      input,
    ]),
  );
  assert.equal(result.stderr.toString(), `stderr:${projectJavaHome}`);
});

test("passes no arguments to the application for default stdio LSP mode", (t) => {
  const { jar, env } = fixture(t);
  assert.equal(
    run([], env).stdout.toString(),
    ["-Xmx2g", "-jar", jar, ""].join("\0"),
  );
});

test("falls back to JAVA_HOME, then searches PATH", (t) => {
  const { jar, javaHome, env } = fixture(t);
  delete env.GVY_JAVA_HOME;
  env.JAVA_HOME = javaHome;
  assert.equal(run(["version"], env).status, 37);
  delete env.JAVA_HOME;
  env.PATH = `${path.join(javaHome, "bin")}${path.delimiter}${process.env.PATH}`;
  const result = run(["version"], env);
  assert.equal(result.status, 37);
  assert.equal(
    result.stdout.toString(),
    ["-Xmx2g", "-jar", jar, "version", ""].join("\0"),
  );
});

test(
  isWindows
    ? "uses a canonical sibling JAR through an installation junction"
    : "uses a canonical sibling JAR through executable and directory symlinks",
  (t) => {
    const { root, env } = fixture(t);
    const physicalInstall = path.join(root, "physical installation");
    fs.mkdirSync(physicalInstall);
    const install = path.join(root, "installation");
    fs.symlinkSync(physicalInstall, install, isWindows ? "junction" : "dir");
    const installedLauncher = path.join(install, launcherName);
    fs.copyFileSync(launcher, installedLauncher);
    fs.chmodSync(installedLauncher, 0o755);
    const jar = path.join(install, "gvy.jar");
    fs.writeFileSync(jar, "probe payload");
    const bin = path.join(root, "bin");
    fs.mkdirSync(bin);
    if (isWindows) {
      fs.symlinkSync(
        physicalInstall,
        path.join(bin, "installation"),
        "junction",
      );
    } else {
      fs.symlinkSync(installedLauncher, path.join(bin, launcherName));
    }
    delete env.GVY_JAR;
    const executableDirectory = isWindows
      ? path.join(bin, "installation")
      : bin;
    env.PATH = `${executableDirectory}${path.delimiter}${env.PATH}`;
    const result = spawnSync(launcherName, ["version"], {
      env,
      cwd: root,
      timeout: 10000,
    });
    assert.ifError(result.error);
    assert.equal(result.status, 37);
    assert.equal(
      result.stdout.toString(),
      ["-Xmx2g", "-jar", fs.realpathSync.native(jar), "version", ""].join("\0"),
    );
  },
);

test("missing payload and invalid explicit homes fail on stderr without fallback", (t) => {
  const { root, env, javaHome } = fixture(t);
  env.JAVA_HOME = javaHome;
  const installedLauncher = path.join(root, launcherName);
  fs.copyFileSync(launcher, installedLauncher);
  fs.chmodSync(installedLauncher, 0o755);
  fs.writeFileSync(path.join(root, "gvy.jar"), "valid sibling payload");
  const fallback = { ...env };
  delete fallback.GVY_JAR;
  delete fallback.GVY_JAVA_HOME;
  const positive = spawnSync(installedLauncher, ["version"], {
    env: fallback,
    timeout: 10000,
  });
  assert.ifError(positive.error);
  assert.equal(positive.status, 37, positive.stderr.toString());
  for (const invalid of ["", path.join(root, "missing.jar"), root]) {
    const result = spawnSync(installedLauncher, [], {
      env: { ...env, GVY_JAR: invalid },
      timeout: 10000,
    });
    assert.ifError(result.error);
    assert.equal(result.status, 1);
    assert.equal(result.stdout.length, 0);
    assert.match(result.stderr.toString(), /gvy launcher:.*GVY_JAR/);
  }
  for (const [invalid, status] of [
    ["", 1],
    [path.join(root, "missing java"), 127],
  ]) {
    const result = run([], { ...env, GVY_JAVA_HOME: invalid });
    assert.ifError(result.error);
    assert.equal(result.status, status, result.stderr.toString());
    assert.equal(result.stdout.length, 0);
    assert.match(result.stderr.toString(), /gvy launcher:/);
    if (isWindows && invalid === "")
      assert.match(result.stderr.toString(), /GVY_JAVA_HOME is empty/);
    assert.doesNotMatch(result.stderr.toString(), /stderr:/);
  }
});

test("missing and unexecutable Java report shell-compatible statuses", (t) => {
  const { env, java } = fixture(t);
  if (isWindows) fs.writeFileSync(java, "not a Windows executable");
  else fs.chmodSync(java, 0o644);
  assert.equal(run([], env).status, 126);
  fs.unlinkSync(java);
  const result = run([], env);
  assert.equal(result.status, 127);
  assert.equal(result.stdout.length, 0);
  assert.match(result.stderr.toString(), /gvy launcher:.*java/);
});

if (isWindows) {
  test("accepts an extended Java home beyond MAX_PATH", (t) => {
    const { root, env, java } = fixture(t);
    const javaHome = path.join(
      root,
      "long runtime directory ".repeat(8),
      "nested runtime ".repeat(4),
      "Java Home",
    );
    assert.ok(javaHome.length > 260);
    fs.mkdirSync(path.join(javaHome, "bin"), { recursive: true });
    fs.copyFileSync(java, path.join(javaHome, "bin", "java.exe"));
    const result = run(["version"], {
      ...env,
      GVY_JAVA_HOME: `\\\\?\\${javaHome}`,
    });
    assert.ifError(result.error);
    assert.equal(result.status, 37, result.stderr.toString());
  });

  test("a hidden launch preserves stdio without allocating a child console", (t) => {
    const { env, java } = fixture(t);
    env.GVY_PROBE_MODE = "console";
    const direct = spawnSync(java, [], {
      env,
      windowsHide: true,
      timeout: 10000,
    });
    assert.ifError(direct.error);
    assert.equal(direct.status, 37, direct.stderr.toString());
    assert.equal(direct.stdout.toString(), "0\n");
    const result = run([], env, { windowsHide: true });
    assert.ifError(result.error);
    assert.equal(result.status, 37, result.stderr.toString());
    assert.equal(result.stdout.toString(), "0\n");
  });

  test("finds the sibling JAR when the installed launcher path exceeds MAX_PATH", (t) => {
    const { root, env } = fixture(t);
    const install = path.join(
      root,
      "long installation directory ".repeat(7),
      "nested installation ".repeat(4),
    );
    assert.ok(install.length > 260);
    fs.mkdirSync(install, { recursive: true });
    const executable = path.join(install, "gvy.exe");
    const jar = path.join(install, "gvy.jar");
    fs.copyFileSync(launcher, executable);
    fs.writeFileSync(jar, "probe payload");
    delete env.GVY_JAR;
    const result = spawnSync(`\\\\?\\${executable}`, ["version"], {
      env,
      timeout: 10000,
    });
    assert.ifError(result.error);
    assert.equal(result.status, 37, result.stderr.toString());
    const args = result.stdout.toString().split("\0");
    assert.deepEqual(
      [args[0], args[1], args[3], args[4]],
      ["-Xmx2g", "-jar", "version", ""],
    );
    const actualJar = args[2].startsWith("\\\\?\\")
      ? args[2].slice(4)
      : args[2];
    assert.equal(
      path.normalize(actualJar),
      path.normalize(fs.realpathSync.native(jar)),
      JSON.stringify({ argv: args }),
    );
  });

  test(
    "delivers genuine Ctrl+C within a private console and returns the application status",
    { timeout: 15000 },
    async (t) => {
      const { env, java, processes } = fixture(t);
      const control = trackProcess(
        spawn(java, [], {
          env: { ...env, GVY_PROBE_MODE: "wait" },
          stdio: ["pipe", "pipe", "pipe"],
        }),
        processes,
      );
      await processLine(control);
      const direct = spawnSync(java, [], {
        env: {
          ...env,
          GVY_PROBE_MODE: "console-host",
          GVY_CONSOLE_LAUNCHER: java,
          GVY_CONSOLE_CLEAR_IGNORE: "1",
        },
        timeout: 12000,
        maxBuffer: 1024 * 1024,
      });
      assert.ifError(direct.error);
      assert.equal(
        direct.status,
        73,
        direct.stdout.toString() + direct.stderr.toString(),
      );
      assert.match(direct.stdout.toString(), /ctrlc-received/);
      const result = spawnSync(java, [], {
        env: {
          ...env,
          GVY_PROBE_MODE: "console-host",
          GVY_CONSOLE_LAUNCHER: launcher,
        },
        timeout: 12000,
        maxBuffer: 1024 * 1024,
      });
      assert.ifError(result.error);
      assert.equal(
        result.status,
        73,
        result.stdout.toString() + result.stderr.toString(),
      );
      assert.match(result.stdout.toString(), /ctrlc-received/);
      assert.equal(processAlive(control.pid), true);
    },
  );

  test("preserves the full Windows child exit DWORD", (t) => {
    const { env } = fixture(t);
    const result = run([], { ...env, GVY_PROBE_EXIT: "0x81234567" });
    assert.ifError(result.error);
    assert.equal(result.status >>> 0, 0x81234567);
  });

  test("empty PATH entries do not implicitly select current-directory Java", (t) => {
    const { root, env, java, javaHome } = fixture(t);
    fs.copyFileSync(java, path.join(root, "java.exe"));
    delete env.GVY_JAVA_HOME;
    delete env.JAVA_HOME;
    env.GVY_PROBE_MODE = "image";
    for (const searchPath of [";", '""', "", undefined]) {
      const searchEnv = { ...env };
      if (searchPath === undefined) delete searchEnv.PATH;
      else searchEnv.PATH = searchPath;
      // Node can copy Windows PATH from its own environment when absent in the child override.
      const inheritedPath = process.env.PATH;
      let result;
      try {
        if (searchPath === undefined) delete process.env.PATH;
        result = run([], searchEnv, { cwd: root });
      } finally {
        if (inheritedPath === undefined) delete process.env.PATH;
        else process.env.PATH = inheritedPath;
      }
      assert.ifError(result.error);
      assert.equal(result.status, 127, result.stderr.toString());
      assert.equal(result.stdout.length, 0);
    }
    const explicit = run([], { ...env, PATH: "." }, { cwd: root });
    assert.ifError(explicit.error);
    assert.equal(explicit.status, 37, explicit.stderr.toString());
    assert.equal(
      fs.realpathSync.native(explicit.stdout.toString()),
      fs.realpathSync.native(path.join(root, "java.exe")),
    );
    const bin = path.join(javaHome, "bin");
    const empty = path.join(root, "empty search directory");
    fs.mkdirSync(empty);
    for (const searchPath of [`;${bin}`, `${bin};`, `${empty};;${bin}`]) {
      const configured = run([], { ...env, PATH: searchPath }, { cwd: root });
      assert.ifError(configured.error);
      assert.equal(configured.status, 37, configured.stderr.toString());
      assert.equal(
        fs.realpathSync.native(configured.stdout.toString()),
        fs.realpathSync.native(java),
      );
    }
  });

  test("resolves PATH java.exe without current-directory or command-script fallback", (t) => {
    const { root, env, javaHome } = fixture(t);
    fs.writeFileSync(
      path.join(root, "java.exe"),
      "invalid current-directory shadow",
    );
    const scripts = path.join(root, "script directory");
    fs.mkdirSync(scripts);
    const sentinel = path.join(root, "shell-was-invoked.txt");
    fs.writeFileSync(
      path.join(scripts, "java.cmd"),
      `@echo invoked>"${sentinel}"\r\n@exit /b 88\r\n`,
    );
    delete env.GVY_JAVA_HOME;
    delete env.JAVA_HOME;
    env.PATH = [scripts, path.join(javaHome, "bin"), env.PATH].join(
      path.delimiter,
    );
    env.PATHEXT = ".CMD;.EXE";
    const result = run(["version"], env, { cwd: root });
    assert.ifError(result.error);
    assert.equal(result.status, 37);
    assert.equal(fs.existsSync(sentinel), false);
  });

  test(
    "cancellation removes the private Windows process tree and preserves an unrelated process",
    { timeout: 15000 },
    async (t) => {
      const { env, java, processes } = fixture(t);
      const control = trackProcess(
        spawn(java, [], {
          env: { ...env, GVY_PROBE_MODE: "wait" },
          stdio: ["pipe", "pipe", "pipe"],
        }),
        processes,
      );
      const [controlPid] = (await processLine(control)).split(" ").map(Number);
      assert.equal(controlPid, control.pid);
      const child = trackProcess(
        spawn(launcher, [], {
          env: { ...env, GVY_PROBE_MODE: "tree" },
          stdio: ["pipe", "pipe", "pipe"],
        }),
        processes,
      );
      const exited = new Promise((resolve, reject) => {
        child.once("error", reject);
        child.once("exit", resolve);
      });
      const pids = (await processLine(child)).split(" ").map(Number);
      assert.equal(pids.length, 2);
      for (const pid of pids) {
        assert.ok(Number.isSafeInteger(pid) && pid > 0);
        processes.set(pid, null);
        assert.equal(processAlive(pid), true);
      }
      assert.notEqual(pids[0], child.pid);
      assert.equal(child.kill("SIGTERM"), true);
      await exited;
      await waitForStopped(pids);
      for (const pid of pids) processes.delete(pid);
      assert.equal(processAlive(control.pid), true);
    },
  );

  test(
    "Windows direct JVM Unicode JAR compatibility (#1817)",
    {
      skip:
        !process.env.GVY_TEST_JAR &&
        "Set GVY_TEST_JAR for actual JVM compatibility checks (required in CI)",
      timeout: 60000,
    },
    (t) => {
      // Windows TEMP may contain an 8.3 alias; match the launcher's handle-based canonical path.
      const root = fs.realpathSync.native(
        fs.mkdtempSync(path.join(os.tmpdir(), "gvy-runtime-probe-")),
      );
      t.after(() =>
        fs.rmSync(root, {
          recursive: true,
          force: true,
          maxRetries: 20,
          retryDelay: 50,
        }),
      );
      const asciiJar = path.join(root, "control.jar");
      fs.copyFileSync(process.env.GVY_TEST_JAR, asciiJar);
      const install = path.join(root, "installed 世界 λ");
      fs.mkdirSync(install);
      const jar = path.join(install, "gvy.jar");
      const executable = path.join(install, launcherName);
      fs.copyFileSync(asciiJar, jar);
      fs.copyFileSync(launcher, executable);
      const env = { ...process.env };
      for (const key of Object.keys(env))
        if (/^gvy_(jar|java_home)$/i.test(key)) delete env[key];
      const java = env.JAVA_HOME
        ? path.join(env.JAVA_HOME, "bin", javaName)
        : javaName;
      const invoke = (jarPath) =>
        spawnSync(java, ["-Xmx2g", "-jar", jarPath, "version", "--json"], {
          env,
          timeout: 30000,
        });
      const ascii = invoke(asciiJar);
      assert.ifError(ascii.error);
      assert.equal(ascii.status, 0, ascii.stderr.toString());
      const direct = invoke(jar);
      assert.ifError(direct.error);
      const result = spawnSync(executable, ["version", "--json"], {
        env,
        timeout: 30000,
      });
      assert.ifError(result.error);
      if (direct.status !== 0 && result.status !== 0) {
        assert.equal(direct.status, 1, direct.stderr.toString());
        assert.match(
          direct.stderr.toString(),
          /^Error: Unable to access jarfile /,
        );
        assert.equal(direct.stdout.length, 0);
        assert.equal(result.status, direct.status);
        assert.deepEqual(result.stderr, direct.stderr);
        assert.equal(result.stdout.length, 0);
        // TODO(#1817): Resolve the JVM launcher limitation; re-review by 2026-12-11.
        //   See: https://github.com/albertocavalcante/gvy/issues/1817
        if (Date.now() < Date.parse("2026-12-12T00:00:00Z")) {
          t.todo(
            "#1817: direct JVM rejects Unicode JAR paths; launcher/runtime owner must review by 2026-12-11",
          );
        }
      }
      assert.equal(result.status, 0, result.stderr.toString());
      assert.deepEqual(
        JSON.parse(result.stdout.toString()),
        JSON.parse(ascii.stdout.toString()),
      );
    },
  );
} else {
  test(
    "exec replaces the launcher PID and delivers cancellation to the application",
    { timeout: 10000 },
    async (t) => {
      const { env, java, processes } = fixture(t);
      fs.writeFileSync(java, '#!/bin/sh\nprintf "%s\\n" "$$"\nread -r _\n');
      const child = trackProcess(
        spawn(launcher, [], { env, stdio: ["pipe", "pipe", "pipe"] }),
        processes,
      );
      const exited = new Promise((resolve, reject) => {
        child.once("error", reject);
        child.once("exit", (code, signal) => resolve({ code, signal }));
      });
      const pid = Number(await processLine(child));
      assert.equal(pid, child.pid);
      child.kill("SIGINT");
      assert.deepEqual(await exited, { code: null, signal: "SIGINT" });
    },
  );
}

test(
  "real JVM artifact preserves version JSON and completes the default LSP handshake",
  {
    skip:
      !process.env.GVY_TEST_JAR &&
      "Set GVY_TEST_JAR for the actual JVM artifact smoke (required in CI)",
    timeout: 60000,
  },
  async (t) => {
    const jar = path.resolve(process.env.GVY_TEST_JAR);
    fs.accessSync(jar, fs.constants.R_OK);
    const env = { ...process.env, GVY_JAR: jar };
    delete env.GVY_JAVA_HOME;
    const java = env.JAVA_HOME
      ? path.join(env.JAVA_HOME, "bin", javaName)
      : javaName;
    const direct = spawnSync(
      java,
      ["-Xmx2g", "-jar", jar, "version", "--json"],
      { env, timeout: 30000 },
    );
    const native = run(["version", "--json"], env, { timeout: 30000 });
    assert.ifError(direct.error);
    assert.ifError(native.error);
    assert.equal(direct.status, 0, direct.stderr.toString());
    assert.equal(native.status, 0, native.stderr.toString());
    assert.deepEqual(
      JSON.parse(native.stdout.toString()),
      JSON.parse(direct.stdout.toString()),
    );
    const {
      smokeServer,
      smokeServerCommand,
    } = require("../../editors/code/tools/smoke-server.js");
    await smokeServer(jar);
    await smokeServerCommand(launcher, [], 30000, env);

    const install = fs.mkdtempSync(
      path.join(
        os.tmpdir(),
        isWindows ? "gvy installed path " : "gvy installed 世界 λ-",
      ),
    );
    t.after(() =>
      fs.rmSync(install, {
        recursive: true,
        force: true,
        maxRetries: 20,
        retryDelay: 50,
      }),
    );
    const installedLauncher = path.join(install, launcherName);
    fs.copyFileSync(launcher, installedLauncher);
    fs.chmodSync(installedLauncher, 0o755);
    fs.copyFileSync(jar, path.join(install, "gvy.jar"));
    for (const key of Object.keys(env))
      if (/^gvy_jar$/i.test(key)) delete env[key];
    const installed = spawnSync(installedLauncher, ["version", "--json"], {
      env,
      timeout: 30000,
    });
    assert.ifError(installed.error);
    assert.equal(installed.status, 0, installed.stderr.toString());
    assert.deepEqual(
      JSON.parse(installed.stdout.toString()),
      JSON.parse(direct.stdout.toString()),
    );
    await smokeServerCommand(installedLauncher, [], 30000, env);
  },
);
