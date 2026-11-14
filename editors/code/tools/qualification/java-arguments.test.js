// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

// Transpile client/src/features/fileExecution/javaArguments.ts with the pinned
// esbuild, then set GVY_JAVA_ARGUMENTS_MODULE to that exact compiled module.
const assert = require("node:assert/strict");
const fs = require("node:fs/promises");
const os = require("node:os");
const path = require("node:path");
const { spawnSync } = require("node:child_process");
const { test } = require("node:test");
const { shortenJavaArguments, qualifyWindowsArguments } = require(
  process.env.GVY_JAVA_ARGUMENTS_MODULE,
);

test("native Java receives the long classpath and literal program arguments", async (t) => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), "gvy-java-args-"));
  t.after(() => fs.rm(root, { recursive: true, force: true }));
  const extension =
    process.env.GVY_TEST_EXTENSION_ROOT || path.resolve(__dirname, "../..");
  const manifest = JSON.parse(
    await fs.readFile(path.join(extension, "runtime/manifest.json"), "utf8"),
  );
  const jars = manifest.artifacts.map((item) =>
    path.join(extension, "runtime", item.file),
  );
  const source = path.join(root, "source with spaces.groovy");
  await fs.writeFile(
    source,
    "println java.util.Base64.encoder.encodeToString(args[0].getBytes('UTF-8'))",
  );
  const classpath = Array.from({ length: 1000 }, () => jars)
    .flat()
    .join(path.delimiter);
  let value = "literal λ $() & ; | ! % spaces";
  const args = [
    "-Xmx128m",
    "-cp",
    classpath,
    "groovy.ui.GroovyMain",
    source,
    value,
  ];
  const file = path.join(root, "classpath with spaces.args");
  const executable = process.env.GVY_TEST_JAVA || "java";
  const version = spawnSync(executable, ["-Xmx64m", "-version"], {
    encoding: "utf8",
    timeout: 10000,
  });
  assert.ifError(version.error);
  assert.equal(version.status, 0);
  const major = Number(/version "(?:1\.)?(\d+)/.exec(version.stderr)?.[1]);
  const probe = async () => {
    const settings = spawnSync(
      executable,
      ["-Xmx64m", "-XshowSettings:properties", "-version"],
      { encoding: "utf8", timeout: 10000 },
    );
    assert.ifError(settings.error);
    assert.equal(settings.status, 0);
    return settings.stderr;
  };
  try {
    await qualifyWindowsArguments(args, process.platform, probe);
  } catch (error) {
    assert.equal(process.platform, "win32");
    assert.match(error.message, /cannot safely pass Unicode/);
    t.diagnostic(
      "Legacy Windows native encoding: Unicode rejected by the actual client guard; executing the supported ASCII subset.",
    );
    value = "literal $() & ; | ! % spaces";
    args[args.length - 1] = value;
    await qualifyWindowsArguments(args, process.platform, probe);
  }
  const shortened = await shortenJavaArguments(
    args,
    file,
    process.platform,
    async () => major,
    fs.writeFile,
  );
  assert.equal(shortened[2], `@${file}`);
  const result = spawnSync(executable, shortened, {
    encoding: "utf8",
    timeout: 30000,
    windowsHide: true,
  });
  assert.ifError(result.error);
  assert.equal(result.status, 0, result.stderr);
  assert.equal(result.stdout.trim(), Buffer.from(value).toString("base64"));
});
