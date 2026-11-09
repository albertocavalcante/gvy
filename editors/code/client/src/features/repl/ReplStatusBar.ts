import * as vscode from "vscode";

export interface StatusBarState {
  text: string;
  tooltip: string;
  command: string;
  color?: vscode.ThemeColor;
}

/**
 * Contains the pure, testable logic for determining the state of the status bar.
 */
export class ReplStatusBarLogic {
  public getState(isRunning: boolean): StatusBarState {
    if (isRunning) {
      return {
        text: "$(terminal) REPL",
        tooltip: "Groovy REPL is running. Click to show.",
        command: "groovy.repl.show",
        color: new vscode.ThemeColor("statusBarItem.activeBackground"),
      };
    } else {
      return {
        text: "$(terminal) REPL",
        tooltip: "Groovy REPL is not running. Click to start.",
        command: "groovy.repl.start",
        color: undefined,
      };
    }
  }
}

/**
 * Manages the VS Code status bar item for the REPL.
 */
export class ReplStatusBar implements vscode.Disposable {
  private item: vscode.StatusBarItem;
  private logic: ReplStatusBarLogic;

  constructor() {
    this.item = vscode.window.createStatusBarItem(
      vscode.StatusBarAlignment.Right,
      100,
    );
    this.logic = new ReplStatusBarLogic();
    this.update(false); // Initial state is not running
  }

  public update(isRunning: boolean): void {
    const state = this.logic.getState(isRunning);

    this.item.text = state.text;
    this.item.tooltip = state.tooltip;
    this.item.command = state.command;
    this.item.color = state.color;
    // NOTE: shown only for a running session. No server version implements
    // groovy/execute, so an always-visible "click to start" item would offer
    // a REPL that cannot evaluate anything.
    if (isRunning) {
      this.item.show();
    } else {
      this.item.hide();
    }
  }

  public dispose(): void {
    this.item.dispose();
  }
}
