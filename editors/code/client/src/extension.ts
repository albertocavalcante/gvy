// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

/**
 * VSCode Groovy Language Extension
 */

import * as fs from "fs";
import * as vscode from "vscode";
import {
  initializeClient,
  startClient,
  stopClient,
  getClient,
  onClientStarted,
} from "./server/client";
import { startServerForActivation } from "./server/activation";
import { registerStatusBarItem, getStatusBarManager } from "./ui/statusBar";
import {
  createLanguageStatusManager,
  disposeLanguageStatusManager,
} from "./ui/languageStatus";
import {
  registerCommands,
  initializeUpdateService,
  setServerOutputChannel,
} from "./commands";
import { setupConfigurationWatcher } from "./configuration/watcher";
import {
  getConfiguration,
  getUpdateConfiguration,
} from "./configuration/settings";
import { resolveServerVersion } from "./features/update/serverVersion";
import { replService } from "./features/repl";
import { registerGradleFeatures } from "./features/gradle";
import { registerTestingFeatures } from "./features/testing";
import { registerDependencyFeatures } from "./features/dependencies";
import { LSPToolService } from "./features/ai/LSPToolService";
import { ToolRegistry } from "./features/ai/ToolRegistry";
import { LMToolProvider } from "./features/ai/LMToolProvider";
import { CommandProvider } from "./features/ai/CommandProvider";
import { TestFeature } from "./features/testing/TestFeature";
import { registerFileExecution } from "./features/fileExecution";
import { registerAstFeatures } from "./features/ast/AstProvider";

/**
 * Activates the extension.
 *
 * Every contributed command and view is registered before the language
 * server starts. Features resolve the client lazily, so they keep working
 * across restarts, and a server that fails to start (for example no Java)
 * does not leave declared commands unregistered.
 */
export async function activate(context: vscode.ExtensionContext) {
  const extensionVersion =
    (context.extension.packageJSON.version as string) || "unknown";

  initializeClient(context);

  context.subscriptions.push(
    registerStatusBarItem(undefined, extensionVersion),
  );

  const serverOutputChannel = vscode.window.createOutputChannel(
    "Groovy Language Server",
    { log: true },
  );
  context.subscriptions.push(serverOutputChannel);
  setServerOutputChannel(serverOutputChannel);
  getStatusBarManager()?.setOutputChannel(serverOutputChannel);
  let initialStart: Promise<boolean> | undefined;
  const requestInitialStart = (): Promise<boolean> => {
    initialStart ??= (async () => {
      try {
        await startClient(serverOutputChannel);
        return true;
      } catch (error) {
        // startClient already reports failures; keep the registered commands usable.
        console.error("Groovy Language Server did not start:", error);
        return false;
      }
    })();
    return initialStart;
  };
  context.subscriptions.push(
    onClientStarted(() => {
      initialStart = Promise.resolve(true);
      vscode.commands
        .executeCommand("setContext", "groovy.hasProject", true)
        .then(undefined, (error: unknown) => {
          console.error("Could not update Groovy project context:", error);
        });
    }),
  );

  createLanguageStatusManager();
  context.subscriptions.push({ dispose: () => disposeLanguageStatusManager() });

  registerCommands(context, requestInitialStart);
  registerFileExecution(context, requestInitialStart);
  context.subscriptions.push(setupConfigurationWatcher());

  replService.initialize(context);
  registerAstFeatures(context);
  context.subscriptions.push(new TestFeature());

  const testOutputChannel = vscode.window.createOutputChannel("Groovy Tests");
  context.subscriptions.push(testOutputChannel);
  registerGradleFeatures(context);
  registerTestingFeatures(context, testOutputChannel);
  registerDependencyFeatures(context);

  // AI tools
  const lspToolService = new LSPToolService(
    vscode,
    getClient,
    requestInitialStart,
  );
  const toolRegistry = new ToolRegistry();
  context.subscriptions.push(
    new LMToolProvider(lspToolService, toolRegistry),
    new CommandProvider(lspToolService, toolRegistry),
  );

  // Update checks compare gvy releases with the server's own version (the
  // extension is versioned separately).
  const updateService = initializeUpdateService(context, () =>
    resolveServerVersion({
      reported: getClient()?.initializeResult?.serverInfo?.version,
      serverPath: getConfiguration().serverPath,
      readInstalledTag: () =>
        fs.readFileSync(context.asAbsolutePath("server/.gls-version"), "utf8"),
    }),
  );
  context.subscriptions.push({ dispose: () => updateService.dispose() });
  if (getUpdateConfiguration().checkOnStartup) {
    updateService.activate().catch((error) => {
      console.warn("Background update check failed:", error);
    });
  }

  // Static GDSL editing waits for a supported document or an explicit server command.
  await startServerForActivation(context, async () => {
    await requestInitialStart();
  });
}

/**
 * Deactivates the extension
 */
export async function deactivate(): Promise<void> {
  try {
    await stopClient();
  } catch (error) {
    console.error("Error during deactivation:", error);
  }
}
