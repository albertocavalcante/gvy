import * as vscode from 'vscode';
import { GroovyReplClient } from './GroovyReplClient';
import { getClient } from '../../server/client';
import { ReplStatusBar } from './ReplStatusBar';

export class ReplService implements vscode.Disposable {
    private replClient: GroovyReplClient | undefined;
    private terminal: vscode.Terminal | undefined;
    private readonly writeEmitter = new vscode.EventEmitter<string>();
    private readonly closeEmitter = new vscode.EventEmitter<number>();
    private readonly statusBar: ReplStatusBar;

    constructor() {
        this.statusBar = new ReplStatusBar();
    }

    public initialize(context: vscode.ExtensionContext) {
        this.statusBar.update(false); // Initial state
        context.subscriptions.push(
            vscode.commands.registerCommand('groovy.repl.start', () => this.startRepl()),
            vscode.commands.registerCommand('groovy.repl.sendLine', () => this.sendLine()),
            vscode.commands.registerCommand('groovy.repl.sendSelection', () => this.sendSelection()),
            vscode.commands.registerCommand('groovy.repl.sendFile', () => this.sendFile()),
            vscode.commands.registerCommand('groovy.repl.restart', () => this.restart()),
            vscode.commands.registerCommand('groovy.repl.stop', () => this.stop()),
            vscode.commands.registerCommand('groovy.repl.clear', () => this.clear()),
            vscode.commands.registerCommand('groovy.repl.show', () => this.show()),
            this,
            this.statusBar
        );
    }

    public dispose() {
        this.stop();
        this.writeEmitter.dispose();
        this.closeEmitter.dispose();
    }

    public getStatus(): { isRunning: boolean } {
        return { isRunning: !!this.terminal };
    }

    private show(): void {
        if (this.terminal) {
            this.terminal.show();
        } else {
            vscode.window.showInformationMessage('REPL is not running. Use "Groovy: Start REPL" to begin.');
        }
    }

    private clear(): void {
        if (!this.terminal) {
            vscode.window.showInformationMessage('REPL is not running.');
            return;
        }
        // We can use a special sequence to clear the terminal
        this.writeEmitter.fire('\x1b[2J\x1b[3J\x1b[;H');
    }

    private stop(): void {
        if (this.terminal) {
            this.terminal.dispose();
            this.terminal = undefined;
            this.replClient = undefined;
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

    private async startRepl() {
        const client = getClient();
        if (!client) {
            vscode.window.showErrorMessage('Groovy Language Server is not ready.');
            this.statusBar.update(false);
            return;
        }

        this.replClient = new GroovyReplClient(client);

        if (this.terminal) {
            this.terminal.show();
            this.statusBar.update(true);
            return;
        }

        const pty: vscode.Pseudoterminal = {
            onDidWrite: this.writeEmitter.event,
            onDidClose: this.closeEmitter.event,
            open: () => {
                this.writeEmitter.fire('Welcome to Groovy REPL\r\n');
                this.writeEmitter.fire('Type your code in the editor and use "Send to REPL" commands.\r\n\r\n');
            },
            close: () => {
                // This is called when the terminal is closed by the user (e.g. clicking the trash icon)
                this.stop();
            },
            handleInput: (_data: string) => {
                // Basic input handling if we wanted an interactive shell, 
                // but for now we rely on editor commands.
            }
        };

        this.terminal = vscode.window.createTerminal({
            name: 'Groovy REPL',
            pty
        });

        this.terminal.show();
        this.statusBar.update(true);
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
        if (!this.replClient || !this.terminal) {
            await this.startRepl();
        }

        if (!this.replClient) {
            // If replClient is still undefined after attempting to start,
            // it means startRepl() failed (e.g., LSP not ready) and already showed an error.
            // Log this for clarity and prevent further execution.
            console.error('REPL client could not be initialized. Evaluation aborted.');
             return;
        }

        this.writeEmitter.fire(`> ${code.replaceAll('\n', '\r\n> ')}\r\n`);

        try {
            const result = await this.replClient.evaluate(code);
            if (result.output) {
                this.writeEmitter.fire(result.output.replaceAll('\n', '\r\n'));
            }
            if (result.result && result.result !== 'null') {
                this.writeEmitter.fire(`<= ${result.result}\r\n`);
            }
        } catch (error) {
            const errorMessage = error instanceof Error ? error.message : String(error);
            this.writeEmitter.fire(`Error: ${errorMessage}\r\n`);
        }
        
        this.writeEmitter.fire('\r\n');
    }
}

export const replService = new ReplService();
