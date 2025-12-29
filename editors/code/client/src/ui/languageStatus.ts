import * as vscode from 'vscode';
import { ServerState } from './statusBar';
import { JavaResolution } from '../java/finder';

/**
 * Document selector for Groovy-related files
 */
const GROOVY_DOCUMENT_SELECTOR: vscode.DocumentSelector = [
    { language: 'groovy' },
    { language: 'jenkinsfile' },
];

/**
 * Manages Language Status Items for rich per-file context display.
 * These appear near the language selector in the status bar.
 */
export class LanguageStatusManager implements vscode.Disposable {
    private serverStatusItem: vscode.LanguageStatusItem;
    private javaRuntimeItem: vscode.LanguageStatusItem | undefined;
    private buildToolItem: vscode.LanguageStatusItem | undefined;

    private serverState: ServerState = 'stopped';
    private serverVersion: string = 'unknown';
    private javaResolution: JavaResolution | undefined;
    private buildTool: BuildToolInfo | undefined;

    constructor() {
        // Server Status Item (always shown for Groovy files)
        this.serverStatusItem = vscode.languages.createLanguageStatusItem(
            'groovy.server',
            GROOVY_DOCUMENT_SELECTOR
        );
        this.serverStatusItem.name = 'Groovy Server';
        this.updateServerStatus('stopped', 'unknown');
    }

    /**
     * Updates the server status display
     */
    updateServerStatus(state: ServerState, version: string, message?: string): void {
        this.serverState = state;
        this.serverVersion = version;

        const { text, detail, severity } = this.getServerStatusDisplay(state, message);
        this.serverStatusItem.text = text;
        this.serverStatusItem.detail = detail;
        this.serverStatusItem.severity = severity;

        // Set appropriate command based on state
        if (state === 'stopped') {
            this.serverStatusItem.command = {
                title: 'Start Server',
                command: 'groovy.restartServer',
            };
        } else if (state === 'degraded') {
            this.serverStatusItem.command = {
                title: 'View Problems',
                command: 'workbench.actions.view.problems',
            };
        } else {
            this.serverStatusItem.command = {
                title: 'Server Menu',
                command: 'groovy.showStatusMenu',
            };
        }
    }

    /**
     * Gets the display properties for a server state
     */
    private getServerStatusDisplay(state: ServerState, message?: string): {
        text: string;
        detail: string;
        severity: vscode.LanguageStatusSeverity;
    } {
        switch (state) {
            case 'stopped':
                return {
                    text: '$(stop-circle) Stopped',
                    detail: 'Server is not running. Click to start.',
                    severity: vscode.LanguageStatusSeverity.Error,
                };
            case 'starting':
                return {
                    text: '$(loading~spin) Starting',
                    detail: 'Server is launching...',
                    severity: vscode.LanguageStatusSeverity.Information,
                };
            case 'resolving-deps':
                return {
                    text: '$(loading~spin) Dependencies',
                    detail: message || 'Resolving project dependencies...',
                    severity: vscode.LanguageStatusSeverity.Information,
                };
            case 'indexing':
                return {
                    text: '$(loading~spin) Indexing',
                    detail: message || 'Analyzing source files...',
                    severity: vscode.LanguageStatusSeverity.Information,
                };
            case 'ready':
                return {
                    text: `$(pass-filled) v${this.serverVersion}`,
                    detail: 'Server is ready',
                    severity: vscode.LanguageStatusSeverity.Information,
                };
            case 'degraded':
                return {
                    text: '$(warning) Degraded',
                    detail: message || 'Server running with limited functionality',
                    severity: vscode.LanguageStatusSeverity.Warning,
                };
        }
    }

