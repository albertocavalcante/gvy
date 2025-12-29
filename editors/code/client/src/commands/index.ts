import { ExtensionContext, commands, Disposable, window, Uri } from 'vscode';
import { restartClient, stopClient, getClient } from '../server/client';
import { ExecuteCommandRequest } from 'vscode-languageclient';
import { UpdateService } from '../features/update/UpdateService';
import { showStatusMenu, getStatusBarManager } from '../ui/statusBar';

let updateService: UpdateService | null = null;
let serverOutputChannel: import('vscode').OutputChannel | null = null;

/**
 * Sets the server output channel for the openLogs command
 */
export function setServerOutputChannel(channel: import('vscode').OutputChannel): void {
    serverOutputChannel = channel;
    getStatusBarManager()?.setOutputChannel(channel);
}

/**
 * Registers all extension commands
 */
export function registerCommands(context: ExtensionContext): Disposable[] {
    const disposables: Disposable[] = [];

    // Register status menu command
    const showStatusMenuCommand = commands.registerCommand('groovy.showStatusMenu', async () => {
        const manager = getStatusBarManager();
        if (manager) {
            await showStatusMenu(manager);
        }
    });
    disposables.push(showStatusMenuCommand);

    // Register open logs command
    const openLogsCommand = commands.registerCommand('groovy.openLogs', () => {
        if (serverOutputChannel) {
            serverOutputChannel.show();
        } else {
            window.showWarningMessage('Server output channel is not available');
        }
    });
    disposables.push(openLogsCommand);

    // Register stop server command
    const stopServerCommand = commands.registerCommand('groovy.stopServer', async () => {
        await stopClient();
        window.showInformationMessage('Groovy Language Server stopped');
    });
    disposables.push(stopServerCommand);

    // Register restart server command
    const restartServerCommand = commands.registerCommand('groovy.restartServer', async () => {
        await restartClient();
    });
    disposables.push(restartServerCommand);

    // Register show version command
    const showVersionCommand = commands.registerCommand('groovy.showVersion', async () => {
        const client = getClient();
        if (!client) {
            window.showErrorMessage('Groovy Language Server is not running');
            return;
        }

        try {
            const version = await client.sendRequest(ExecuteCommandRequest.type, {
                command: 'groovy.version',
                arguments: []
            });

            window.showInformationMessage(
                `Groovy Language Server: ${version || 'Unknown version'}`,
                'OK'
            );
        } catch (error) {
            console.error('Failed to get server version:', error);
            window.showErrorMessage(
                `Failed to get server version: ${error instanceof Error ? error.message : 'Unknown error'}`
            );
        }
    });
    disposables.push(showVersionCommand);

    // Register check for updates command
    const checkForUpdatesCommand = commands.registerCommand('groovy.checkForUpdates', async () => {
        if (updateService) {
            await updateService.checkNow();
        } else {
            window.showWarningMessage('Update service is not initialized');
        }
    });
    disposables.push(checkForUpdatesCommand);

    // Register report issue command
    const reportIssueCommand = commands.registerCommand('groovy.reportIssue', async () => {
        const url = 'https://github.com/albertocavalcante/gvy/issues/new';
        await commands.executeCommand('vscode.open', Uri.parse(url));
    });
    disposables.push(reportIssueCommand);

    // Add all disposables to context subscriptions
    context.subscriptions.push(...disposables);

    return disposables;
}

/**
 * Initializes the update service with the current extension version.
 * Should be called after the language client starts.
 */
export function initializeUpdateService(context: ExtensionContext, currentVersion: string): UpdateService {
    updateService = new UpdateService(currentVersion, context.globalState);
    return updateService;
}

/**
 * Gets the update service instance.
 */
export function getUpdateService(): UpdateService | null {
    return updateService;
}
