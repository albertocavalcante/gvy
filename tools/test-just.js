// @ts-check
const { test, beforeEach, afterEach } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { spawnSync } = require("node:child_process");
/** @type {string} */ let base;
/** @type {string} */ let project;
/** @type {string} */ let log;
beforeEach(() => {
  base = fs.mkdtempSync(path.join(os.tmpdir(), "gvy just recipes "));
  project = path.join(base, "project");
  log = path.join(base, "commands.jsonl");
  fs.mkdirSync(path.join(project, "tools"), { recursive: true });
  fs.mkdirSync(path.join(project, "child"));
  fs.copyFileSync(
    path.join(__dirname, "..", "Justfile"),
    path.join(project, "Justfile"),
  );
  fs.copyFileSync(
    path.join(__dirname, "..", "Makefile"),
    path.join(project, "Makefile"),
  );
  fs.writeFileSync(
    path.join(project, "tools", "dev-command.js"),
    `
    const fs = require('node:fs');
    for (const action of ['gradle', 'extension', 'command', 'installSource']) exports[action] = (...args) => {
      fs.appendFileSync(process.env.GVY_COMMAND_LOG, JSON.stringify({action, args, cwd: process.cwd()}) + '\\n');
      if (process.env.GVY_FAIL_ACTION === action) throw new Error('injected command failure');
    };
  `,
  );
});
afterEach(() => fs.rmSync(base, { recursive: true, force: true }));
/** @param {string[]} args @param {string} cwd @param {NodeJS.ProcessEnv} overrides */
function run(args, cwd, overrides = {}) {
  return spawnSync("just", args, {
    cwd,
    encoding: "utf8",
    env: { ...process.env, GVY_COMMAND_LOG: log, ...overrides },
  });
}
function calls() {
  return fs
    .readFileSync(log, "utf8")
    .trim()
    .split("\n")
    .map((line) => JSON.parse(line));
}

for (const location of ["root", "child", "parent"]) {
  test(`Just invocation from ${location} preserves arguments and project directory`, () => {
    const cwd =
      location === "root"
        ? project
        : location === "child"
          ? path.join(project, "child")
          : base;
    const recipe = location === "parent" ? "project/build" : "build";
    const result = run(
      [recipe, "path with spaces", "semi;colon", "$(literal)"],
      cwd,
    );
    assert.equal(result.status, 0, result.stderr);
    const recorded = calls()[0];
    assert.deepEqual(recorded.args, [
      ["build", "path with spaces", "semi;colon", "$(literal)"],
    ]);
    assert.equal(recorded.cwd, fs.realpathSync(project));
  });
}

test("editor check installs locked dependencies then runs the canonical check", () => {
  const result = run(["check-editor"], project);
  assert.equal(result.status, 0, result.stderr);
  assert.deepEqual(
    calls().map((call) => call.args),
    [
      [["install", "--frozen-lockfile"], { SKIP_PREPARE_SERVER: "true" }],
      [["run", "check"]],
    ],
  );
});

test("a failed prerequisite prevents the editor check", () => {
  const result = run(["check-editor"], project, {
    GVY_FAIL_ACTION: "extension",
  });
  assert.notEqual(result.status, 0);
  assert.equal(calls().length, 1);
});

test("source install forwards explicit options without interpreting paths", () => {
  const result = run(
    ["install-vscode-source", "--build-root", "scratch build", "--json"],
    project,
  );
  assert.equal(result.status, 0, result.stderr);
  assert.deepEqual(calls()[0].args, [
    [
      "--from-source",
      "--installer",
      "code",
      "--build-root",
      "scratch build",
      "--json",
    ],
  ]);
});

test(
  "legacy Make delegates to Just and preserves Gradle arguments",
  { skip: process.platform === "win32" },
  () => {
    const result = spawnSync(
      "make",
      ["test", "GRADLE_ARGS=--tests 'suite with spaces'"],
      {
        cwd: project,
        encoding: "utf8",
        env: { ...process.env, GVY_COMMAND_LOG: log },
      },
    );
    assert.equal(result.status, 0, result.stderr);
    assert.deepEqual(calls()[0].args, [
      ["test", "--tests", "suite with spaces"],
    ]);
  },
);

