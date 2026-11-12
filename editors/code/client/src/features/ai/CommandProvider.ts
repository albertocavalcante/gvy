import * as vscode from "vscode";
import { ILSPToolService, TOOL_DEFINITIONS } from "./types";
import { ToolRegistry } from "./ToolRegistry";

/**
 * Provides standard commands for AI agents (like Cursor) to invoke tools.
 */
export class CommandProvider implements vscode.Disposable {
  private disposables: vscode.Disposable[] = [];

  constructor(
    private readonly lspService: ILSPToolService,
    private readonly registry: ToolRegistry,
  ) {
    this.registerCommands();
  }

  private registerCommands() {
    TOOL_DEFINITIONS.forEach((tool) => {
      this.disposables.push(
        vscode.commands.registerCommand(tool.command, async (args: unknown) => {
          this.registry.assertToolEnabled(tool.name);
          return await tool.handler(this.lspService, args);
        }),
      );
    });
  }

  public dispose() {
    this.disposables.forEach((d) => d.dispose());
  }
}
