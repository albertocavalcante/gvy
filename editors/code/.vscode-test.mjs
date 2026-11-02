import { defineConfig } from "@vscode/test-cli";

export default defineConfig([
  {
    label: "integrationTests",
    files: "client/out/test/integration/**/*.test.js",
    // Pinned to the engines.vscode floor: @vscode/test-electron 2.5.2 cannot launch VS Code >=1.138 on macOS (Electron ENOENT).
    // TODO: bump alongside engines.vscode / once a test-electron release includes the CFBundleExecutable fix.
    version: "1.103.0",
    workspaceFolder: "./",
    mocha: {
      ui: "tdd",
      timeout: 30000,
    },
  },
]);
