// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import * as vscode from "vscode";
import { ILSPToolService, TOOL_DEFINITIONS } from "./types";
import { ToolRegistry } from "./ToolRegistry";

/**
 * Provides tools to the VS Code Language Model API.
 */
export class LMToolProvider implements vscode.Disposable {
  private disposables: vscode.Disposable[] = [];

  constructor(
    private readonly lspService: ILSPToolService,
    private readonly registry: ToolRegistry,
  ) {
    this.registerTools();
  }

  private registerTools() {
    TOOL_DEFINITIONS.forEach((tool) => {
      this.disposables.push(
        vscode.lm.registerTool(tool.name, {
          invoke: async (
            options: vscode.LanguageModelToolInvocationOptions<
              Record<string, unknown>
            >,
          ) => {
            this.registry.assertToolEnabled(tool.name);
            const params = options.input;
            const result = await tool.handler(this.lspService, params);
            return new vscode.LanguageModelToolResult([
              new vscode.LanguageModelTextPart(JSON.stringify(result, null, 2)),
            ]);
          },
        }),
      );
    });
  }

  public dispose() {
    this.disposables.forEach((d) => d.dispose());
  }
}
