import * as vscode from 'vscode';
import { State, LanguageClient, ProgressType } from 'vscode-languageclient/node';
import { WorkDoneProgressBegin, WorkDoneProgressReport, WorkDoneProgressEnd } from 'vscode-languageserver-protocol';
import { getLanguageStatusManager } from './languageStatus';

const TITLE = 'Groovy';

/**
 * Server activity state - more granular than just Running/Stopped
 */
export type ServerState =
    | 'stopped'
    | 'starting'
    | 'resolving-deps'
    | 'indexing'
    | 'ready'
    | 'degraded';

/**
 * Server health status (follows rust-analyzer pattern)
 */
export type ServerHealth = 'ok' | 'warning' | 'error';

/**
 * Groovy server status notification parameters.
 * Based on rust-analyzer's `experimental/serverStatus` notification pattern.
 */
export interface GroovyStatusParams {
    /** Server functional state (ok, warning, error) */
    health: ServerHealth;
    /** Whether there is any pending background work */
    quiescent: boolean;
    /** Optional human-readable message */
    message?: string;
    /** Current number of files indexed (for progress display) */
    filesIndexed?: number;
    /** Total number of files to index (for progress display) */
    filesTotal?: number;
}

/**
 * Document selector for Groovy-related files.
 * Note: .gradle files are registered as 'groovy' language in package.json.
 * We do NOT include .gradle.kts - those are Kotlin files, not supported.
 */
const GROOVY_DOCUMENT_SELECTOR: vscode.DocumentSelector = [
    { language: 'groovy' },
    { language: 'jenkinsfile' },
];

/**
 * Status bar visibility setting
 */
type StatusBarShowSetting = 'always' | 'onGroovyFile' | 'never';

/**
 * Status bar click action setting
 */
type StatusBarClickAction = 'menu' | 'logs' | 'restart';

/**
 * Status bar icons for each state
 */
const STATUS_ICONS: Record<ServerState, string> = {
    stopped: '$(stop-circle)',
    starting: '$(loading~spin)',
    'resolving-deps': '$(loading~spin)',
    indexing: '$(loading~spin)',
    ready: '$(pass-filled)',
    degraded: '$(warning)',
};

/**
 * Manages the Groovy Language Server status bar item with smart visibility,
 * click-to-open menu, and rich tooltips.
 */
export class StatusBarManager implements vscode.Disposable {
    private statusBarItem: vscode.StatusBarItem;
    private currentClient: LanguageClient | undefined;
    private currentState: ServerState = 'stopped';
    private currentProgressMessage: string | undefined;
    private activeProgressCount = 0;
    private extensionVersion: string;
    private serverVersion: string = 'unknown';
    private outputChannel: vscode.OutputChannel | undefined;

    // Server status notification fields (from groovy/status)
    private quiescent: boolean = true;
    private filesIndexed: number | undefined;
    private filesTotal: number | undefined;
    private serverHealth: ServerHealth = 'ok';

    private disposables: vscode.Disposable[] = [];
    private clientDisposables: vscode.Disposable[] = [];

    constructor(extensionVersion: string) {
        this.extensionVersion = extensionVersion;

        // Create status bar item (left-aligned like rust-analyzer)
        this.statusBarItem = vscode.window.createStatusBarItem(
            'groovy.serverStatus',
            vscode.StatusBarAlignment.Left,
            100
        );
        this.statusBarItem.name = 'Groovy Language Server';

        // Set click action from settings
        this.updateClickAction();

        // Smart visibility - only show on Groovy files (based on settings)
        this.disposables.push(
            vscode.window.onDidChangeActiveTextEditor((editor) => {
                this.updateVisibility(editor);
            })
        );

        // Listen for configuration changes
        this.disposables.push(
            vscode.workspace.onDidChangeConfiguration((e) => {
                if (e.affectsConfiguration('groovy.statusBar')) {
                    this.updateClickAction();
                    this.updateVisibility(vscode.window.activeTextEditor);
                }
            })
        );

        // Initial visibility check
        this.updateVisibility(vscode.window.activeTextEditor);
        this.updateView();
    }

