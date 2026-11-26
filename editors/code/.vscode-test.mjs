// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import { defineConfig } from "@vscode/test-cli";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

// Set VSCODE_TEST_EXECUTABLE to an installed VS Code binary (for example
// "/Applications/Visual Studio Code.app/Contents/MacOS/Code") to skip the
// download of a test copy into .vscode-test/.
const installed = process.env.VSCODE_TEST_EXECUTABLE;
const userDataDir = process.env.GVY_TEST_USER_DATA_DIR;
const runtime = installed
  ? { useInstallation: { fromPath: installed } }
  : { version: "1.144.0" };

// The runner outlives VS Code's text models. A tab can close long before the corresponding didClose.
const jenkinsFixtures = mkdtempSync(join(tmpdir(), "gvy-jenkins-authoring-"));
process.once("exit", () => {
  rmSync(jenkinsFixtures, {
    recursive: true,
    force: true,
    maxRetries: 8,
    retryDelay: 100,
  });
});
const jenkinsProfile = userDataDir
  ? `${userDataDir}-jenkins`
  : fileURLToPath(new URL(".vscode-test/jenkins-user-data", import.meta.url));

export default defineConfig([
  {
    label: "integrationTests",
    files: "client/out/test/integration/**/!(jenkinsAuthoring).test.js",
    // Packaged tests load the unpacked VSIX while fixtures stay in this workspace.
    extensionDevelopmentPath: process.env.GVY_TEST_EXTENSION_ROOT,
    // macOS limits the IPC socket path; long worktree paths need a short test profile.
    ...(userDataDir ? { launchArgs: [`--user-data-dir=${userDataDir}`] } : {}),
    // Without an installed binary, download the engines.vscode floor so the
    // tests exercise the oldest VS Code the manifest accepts. Bump together
    // with engines.vscode. (@vscode/test-electron >=3.1 resolves the macOS
    // executable from Info.plist, so >=1.138 launches.)
    ...runtime,
    // Keep the editor workspace independent of extension assets and project detection.
    workspaceFolder: "client/src/test/fixtures",
    mocha: {
      ui: "tdd",
      timeout: 30000,
    },
  },
  {
    label: "jenkinsAuthoring",
    files: "client/out/test/integration/jenkinsAuthoring.test.js",
    extensionDevelopmentPath: process.env.GVY_TEST_EXTENSION_ROOT,
    ...runtime,
    workspaceFolder: "client/src/test/fixtures",
    env: { GVY_JENKINS_FIXTURE_ROOT: jenkinsFixtures },
    launchArgs: [`--user-data-dir=${jenkinsProfile}`],
    mocha: { ui: "tdd", timeout: 30000 },
  },
  {
    label: "gdslLifecycle",
    files: "client/out/test/gdsl-lifecycle/**/*.test.js",
    extensionDevelopmentPath: process.env.GVY_TEST_EXTENSION_ROOT,
    ...runtime,
    workspaceFolder: "client/src/test/gdsl-workspace",
    launchArgs: [
      "--log=trace",
      `--user-data-dir=${fileURLToPath(new URL(".vscode-test/gdsl-user-data", import.meta.url))}`,
    ],
    mocha: { ui: "tdd", timeout: 30000 },
  },
]);
