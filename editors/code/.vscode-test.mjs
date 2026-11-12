// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import { defineConfig } from "@vscode/test-cli";
import { vscodeTestConfigurations } from "./tools/vscode-test-config.mjs";

// Set VSCODE_TEST_EXECUTABLE to an installed VS Code binary (for example
// "/Applications/Visual Studio Code.app/Contents/MacOS/Code") to skip the
// download of a test copy into .vscode-test/.
// The same editor-local configuration supplies the CI provisioning version and
// guards. Keep its version aligned with engines.vscode and @types/vscode.
export default defineConfig(vscodeTestConfigurations());