    /**
     * Updates the click action based on settings
     */
    private updateClickAction(): void {
        const config = vscode.workspace.getConfiguration('groovy');
        const clickAction = config.get<StatusBarClickAction>('statusBar.clickAction', 'menu');

        switch (clickAction) {
            case 'logs':
                this.statusBarItem.command = 'groovy.openLogs';
                break;
            case 'restart':
                this.statusBarItem.command = 'groovy.restartServer';
                break;
            case 'menu':
            default:
                this.statusBarItem.command = 'groovy.showStatusMenu';
                break;
        }
    }

    /**
     * Sets the output channel for "Open Logs" command
     */
    setOutputChannel(channel: vscode.OutputChannel): void {
        this.outputChannel = channel;
    }

    /**
     * Gets the output channel
     */
    getOutputChannel(): vscode.OutputChannel | undefined {
        return this.outputChannel;
    }

    /**
     * Updates status bar visibility based on active editor and settings
     */
    private updateVisibility(editor: vscode.TextEditor | undefined): void {
        const config = vscode.workspace.getConfiguration('groovy');
        const showSetting = config.get<StatusBarShowSetting>('statusBar.show', 'onGroovyFile');

        switch (showSetting) {
            case 'always':
                this.statusBarItem.show();
                return;

            case 'never':
                this.statusBarItem.hide();
                return;

            case 'onGroovyFile':
            default:
                if (!editor) {
                    // No editor - hide status bar
                    this.statusBarItem.hide();
                    return;
                }

                // Check if the document matches our selector
                if (vscode.languages.match(GROOVY_DOCUMENT_SELECTOR, editor.document) > 0) {
                    this.statusBarItem.show();
                } else {
                    this.statusBarItem.hide();
                }
                return;
        }
    }

    /**
     * Sets the Language Client and subscribes to its events
     */
    setClient(client: LanguageClient | undefined): void {
        // Clean up previous client subscriptions
        this.clientDisposables.forEach(d => d.dispose());
        this.clientDisposables = [];

        this.currentClient = client;
        this.currentState = 'stopped';
        this.currentProgressMessage = undefined;
        this.activeProgressCount = 0;
        this.quiescent = true;
        this.filesIndexed = undefined;
        this.filesTotal = undefined;
        this.serverHealth = 'ok';

        if (this.currentClient) {
            // Listen for state changes
            this.clientDisposables.push(
                this.currentClient.onDidChangeState((event) => {
                    this.updateStateFromClient(event.newState);
                    this.updateView();
                })
            );

            // Listen for groovy/status notifications (primary status source)
            this.clientDisposables.push(
                this.setupGroovyStatusHandling(this.currentClient)
            );

            // Listen for progress notifications (fallback for generic progress)
            this.clientDisposables.push(
                this.setupProgressHandling(this.currentClient)
            );
        }

        this.updateView();
    }

    /**
     * Sets up handling for groovy/status notifications.
     * This is the primary status source (replaces inference from progress messages).
     */
    private setupGroovyStatusHandling(client: LanguageClient): vscode.Disposable {
        return client.onNotification('groovy/status', (params: GroovyStatusParams) => {
            this.handleGroovyStatus(params);
        });
    }

    /**
     * Handles groovy/status notifications from the server.
     * Based on rust-analyzer's experimental/serverStatus pattern.
     */
    private handleGroovyStatus(params: GroovyStatusParams): void {
        // Update server health
        this.serverHealth = params.health;

        // Update quiescent state
        this.quiescent = params.quiescent;

        // Update file counts
        this.filesIndexed = params.filesIndexed;
        this.filesTotal = params.filesTotal;

        // Update message
        if (params.message) {
            this.currentProgressMessage = params.message;
        } else if (params.quiescent) {
            this.currentProgressMessage = undefined;
        }

        // Map health + quiescent to ServerState
        this.updateStateFromStatus(params);

        this.updateView();
    }

