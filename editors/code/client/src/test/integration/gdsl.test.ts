// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import * as assert from "assert";
import * as vscode from "vscode";
import * as path from "path";
import * as fs from "fs";

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

  test("recognizes a saved Jenkins descriptor", async () => {
    const fixture = vscode.Uri.file(
      path.join(
        vscode.workspace.workspaceFolders![0].uri.fsPath,
        "jenkins-descriptor.gdsl",
      ),
    );
    const document = await vscode.workspace.openTextDocument(fixture);
    assert.strictEqual(document.languageId, "gdsl");
    assert.ok(
      document.getText().includes("contributor(context(scope: scriptScope()))"),
    );
  });

  test("closes quotes and indents a contributor block", async () => {
    await vscode.commands.executeCommand("type", { text: "'" });
    assert.strictEqual(editor.document.getText(), "''");
    await vscode.commands.executeCommand("undo");
    await vscode.commands.executeCommand("type", { text: "contributor {" });
    await vscode.commands.executeCommand("type", { text: "\n" });
    assert.match(editor.document.getText(), /contributor \{\n\s+\n?\}/);
  });

  test("selects and advances editable method snippet fields", async () => {
    await vscode.commands.executeCommand("editor.action.insertSnippet", {
      langId: "gdsl",
      name: "GDSL method",
    });
    assert.strictEqual(editor.document.getText(editor.selection), "myMethod");
    await vscode.commands.executeCommand("type", { text: "deploy" });
    await vscode.commands.executeCommand("jumpToNextSnippetPlaceholder");
    assert.strictEqual(editor.document.getText(editor.selection), "void");
    assert.ok(editor.document.getText().includes("name: 'deploy'"));
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
    [
      "GDSL contributor",
      "contributor(context(scope: scriptScope())) {\n    \n}",
    ],
    ["GDSL context", "def ctx = context(scope: scriptScope())\n"],
    [
      "GDSL method",
      "method(name: 'myMethod', type: 'void', params: [arg: 'java.lang.String'], doc: 'Description')",
    ],
    [
      "GDSL named-parameter method",
      "method(name: 'myMethod', type: 'void', namedParams: [\n    parameter(name: 'myParam', type: 'java.lang.String', doc: 'Parameter description')\n], doc: 'Description')",
    ],
    [
      "GDSL property",
      "property(name: 'myProperty', type: 'java.lang.Object', doc: 'Description')",
    ],
    [
      "GDSL parameter",
      "parameter(name: 'myParam', type: 'java.lang.String', doc: 'Description')",
    ],
  ]) {
    test(`inserts the contributed ${name} snippet`, async () => {
      await vscode.commands.executeCommand("editor.action.insertSnippet", {
        langId: "gdsl",
        name,
      });
      const expanded = editor.document.getText().replace(/\t/g, "    ");
      assert.strictEqual(expanded, expected);
      const fixturePath = path.resolve(
        vscode.workspace.workspaceFolders![0].uri.fsPath,
        "gdsl-snippet-defaults.gdsl",
      );
      const compact = (value: string) => value.replace(/\s+/g, "");
      const fixture = compact(fs.readFileSync(fixturePath, "utf8"));
      const fragment =
        name === "GDSL contributor" ? expanded.split("{")[0] + "{" : expanded;
      assert.ok(
        fixture.includes(compact(fragment)),
        `${name} differs from the consumer fixture`,
      );
      assert.ok(!editor.document.getText().includes("${"));
    });
  }
});
