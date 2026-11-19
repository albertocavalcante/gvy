// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

const assert = require("node:assert/strict");
const test = require("node:test");
const { verifyHover } = require("../verify-release-editor.js");

test("release editor proof rejects empty and generic word hovers", () => {
  for (const text of [
    "",
    "greet",
    "String Greeter",
    "greet(String other) Greeter",
    "Object greet(String name) Greeter",
  ]) {
    assert.throws(() => verifyHover(text));
  }
});

test("release editor proof requires signature and declaring class", () => {
  assert.throws(() => verifyHover("String greet(String name)"));
  verifyHover("public String greet(String name)\nDeclaring class: Greeter");
});

const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { verifyInstalledRelease } = require("../verify-release-editor.js");

test("installed release proof rejects an altered manifest and client", () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "gvy-release-negative-"));
  const packaged = { version: "0.5.4", main: "client.js" };
  try {
    fs.writeFileSync(path.join(root, "client.js"), "altered client");
    fs.writeFileSync(
      path.join(root, "package.json"),
      JSON.stringify({ ...packaged, main: "other.js" }),
    );
    assert.throws(
      () => verifyInstalledRelease(root, packaged),
      /Installed manifest differs/,
    );
    fs.writeFileSync(
      path.join(root, "package.json"),
      JSON.stringify({ ...packaged, __metadata: { installedTimestamp: 1 } }),
    );
    assert.throws(
      () => verifyInstalledRelease(root, packaged),
      /Installed client differs/,
    );
  } finally {
    fs.rmSync(root, { recursive: true });
  }
});