    /**
     * Maps groovy/status health and quiescent to our ServerState
     */
    private updateStateFromStatus(params: GroovyStatusParams): void {
        // Error health always means degraded
        if (params.health === 'error') {
            this.currentState = 'degraded';
            return;
        }

        // Warning health means degraded
        if (params.health === 'warning') {
            this.currentState = 'degraded';
            return;
        }

        // Not quiescent means server is working
        if (!params.quiescent) {
            // Determine specific state from message
            const message = (params.message || '').toLowerCase();
            if (message.includes('resolving') || message.includes('dependencies')) {
                this.currentState = 'resolving-deps';
            } else if (message.includes('indexing')) {
                this.currentState = 'indexing';
            } else if (message.includes('initializing') || message.includes('starting')) {
                this.currentState = 'starting';
            } else {
                // Default to indexing if we have file counts
                if (params.filesTotal && params.filesTotal > 0) {
                    this.currentState = 'indexing';
                } else {
                    this.currentState = 'starting';
                }
            }
            return;
        }

        // Quiescent and healthy = ready
        this.currentState = 'ready';
    }

    /**
     * Sets the server version (from LSP initialization or version command)
     */
    setServerVersion(version: string): void {
        this.serverVersion = version;
        this.updateView();
    }

    /**
     * Gets current server state
     */
    getState(): ServerState {
        return this.currentState;
    }

    /**
     * Gets current progress message
     */
    getProgressMessage(): string | undefined {
        return this.currentProgressMessage;
    }

    /**
     * Sets up handling for LSP progress notifications
     */
    private setupProgressHandling(client: LanguageClient): vscode.Disposable {
        type ProgressValue = WorkDoneProgressBegin | WorkDoneProgressReport | WorkDoneProgressEnd;
        const progressType = new ProgressType<ProgressValue>();

        return client.onProgress(progressType, '*', (value: ProgressValue) => {
            this.handleProgress(value);
        });
    }

    /**
     * Handles progress notifications from the LSP
     */
    private handleProgress(value: WorkDoneProgressBegin | WorkDoneProgressReport | WorkDoneProgressEnd): void {
        if (!value) return;

        const kind = value.kind;
        const message = value.message?.toLowerCase() || '';
        const title = 'title' in value ? (value.title?.toLowerCase() || '') : '';

        if (kind === 'begin') {
            this.activeProgressCount++;
            this.currentProgressMessage = value.message || ('title' in value ? value.title : undefined);
            this.inferStateFromMessage(message || title);
        } else if (kind === 'report') {
            this.currentProgressMessage = value.message;
            this.inferStateFromMessage(message);
        } else if (kind === 'end') {
            this.activeProgressCount = Math.max(0, this.activeProgressCount - 1);

            if (this.activeProgressCount === 0) {
                this.currentProgressMessage = undefined;
                if (this.currentState === 'resolving-deps' || this.currentState === 'indexing') {
                    this.currentState = 'ready';
                }
            }
        }

        this.updateView();
    }

    /**
     * Infers server state from progress message content.
     * @deprecated This is a fallback for servers that don't send groovy/status.
     *             New servers should use groovy/status notifications instead.
     */
    private inferStateFromMessage(message: string): void {
        // Skip inference if we've received groovy/status notifications
        // (indicated by having explicit file counts or recent status update)
        if (this.filesTotal !== undefined) {
            return;
        }

        // Check for errors FIRST
        if (message.includes('failed') || message.includes('error')) {
            this.currentState = 'degraded';
        } else if (
            message.includes('resolving') ||
            message.includes('gradle') ||
            message.includes('maven') ||
            message.includes('dependencies') ||
            message.includes('connecting')
        ) {
            this.currentState = 'resolving-deps';
        } else if (
            message.includes('indexing') ||
            message.includes('compiling') ||
            message.includes('analyzing')
        ) {
            this.currentState = 'indexing';
        } else if (
            message.includes('ready') ||
            message.includes('complete') ||
            message.includes('loaded')
        ) {
            this.currentState = 'ready';
        }
    }

    /**
     * Maps LSP client state to our server state
     */
    private updateStateFromClient(state: State): void {
        switch (state) {
            case State.Running:
                if (this.currentState === 'stopped' || this.currentState === 'starting') {
                    this.currentState = 'ready';
                }
                break;
            case State.Starting:
                this.currentState = 'starting';
                this.currentProgressMessage = undefined;
                this.activeProgressCount = 0;
                break;
            default:
                this.currentState = 'stopped';
                this.currentProgressMessage = undefined;
                this.activeProgressCount = 0;
        }
    }