    /**
     * Updates Java runtime status
     */
    updateJavaRuntime(resolution: JavaResolution | undefined): void {
        this.javaResolution = resolution;

        if (!resolution) {
            // Hide Java item if no resolution
            this.javaRuntimeItem?.dispose();
            this.javaRuntimeItem = undefined;
            return;
        }

        // Create or update Java runtime item
        if (!this.javaRuntimeItem) {
            this.javaRuntimeItem = vscode.languages.createLanguageStatusItem(
                'groovy.javaRuntime',
                GROOVY_DOCUMENT_SELECTOR
            );
            this.javaRuntimeItem.name = 'Java Runtime';
        }

        this.javaRuntimeItem.text = `$(coffee) Java ${resolution.version}`;
        this.javaRuntimeItem.detail = this.getJavaSourceDetail(resolution);
        this.javaRuntimeItem.severity = resolution.version >= 17
            ? vscode.LanguageStatusSeverity.Information
            : vscode.LanguageStatusSeverity.Warning;

        this.javaRuntimeItem.command = {
            title: 'Configure Java',
            command: 'workbench.action.openSettings',
            arguments: ['groovy.java.home'],
        };
    }

    /**
     * Gets a human-readable description of where Java was found
     */
    private getJavaSourceDetail(resolution: JavaResolution): string {
        switch (resolution.source) {
            case 'setting':
                return `From groovy.java.home: ${resolution.path}`;
            case 'java_home':
                return `From JAVA_HOME: ${resolution.path}`;
            case 'jdk_manager':
                return `From JDK manager: ${resolution.path}`;
            case 'login_shell':
                return `From shell: ${resolution.path}`;
            case 'system':
            default:
                return resolution.path;
        }
    }

    /**
     * Updates build tool status
     */
    updateBuildTool(info: BuildToolInfo | undefined): void {
        this.buildTool = info;

        if (!info) {
            // Hide build tool item if no info
            this.buildToolItem?.dispose();
            this.buildToolItem = undefined;
            return;
        }

        // Create or update build tool item
        if (!this.buildToolItem) {
            this.buildToolItem = vscode.languages.createLanguageStatusItem(
                'groovy.buildTool',
                GROOVY_DOCUMENT_SELECTOR
            );
            this.buildToolItem.name = 'Build Tool';
        }

        const icon = info.type === 'gradle' ? '$(package)' : '$(file-code)';
        this.buildToolItem.text = `${icon} ${info.type === 'gradle' ? 'Gradle' : 'Maven'}`;
        this.buildToolItem.detail = info.buildFile;
        this.buildToolItem.severity = vscode.LanguageStatusSeverity.Information;

        if (info.buildFileUri) {
            this.buildToolItem.command = {
                title: 'Open Build File',
                command: 'vscode.open',
                arguments: [info.buildFileUri],
            };
        } else {
            this.buildToolItem.command = {
                title: 'Refresh Project',
                command: 'groovy.gradle.refresh',
            };
        }
    }

    /**
     * Hides all status items (e.g., when no Groovy files are open)
     */
    hideAll(): void {
        // Language status items are automatically hidden when no matching documents are open
        // This method is for explicit hiding if needed
    }

    dispose(): void {
        this.serverStatusItem.dispose();
        this.javaRuntimeItem?.dispose();
        this.buildToolItem?.dispose();
    }
}

/**
 * Build tool information
 */
export interface BuildToolInfo {
    type: 'gradle' | 'maven';
    buildFile: string;
    buildFileUri?: vscode.Uri;
}

// =============================================================================
// Singleton instance
// =============================================================================

let languageStatusManager: LanguageStatusManager | undefined;

/**
 * Creates and returns the language status manager
 */
export function createLanguageStatusManager(): LanguageStatusManager {
    if (!languageStatusManager) {
        languageStatusManager = new LanguageStatusManager();
    }
    return languageStatusManager;
}

/**
 * Gets the language status manager instance
 */
export function getLanguageStatusManager(): LanguageStatusManager | undefined {
    return languageStatusManager;
}

/**
 * Disposes the language status manager
 */
export function disposeLanguageStatusManager(): void {
    languageStatusManager?.dispose();
    languageStatusManager = undefined;
}

