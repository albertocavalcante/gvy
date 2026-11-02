/**
 * Log Level Commands
 *
 * Provides quick toggle and picker UX for server log level configuration.
 */

import * as vscode from "vscode";
import { settingsTarget } from "../configuration/target";

type LogLevel = "error" | "warn" | "info" | "debug" | "trace";

/**
 * Toggles between 'info' and 'debug' log levels for quick debugging.
 * The configuration watcher prompts for the server restart.
 */
export async function toggleDebugLogs(): Promise<void> {
  const config = vscode.workspace.getConfiguration("groovy");
  const current = config.get<LogLevel>("server.logLevel", "info");

  // Toggle between info and debug, treating trace as debug-enabled
  const newLevel: LogLevel =
    current === "debug" || current === "trace" ? "info" : "debug";

  await config.update("server.logLevel", newLevel, settingsTarget());

  // The configuration watcher offers the restart (server.logLevel is a
  // restart-required setting), so no second prompt here.
}

/**
 * Shows quick pick to select log level.
 */
export async function selectLogLevel(): Promise<void> {
  const config = vscode.workspace.getConfiguration("groovy");
  const current = config.get<LogLevel>("server.logLevel", "info");

  interface LogLevelItem extends vscode.QuickPickItem {
    level: LogLevel;
  }

  const levels: LogLevelItem[] = (
    [
      { level: "error", label: "error", description: "Only errors" },
      { level: "warn", label: "warn", description: "Warnings and errors" },
      {
        level: "info",
        label: "info",
        description: "Normal operation (default)",
      },
      {
        level: "debug",
        label: "debug",
        description: "Detailed debugging (recommended for issues)",
      },
      { level: "trace", label: "trace", description: "Most verbose" },
    ] as const
  ).map((item) => ({
    ...item,
    picked: item.level === current,
    detail: item.level === current ? "$(check) Current" : undefined,
  }));

  const selected = await vscode.window.showQuickPick(levels, {
    title: "Select Log Level",
    placeHolder: `Current: ${current}`,
  });

  if (selected && selected.level !== current) {
    await config.update("server.logLevel", selected.level, settingsTarget());
  }
}

/**
 * Gets current log level for display in UI
 */
export function getCurrentLogLevel(): LogLevel {
  const config = vscode.workspace.getConfiguration("groovy");
  return config.get<LogLevel>("server.logLevel", "info");
}

/**
 * Checks if debug logging is currently enabled
 */
export function isDebugEnabled(): boolean {
  const level = getCurrentLogLevel();
  return level === "debug" || level === "trace";
}
