// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

export const vscodeTestVersion = "1.144.0";
export const vscodeTestSdkVersion = "3.1.0";
export const vscodeTestCliVersion = "0.0.15";
export const vscodeTestProxyAgentVersion = "7.0.6";
export const vscodeTestIdleTimeout = 15000;

export function vscodeTestConfigurations(env = process.env) {
  const installed = env.VSCODE_TEST_EXECUTABLE;
  return [
    {
      label: "integrationTests",
      files: "client/out/test/integration/**/*.test.js",
      extensionDevelopmentPath: env.GVY_TEST_EXTENSION_ROOT,
      ...(installed
        ? { useInstallation: { fromPath: installed } }
        : { version: vscodeTestVersion }),
      workspaceFolder: "client/src/test/fixtures",
      mocha: { ui: "tdd", timeout: 30000 },
    },
  ];
}

export function validateVscodeTestInputs(manifest, configurations, installed) {
  if (manifest?.engines?.vscode !== `^${vscodeTestVersion}`)
    throw new Error("editor-floor-mismatch");
  if (
    manifest.extensionDependencies !== undefined &&
    (!Array.isArray(manifest.extensionDependencies) ||
      manifest.extensionDependencies.length !== 0)
  )
    throw new Error("unsupported-extension-installation");
  if (!Array.isArray(configurations) || configurations.length !== 1)
    throw new Error("unsupported-test-configuration");
  const config = configurations[0];
  const allowed = [
    "label",
    "files",
    "extensionDevelopmentPath",
    "workspaceFolder",
    "mocha",
    installed ? "useInstallation" : "version",
  ];
  if (
    !config ||
    Object.keys(config).some((key) => !allowed.includes(key)) ||
    config?.label !== "integrationTests" ||
    config.files !== "client/out/test/integration/**/*.test.js" ||
    config.workspaceFolder !== "client/src/test/fixtures" ||
    config.mocha?.ui !== "tdd" ||
    config.mocha?.timeout !== 30000 ||
    Object.keys(config.mocha).some(
      (key) => !["ui", "timeout", "retries"].includes(key),
    ) ||
    ![undefined, 0].includes(config.mocha?.retries) ||
    config.installExtensions !== undefined
  )
    throw new Error("unsupported-test-configuration");
  if (
    installed
      ? config.useInstallation?.fromPath !== installed ||
        Object.keys(config.useInstallation).some((key) => key !== "fromPath")
      : config.version !== vscodeTestVersion ||
        config.useInstallation !== undefined
  )
    throw new Error("unsupported-editor-selection");
}
