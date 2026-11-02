import * as vscode from "vscode";
import { GroovyReplClient } from "./GroovyReplClient";
import { getClient } from "../../server/client";
import { ReplStatusBar } from "./ReplStatusBar";

/**
 * One REPL terminal. Owns its own emitters so a disposed terminal's late
 * close event cannot affect its successor, and buffers output until VS Code
 * opens the pseudoterminal (events fired before open() are dropped).
 */
class ReplSession implements vscode.Disposable {
  private readonly writeEmitter = new vscode.EventEmitter<string>();
  private readonly closeEmitter = new vscode.EventEmitter<number>();
  private pending: string[] | undefined = [];
  readonly terminal: vscode.Terminal;

  constructor(onUserClose: (session: ReplSession) => void) {
    const pty: vscode.Pseudoterminal = {
      onDidWrite: this.writeEmitter.event,
      onDidClose: this.closeEmitter.event,
      open: () => {
        const buffered = this.pending ?? [];
        this.pending = undefined;
        this.write("Welcome to Groovy REPL\r\n");
        this.write(
          'Type your code in the editor and use "Send to REPL" commands.\r\n\r\n',
        );
        buffered.forEach((text) => this.write(text));
      },
      // Called when the user closes the terminal (or after dispose()).
      close: () => onUserClose(this),
    };
    this.terminal = vscode.window.createTerminal({ name: "Groovy REPL", pty });
  }

  write(text: string): void {
    if (this.pending) {
      this.pending.push(text);
    } else {
      this.writeEmitter.fire(text);
    }
  }

  dispose(): void {
    this.terminal.dispose();
    this.writeEmitter.dispose();
    this.closeEmitter.dispose();
  }
}

export class ReplService implements vscode.Disposable {
  private readonly replClient = new GroovyReplClient(getClient);
  private session: ReplSession | undefined;
  private readonly statusBar: ReplStatusBar;

  constructor() {
    this.statusBar = new ReplStatusBar();
  }

  public initialize(context: vscode.ExtensionContext) {
    this.statusBar.update(false); // Initial state
    context.subscriptions.push(
      vscode.commands.registerCommand("groovy.repl.start", () =>
        this.startRepl(),
      ),
      vscode.commands.registerCommand("groovy.repl.sendLine", () =>
        this.sendLine(),
      ),
      vscode.commands.registerCommand("groovy.repl.sendSelection", () =>
        this.sendSelection(),
      ),
      vscode.commands.registerCommand("groovy.repl.sendFile", () =>
        this.sendFile(),
      ),
      vscode.commands.registerCommand("groovy.repl.restart", () =>
        this.restart(),
      ),
      vscode.commands.registerCommand("groovy.repl.stop", () => this.stop()),
      vscode.commands.registerCommand("groovy.repl.clear", () => this.clear()),
      vscode.commands.registerCommand("groovy.repl.show", () => this.show()),
      this,
      this.statusBar,
    );
  }

  public dispose() {
    this.stop();
  }

  private show(): void {
    if (this.session) {
      this.session.terminal.show();
    } else {
      vscode.window.showInformationMessage(
        'REPL is not running. Use "Groovy: Start REPL" to begin.',
      );
    }
  }

  private clear(): void {
    if (!this.session) {
      vscode.window.showInformationMessage("REPL is not running.");
      return;
    }
    this.session.write("\x1b[2J\x1b[3J\x1b[;H");
  }

  private stop(): void {
    const session = this.session;
    if (session) {
      this.session = undefined;
      session.dispose();
      this.statusBar.update(false);
    }
  }

  private async restart(): Promise<void> {
    this.stop();
    await this.startRepl();
  }

  private async sendFile(): Promise<void> {
    const editor = vscode.window.activeTextEditor;
    if (!editor) {
      return;
    }
    const code = editor.document.getText();
    if (code.trim()) {
      await this.evaluate(code);
    }
  }

  private async startRepl(): Promise<ReplSession | undefined> {
    if (!getClient()) {
      vscode.window.showErrorMessage("Groovy Language Server is not ready.");
      this.statusBar.update(false);
      return undefined;
    }

    if (!this.session) {
      this.session = new ReplSession((closed) => {
        // Ignore late close events from a terminal that was already replaced.
        if (this.session === closed) {
          this.stop();
        }
      });
    }
    this.session.terminal.show();
    this.statusBar.update(true);
    return this.session;
  }

  private async sendLine() {
    const editor = vscode.window.activeTextEditor;
    if (!editor) {
      return;
    }

    const line = editor.document.lineAt(editor.selection.active.line).text;
    await this.evaluate(line);
  }

  private async sendSelection() {
    const editor = vscode.window.activeTextEditor;
    if (!editor) {
      return;
    }

    const selection = editor.document.getText(editor.selection);
    if (!selection) {
      return;
    }

    await this.evaluate(selection);
  }

  private async evaluate(code: string) {
    const session = this.session ?? (await this.startRepl());
    if (!session) {
      return; // startRepl() already told the user why
    }

    session.write(`> ${code.replaceAll("\n", "\r\n> ")}\r\n`);

    try {
      const result = await this.replClient.evaluate(code);
      if (result.output) {
        session.write(result.output.replaceAll("\n", "\r\n"));
      }
      if (result.result && result.result !== "null") {
        session.write(`<= ${result.result}\r\n`);
      }
    } catch (error) {
      const errorMessage =
        error instanceof Error ? error.message : String(error);
      session.write(`Error: ${errorMessage}\r\n`);
    }

    session.write("\r\n");
  }
}

export const replService = new ReplService();
