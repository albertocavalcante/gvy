/**
 * VSCode Groovy Language Extension
 * Modern, modular architecture with domain-driven design
 */

import * as vscode from "vscode";
import { initializeClient, startClient, stopClient, getClient } from "./server/client";
import { registerStatusBarItem, getStatusBarManager } from "./ui/statusBar";
import { createLanguageStatusManager, disposeLanguageStatusManager, getLanguageStatusManager } from "./ui/languageStatus";
import { registerCommands, initializeUpdateService, setServerOutputChannel } from "./commands";
import { setupConfigurationWatcher } from "./configuration/watcher";
import { getUpdateConfiguration } from "./configuration/settings";
import { registerFormatting } from "./features/formatting/formatter";
import { replService } from "./features/repl";
import { registerGradleFeatures } from "./features/gradle";
import { registerTestingFeatures } from "./features/testing";
import { LSPToolService } from "./features/ai/LSPToolService";
import { ToolRegistry } from "./features/ai/ToolRegistry";
import { LMToolProvider } from "./features/ai/LMToolProvider";
import { CommandProvider } from "./features/ai/CommandProvider";
import { TestFeature } from "./features/testing/TestFeature";

/**
 * Activates the extension
 */
export async function activate(context: vscode.ExtensionContext) {
    console.log("Groovy Language Extension is activating...");

    try {
        // Get extension version for status bar
        const extensionVersion = context.extension.packageJSON.version as string;

        // Initialize the LSP client with context
        initializeClient(context);

        // Register status bar indicator with version info
        const statusBarDisposable = registerStatusBarItem(undefined, extensionVersion);
        context.subscriptions.push(statusBarDisposable);

        // Create server output channel and link to status bar
        const serverOutputChannel = vscode.window.createOutputChannel("Groovy Language Server");
        context.subscriptions.push(serverOutputChannel);
        setServerOutputChannel(serverOutputChannel);
        getStatusBarManager()?.setOutputChannel(serverOutputChannel);

        // Create Language Status Items for rich status display
        const languageStatusManager = createLanguageStatusManager();
        context.subscriptions.push({ dispose: () => disposeLanguageStatusManager() });

        // Register commands
        registerCommands(context);

        // Setup configuration watchers
        const configWatcher = setupConfigurationWatcher();
        context.subscriptions.push(configWatcher);

        // Initialize REPL
        replService.initialize(context);

        // Start the Language Server
        await startClient();

        // AI Tools Integration
        const lspToolService = new LSPToolService(vscode, getClient);
        const toolRegistry = new ToolRegistry(vscode.workspace.getConfiguration('groovy'));

        // Register Adapters
        const lmToolProvider = new LMToolProvider(lspToolService, toolRegistry);
        const commandProvider = new CommandProvider(lspToolService, toolRegistry);

        context.subscriptions.push(lmToolProvider, commandProvider);

        // Register features that depend on the client
        registerFormatting(context);
        registerGradleFeatures(context);

        // Register testing features
        const testOutputChannel = vscode.window.createOutputChannel("Groovy Tests");
        context.subscriptions.push(testOutputChannel);
        registerTestingFeatures(context, testOutputChannel);

        // Register Spock Test Scaffolding
        // TODO: Move this into registerTestingFeatures once refined
        context.subscriptions.push(new TestFeature());

        // Initialize update service for LSP version checking
        // Uses extension version from package.json (which bundles the LSP)
        const updateService = initializeUpdateService(context, extensionVersion);
        const updateConfig = getUpdateConfiguration();
        if (updateConfig.checkOnStartup) {
            // Run async, don't block activation
            updateService.activate().catch((error) => {
                console.warn("Background update check failed:", error);
            });
        }
        context.subscriptions.push({ dispose: () => updateService.dispose() });

        console.log("Groovy Language Extension activated successfully");
    } catch (error) {
        const message = `Error activating Groovy Language Extension: ${error instanceof Error ? error.message : "Unknown error"}`;
        console.error(message);
        // Don't show error message to user during activation, as it might be transient
        // The individual components will show their own error messages as needed
    }
}

/**
 * Deactivates the extension
 */
export async function deactivate(): Promise<void> {
    console.log("Deactivating Groovy Language Extension...");

    try {
        await stopClient();
        console.log("Groovy Language Extension deactivated successfully");
    } catch (error) {
        console.error("Error during deactivation:", error);
    }
}
