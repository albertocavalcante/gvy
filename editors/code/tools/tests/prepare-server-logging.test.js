// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const path = require("node:path");
const { createRequire } = require("node:module");
const { spawnSync } = require("node:child_process");

const file = path.resolve(__dirname, "../prepare-server.js");
const source = fs.readFileSync(file, "utf8");
function load(extra = "", execFailure = null) {
  const mod = { exports: {} };
  const logs = [];
  const fakeProcess = Object.create(process);
  fakeProcess.env = {
    CI: "true",
    GLS_ALLOW_PINNED_FALLBACK: "true",
    BUILD_LOCAL: "false",
  };
  fakeProcess.exit = (code) => {
    throw new Error("EXIT:" + code);
  };
  const logger = Object.fromEntries(
    ["log", "warn", "error"].map((level) => [
      level,
      (...args) => logs.push(args.join(" ")),
    ]),
  );
  const requireFromTool = createRequire(file);
  const stubRequire = (id) => {
    if (id === "node:fs") return { ...fs, existsSync: () => true };
    if (id === "node:child_process" && execFailure !== null) {
      return {
        ...requireFromTool(id),
        execFileSync: () => {
          throw execFailure;
        },
      };
    }
    return requireFromTool(id);
  };
  const sandbox = {
    require: stubRequire,
    module: mod,
    exports: mod.exports,
    __filename: file,
    __dirname: path.dirname(file),
    process: fakeProcess,
    console: logger,
    Buffer,
    URL,
    setTimeout,
    clearTimeout,
  };
  vm.runInNewContext(
    source +
      "\nmodule.exports.__test = { sanitizeForLog, prepareServer" +
      extra +
      " };",
    sandbox,
    { filename: file },
  );
  return { api: mod.exports.__test, logs };
}
test("untrusted LF cannot create a second log record", () => {
  const { api } = load();
  const rendered = api.sanitizeForLog("first\\nFORGED".replace("\\n", "\n"));
  assert.ok(!rendered.includes("\n"), "line feed leaked into a log record");
  assert.match(rendered, /first.*FORGED/);
});
test("controls, long strings and hostile values remain bounded", () => {
  const { api } = load(", safeErrorMessage");
  const output = api.sanitizeForLog(
    "start\n\r\t\x1b[31mred\x1b[0m\x7f\x85\x9b\u2028\u2029" + "x".repeat(10000),
  );
  for (const character of output) {
    const code = character.charCodeAt(0);
    assert.ok(
      code > 0x1f &&
        (code < 0x7f || code > 0x9f) &&
        code !== 0x2028 &&
        code !== 0x2029,
    );
  }
  assert.match(output, /^startred/);
  assert.equal(output.length, 500);
  assert.ok(output.endsWith("... (truncated)"));
  assert.equal(
    api.sanitizeForLog({
      toString() {
        throw Error("coercion");
      },
    }),
    "Unknown error",
  );
  assert.equal(
    api.sanitizeForLog(
      {
        toString() {
          throw Error("coercion");
        },
      },
      0,
    ),
    "",
  );
  assert.equal(
    api.sanitizeForLog(
      {
        toString() {
          throw Error("coercion");
        },
      },
      3,
    ),
    "...",
  );
  const scanned = api.sanitizeForLog("\n".repeat(4095) + "😀");
  assert.ok(!/[\uD800-\uDBFF](?![\uDC00-\uDFFF])/.test(scanned));
  const clipped = api.sanitizeForLog("aaaa😀" + "x".repeat(100), 20);
  assert.ok(
    !/[\uD800-\uDBFF]$/.test(clipped.slice(0, -"... (truncated)".length)),
  );
  const bad = new Proxy(
    {},
    {
      getOwnPropertyDescriptor() {
        throw Error("trap");
      },
    },
  );
  assert.equal(api.safeErrorMessage(bad), "Unknown error");
  const getter = Object.defineProperty({}, "message", {
    get() {
      throw Error("getter");
    },
  });
  assert.equal(api.safeErrorMessage(getter), "Unknown error");
});
test("primary and fallback error messages remain single records", async () => {
  const { api, logs } = load(
    ", setFailures: () => { resolveTarget = async () => { throw new Error('timeout\\nPRIMARY'); }; downloadRelease = async () => { throw new Error('fallback\\nFORGED'); }; detectMonorepoEnvironment = () => false; }",
  );
  api.setFailures();
  await assert.rejects(api.prepareServer({ argv: ["--latest"] }), /EXIT:1/);
  assert.ok(logs.some((line) => line.includes("PRIMARY")));
  assert.ok(logs.some((line) => line.includes("FORGED")));
  assert.ok(
    logs.every(
      (line) => !line.includes("\nPRIMARY") && !line.includes("\nFORGED"),
    ),
  );
});
test("hostile HttpError accessors do not replace failure handling", async () => {
  const { api, logs } = load(
    ", setFailures: () => { resolveTarget = async () => { const e = new HttpError({url:'https://example.test',statusCode:403,statusMessage:'timeout',headers:{}}); Object.defineProperty(e,'isGitHubRateLimit',{get(){throw new Error('getter ran');}}); throw e; }; downloadRelease = async () => { throw new Error('FALLBACK-REACHED'); }; detectMonorepoEnvironment = () => false; }",
  );
  api.setFailures();
  await assert.rejects(api.prepareServer({ argv: ["--latest"] }), /EXIT:1/);
  assert.ok(logs.some((line) => line.includes("FALLBACK-REACHED")));
});
test("normal GitHub rate limit still offers reset guidance", async () => {
  const { api, logs } = load(
    ", setFailures: () => { resolveTarget = async () => { throw new HttpError({url:'https://api.github.com',statusCode:403,statusMessage:'limited',headers:{'x-ratelimit-remaining':'0','x-ratelimit-reset':'1700000000'}}); }; detectMonorepoEnvironment = () => false; }",
  );
  api.setFailures();
  await assert.rejects(api.prepareServer({ argv: ["--latest"] }), /EXIT:1/);
  assert.ok(
    logs.some((line) => line.includes("GitHub API rate limit exceeded")),
  );
  assert.ok(
    logs.some((line) =>
      line.includes("Rate limit resets at: 2023-11-14T22:13:20.000Z"),
    ),
  );
});
test("late network classification still reaches fallback", async () => {
  const { api, logs } = load(
    ", setFailures: () => { resolveTarget = async () => { throw new Error('x'.repeat(600) + 'timeout'); }; downloadRelease = async () => { throw new Error('FALLBACK-REACHED'); }; detectMonorepoEnvironment = () => false; }",
  );
  api.setFailures();
  await assert.rejects(api.prepareServer({ argv: ["--latest"] }), /EXIT:1/);
  assert.ok(logs.some((line) => line.includes("FALLBACK-REACHED")));
});
test("wrapped late build timeout preserves network guidance", async () => {
  const failure = new Error("x".repeat(600) + "timeout\nINJECTED");
  const { api, logs } = load(
    ", setupBuild: () => { detectMonorepoEnvironment = () => true; findLocalGroovyLspJar = () => null; getMonorepoRoot = () => '/controlled'; }",
    failure,
  );
  api.setupBuild();
  await assert.rejects(
    api.prepareServer({ argv: ["--latest"], buildLocal: true }),
    /EXIT:1/,
  );
  assert.ok(
    logs.some((line) =>
      line.includes("Network error. Please check your internet connection"),
    ),
    "late wrapped timeout lost network guidance",
  );
});

