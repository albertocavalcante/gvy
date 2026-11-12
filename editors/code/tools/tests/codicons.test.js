// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { test } = require("node:test");
const { copyCodicons } = require("../codicons");

test("copies assets and both upstream licenses together", (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "codicons-test-"));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const source = path.join(root, "package");
  const dist = path.join(source, "dist");
  const target = path.join(root, "target");
  fs.mkdirSync(dist, { recursive: true });
  for (const file of ["codicon.css", "codicon.ttf"]) {
    fs.writeFileSync(path.join(dist, file), file);
  }
  for (const file of ["LICENSE", "LICENSE-CODE"]) {
    fs.writeFileSync(path.join(source, file), file);
  }
  copyCodicons(dist, target);
  for (const file of [
    "codicon.css",
    "codicon.ttf",
    "LICENSE",
    "LICENSE-CODE",
  ]) {
    assert.equal(fs.readFileSync(path.join(target, file), "utf8"), file);
  }
  assert.match(
    fs.readFileSync(path.join(target, "NOTICE"), "utf8"),
    /Microsoft Corporation/,
  );
});

test("fails if either upstream license is missing", (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "codicons-test-"));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const dist = path.join(root, "dist");
  fs.mkdirSync(dist);
  for (const file of ["codicon.css", "codicon.ttf"]) {
    fs.writeFileSync(path.join(dist, file), file);
  }
  assert.throws(() => copyCodicons(dist, path.join(root, "target")), /ENOENT/);
  fs.writeFileSync(path.join(root, "LICENSE"), "license");
  assert.throws(() => copyCodicons(dist, path.join(root, "target")), /ENOENT/);
});
