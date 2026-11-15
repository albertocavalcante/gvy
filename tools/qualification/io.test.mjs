// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import assert from "node:assert/strict";
import test from "node:test";
import { mkdtemp, writeFile, unlink, rmdir, mkdir, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import {
  command,
  fileIdentity,
  readJson,
  writeJson,
  configurationSnapshot,
} from "./io.mjs";

test("argv metacharacters are literal and output/deadlines are bounded", async () => {
  const literal = "$(touch /should-never-exist); `echo injected`";
  const result = await command([
    process.execPath,
    "-e",
    "process.stdout.write(process.argv[1])",
    literal,
  ]);
  assert.equal(result.code, 0);
  assert.equal(result.stdout, literal);
  await assert.rejects(
    command(
      [process.execPath, "-e", 'process.stdout.write("x".repeat(1024))'],
      { maxBytes: 100 },
    ),
    /output exceeds/,
  );
  await assert.rejects(
    command([process.execPath, "-e", "setInterval(() => {}, 1000)"], {
      timeoutMs: 50,
    }),
    /deadline exceeded/,
  );
});

test("artifact fingerprints and private atomic JSON receipts preserve exact bytes", async () => {
  const directory = await mkdtemp(join(tmpdir(), "gvy-qualification-test-"));
  const path = join(directory, "receipt.json");
  try {
    await writeJson(path, { result: "unqualified" });
    assert.deepEqual(await readJson(path), { result: "unqualified" });
    const before = await fileIdentity(path);
    await writeFile(path, "different");
    const after = await fileIdentity(path);
    assert.notEqual(before.sha256, after.sha256);
    assert.equal(after.bytes, 9);
  } finally {
    await unlink(path);
    await rmdir(directory);
  }
});

test("configuration preservation snapshots hash user settings and init scripts without exposing credentials", async () => {
  const directory = await mkdtemp(join(tmpdir(), "gvy-config-test-"));
  try {
    await mkdir(join(directory, "init.d"));
    await mkdir(join(directory, ".m2"));
    await writeFile(
      join(directory, "gradle.properties"),
      "password=private-fixture-secret",
    );
    await writeFile(
      join(directory, "init.d/custom.gradle"),
      "repositories { mavenLocal() }",
    );
    await writeFile(
      join(directory, ".m2/settings.xml"),
      "<settings>private-fixture-secret</settings>",
    );
    const before = await configurationSnapshot(
      directory,
      join(directory, ".m2"),
    );
    assert.equal(before.filter((item) => item.sha256).length, 3);
    assert.equal(
      JSON.stringify(before).includes("private-fixture-secret"),
      false,
    );
    await writeFile(join(directory, "init.d/custom.gradle"), "changed");
    assert.notDeepEqual(
      await configurationSnapshot(directory, join(directory, ".m2")),
      before,
    );
  } finally {
    await rm(directory, { recursive: true });
  }
});
