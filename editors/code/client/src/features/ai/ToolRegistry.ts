import * as vscode from "vscode";

/**
 * Manages the registration and availability of AI tools based on configuration.
 */
export class ToolRegistry {
  constructor() {}

  /**
   * Checks if a specific tool is enabled.
   */
  public isToolEnabled(toolName: string): boolean {
    // Get fresh configuration on each check to avoid stale values
    const config = vscode.workspace.getConfiguration("groovy");

    // 1. Check Master Switch
    const enabled = config.get<boolean>("ai.tools.enabled", false);
    if (!enabled) {
      return false;
    }

    // 2. Check Allowed List
    const allowed = config.get<string[]>("ai.tools.allowed", ["all"]);
    if (!allowed) {
      return false;
    }

    if (allowed.includes("all")) {
      return true;
    }

    return allowed.includes(toolName);
  }
}
