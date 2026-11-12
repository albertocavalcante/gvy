// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

export const vscodeTestVersion = "1.144.0";
export const vscodeTestSdkVersion = "3.1.0";
export const vscodeTestCliVersion = "0.0.15";
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
  if (
    config?.label !== "integrationTests" ||
    config.mocha?.timeout !== 30000 ||
    ![undefined, 0].includes(config.mocha?.retries) ||
    config.skipExtensionDependencies === true ||
    (config.installExtensions !== undefined &&
      (!Array.isArray(config.installExtensions) ||
        config.installExtensions.length !== 0)) ||
    (config.download !== undefined &&
      config.download.timeout !== vscodeTestIdleTimeout)
  )
    throw new Error("unsupported-test-configuration");
  if (
    installed
      ? config.useInstallation?.fromPath !== installed ||
        config.useInstallation.fromMachine !== undefined
      : config.version !== vscodeTestVersion || config.useInstallation !== undefined
  )
    throw new Error("unsupported-editor-selection");
}