    /**
     * Updates the status bar item view
     */
    private updateView(): void {
        this.statusBarItem.text = this.computeText();
        this.statusBarItem.tooltip = this.computeTooltip();
        this.statusBarItem.backgroundColor = this.computeBackgroundColor();
        this.statusBarItem.color = this.computeForegroundColor();

        // Also update Language Status Items
        this.syncLanguageStatus();
    }

    /**
     * Synchronizes state to Language Status Items
     */
    private syncLanguageStatus(): void {
        const langStatus = getLanguageStatusManager();
        if (langStatus) {
            langStatus.updateServerStatus(
                this.currentState,
                this.serverVersion,
                this.currentProgressMessage,
                this.filesIndexed,
                this.filesTotal
            );
        }
    }

    /**
     * Computes the status bar text with icon
     */
    private computeText(): string {
        const icon = STATUS_ICONS[this.currentState];
        const suffix = this.getStateSuffix();
        return suffix ? `${icon} ${TITLE}: ${suffix}` : `${icon} ${TITLE}`;
    }

    /**
     * Gets the suffix for the status bar text.
     * Shows file counts during indexing if available.
     */
    private getStateSuffix(): string {
        switch (this.currentState) {
            case 'resolving-deps':
                return 'Deps';
            case 'indexing':
                // Show file counts if available (e.g., "23/456")
                if (this.filesTotal !== undefined && this.filesTotal > 0) {
                    const indexed = this.filesIndexed ?? 0;
                    return `${indexed}/${this.filesTotal}`;
                }
                return 'Indexing';
            case 'starting':
                return 'Starting';
            default:
                return '';
        }
    }

    /**
     * Computes the rich tooltip with version info and actions
     */
    private computeTooltip(): vscode.MarkdownString {
        const md = new vscode.MarkdownString('', true);
        md.isTrusted = true;
        md.supportThemeIcons = true;

        // Header
        md.appendMarkdown(`### Groovy Language Server\n\n`);

        // Status with icon
        const statusIcon = STATUS_ICONS[this.currentState];
        const statusText = this.getStateDisplayText();
        md.appendMarkdown(`**Status:** ${statusIcon} ${statusText}\n\n`);

        // Progress message if any
        if (this.currentProgressMessage) {
            md.appendMarkdown(`**Activity:** ${this.currentProgressMessage}\n\n`);
        }

        // Version info
        md.appendMarkdown(`---\n\n`);
        md.appendMarkdown(`**Extension:** v${this.extensionVersion}\n\n`);
        md.appendMarkdown(`**Server:** v${this.serverVersion}\n\n`);

        // Action links
        md.appendMarkdown(`---\n\n`);
        md.appendMarkdown(
            `[$(terminal) Logs](command:groovy.openLogs "Open Server Logs") · ` +
            `[$(refresh) Reload](command:groovy.gradle.refresh "Reload Workspace") · ` +
            `[$(debug-restart) Restart](command:groovy.restartServer "Restart Server")`
        );

        if (this.currentState === 'stopped') {
            md.appendMarkdown(` · [$(play) Start](command:groovy.restartServer "Start Server")`);
        } else {
            md.appendMarkdown(` · [$(stop-circle) Stop](command:groovy.stopServer "Stop Server")`);
        }

        return md;
    }

    /**
     * Gets human-readable state description
     */
    private getStateDisplayText(): string {
        switch (this.currentState) {
            case 'stopped':
                return 'Stopped';
            case 'starting':
                return 'Starting...';
            case 'resolving-deps':
                return 'Resolving Dependencies...';
            case 'indexing':
                // Show file counts if available
                if (this.filesTotal !== undefined && this.filesTotal > 0) {
                    const indexed = this.filesIndexed ?? 0;
                    const pct = Math.round((indexed / this.filesTotal) * 100);
                    return `Indexing ${indexed}/${this.filesTotal} files (${pct}%)`;
                }
                return 'Indexing...';
            case 'ready':
                return 'Ready';
            case 'degraded':
                return 'Degraded';
        }
    }

