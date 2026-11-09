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
