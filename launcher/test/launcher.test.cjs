const assert = require("node:assert/strict");
const { spawn, spawnSync } = require("node:child_process");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { test } = require("node:test");

const launcher = process.env.GVY_LAUNCHER;
assert.ok(
  launcher && path.isAbsolute(launcher),
  "Set GVY_LAUNCHER to the absolute native executable path",
);
fs.accessSync(launcher, fs.constants.X_OK);

function fixture(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "gvy launcher λ-"));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const javaHome = path.join(root, "Java Home");
  fs.mkdirSync(path.join(javaHome, "bin"), { recursive: true });
  const jar = path.join(root, "my server.jar");
  fs.writeFileSync(jar, "probe payload");
  const env = {
    ...process.env,
    GVY_JAR: jar,
    GVY_JAVA_HOME: javaHome,
    JAVA_HOME: "/project/java",
  };
  delete env.JDK_JAVA_OPTIONS;
  delete env.JAVA_TOOL_OPTIONS;
  delete env._JAVA_OPTIONS;
  const java = path.join(javaHome, "bin/java");
  fs.writeFileSync(
    java,
    '#!/bin/sh\nprintf "%s\\0" "$@"\ncat\nprintf "stderr:%s" "$JAVA_HOME" >&2\nexit 37\n',
    { mode: 0o755 },
  );
  return { root, jar, java, javaHome, env };
}

function run(args, env, options = {}) {
  return spawnSync(launcher, args, {
    env,
    timeout: 10000,
    maxBuffer: 1024 * 1024,
    ...options,
  });
}

test("preserves argv boundaries, Unicode, stdin, independent stderr, environment and exit status", (t) => {
  const { jar, env } = fixture(t);
  const args = [
    "check",
    "",
    "a b",
    "groovy-λ-世界",
    'a"b\\c',
    "$(touch sentinel);*",
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
  assert.equal(result.stderr.toString(), "stderr:/project/java");
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

test("uses a sibling gvy.jar through an executable symlink on PATH", (t) => {
  const { root, env } = fixture(t);
  const install = path.join(root, "installation");
  fs.mkdirSync(install);
  const installedLauncher = path.join(install, "gvy");
  fs.copyFileSync(launcher, installedLauncher);
  fs.chmodSync(installedLauncher, 0o755);
  const jar = path.join(install, "gvy.jar");
  fs.writeFileSync(jar, "probe payload");
  const bin = path.join(root, "bin");
  fs.mkdirSync(bin);
  fs.symlinkSync(installedLauncher, path.join(bin, "gvy"));
  delete env.GVY_JAR;
  env.PATH = `${bin}${path.delimiter}${process.env.PATH}`;
  const result = spawnSync("gvy", ["version"], {
    env,
    cwd: root,
    timeout: 10000,
  });
  assert.equal(result.status, 37);
  assert.equal(
    result.stdout.toString(),
    ["-Xmx2g", "-jar", jar, "version", ""].join("\0"),
  );
});

test("missing payload and invalid explicit homes fail on stderr without fallback", (t) => {
  const { root, env } = fixture(t);
  for (const invalid of ["", path.join(root, "missing.jar"), root]) {
    const result = run([], { ...env, GVY_JAR: invalid });
    assert.equal(result.status, 1);
    assert.equal(result.stdout.length, 0);
    assert.match(result.stderr.toString(), /gvy launcher:.*GVY_JAR/);
  }
  for (const invalid of ["", path.join(root, "missing java")]) {
    const result = run([], { ...env, GVY_JAVA_HOME: invalid });
    assert.ok(result.status > 0);
    assert.equal(result.stdout.length, 0);
    assert.match(result.stderr.toString(), /gvy launcher:/);
    assert.doesNotMatch(result.stderr.toString(), /stderr:/);
  }
});

test("missing and unexecutable Java report shell-compatible statuses", (t) => {
  const { env, java } = fixture(t);
  fs.chmodSync(java, 0o644);
  assert.equal(run([], env).status, 126);
  fs.unlinkSync(java);
  const result = run([], env);
  assert.equal(result.status, 127);
  assert.equal(result.stdout.length, 0);
  assert.match(result.stderr.toString(), /gvy launcher:.*java/);
});

test(
  "exec replaces the launcher PID and delivers cancellation to the application",
  { timeout: 10000 },
  async (t) => {
    const { env, java } = fixture(t);
    fs.writeFileSync(java, '#!/bin/sh\nprintf "%s\\n" "$$"\nread -r input\n');
    const child = spawn(launcher, [], { env, stdio: ["pipe", "pipe", "pipe"] });
    t.after(() => {
      if (child.exitCode === null && child.signalCode === null)
        child.kill("SIGKILL");
    });
    const exited = new Promise((resolve, reject) => {
      child.once("error", reject);
      child.once("exit", (code, signal) => resolve({ code, signal }));
    });
    const pid = await new Promise((resolve, reject) => {
      child.once("error", reject);
      child.stdout.once("data", (chunk) =>
        resolve(Number(chunk.toString().trim())),
      );
      child.once("exit", () =>
        reject(new Error("Child exited without reporting its PID")),
      );
    });
    assert.equal(pid, child.pid);
    child.kill("SIGINT");
    assert.deepEqual(await exited, { code: null, signal: "SIGINT" });
  },
);

test(
  "real JVM artifact preserves version JSON and completes the default LSP handshake",
  {
    skip:
      !process.env.GVY_TEST_JAR &&
      "Set GVY_TEST_JAR for the actual JVM artifact smoke (required in CI)",
    timeout: 60000,
  },
  async () => {
    const jar = path.resolve(process.env.GVY_TEST_JAR);
    fs.accessSync(jar, fs.constants.R_OK);
    const env = { ...process.env, GVY_JAR: jar };
    delete env.GVY_JAVA_HOME;
    const java = env.JAVA_HOME ? path.join(env.JAVA_HOME, "bin/java") : "java";
    const direct = spawnSync(
      java,
      ["-Xmx2g", "-jar", jar, "version", "--json"],
      { env, timeout: 30000 },
    );
    const native = run(["version", "--json"], env, { timeout: 30000 });
    assert.ifError(direct.error);
    assert.ifError(native.error);
    assert.equal(direct.status, 0);
    assert.equal(native.status, 0);
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
  },
);
