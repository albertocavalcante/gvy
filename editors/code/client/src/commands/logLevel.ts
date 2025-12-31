/**
 * Log Level Commands
 *
 * Provides quick toggle and picker UX for server log level configuration.
 */

import * as vscode from 'vscode';

type LogLevel = 'error' | 'warn' | 'info' | 'debug' | 'trace';

/**
 * Toggles between 'info' and 'debug' log levels for quick debugging.
 * Prompts for server restart after change.
 */
export async function toggleDebugLogs(): Promise<void> {
    const config = vscode.workspace.getConfiguration('groovy');
    const current = config.get<LogLevel>('server.logLevel', 'info');

    // Toggle between info and debug, treating trace as debug-enabled
    const newLevel: LogLevel = (current === 'debug' || current === 'trace') ? 'info' : 'debug';

    await config.update('server.logLevel', newLevel, vscode.ConfigurationTarget.Workspace);

    const verb = newLevel === 'debug' ? 'enabled' : 'disabled';
    const action = await vscode.window.showInformationMessage(
        `Debug logging ${verb}. Restart server for changes to take effect.`,
        'Restart Now',
        'Later'
    );

    if (action === 'Restart Now') {
        await vscode.commands.executeCommand('groovy.restartServer');
    }
}

/**
 * Shows quick pick to select log level with restart prompt.
 */
export async function selectLogLevel(): Promise<void> {
    const config = vscode.workspace.getConfiguration('groovy');
    const current = config.get<LogLevel>('server.logLevel', 'info');

    interface LogLevelItem extends vscode.QuickPickItem {
        level: LogLevel;
    }

    const levels: LogLevelItem[] = ([
        { level: 'error', label: 'error', description: 'Only errors' },
        { level: 'warn', label: 'warn', description: 'Warnings and errors' },
        { level: 'info', label: 'info', description: 'Normal operation (default)' },
        { level: 'debug', label: 'debug', description: 'Detailed debugging (recommended for issues)' },
        { level: 'trace', label: 'trace', description: 'Most verbose' },
    ] as const).map(item => ({
        ...item,
        picked: item.level === current,
        detail: item.level === current ? '$(check) Current' : undefined,
    }));

    const selected = await vscode.window.showQuickPick(levels, {
        title: 'Select Log Level',
        placeHolder: `Current: ${current}`,
    });

    if (selected && selected.level !== current) {
        await config.update('server.logLevel', selected.level, vscode.ConfigurationTarget.Workspace);

        const action = await vscode.window.showInformationMessage(
            `Log level set to "${selected.level}". Restart server?`,
            'Restart Now',
            'Later'
        );

        if (action === 'Restart Now') {
            await vscode.commands.executeCommand('groovy.restartServer');
        }
    }
}

/**
 * Gets current log level for display in UI
 */
export function getCurrentLogLevel(): LogLevel {
    const config = vscode.workspace.getConfiguration('groovy');
    return config.get<LogLevel>('server.logLevel', 'info');
}

/**
 * Checks if debug logging is currently enabled
 */
export function isDebugEnabled(): boolean {
    const level = getCurrentLogLevel();
    return level === 'debug' || level === 'trace';
}