test("wrapped build diagnostic remains bounded and single line", async () => {
  const failure = new Error("x".repeat(600) + "timeout\nINJECTED");
  const { api, logs } = load(
    ", setupBuild: () => { detectMonorepoEnvironment = () => true; findLocalGroovyLspJar = () => null; getMonorepoRoot = () => '/controlled'; }",
    failure,
  );
  api.setupBuild();
  await assert.rejects(
    api.prepareServer({ argv: ["--latest"], buildLocal: true }),
    /EXIT:1/,
  );
  const diagnostic = logs.find((line) =>
    line.startsWith("Failed to build local JAR:"),
  );
  assert.ok(diagnostic);
  assert.ok(diagnostic.length <= 500 && !diagnostic.includes("\n"));
});

test("hostile build error accessor cannot replace the wrapper diagnostic", async () => {
  const failure = Object.defineProperty({}, "message", {
    get() {
      throw new Error("getter ran");
    },
  });
  const { api, logs } = load(
    ", setupBuild: () => { detectMonorepoEnvironment = () => true; findLocalGroovyLspJar = () => null; getMonorepoRoot = () => '/controlled'; }",
    failure,
  );
  api.setupBuild();
  await assert.rejects(
    api.prepareServer({ argv: ["--latest"], buildLocal: true }),
    /EXIT:1/,
  );
  assert.ok(
    logs.some((line) => line.includes("Failed to build local JAR: ./gradlew")),
  );
});
test("real CLI unknown argument is a single record", () => {
  const child = spawnSync(
    process.execPath,
    [file, "--help", "--unknown\nFORGED"],
    {
      encoding: "utf8",
      timeout: 5000,
      env: { ...process.env, SKIP_PREPARE_SERVER: "true" },
    },
  );
  assert.equal(child.error, undefined);
  assert.equal(child.status, 0);
  const warning = child.stderr
    .split("\n")
    .find((line) => line.includes("Ignoring unknown options"));
  assert.ok(warning && warning.includes("FORGED"));
  assert.ok(!child.stderr.includes("\nFORGED"));
});
