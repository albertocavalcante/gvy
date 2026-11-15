// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import assert from "node:assert/strict";
import test from "node:test";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { spawnSync } from "node:child_process";

// Exercise the workflow's actual admission shell, including Bash's conditional-errexit semantics.
// Stubbed tools test failure propagation only; they are never mechanism qualification evidence.
test("CI admission cannot ignore a failed Node resource or identity check", async () => {
  const directory = await mkdtemp(join(tmpdir(), "gvy-ci-admission-"));
  try {
    const yaml = await readFile(
      new URL(
        "../../.github/workflows/qualification-mechanism.yml",
        import.meta.url,
      ),
      "utf8",
    );
    const section = yaml
      .split("      - name: Admit preinstalled native tools and resources\n")[1]
      ?.split("\n      - name:")[0];
    const body = section
      ?.split("        run: |\n")[1]
      ?.split("\n")
      .map((line) => (line.startsWith("          ") ? line.slice(10) : line))
      .join("\n");
    assert.ok(body, "The actual workflow admission script must be present");
    const stubs =
      "node() { return 42; }; cc() { return 0; }; unshare() { return 0; }; ip() { return 0; }; strace() { return 0; };\n";
    const result = spawnSync("/bin/bash", ["-c", stubs + body], {
      encoding: "utf8",
      timeout: 5000,
      env: {
        ...process.env,
        RUNNER_TEMP: directory,
        QUALIFICATION_NAME: "gvy-qualification-test",
        GITHUB_ENV: join(directory, "github-env"),
      },
    });
    assert.equal(result.error, undefined);
    assert.equal(
      result.status,
      1,
      `Failed admission was ignored: ${result.stdout}\n${result.stderr}`,
    );
  } finally {
    await rm(directory, { recursive: true });
  }
});
