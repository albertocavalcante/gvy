import * as vscode from 'vscode';
import { State, LanguageClient, ProgressType } from 'vscode-languageclient/node';
import { WorkDoneProgressBegin, WorkDoneProgressReport, WorkDoneProgressEnd } from 'vscode-languageserver-protocol';

const TITLE = 'Groovy';
const LSP_TITLE = 'LSP';

/**
 * Server activity state - more granular than just Running/Stopped
 */
type ServerState =
    | 'stopped'
    | 'starting'
    | 'resolving-deps'
    | 'indexing'
    | 'ready'
    | 'degraded';

let statusBarItem: vscode.StatusBarItem | undefined;
let currentClient: LanguageClient | undefined;
let currentServerState: ServerState = 'stopped';
let currentProgressMessage: string | undefined;
let progressDisposable: vscode.Disposable | undefined;
let stateChangeDisposable: vscode.Disposable | undefined;

/**
 * Tracks active progress operations to handle concurrent tasks.
 * Only transitions to 'ready' when all active tasks complete.
 */
let activeProgressCount = 0;

export function registerStatusBarItem(client?: LanguageClient) {
    if (statusBarItem) {
        statusBarItem.dispose();
    }
    if (progressDisposable) {
        progressDisposable.dispose();
    }
    if (stateChangeDisposable) {
        stateChangeDisposable.dispose();
    }

    statusBarItem = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Right, 100);
    statusBarItem.text = TITLE;
    statusBarItem.show();

    if (client) {
        setClient(client);
    }

    updateView();

    return {
        dispose: () => {
            if (statusBarItem) {
                statusBarItem.dispose();
                statusBarItem = undefined;
            }
            if (progressDisposable) {
                progressDisposable.dispose();
                progressDisposable = undefined;
            }
            if (stateChangeDisposable) {
                stateChangeDisposable.dispose();
                stateChangeDisposable = undefined;
            }
            activeProgressCount = 0;
        }
    };
}

export function setClient(client: LanguageClient | undefined) {
    // Clean up previous client listeners
    if (progressDisposable) {
        progressDisposable.dispose();
        progressDisposable = undefined;
    }
    if (stateChangeDisposable) {
        stateChangeDisposable.dispose();
        stateChangeDisposable = undefined;
    }

    currentClient = client;
    currentServerState = 'stopped';
    currentProgressMessage = undefined;
    activeProgressCount = 0;

    if (currentClient) {
        // Listen for state changes
        stateChangeDisposable = currentClient.onDidChangeState((event) => {
            updateServerStateFromClientState(event.newState);
            updateView();
        });

        // Listen for progress notifications from LSP
        progressDisposable = setupProgressHandling(currentClient);
    }

    updateView();
}

/**
 * Sets up handling for LSP progress notifications.
 * The LSP sends $/progress with tokens like "groovy-lsp-deps-*" for dependency resolution.
 */
type ProgressValue = WorkDoneProgressBegin | WorkDoneProgressReport | WorkDoneProgressEnd;

function setupProgressHandling(client: LanguageClient): vscode.Disposable {
    // Use the onProgress handler from the client
    // The ProgressType indicates the value type in the progress notification
    const progressType = new ProgressType<ProgressValue>();

    return client.onProgress(
        progressType,
        '*', // Listen to all tokens
        (value: ProgressValue) => {
            handleProgressNotification(value);
        }
    );
}

/**
 * Handles a progress notification from the LSP.
 * Parses the message to infer server state.
 * Tracks active progress tokens to handle concurrent tasks.
 */
function handleProgressNotification(value: ProgressValue) {
    if (!value) return;

    const kind = value.kind;
    const message = value.message?.toLowerCase() || '';
    const title = 'title' in value ? (value.title?.toLowerCase() || '') : '';

    if (kind === 'begin') {
        // Starting a new progress operation
        activeProgressCount++;
        currentProgressMessage = value.message || value.title;
        inferStateFromMessage(message || title);
    } else if (kind === 'report') {
        // Progress update
        currentProgressMessage = value.message;
        inferStateFromMessage(message);
    } else if (kind === 'end') {
        // Progress complete - decrement counter
        activeProgressCount = Math.max(0, activeProgressCount - 1);

        // Only transition to 'ready' if ALL progress tasks are complete
        if (activeProgressCount === 0) {
            currentProgressMessage = undefined;
            if (currentServerState === 'resolving-deps' || currentServerState === 'indexing') {
                currentServerState = 'ready';
            }
        }
    }

    updateView();
}

/**
 * Infers server state from progress message content.
 * Checks for error conditions FIRST to ensure they're not masked.
 */
