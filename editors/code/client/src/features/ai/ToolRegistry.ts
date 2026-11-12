import * as vscode from "vscode";

/**
 * Manages the registration and availability of AI tools based on configuration.
 */
export class ToolRegistry {
  public assertToolEnabled(toolName: string): void {
    if (!this.isToolEnabled(toolName)) {
      throw new Error(
        `AI tool '${toolName}' is disabled or not allowed by settings.`,
      );
    }
  }

  /**
   * Checks if a specific tool is enabled.
   */
  public isToolEnabled(toolName: string): boolean {
    // Get fresh configuration on each check to avoid stale values
    const config = vscode.workspace.getConfiguration("groovy");

    // 1. Check Master Switch
    const enabled = config.get<unknown>("ai.tools.enabled", false);
    if (enabled !== true) {
      return false;
    }

    // 2. Check Allowed List
    const allowed = config.get<unknown>("ai.tools.allowed", ["all"]);
    return (
      Array.isArray(allowed) &&
      allowed.every((name: unknown) => typeof name === "string") &&
      (allowed.includes("all") || allowed.includes(toolName))
    );
  }
}