    /**
     * Computes background color based on state
     */
    private computeBackgroundColor(): vscode.ThemeColor | undefined {
        switch (this.currentState) {
            case 'degraded':
                return new vscode.ThemeColor('statusBarItem.warningBackground');
            case 'stopped':
                return new vscode.ThemeColor('statusBarItem.errorBackground');
            default:
                return undefined;
        }
    }

    /**
     * Computes foreground color based on state
     */
    private computeForegroundColor(): vscode.ThemeColor | undefined {
        switch (this.currentState) {
            case 'degraded':
                return new vscode.ThemeColor('statusBarItem.warningForeground');
            case 'stopped':
                return new vscode.ThemeColor('statusBarItem.errorForeground');
            default:
                return undefined;
        }
    }

    dispose(): void {
        this.statusBarItem.dispose();
        this.disposables.forEach(d => d.dispose());
        this.clientDisposables.forEach(d => d.dispose());
    }
}

// =============================================================================
// Status Menu (Quick Pick)
// =============================================================================

interface StatusMenuItem extends vscode.QuickPickItem {
    command?: string;
    args?: unknown[];
}

/**
 * Shows the status menu with available actions
 */
export async function showStatusMenu(manager: StatusBarManager): Promise<void> {
    const state = manager.getState();
    const items: StatusMenuItem[] = [];

    // Context-specific items first
    if (state === 'degraded') {
        items.push({
            label: '$(warning) View Problems',
            description: 'Show workspace problems',
            command: 'workbench.action.problems.focus',
        });
        items.push({ label: '', kind: vscode.QuickPickItemKind.Separator });
    }

    // Core actions
    items.push({
        label: '$(terminal) Open Logs',
        description: 'View server output',
        command: 'groovy.openLogs',
    });

    items.push({
        label: '$(refresh) Reload Workspace',
        description: 'Refresh project structure',
        command: 'groovy.gradle.refresh',
    });

    if (state === 'stopped') {
        items.push({
            label: '$(play) Start Server',
            description: 'Start the language server',
            command: 'groovy.restartServer',
        });
    } else {
        items.push({
            label: '$(debug-restart) Restart Server',
            description: 'Restart the language server',
            command: 'groovy.restartServer',
        });

        items.push({
            label: '$(stop-circle) Stop Server',
            description: 'Stop the language server',
            command: 'groovy.stopServer',
        });
    }

    // Separator
    items.push({ label: '', kind: vscode.QuickPickItemKind.Separator });

    // Version & Updates
    items.push({
        label: '$(info) Show Version',
        description: 'Show server version info',
        command: 'groovy.showVersion',
    });

    items.push({
        label: '$(cloud-download) Check for Updates',
        description: 'Check for server updates',
        command: 'groovy.checkForUpdates',
    });

    // Separator
    items.push({ label: '', kind: vscode.QuickPickItemKind.Separator });

    // Settings & Help
    items.push({
        label: '$(gear) Open Settings',
        description: 'Configure Groovy extension',
        command: 'workbench.action.openSettings',
        args: ['@ext:albertocavalcante.gvy'],
    });

    items.push({
        label: '$(github) Report Issue',
        description: 'Report a bug or request a feature',
        command: 'groovy.reportIssue',
    });

    // Show the quick pick
    const selected = await vscode.window.showQuickPick(items, {
        title: 'Groovy Language Server',
        placeHolder: 'Select an action',
    });

    if (selected?.command) {
        await vscode.commands.executeCommand(selected.command, ...(selected.args || []));
    }
}

// =============================================================================
// Legacy API (for backward compatibility)
// =============================================================================

let statusBarManager: StatusBarManager | undefined;

/**
 * Registers the status bar item (legacy API)
 */
export function registerStatusBarItem(client?: LanguageClient, extensionVersion?: string): vscode.Disposable {
    statusBarManager = new StatusBarManager(extensionVersion || 'unknown');

    if (client) {
        statusBarManager.setClient(client);
    }

    return statusBarManager;
}

/**
 * Sets the client on the status bar manager (legacy API)
 */
export function setClient(client: LanguageClient | undefined): void {
    statusBarManager?.setClient(client);
}

/**
 * Gets the status bar manager instance
 */
export function getStatusBarManager(): StatusBarManager | undefined {
    return statusBarManager;
}
