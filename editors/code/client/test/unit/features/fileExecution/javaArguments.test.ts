// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import * as assert from "assert";
import * as fs from "fs/promises";
import * as os from "os";
import * as path from "path";
import { spawnSync } from "child_process";
import {
  qualifyWindowsArguments,
  quoteJavaArgument,
  shortenJavaArguments,
} from "../../../../src/features/fileExecution/javaArguments";

describe("Java launcher argument files", () => {
  it("escapes paths and preserves all unrelated arguments", async () => {
    const original = [
      "-Xmx64m",
      "-cp",
      'C:\\folder with spaces\\a"b;C:\\x'.repeat(700),
      "Main",
      "$(literal)",
      "日本語",
    ];
    let content = "";
    const shortened = await shortenJavaArguments(
      original,
      "C:\\managed\\class path.args",
      "win32",
      async () => 21,
      async (_file, text) => {
        content = text;
      },
    );
    assert.deepStrictEqual(shortened, [
      original[0],
      "-cp",
      "@C:\\managed\\class path.args",
      "Main",
      "$(literal)",
      "日本語",
    ]);
    assert.strictEqual(content, quoteJavaArgument(original[2]) + "\n");
    assert.ok(content.includes("\\\\folder"));
    assert.ok(content.includes('\\"b'));
  });
  it("leaves short commands unchanged without probing or writing", async () => {
    const args = ["-cp", "/x", "Main"];
    assert.strictEqual(
      await shortenJavaArguments(
        args,
        "/out/args",
        "linux",
        async () => {
          throw new Error();
        },
        async () => {
          throw new Error();
        },
      ),
      args,
    );
  });
  it("rejects legacy Java and unqualified Windows classpath encodings", async () => {
    const args = ["-cp", "x".repeat(17000), "Main"];
    await assert.rejects(
      () =>
        shortenJavaArguments(
          args,
          "/out/args",
          "win32",
          async () => 8,
          async () => {},
        ),
      /Java 9/,
    );
    await assert.rejects(
      () =>
        shortenJavaArguments(
          ["-cp", "日本".repeat(9000), "Main"],
          "/out/args",
          "win32",
          async () => 21,
          async () => {},
        ),
      /encoding/,
    );
  });
  it("round-trips a long classpath through the local Java launcher", async function () {
    this.timeout(20000);
    const version = spawnSync("java", ["-version"], { encoding: "utf8" });
    if (version.error || version.status !== 0) this.skip();
    const dir = await fs.mkdtemp(path.join(os.tmpdir(), "gvy-java-args-"));
    try {
      const first = path.join(
        dir,
        process.platform === "win32"
          ? "space & symbols"
          : "日本 space & symbols",
      );
      const cp = [
        first,
        ...Array(7000).fill(path.join(dir, "dependency.jar")),
      ].join(path.delimiter);
      const args = ["-cp", cp, "-XshowSettings:properties", "-version"];
      const shortened = await shortenJavaArguments(
        args,
        path.join(dir, "cp.args"),
        process.platform,
        async () => 21,
        async (file, content) => fs.writeFile(file, content, "utf8"),
      );
      const run = spawnSync("java", shortened, {
        encoding: "utf8",
        maxBuffer: 4 * 1024 * 1024,
      });
      assert.strictEqual(run.status, 0, run.stderr);
      assert.ok(run.stderr.includes(`java.class.path = ${first}`));
    } finally {
      await fs.rm(dir, { recursive: true, force: true });
    }
  });
  it("rejects Windows Unicode argv when the selected launcher uses a legacy code page", async () => {
    await assert.rejects(
      () =>
        qualifyWindowsArguments(
          ["Main", "日本語"],
          "win32",
          async () => "    native.encoding = windows-1252\n",
        ),
      /encoding/i,
    );
    await qualifyWindowsArguments(
      ["Main", "日本語"],
      "win32",
      async () => "    native.encoding = UTF-8\n",
    );
    await qualifyWindowsArguments(["Main", "ASCII"], "win32", async () => {
      throw new Error("must not probe");
    });
    await qualifyWindowsArguments(["Main", "日本語"], "linux", async () => {
      throw new Error("must not probe");
    });
  });
});
