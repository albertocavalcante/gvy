// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import { assert } from "chai";
import {
  escapeGroovySingleQuoted,
  planFoojayInsertion,
} from "../../../src/java/settingsGradle";

const ID = "org.gradle.toolchains.foojay-resolver-convention";

describe("settingsGradle", () => {
  describe("planFoojayInsertion", () => {
    it("inserts a new block at the top of a file without pluginManagement", () => {
      assert.deepEqual(planFoojayInsertion("rootProject.name = 'a'\n"), {
        kind: "newBlock",
        offset: 0,
      });
    });

    it("places a new plugins block after pluginManagement", () => {
      const head =
        "pluginManagement {\n  repositories { gradlePluginPortal() }\n}\n";
      const plan = planFoojayInsertion(`${head}rootProject.name = 'a'\n`);
      assert.deepEqual(plan, { kind: "newBlock", offset: head.length });
    });

    it("places a new plugins block after buildscript and pluginManagement", () => {
      const head = "buildscript { }\npluginManagement {\n}\n";
      const plan = planFoojayInsertion(`${head}include 'x'\n`);
      assert.deepEqual(plan, { kind: "newBlock", offset: head.length });
    });

    it("skips a trailing comment after the closing brace", () => {
      const head = "pluginManagement {\n} // end\n";
      const plan = planFoojayInsertion(`${head}include 'x'\n`);
      assert.deepEqual(plan, { kind: "newBlock", offset: head.length });
    });

    it("ignores plugins nested in pluginManagement", () => {
      const text =
        "pluginManagement {\n  plugins {\n    id 'x' version '1'\n  }\n}\n";
      assert.equal(planFoojayInsertion(text).kind, "newBlock");
    });

    it("ignores plugins mentioned in comments", () => {
      const text = `// plugins {\n//   id '${ID}'\n// }\n/* plugins { id '${ID}' } */\nrootProject.name = 'a'\n`;
      assert.equal(planFoojayInsertion(text).kind, "newBlock");
    });

    it("inserts into the top-level plugins block", () => {
      const text = "pluginManagement { }\nplugins {\n  id 'x'\n}\n";
      const plan = planFoojayInsertion(text);
      assert.deepEqual(plan, {
        kind: "intoPlugins",
        offset: text.indexOf("plugins {") + "plugins {".length,
      });
    });

    it("does not match across unrelated blocks", () => {
      const text = `plugins {\n  id 'x'\n}\n// later: id '${ID}'\n`;
      assert.equal(planFoojayInsertion(text).kind, "intoPlugins");
    });

    it("detects Groovy and Kotlin declarations in the plugins block", () => {
      assert.equal(
        planFoojayInsertion(`plugins { id '${ID}' version '1' }`).kind,
        "configured",
      );
      assert.equal(
        planFoojayInsertion(`plugins {\n id("${ID}") version "1"\n}`).kind,
        "configured",
      );
    });
  });

  describe("escapeGroovySingleQuoted", () => {
    it("escapes quotes, backslashes and newlines", () => {
      assert.equal(escapeGroovySingleQuoted("Bob's app"), "Bob\\'s app");
      assert.equal(escapeGroovySingleQuoted("a\\b\nc"), "a\\\\b\\nc");
    });
  });
});
