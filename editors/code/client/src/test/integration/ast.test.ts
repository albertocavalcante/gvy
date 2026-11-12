// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import * as assert from "assert";
import * as vscode from "vscode";

suite("AST Explorer Test Suite", () => {
  let document: vscode.TextDocument | undefined;
  let initialTabs: Set<vscode.Tab> | undefined;
  let astPanel: vscode.Tab | undefined;
  const tabs = () => vscode.window.tabGroups.all.flatMap((group) => group.tabs);
  const ownedPanels = () =>
    tabs().filter(
      (tab) =>
        !initialTabs?.has(tab) &&
        tab.input instanceof vscode.TabInputWebview &&
        tab.label === "Groovy AST",
    );

  function waitForPanel(): Promise<vscode.Tab> {
    return new Promise((resolve, reject) => {
      const check = () => {
        const panels = ownedPanels();
        if (!panels.length) return;
        listener.dispose();
        clearTimeout(timeout);
        if (panels.length !== 1) {
          reject(new Error("AST command opened ambiguous webview tabs"));
          return;
        }
        astPanel = panels[0];
        resolve(astPanel);
      };
      const listener = vscode.window.tabGroups.onDidChangeTabs(check);
      const timeout = setTimeout(() => {
        listener.dispose();
        reject(new Error("AST webview tab did not open"));
      }, 5000);
      check();
    });
  }

  teardown(async () => {
    if (!document) return;
    try {
      const panels = ownedPanels();
      assert.ok(
        astPanel || panels.length <= 1,
        "Cannot identify owned AST tab",
      );
      const panel = astPanel ?? panels[0];
      if (panel && tabs().includes(panel)) {
        assert.ok(
          await vscode.window.tabGroups.close(panel, true),
          "AST test webview must close",
        );
      }
    } finally {
      if (!document.isClosed) {
        assert.ok(
          document.isUntitled,
          "Only discard the owned untitled fixture",
        );
        await vscode.window.showTextDocument(document, { preview: false });
        assert.strictEqual(
          vscode.window.activeTextEditor?.document.uri.toString(),
          document.uri.toString(),
        );
        await vscode.commands.executeCommand(
          "workbench.action.revertAndCloseActiveEditor",
        );
      }
    }
    assert.deepStrictEqual(
      { panels: ownedPanels().length, documentClosed: document.isClosed },
      { panels: 0, documentClosed: true },
      "AST test must release its webview and untitled document",
    );
    for (const tab of initialTabs ?? []) {
      assert.ok(
        tabs().includes(tab),
        "AST cleanup must preserve preexisting tabs",
      );
    }
    document = undefined;
    initialTabs = undefined;
    astPanel = undefined;
  });

  test("Should register AST Explorer command", async () => {
    const commands = await vscode.commands.getCommands(true);
    assert.ok(
      commands.includes("groovy.ast.show"),
      "Should register groovy.ast.show command",
    );
  });

  test("Should execute AST Explorer command", async () => {
    initialTabs = new Set(tabs());
    document = await vscode.workspace.openTextDocument({
      language: "groovy",
      content: "class Foo {}",
    });
    await vscode.window.showTextDocument(document, { preview: false });
    await vscode.commands.executeCommand("groovy.ast.show");
    await waitForPanel();
    assert.strictEqual(
      ownedPanels().length,
      1,
      "AST command must open a webview",
    );
  });
});
