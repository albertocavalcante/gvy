import * as vscode from "vscode";
import { getClient } from "../../server/client";

export function registerAstFeatures(context: vscode.ExtensionContext) {
  context.subscriptions.push(
    vscode.commands.registerCommand("groovy.ast.show", () => {
      AstProvider.createOrShow(context.extensionUri);
    }),
  );
}

class AstProvider {
  public static currentPanel: AstProvider | undefined;
  public static readonly viewType = "groovyAst";

  private readonly _panel: vscode.WebviewPanel;
  private readonly _extensionUri: vscode.Uri;
  private _disposables: vscode.Disposable[] = [];
  /**
   * The Groovy document the panel shows. While the webview has focus
   * `activeTextEditor` is undefined, so the target must be remembered.
   */
  private _documentUri: string | undefined;

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
      "Groovy AST",
      column || vscode.ViewColumn.One,
      {
        enableScripts: true,
        localResourceRoots: [vscode.Uri.joinPath(extensionUri, "resources")],
      },
    );

    AstProvider.currentPanel = new AstProvider(panel, extensionUri);
  }

  private constructor(panel: vscode.WebviewPanel, extensionUri: vscode.Uri) {
    this._panel = panel;
    this._extensionUri = extensionUri;
    this.trackEditor(vscode.window.activeTextEditor);

    // Initial HTML
    this._panel.webview.html = this._getHtmlForWebview();

    // Listen for messages from the webview
    this._panel.webview.onDidReceiveMessage(
      (message) => {
        switch (message.type) {
          case "ready":
            this.update();
            break;
          case "highlight":
            this.highlightRange(message.range);
            break;
          case "refresh":
            // Refresh with the requested parser (ignore unknown values)
            this.update(
              message.parser === "core" || message.parser === "native"
                ? message.parser
                : undefined,
            );
            break;
        }
      },
      null,
      this._disposables,
    );

    // Auto-sync on save
    vscode.workspace.onDidSaveTextDocument(
      (document) => {
        if (
          this._panel.visible &&
          document.uri.toString() === this._documentUri
        ) {
          this.update(this._parserOverride || undefined); // Refresh with current state
        }
      },
      null,
      this._disposables,
    );

    // Auto-update when switching active editor
    vscode.window.onDidChangeActiveTextEditor(
      (editor) => {
        if (this.trackEditor(editor) && this._panel.visible) {
          this.update(this._parserOverride || undefined);
        }
      },
      null,
      this._disposables,
    );

    // Listen for when the panel is disposed
    // This happens when the user closes the panel or when the panel is closed programmatically
    this._panel.onDidDispose(() => this.dispose(), null, this._disposables);

    // Update the content based on view state changes
    this._panel.onDidChangeViewState(
      (_) => {
        if (this._panel.visible) {
          this.update(this._parserOverride || undefined);
        }
      },
      null,
      this._disposables,
    );
  }

  private _parserOverride: "core" | "native" | null = null;
  private _parserSelection: "core" | "native" = "core";

  /** Remembers `editor` if it shows a Groovy file; returns whether it did. */
  private trackEditor(editor: vscode.TextEditor | undefined): boolean {
    if (editor?.document.languageId !== "groovy") {
      return false;
    }
    this._documentUri = editor.document.uri.toString();
    return true;
  }

  private targetEditor(): vscode.TextEditor | undefined {
    const active = vscode.window.activeTextEditor;
    if (this.trackEditor(active)) {
      return active;
    }
    return vscode.window.visibleTextEditors.find(
      (e) => e.document.uri.toString() === this._documentUri,
    );
  }

  public async update(parserOverride?: "core" | "native") {
    const editor = this.targetEditor() ?? vscode.window.activeTextEditor;
    if (!editor) {
      this._panel.webview.postMessage({
        type: "error",
        message: "No active editor",
      });
      return;
    }

    if (editor.document.languageId !== "groovy") {
      this._panel.webview.postMessage({
        type: "error",
        message: "Active editor is not a Groovy file",
      });
      return;
    }

    if (parserOverride) {
      this._parserOverride = parserOverride;
      this._parserSelection = parserOverride;
    }

    const client = getClient();
    if (!client) {
      this._panel.webview.postMessage({
        type: "error",
        message: "Language Server not available.",
      });
      return;
    }

    try {
      // Request AST from LSP
      const parser = this._parserSelection;
      const result = (await client.sendRequest("groovy/ast", {
        uri: editor.document.uri.toString(),
        parser: parser,
      })) as { ast: string; parser: string };

      if (!result || typeof result.ast !== "string") {
        throw new Error("Invalid LSP response");
      }

      const ast = JSON.parse(result.ast);
      this._panel.webview.postMessage({
        type: "updateAst",
        ast: ast,
        parser: result.parser,
      });
    } catch (_e: unknown) {
      const error = _e as Error;
      console.error("AST Request failed", error);
      this._panel.webview.postMessage({
        type: "error",
        message: `AST Request failed: ${error.message}`,
      });
    }
  }

  private highlightRange(
    range:
      | {
          startLine: number;
          startColumn: number;
          endLine: number;
          endColumn: number;
        }
      | undefined,
  ) {
    const valid = (n: unknown) => Number.isInteger(n) && (n as number) >= 1;
    if (
      !range ||
      !valid(range.startLine) ||
      !valid(range.startColumn) ||
      !valid(range.endLine) ||
      !valid(range.endColumn)
    )
      return;
    const editor = this.targetEditor();
    if (!editor) return;

    const start = new vscode.Position(
      range.startLine - 1,
      range.startColumn - 1,
    );
    const end = new vscode.Position(range.endLine - 1, range.endColumn - 1);
    const newSelection = new vscode.Selection(start, end);

    editor.selection = newSelection;
    editor.revealRange(
      newSelection,
      vscode.TextEditorRevealType.InCenterIfOutsideViewport,
    );
  }

  public dispose() {
    AstProvider.currentPanel = undefined;

    // Clean up our resources
    this._panel.dispose();

    vscode.Disposable.from(...this._disposables).dispose();
  }

  private _getHtmlForWebview(): string {
    const webview = this._panel.webview;

    // Local path to main script
    const scriptPathOnDisk = vscode.Uri.joinPath(
      this._extensionUri,
      "resources",
      "ast-view",
      "main.js",
    );
    const scriptUri = webview.asWebviewUri(scriptPathOnDisk);

    // Local path to css styles
    const stylePathOnDisk = vscode.Uri.joinPath(
      this._extensionUri,
      "resources",
      "ast-view",
      "style.css",
    );
    const styleUri = webview.asWebviewUri(stylePathOnDisk);

    // Codicons (copied from @vscode/codicons by esbuild.js; node_modules is
    // neither packaged nor allowed by localResourceRoots)
    const codiconsUri = webview.asWebviewUri(
      vscode.Uri.joinPath(
        this._extensionUri,
        "resources",
        "ast-view",
        "codicons",
        "codicon.css",
      ),
    );

    // Use a nonce to whitelist which scripts can be run
    const nonce = getNonce();

    return `<!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src ${webview.cspSource} data:; style-src ${webview.cspSource}; script-src 'nonce-${nonce}'; font-src ${webview.cspSource};">
                <title>Groovy AST</title>
                <link href="${styleUri}" rel="stylesheet">
                <link href="${codiconsUri}" rel="stylesheet">
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
  let text = "";
  const possible =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
  for (let i = 0; i < 32; i++) {
    text += possible.charAt(Math.floor(Math.random() * possible.length));
  }
  return text;
}