test(
  "Make runtime aliases keep Gradle flags out of server arguments",
  { skip: process.platform === "win32" },
  () => {
    const result = spawnSync("make", ["run-stdio", "GRADLE_ARGS=--offline"], {
      cwd: project,
      encoding: "utf8",
      env: { ...process.env, GVY_COMMAND_LOG: log },
    });
    assert.equal(result.status, 0, result.stderr);
    const recorded = calls();
    assert.deepEqual(recorded[0].args, [
      [":gls:stageEditorServer", "--offline"],
    ]);
    assert.equal(recorded.length, 2);
    assert.ok(!JSON.stringify(recorded[1].args).includes("--offline"));
  },
);

test("Git checkout keeps the Justfile LF even with autocrlf enabled", () => {
  fs.copyFileSync(
    path.join(__dirname, "..", ".gitattributes"),
    path.join(project, ".gitattributes"),
  );
  const git = (/** @type {string[]} */ args) => {
    const result = spawnSync(
      "git",
      ["-c", "core.autocrlf=true", "-c", "core.attributesfile=", ...args],
      { cwd: project, encoding: "utf8" },
    );
    assert.equal(result.status, 0, result.stderr);
  };
  git(["init", "--quiet"]);
  git(["add", ".gitattributes", "Justfile"]);
  fs.unlinkSync(path.join(project, "Justfile"));
  git(["checkout-index", "--force", "--", "Justfile"]);
  assert.ok(
    !fs.readFileSync(path.join(project, "Justfile"), "utf8").includes("\r\n"),
    "Justfile checkout must retain LF for the formatter",
  );
});

test(
  "Windows launcher resolves batch shims and preserves argv and exit status",
  { skip: process.platform !== "win32" },
  () => {
    const bin = path.join(base, "bin with spaces");
    fs.mkdirSync(bin);
    const shim = path.join(bin, "fixture-cli.cmd");
    const recorder = path.join(bin, "record.js");
    fs.writeFileSync(path.join(bin, "fixture-cli"), "#!/bin/sh\nexit 99\n");
    fs.writeFileSync(
      recorder,
      "require('node:fs').writeFileSync(process.env.GVY_ARGV_LOG, JSON.stringify(process.argv.slice(2))); process.exit(Number(process.env.GVY_EXIT || 0));",
    );
    fs.writeFileSync(
      shim,
      `@echo off\r\n"${process.execPath}" "${recorder}" %*\r\n`,
    );
    const launcher = path.join(__dirname, "dev-command.js");
    const expected = ["space bearing argument", "C:\\path with spaces\\", ""];
    for (const executable of ["fixture-cli", shim]) {
      for (const exit of [0, 7]) {
        const result = spawnSync(
          process.execPath,
          [
            "-e",
            `require(${JSON.stringify(launcher)}).command(${JSON.stringify(executable)}, ${JSON.stringify(expected)})`,
          ],
          {
            encoding: "utf8",
            env: {
              ...process.env,
              Path: bin,
              PATH: bin,
              PATHEXT: ".COM;.EXE;.BAT;.CMD",
              GVY_ARGV_LOG: log,
              GVY_EXIT: String(exit),
            },
          },
        );
        assert.equal(result.status, exit === 0 ? 0 : 1, result.stderr);
        assert.deepEqual(JSON.parse(fs.readFileSync(log, "utf8")), expected);
        if (exit) assert.match(result.stderr, /exited 7/u);
      }
    }
  },
);

test("Windows lookup ignores POSIX shims and runs native executables directly", () => {
  const {
    installerCommand,
  } = require("../editors/code/tools/editor-command.js");
  const bin = path.join(base, "shim directory");
  fs.mkdirSync(bin);
  fs.writeFileSync(path.join(bin, "fixture"), "POSIX shim");
  const shim = path.join(bin, "fixture.CMD");
  fs.writeFileSync(shim, "batch shim");
  const options = { cwd: base, env: { Path: bin, PATHEXT: ".EXE;.CMD" } };
  assert.deepEqual(
    installerCommand("fixture", ["C:\\space path\\"], "win32", options),
    {
      cmd: `"${shim}"`,
      args: ['"C:\\space path\\\\"'],
      shell: true,
    },
  );
  const exe = path.join(bin, "fixture.EXE");
  fs.writeFileSync(exe, "native executable");
  assert.deepEqual(
    installerCommand("fixture", ["literal & argument"], "win32", options),
    {
      cmd: exe,
      args: ["literal & argument"],
      shell: false,
    },
  );
});