function inferStateFromMessage(message: string) {
    // Check for errors FIRST - these should not be masked by other keywords
    if (message.includes('failed') ||
        message.includes('error')) {
        currentServerState = 'degraded';
    } else if (message.includes('resolving') ||
        message.includes('gradle') ||
        message.includes('maven') ||
        message.includes('dependencies') ||
        message.includes('connecting')) {
        currentServerState = 'resolving-deps';
    } else if (message.includes('indexing') ||
        message.includes('compiling') ||
        message.includes('analyzing')) {
        currentServerState = 'indexing';
    } else if (message.includes('ready') ||
        message.includes('complete') ||
        message.includes('loaded')) {
        currentServerState = 'ready';
    }
}

/**
 * Maps LSP client state to our more granular server state.
 */
function updateServerStateFromClientState(state: State) {
    switch (state) {
        case State.Running:
            // Only set to ready if we don't have a more specific state
            if (currentServerState === 'stopped' || currentServerState === 'starting') {
                currentServerState = 'ready';
            }
            break;
        case State.Starting:
            currentServerState = 'starting';
            currentProgressMessage = undefined;
            activeProgressCount = 0;
            break;
        default:
            currentServerState = 'stopped';
            currentProgressMessage = undefined;
            activeProgressCount = 0;
    }
}

function updateView() {
    if (!statusBarItem) return;
    statusBarItem.tooltip = computeTooltip();
    statusBarItem.text = computeText();
    statusBarItem.backgroundColor = computeBackgroundColor();
}

function computeBackgroundColor(): vscode.ThemeColor | undefined {
    switch (currentServerState) {
        case 'degraded':
            return new vscode.ThemeColor('statusBarItem.warningBackground');
        case 'stopped':
            return new vscode.ThemeColor('statusBarItem.errorBackground');
        default:
            return undefined;
    }
}

function computeTooltip(): vscode.MarkdownString {
    const text = new vscode.MarkdownString();
    text.isTrusted = true;
    text.supportThemeIcons = true;
    text.supportHtml = true;

    const stateDescription = getStateDescription();
    const lspState = `<div>${getLspClientStatus()}</div>`;
    const progressInfo = currentProgressMessage
        ? `<div><em>${currentProgressMessage}</em></div>`
        : '';

    text.appendMarkdown(`
<div>
<h4>Groovy Language Server</h4>
${lspState}
${progressInfo}
<p><small>${stateDescription}</small></p>
</div>
    `);
    return text;
}

function getStateDescription(): string {
    switch (currentServerState) {
        case 'stopped':
            return 'Server is not running. Click restart to start.';
        case 'starting':
            return 'Server is launching...';
        case 'resolving-deps':
            return 'Loading project dependencies from build tool.';
        case 'indexing':
            return 'Analyzing source files for code intelligence.';
        case 'ready':
            return 'Server is ready. All features available.';
        case 'degraded':
            return 'Server running with limited functionality.';
    }
}

function computeText(): string {
    switch (currentServerState) {
        case 'ready':
            return `$(check) ${TITLE}`;
        case 'starting':
            return `$(sync~spin) ${TITLE}`;
        case 'resolving-deps':
            return `$(sync~spin) ${TITLE}: Deps`;
        case 'indexing':
            return `$(sync~spin) ${TITLE}: Indexing`;
        case 'degraded':
            return `$(warning) ${TITLE}`;
        case 'stopped':
        default:
            return `$(stop) ${TITLE}`;
    }
}

function getLspClientStatus(): string {
    const restartButton = `<a href="command:groovy.restartServer" title="Restart Server">$(sync)</a>`;
    const checkUpdatesButton = `<a href="command:groovy.checkForUpdates" title="Check for Updates">$(cloud-download)</a>`;

    switch (currentServerState) {
        case 'ready':
            return `$(check) ${LSP_TITLE}: Ready&nbsp;&nbsp;${restartButton}&nbsp;${checkUpdatesButton}`;
        case 'starting':
            return `$(sync~spin) ${LSP_TITLE}: Starting...`;
        case 'resolving-deps':
            return `$(sync~spin) ${LSP_TITLE}: Resolving Dependencies...`;
        case 'indexing':
            return `$(sync~spin) ${LSP_TITLE}: Indexing...`;
        case 'degraded':
            return `$(warning) ${LSP_TITLE}: Degraded&nbsp;&nbsp;${restartButton}`;
        case 'stopped':
        default:
            return `$(stop) ${LSP_TITLE}: Stopped&nbsp;&nbsp;${restartButton}`;
    }
}