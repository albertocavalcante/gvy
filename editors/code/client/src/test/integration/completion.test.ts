// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import * as assert from "assert";
import { createHash } from "crypto";
import {
  existsSync,
  mkdtempSync,
  readFileSync,
  realpathSync,
  rmSync,
  writeFileSync,
} from "fs";
import { tmpdir } from "os";
import * as path from "path";
import * as vscode from "vscode";

suite("Manual completion with the current server", () => {
  const declarations = [
    "class Greeter {",
    "    String greet(String name) { name }",
    "}",
    "",
  ].join("\n");

  function assertBundledServer(scope?: vscode.Uri) {
    assert.ok(
      !vscode.workspace
        .getConfiguration("groovy", scope)
        .get<string>("server.path"),
      "Candidate tests require the bundled server, without a groovy.server.path override",
    );
  }

  suiteSetup(async function () {
    this.timeout(60000);
    const extension = vscode.extensions.getExtension("albertocavalcante.gvy");
    assert.ok(extension);
    if (process.env.GVY_TEST_EXTENSION_ROOT) {
      assert.strictEqual(
        realpathSync(extension.extensionUri.fsPath),
        realpathSync(process.env.GVY_TEST_EXTENSION_ROOT),
        "Editor must load the unpacked candidate VSIX",
      );
    }
    const candidate =
      process.env.GVY_TEST_SERVER_JAR ||
      path.resolve(
        __dirname,
        "../../../../../../gls/build/editor-server/gls.jar",
      );
    assert.ok(
      existsSync(candidate),
      "Prepare the current candidate server first",
    );
    const bundled = vscode.Uri.joinPath(
      extension.extensionUri,
      "server/gls.jar",
    );
    const hash = (file: string) =>
      createHash("sha256").update(readFileSync(file)).digest("hex");
    assert.strictEqual(hash(bundled.fsPath), hash(candidate));
    assertBundledServer();
    await extension.activate();
  });

  async function withCompletion(
    suffix: string,
    check: (
      editor: vscode.TextEditor,
      position: vscode.Position,
      items: vscode.CompletionItem[],
    ) => Promise<void>,
  ) {
    const source = declarations + suffix;
    const document = await vscode.workspace.openTextDocument({
      language: "groovy",
      content: source,
    });
    const editor = await vscode.window.showTextDocument(document);
    const position = document.positionAt(source.length);
    editor.selection = new vscode.Selection(position, position);
    try {
      assertBundledServer(document.uri);
      // No trigger character: this invokes the manual Ctrl+Space provider path.
      const result =
        await vscode.commands.executeCommand<vscode.CompletionList>(
          "vscode.executeCompletionItemProvider",
          document.uri,
          position,
        );
      assert.ok(result, "Manual completion must return a completion list");
      assert.strictEqual(document.getText(), source);
      await check(editor, position, result.items);
    } finally {
      await vscode.window.showTextDocument(document);
      await vscode.commands.executeCommand(
        "workbench.action.revertAndCloseActiveEditor",
      );
    }
  }

  async function insertCompletion(
    editor: vscode.TextEditor,
    position: vscode.Position,
    item: vscode.CompletionItem,
  ) {
    assert.ok(item.insertText, "Candidate must provide insertion text");
    assert.ok(
      !item.additionalTextEdits?.length,
      "A local symbol needs no import edit",
    );
    const range =
      item.range instanceof vscode.Range
        ? item.range
        : item.range?.replacing ||
          editor.document.getWordRangeAtPosition(position) ||
          new vscode.Range(position, position);
    const snippet =
      typeof item.insertText === "string"
        ? new vscode.SnippetString().appendText(item.insertText)
        : item.insertText;
    assert.ok(await editor.insertSnippet(snippet, range));
    assert.ok(editor.document.getText().startsWith(declarations));
  }

  function waitForDiagnostics(
    uri: vscode.Uri,
    predicate: (diagnostics: vscode.Diagnostic[]) => boolean,
  ): Promise<vscode.Diagnostic[]> {
    return new Promise((resolve, reject) => {
      const subscription = vscode.languages.onDidChangeDiagnostics((event) => {
        if (
          event.uris.some((changed) => changed.toString() === uri.toString())
        ) {
          check();
        }
      });
      const timeout = setTimeout(() => {
        subscription.dispose();
        reject(new Error("Timed out waiting for current syntax diagnostics"));
      }, 30000);
      function check() {
        const diagnostics = vscode.languages.getDiagnostics(uri);
        if (predicate(diagnostics)) {
          clearTimeout(timeout);
          subscription.dispose();
          resolve(diagnostics);
        }
      }
      check();
    });
  }

  test("unfinished syntax keeps completion and signatures usable and clears on save", async function () {
    this.timeout(60000);
    const directory = mkdtempSync(path.join(tmpdir(), "gvy-syntax-"));
    const uri = vscode.Uri.file(path.join(directory, "Typing.groovy"));
    let openedDocument: vscode.TextDocument | undefined;
    try {
      writeFileSync(uri.fsPath, "import java.time.\n");
      const document = await vscode.workspace.openTextDocument(uri);
      openedDocument = document;
      const editor = await vscode.window.showTextDocument(document);
      const replace = async (source: string) => {
        assert.ok(
          await editor.edit((edit) => {
            edit.replace(
              new vscode.Range(
                document.positionAt(0),
                document.positionAt(document.getText().length),
              ),
              source,
            );
          }),
        );
      };
      assertBundledServer(uri);
      const diagnostics = await waitForDiagnostics(uri, (items) =>
        items.some((item) => item.code === "incomplete-import"),
      );
      const incomplete = diagnostics.find(
        (item) => item.code === "incomplete-import",
      );
      assert.ok(incomplete);
      assert.strictEqual(incomplete.severity, vscode.DiagnosticSeverity.Error);
      assert.strictEqual(incomplete.range.start.line, 0);
      assert.strictEqual(incomplete.range.start.character, 16);
      assert.strictEqual(incomplete.range.end.character, 17);
      const imports =
        await vscode.commands.executeCommand<vscode.CompletionList>(
          "vscode.executeCompletionItemProvider",
          uri,
          new vscode.Position(0, 17),
        );
      const instant = imports?.items.find((item) => {
        const label =
          typeof item.label === "string" ? item.label : item.label.label;
        return label === "Instant" || label === "java.time.Instant";
      });
      assert.ok(instant, "Import completion must offer java.time.Instant");
      const importRange =
        instant.range instanceof vscode.Range
          ? instant.range
          : instant.range?.replacing;
      const importText = instant.insertText;
      assert.ok(
        importRange,
        "Import completion must identify the replaced range",
      );
      assert.ok(
        typeof importText === "string",
        "Import completion must supply the replacement name",
      );
      assert.ok(
        !instant.additionalTextEdits?.length,
        "An import completion needs no additional import",
      );
      assert.ok(
        await editor.edit((edit) => edit.replace(importRange, importText)),
      );
      assert.strictEqual(document.getText(), "import java.time.Instant\n");

      await replace("import java.time.Instant\nInstant.\n");
      const members =
        await vscode.commands.executeCommand<vscode.CompletionList>(
          "vscode.executeCompletionItemProvider",
          uri,
          new vscode.Position(1, 8),
        );
      assert.ok(members?.items.some((item) => item.label === "now"));

      await replace("println(\n");
      const signature =
        await vscode.commands.executeCommand<vscode.SignatureHelp>(
          "vscode.executeSignatureHelpProvider",
          uri,
          new vscode.Position(0, 8),
        );
      assert.ok(signature?.signatures.length);
      await waitForDiagnostics(uri, (items) =>
        items.some((item) => item.code === "unclosed-delimiter"),
      );

      await replace("import java.time.\n");
      await replace("println('repaired')\n");
      assert.ok(await document.save());
      await waitForDiagnostics(
        uri,
        (items) =>
          !items.some(
            (item) => item.severity === vscode.DiagnosticSeverity.Error,
          ),
      );
    } finally {
      try {
        if (openedDocument) {
          await vscode.window.showTextDocument(openedDocument);
          await vscode.commands.executeCommand(
            "workbench.action.revertAndCloseActiveEditor",
          );
        }
      } finally {
        rmSync(directory, { recursive: true, force: true });
      }
    }
  });

  test("Ctrl+Space offers and inserts a class after an incomplete constructor", async () => {
    await withCompletion("new Gr", async (editor, position, items) => {
      const greeter = items.find(
        (item) =>
          item.label === "Greeter" &&
          item.kind === vscode.CompletionItemKind.Class,
      );
      assert.ok(
        greeter,
        `Missing local constructor completion: ${JSON.stringify(items.map((item) => item.label))}`,
      );
      await insertCompletion(editor, position, greeter);
      assert.strictEqual(
        editor.document.getText(),
        declarations + "new Greeter",
      );
    });
  });

  test("Ctrl+Space offers local methods and distinct overloads in member context", async () => {
    await withCompletion(
      "new Greeter().gr",
      async (editor, position, items) => {
        const greet = items.find(
          (item) =>
            item.label === "greet" &&
            item.kind === vscode.CompletionItemKind.Method,
        );
        assert.ok(
          greet,
          `Missing local member completion: ${JSON.stringify(items.map((item) => item.label))}`,
        );
        assert.ok(
          !items.some(
            (item) =>
              item.label === "Greeter" &&
              item.kind === vscode.CompletionItemKind.Class,
          ),
        );
        const grep = items.filter((item) => item.label === "grep");
        assert.strictEqual(
          grep.length,
          2,
          "Both signature-distinct grep overloads must survive",
        );
        assert.strictEqual(new Set(grep.map((item) => item.detail)).size, 2);
        await insertCompletion(editor, position, greet);
        const insertedLine = editor.document.lineAt(position.line).text;
        assert.match(insertedLine, /^new Greeter\(\)\.greet\([^()]*\)$/);
        assert.ok(
          !insertedLine.includes(".grgreet"),
          "Completion must replace the typed prefix",
        );
      },
    );
  });
});
