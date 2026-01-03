import * as vscode from 'vscode';
import { getClient } from '../../server/client';

export function registerAstFeatures(context: vscode.ExtensionContext) {
    context.subscriptions.push(
        vscode.commands.registerCommand('groovy.ast.show', () => {
            AstProvider.createOrShow(context.extensionUri);
        })
    );
}

class AstProvider {
    public static currentPanel: AstProvider | undefined;
    public static readonly viewType = 'groovyAst';

    private readonly _panel: vscode.WebviewPanel;
    private readonly _extensionUri: vscode.Uri;
    private _disposables: vscode.Disposable[] = [];

    public static createOrShow(extensionUri: vscode.Uri) {
        const column = vscode.window.activeTextEditor
            ? vscode.window.activeTextEditor.viewColumn
            : undefined;

        // If we already have a panel, show it.
        if (AstProvider.currentPanel) {
            AstProvider.currentPanel._panel.reveal(column);
            AstProvider.currentPanel.update();
            return;
        }

        // Otherwise, create a new panel.
        const panel = vscode.window.createWebviewPanel(
            AstProvider.viewType,
            'Groovy AST',
            column || vscode.ViewColumn.One,
            {
                enableScripts: true,
                localResourceRoots: [vscode.Uri.joinPath(extensionUri, 'resources')]
            }
        );

        AstProvider.currentPanel = new AstProvider(panel, extensionUri);
    }

    private constructor(panel: vscode.WebviewPanel, extensionUri: vscode.Uri) {
        this._panel = panel;
        this._extensionUri = extensionUri;

        // Initial HTML
        this._panel.webview.html = this._getHtmlForWebview();

        // Listen for messages from the webview
        this._panel.webview.onDidReceiveMessage(
            message => {
                switch (message.type) {
                    case 'ready':
                        this.update();
                        break;
                    case 'highlight':
                        this.highlightRange(message.range);
                        break;
                    case 'refresh':
                        this.update(message.parser); // Refresh with requested parser
                        break;
                }
            },
            null,
            this._disposables
        );

        // Auto-sync on save
        vscode.workspace.onDidSaveTextDocument(
            document => {
                if (this._panel.visible && vscode.window.activeTextEditor?.document === document) {
                    this.update(); // Refresh with current state
                }
            },
            null,
            this._disposables
        );

        // Listen for when the panel is disposed
        // This happens when the user closes the panel or when the panel is closed programmatically
        this._panel.onDidDispose(() => this.dispose(), null, this._disposables);

        // Update the content based on view state changes
        this._panel.onDidChangeViewState(
            e => {
                if (this._panel.visible) {
                    this.update();
                }
            },
            null,
            this._disposables
        );
    }

    public async update(parserOverride?: string) {
        const editor = vscode.window.activeTextEditor;
        if (!editor || editor.document.languageId !== 'groovy') {
            this._panel.webview.postMessage({ type: 'error', message: 'No active Groovy editor.' });
            return;
        }

        const client = getClient();
        if (!client) {
            this._panel.webview.postMessage({ type: 'error', message: 'Language Server not available.' });
            return;
        }

        try {
            // Request AST from LSP
            const parser = parserOverride || 'core'; // Default to core if not specified
            const result: any = await client.sendRequest('groovy/ast', {
                uri: editor.document.uri.toString(),
                parser: parser
            });

            const ast = JSON.parse(result.ast);
            this._panel.webview.postMessage({
                type: 'updateAst',
                ast: ast,
                parser: result.parser
            });
        } catch (e: any) {
            console.error('AST Request failed', e);
            this._panel.webview.postMessage({ type: 'error', message: `AST Request failed: ${e.message}` });
        }
    }

    private highlightRange(range: any) {
        if (!range) return;
        const editor = vscode.window.activeTextEditor;
        if (!editor) return;

        const start = new vscode.Position(range.startLine - 1, range.startColumn - 1);
        const end = new vscode.Position(range.endLine - 1, range.endColumn - 1);
        const newSelection = new vscode.Selection(start, end);

        editor.selection = newSelection;
        editor.revealRange(newSelection, vscode.TextEditorRevealType.InCenterIfOutsideViewport);
    }

    public dispose() {
        AstProvider.currentPanel = undefined;

        // Clean up our resources
        this._panel.dispose();

        while (this._disposables.length) {
            const x = this._disposables.pop();
            if (x) {
                x.dispose();
            }
        }
    }

    private _getHtmlForWebview(): string {
        const webview = this._panel.webview;

        // Local path to main script
        const scriptPathOnDisk = vscode.Uri.joinPath(this._extensionUri, 'resources', 'ast-view', 'main.js');
        const scriptUri = webview.asWebviewUri(scriptPathOnDisk);

        // Local path to css styles
        const stylePathOnDisk = vscode.Uri.joinPath(this._extensionUri, 'resources', 'ast-view', 'style.css');
        const styleUri = webview.asWebviewUri(stylePathOnDisk);

        // Use a nonce to whitelist which scripts can be run
        const nonce = getNonce();

        return `<!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src ${webview.cspSource}; script-src 'nonce-${nonce}'; font-src ${webview.cspSource};">
                <title>Groovy AST</title>
                <link href="${styleUri}" rel="stylesheet">
                <link href="${webview.cspSource}/codicons/codicon.css" rel="stylesheet">
            </head>
            <body>
                <div class="toolbar">
                    <span>Parser:</span>
                    <select id="parser-select">
                        <option value="core">Core (GroovyParser)</option>
                        <option value="native">Native (Groovy 3)</option>
                    </select>
                    <button id="btn-refresh" class="codicon codicon-refresh" title="Refresh AST"></button>
                    <div class="spacer"></div>
                    <button id="btn-export">Export JSON</button>
                </div>
                <div class="main-content">
                    <div id="tree-pane" class="pane">
                        <h3>AST Structure</h3>
                        <div id="tree-container"></div>
                    </div>
                    <div id="details-pane" class="pane">
                        <h3>Properties</h3>
                        <div id="details-container">Select a node to view details</div>
                    </div>
                </div>
                <script nonce="${nonce}" src="${scriptUri}"></script>
            </body>
            </html>`;
    }
}

function getNonce() {
    let text = '';
    const possible = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
    for (let i = 0; i < 32; i++) {
        text += possible.charAt(Math.floor(Math.random() * possible.length));
    }
    return text;
}
