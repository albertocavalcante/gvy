// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import * as assert from "assert";
import * as vscode from "vscode";

suite("GDSL authoring", () => {
  let editor: vscode.TextEditor;

  setup(async () => {
    const document = await vscode.workspace.openTextDocument(
      vscode.Uri.parse("untitled:authoring.gdsl"),
    );
    editor = await vscode.window.showTextDocument(document);
  });

  teardown(async () => {
    await vscode.commands.executeCommand(
      "workbench.action.revertAndCloseActiveEditor",
    );
  });

  test("recognizes .gdsl files without a manual language association", () => {
    assert.strictEqual(editor.document.languageId, "gdsl");
  });

  test("uses Groovy line comments and bracket pairs", async () => {
    await vscode.commands.executeCommand("type", { text: "(" });
    assert.strictEqual(editor.document.getText(), "()");
    await vscode.commands.executeCommand("editor.action.commentLine");
    assert.strictEqual(editor.document.getText(), "// ()");
    await vscode.commands.executeCommand("editor.action.commentLine");
    assert.strictEqual(editor.document.getText(), "()");
  });

  for (const [name, expected] of [
    ["GDSL contributor", "contributor(context(scope: scriptScope())) {"],
    ["GDSL context", "context(scope: scriptScope())"],
    ["GDSL method", "method(name: 'myMethod', type: 'void',"],
    ["GDSL named-parameter method", "namedParams: ["],
    ["GDSL property", "property(name: 'myProperty',"],
    ["GDSL parameter", "parameter(name: 'myParam',"],
  ]) {
    test(`inserts the contributed ${name} snippet`, async () => {
      await vscode.commands.executeCommand("editor.action.insertSnippet", {
        langId: "gdsl",
        name,
      });
      assert.ok(
        editor.document.getText().includes(expected),
        `Missing expanded ${name}: ${editor.document.getText()}`,
      );
      assert.ok(!editor.document.getText().includes("${"));
    });
  }
});
