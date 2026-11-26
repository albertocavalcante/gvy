// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

const assert = require("node:assert/strict");
const { execFileSync } = require("node:child_process");
const { globSync } = require("node:fs");
const path = require("node:path");
const test = require("node:test");

test("the runner gives each compiled integration suite one host", () => {
  const editor = path.resolve(__dirname, "../..");
  const cli = path.join(
    path.dirname(require.resolve("@vscode/test-cli")),
    "bin.mjs",
  );
  const discovered = JSON.parse(
    execFileSync(process.execPath, [cli, "--list-configuration"], {
      cwd: editor,
      encoding: "utf8",
    }),
  );
  function files(label) {
    const host = discovered.find((item) => item.config.label === label);
    assert.ok(host, label);
    return JSON.parse(host.env.VSCODE_TEST_OPTIONS).files.map((file) =>
      path.relative(path.join(editor, "client/out/test/integration"), file),
    );
  }

  const jenkins = "jenkinsAuthoring.test.js";
  const suites = globSync("**/*.test.ts", {
    cwd: path.join(editor, "client/src/test/integration"),
  }).map((file) => path.normalize(file).replace(/\.ts$/, ".js"));
  assert.deepEqual(files("jenkinsAuthoring"), [jenkins]);
  assert.deepEqual(
    files("integrationTests").sort(),
    suites.filter((file) => file !== jenkins).sort(),
  );
});
