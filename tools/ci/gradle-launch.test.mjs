// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { chmodSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { gradleLaunch } from "./gradle-launch.mjs";

test("POSIX uses the executable wrapper without inspecting a Windows override", () => {
  const args = ["lint", "-Pvalue=space and $()"];
  assert.deepEqual(
    gradleLaunch(args, {
      platform: "linux",
      bashPath: "invalid",
      stat: () => {
        throw new Error();
      },
    }),
    { executable: "./gradlew", args },
  );
});

test("Windows rejects absent, relative, root-relative and non-Bash command paths", (t) => {
  const previous = process.env.GVY_CI_GRADLE_BASH;
  delete process.env.GVY_CI_GRADLE_BASH;
  t.after(() => {
    if (previous === undefined) delete process.env.GVY_CI_GRADLE_BASH;
    else process.env.GVY_CI_GRADLE_BASH = previous;
  });
  for (const bashPath of [
    undefined,
    "",
    "bash",
    ".\\bash.exe",
    "\\bash.exe",
    "C:bash.exe",
    "/usr/bin/bash",
    "C:\\Git\\cmd.exe",
    "C:\\Git\\bash.exe\nPRIVATE",
  ]) {
    assert.throws(
      () => gradleLaunch(["lint"], { platform: "win32", bashPath }),
      (error) =>
        /absolute existing Git Bash/.test(error.message) &&
        !error.message.includes("PRIVATE"),
    );
  }
});

test("Windows rejects a missing executable or directory without disclosing its path", () => {
  const bashPath = "C:\\PRIVATE\\bash.exe";
  for (const stat of [
    () => {
      throw new Error(bashPath);
    },
    () => ({ isFile: () => false }),
  ]) {
    assert.throws(
      () => gradleLaunch(["lint"], { platform: "win32", bashPath, stat }),
      (error) =>
        /absolute existing Git Bash/.test(error.message) &&
        !error.message.includes("PRIVATE"),
    );
  }
});

test("Windows sends each Gradle argument to the selected executable without a command string", () => {
  const args = [
    "lint",
    "",
    "-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8",
    '"quoted"',
    "%PATH%!&|$()",
    "trailing\\",
  ];
  const before = [...args];
  const executable = "C:\\Program Files\\Git\\usr\\bin\\bash.exe";
  assert.deepEqual(
    gradleLaunch(args, {
      platform: "win32",
      bashPath: executable,
      stat: () => ({ isFile: () => true }),
    }),
    { executable, args: ["./gradlew", ...args] },
  );
  assert.deepEqual(args, before);
});

test("the native process forwards argv, environment, both streams and command exit status", (t) => {
  const dir = mkdtempSync(join(tmpdir(), "gvy-gradle-launch-"));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const wrapper = join(dir, "gradlew");
  writeFileSync(
    wrapper,
    `#!/bin/sh
printf '%s\\0' "$@"
printf 'stderr:%s' "$GVY_LAUNCH_TEST_ENV" >&2
exit 23
`,
  );
  chmodSync(wrapper, 0o755);
  const args = [
    "lint",
    "",
    "space value",
    '"quoted"',
    "tail\\",
    "%PATH%!&|$()",
    "λ-世界-🦜",
  ];
  const launch = gradleLaunch(args);
  const result = spawnSync(launch.executable, launch.args, {
    cwd: dir,
    shell: false,
    env: { ...process.env, GVY_LAUNCH_TEST_ENV: "env-λ-世界" },
    timeout: 10000,
  });
  assert.equal(result.error, undefined);
  assert.equal(result.status, 23);
  assert.deepEqual(result.stdout, Buffer.from(args.join("\0") + "\0"));
  assert.equal(result.stderr.toString(), "stderr:env-λ-世界");
});

test("an execution timeout is an error rather than a successful command", async (t) => {
  const dir = mkdtempSync(join(tmpdir(), "gvy-gradle-launch-timeout-"));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const wrapper = join(dir, "gradlew");
  // The bounded child exits itself even if the host cannot forward a POSIX signal.
  writeFileSync(wrapper, "#!/bin/sh\nsleep 1\n");
  chmodSync(wrapper, 0o755);
  const launch = gradleLaunch([]);
  const result = spawnSync(launch.executable, launch.args, {
    cwd: dir,
    shell: false,
    timeout: 100,
  });
  assert.equal(result.error?.code, "ETIMEDOUT");
  assert.notEqual(result.status, 0);
  await new Promise((resolve) => setTimeout(resolve, 1100));
});
