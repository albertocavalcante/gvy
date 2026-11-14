// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

// Run explicitly after prepare-runtime, including against an unpacked VSIX with
// GVY_TEST_EXTENSION_ROOT. GVY_TEST_GROOVY_24 supplies an existing local 2.4.21 JAR.
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { spawnSync, spawn } = require("node:child_process");
const { test } = require("node:test");

const extension =
  process.env.GVY_TEST_EXTENSION_ROOT || path.resolve(__dirname, "../..");
const manifest = JSON.parse(
  fs.readFileSync(path.join(extension, "runtime/manifest.json"), "utf8"),
);
const runtimes = [
  {
    version: manifest.version,
    jars: manifest.artifacts.map((artifact) =>
      path.join(extension, "runtime", artifact.file),
    ),
  },
];
if (process.env.GVY_TEST_GROOVY_24) {
  runtimes.push({ version: "2.4.21", jars: [process.env.GVY_TEST_GROOVY_24] });
}

function invoke(args, input) {
  const result = spawnSync(
    process.env.GVY_TEST_JAVA || "java",
    ["-Xmx128m", ...args],
    {
      encoding: "utf8",
      input,
      timeout: 30000,
      windowsHide: true,
    },
  );
  assert.ifError(result.error);
  return result;
}

for (const runtime of runtimes) {
  test(`Groovy ${runtime.version}: real file, stdin, literal arguments and compile-only bytecode`, (t) => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), "gvy runtime-"));
    t.after(() => fs.rmSync(root, { recursive: true, force: true }));
    const script = path.join(root, "héllo space $&.groovy");
    fs.writeFileSync(
      script,
      `
      def helper() { 'helper' }
      static void main(String[] args) {
        println java.util.Base64.encoder.encodeToString(args[0].getBytes('UTF-8'))
        println System.in.newReader().readLine()
        println GroovySystem.version
      }
    `,
    );
    const cp = runtime.jars.join(path.delimiter);
    const argument = "Unicode λ with spaces $() & ; | % !";
    const result = invoke(
      ["-cp", cp, "groovy.ui.GroovyMain", script, argument],
      "real stdin\n",
    );
    assert.equal(result.status, 0, result.stderr);
    assert.deepEqual(result.stdout.trim().split(/\r?\n/), [
      Buffer.from(argument).toString("base64"),
      "real stdin",
      runtime.version,
    ]);

    const source = path.join(root, "Main.groovy");
    fs.writeFileSync(
      source,
      "package demo; class Main { static void main(String[] args) { println 'main ran' } }",
    );
    const output = path.join(root, "fresh output");
    const compiled = invoke([
      "-cp",
      cp,
      "org.codehaus.groovy.tools.FileSystemCompiler",
      "-d",
      output,
      source,
    ]);
    assert.equal(compiled.status, 0, compiled.stderr);
    assert.equal(compiled.stdout, "", "Compile File must not invoke main");
    assert.ok(fs.statSync(path.join(output, "demo/Main.class")).size > 0);
    const launched = invoke([
      "-cp",
      [output, ...runtime.jars].join(path.delimiter),
      "demo.Main",
    ]);
    assert.equal(launched.status, 0, launched.stderr);
    assert.equal(launched.stdout.trim(), "main ran");

    fs.writeFileSync(
      script,
      "throw new IllegalStateException('runtime failure')",
    );
    const exception = invoke(["-cp", cp, "groovy.ui.GroovyMain", script]);
    assert.notEqual(exception.status, 0);
    assert.match(exception.stderr, /runtime failure/);
    fs.writeFileSync(script, "System.exit(7)");
    assert.equal(invoke(["-cp", cp, "groovy.ui.GroovyMain", script]).status, 7);
    fs.writeFileSync(script, "class {");
    assert.notEqual(
      invoke([
        "-cp",
        cp,
        "org.codehaus.groovy.tools.FileSystemCompiler",
        "-d",
        output,
        script,
      ]).status,
      0,
    );
  });

  test(`Groovy ${runtime.version}: cancellation stops an infinite child and next launch succeeds`, async (t) => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), "gvy cancel-"));
    t.after(() => fs.rmSync(root, { recursive: true, force: true }));
    const script = path.join(root, "loop.groovy");
    fs.writeFileSync(script, "println 'started'; while (true) {}");
    const child = spawn(
      process.env.GVY_TEST_JAVA || "java",
      [
        "-Xmx128m",
        "-cp",
        runtime.jars.join(path.delimiter),
        "groovy.ui.GroovyMain",
        script,
      ],
      { stdio: ["ignore", "pipe", "pipe"], windowsHide: true },
    );
    t.after(() => {
      if (child.exitCode === null) child.kill("SIGKILL");
    });
    await new Promise((resolve, reject) => {
      const timeout = setTimeout(() => {
        child.kill("SIGKILL");
        reject(new Error("Child did not start"));
      }, 30000);
      child.once("error", reject);
      child.stdout.once("data", () => {
        clearTimeout(timeout);
        resolve();
      });
    });
    await new Promise((resolve) => {
      child.once("exit", resolve);
      child.kill("SIGKILL");
    });
    fs.writeFileSync(script, "println 'next launch'");
    const next = invoke([
      "-cp",
      runtime.jars.join(path.delimiter),
      "groovy.ui.GroovyMain",
      script,
    ]);
    assert.equal(next.status, 0, next.stderr);
    assert.equal(next.stdout.trim(), "next launch");
  });
}
