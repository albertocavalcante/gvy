// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

const assert = require("node:assert/strict");
const path = require("node:path");
const { spawnSync } = require("node:child_process");
const test = require("node:test");

const entry = path.resolve(__dirname, "../gvy-install.js");

test("CI entrypoint shows help without selecting a source", () => {
  const result = spawnSync(process.execPath, [entry, "--help"], {
    encoding: "utf8",
    timeout: 10000,
    killSignal: "SIGKILL",
  });
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /--resume/);
  assert.match(result.stdout, /--from-ci/);
});

test("CI entrypoint rejects a competing source with one JSON failure", () => {
  const result = spawnSync(
    process.execPath,
    [entry, "--from-source", "--json"],
    {
      encoding: "utf8",
      timeout: 10000,
      killSignal: "SIGKILL",
    },
  );
  assert.equal(result.status, 1);
  const failure = JSON.parse(result.stdout);
  assert.equal(failure.success, false);
  assert.match(failure.error, /Choose exactly one source/);
});
