import { defineConfig } from "@vscode/test-cli";

// Set VSCODE_TEST_EXECUTABLE to an installed VS Code binary (for example
// "/Applications/Visual Studio Code.app/Contents/MacOS/Code") to skip the
// download of a test copy into .vscode-test/.
const installed = process.env.VSCODE_TEST_EXECUTABLE;

export default defineConfig([
  {
    label: "integrationTests",
    files: "client/out/test/integration/**/*.test.js",
    // Packaged tests load the unpacked VSIX while fixtures stay in this workspace.
    extensionDevelopmentPath: process.env.GVY_TEST_EXTENSION_ROOT,
    // Without an installed binary, download the engines.vscode floor so the
    // tests exercise the oldest VS Code the manifest accepts. Bump together
    // with engines.vscode. (@vscode/test-electron >=3.1 resolves the macOS
    // executable from Info.plist, so >=1.138 launches.)
    ...(installed
      ? { useInstallation: { fromPath: installed } }
      : { version: "1.144.0" }),
    // Keep the editor workspace independent of extension assets and project detection.
    workspaceFolder: "client/src/test/fixtures",
    mocha: {
      ui: "tdd",
      timeout: 30000,
    },
  },
]);
