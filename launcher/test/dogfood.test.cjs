const assert = require("node:assert/strict");
const { spawnSync } = require("node:child_process");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { pathToFileURL } = require("node:url");
const { test } = require("node:test");

const launcher = process.env.GVY_LAUNCHER;
const jar = process.env.GVY_TEST_JAR;
const enabled = launcher && jar;

// Exercise useful agent workloads through the same native boundary as editors.
// Every command is bounded and creates files only in its own workspace.
test(
  "agent CLI checks SARIF, formats without overwriting, and exports a usable index",
  {
    skip:
      !enabled && "Set GVY_LAUNCHER and GVY_TEST_JAR (required in native CI)",
    timeout: 120000,
  },
  async (t) => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), "gvy dogfood "));
    t.after(() => fs.rmSync(root, { recursive: true, force: true }));
    const env = { ...process.env, GVY_JAR: path.resolve(jar), NO_COLOR: "1" };
    const good = path.join(root, "Good file.groovy");
    const bad = path.join(root, "Broken file.groovy");
    const source =
      "class Greeter {\n    String greet() {\n        return 'hello 世界 😀'\n    }\n}\n";
    fs.writeFileSync(good, source);
    fs.writeFileSync(bad, "class Broken {\n    def unfinished( {\n");
    function run(args) {
      const result = spawnSync(launcher, args, {
        cwd: root,
        env,
        timeout: 45000,
        maxBuffer: 8 * 1024 * 1024,
      });
      assert.ifError(result.error);
      assert.equal(result.signal, null);
      return result;
    }
    const valid = run(["check", "--format", "sarif", path.basename(good)]);
    assert.equal(valid.status, 0, valid.stderr.toString());
    assert.equal(JSON.parse(valid.stdout.toString()).version, "2.1.0");
    const invalid = run(["check", "--format", "sarif", path.basename(bad)]);
    assert.equal(invalid.status, 1, invalid.stderr.toString());
    const report = JSON.parse(invalid.stdout.toString());
    assert.ok(
      report.runs.some((run) =>
        run.results.some((result) => result.level === "error"),
      ),
    );
    const formatted = run(["format", path.basename(good)]);
    assert.equal(formatted.status, 0, formatted.stderr.toString());
    assert.match(formatted.stdout.toString(), /class Greeter/);
    assert.match(formatted.stdout.toString(), /hello 世界 😀/);
    assert.equal(fs.readFileSync(good, "utf8"), source);
    const index = path.join(root, "symbols.lsif");
    const indexed = run(["index", "--format", "lsif", "--output", index, "."]);
    assert.equal(indexed.status, 0, indexed.stderr.toString());
    const records = fs
      .readFileSync(index, "utf8")
      .trim()
      .split(/\r?\n/)
      .map(JSON.parse);
    assert.ok(records.some((record) => record.label === "metaData"));
    assert.ok(
      records.some(
        (record) =>
          record.label === "document" &&
          record.uri.endsWith("Good%20file.groovy"),
      ),
    );
    const {
      smokeServerCommand,
    } = require("../../editors/code/tools/smoke-server.js");
    let checked = false;
    await smokeServerCommand(launcher, [], 30000, env, {
      uri: pathToFileURL(good).href,
      text: source,
      verifySymbols(symbols) {
        assert.ok(symbols.some((symbol) => symbol.name === "Greeter"));
        checked = true;
      },
    });
    assert.equal(
      checked,
      true,
      "must analyze an opened document before shutdown",
    );
  },
);
