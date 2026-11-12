// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import * as assert from "assert";
import * as vscode from "vscode";
import { mkdtemp } from "node:fs/promises";
import { tmpdir } from "node:os";
import * as path from "node:path";
import { createNewSpecFile } from "../../features/testing/TestFeature";

suite("Spock Test Scaffolding Integration", () => {
  suiteSetup(async function () {
    this.timeout(60000); // Increase timeout for Windows CI
    const extension = vscode.extensions.getExtension("albertocavalcante.gvy");
    if (!extension?.isActive) {
      await extension?.activate();
    }
  });

  test("Command groovy.test.generate should be registered", async () => {
    const commands = await vscode.commands.getCommands(true);
    assert.ok(
      commands.includes("groovy.test.generate"),
      "groovy.test.generate command should be registered",
    );
  });
});

suite("New spec file creation", () => {
  let directory: vscode.Uri;
  const openedDocuments: vscode.TextDocument[] = [];
  const encode = (value: string) => new TextEncoder().encode(value);

  setup(async () => {
    directory = vscode.Uri.file(
      await mkdtemp(path.join(tmpdir(), "gvy-spec-creation-")),
    );
  });

  teardown(async () => {
    for (const document of openedDocuments.splice(0)) {
      if (!document.isClosed) {
        await vscode.window.showTextDocument(document);
        if (document.isDirty) await document.save();
        await vscode.commands.executeCommand(
          "workbench.action.closeActiveEditor",
        );
      }
    }
    await vscode.workspace.fs.delete(directory, {
      recursive: true,
      useTrash: false,
    });
  });

  test("creates UTF-8 contents and refuses repeated creation", async () => {
    const uri = vscode.Uri.joinPath(directory, "Résumé Spec.groovy");
    const contents = encode("// generated specification: café\n");

    assert.strictEqual(await createNewSpecFile(uri, contents), true);
    assert.deepStrictEqual(
      Array.from(await vscode.workspace.fs.readFile(uri)),
      Array.from(contents),
    );
    assert.strictEqual(
      await createNewSpecFile(uri, encode("replacement")),
      false,
    );
    assert.deepStrictEqual(
      Array.from(await vscode.workspace.fs.readFile(uri)),
      Array.from(contents),
    );
  });

  test("preserves an existing file and its unsaved editor contents", async () => {
    const uri = vscode.Uri.joinPath(directory, "ExistingSpec.groovy");
    const original = "// existing specification\n";
    await vscode.workspace.fs.writeFile(uri, encode(original));
    const document = await vscode.workspace.openTextDocument(uri);
    openedDocuments.push(document);
    const editor = await vscode.window.showTextDocument(document);
    await editor.edit((edit) =>
      edit.insert(new vscode.Position(0, 0), "// unsaved\n"),
    );
    assert.strictEqual(document.isDirty, true);

    assert.strictEqual(
      await createNewSpecFile(uri, encode("replacement")),
      false,
    );

    assert.strictEqual(document.getText(), "// unsaved\n" + original);
    assert.strictEqual(document.isDirty, true);
    assert.deepStrictEqual(
      Array.from(await vscode.workspace.fs.readFile(uri)),
      Array.from(encode(original)),
    );
  });

  test("does not replace a directory at the destination", async () => {
    const uri = vscode.Uri.joinPath(directory, "ExistingSpec.groovy");
    await vscode.workspace.fs.createDirectory(uri);
    const child = vscode.Uri.joinPath(uri, "user-content");
    await vscode.workspace.fs.writeFile(child, encode("preserve me"));

    assert.strictEqual(
      await createNewSpecFile(uri, encode("replacement")),
      false,
    );

    assert.deepStrictEqual(
      Array.from(await vscode.workspace.fs.readFile(child)),
      Array.from(encode("preserve me")),
    );
  });

  test("concurrent creation preserves exactly one writer's complete contents", async () => {
    const uri = vscode.Uri.joinPath(directory, "ConcurrentSpec.groovy");
    const contents = Array.from({ length: 16 }, (_, index) =>
      encode(`// writer ${index}\n${"complete contents\n".repeat(64)}`),
    );

    const results = await Promise.all(
      contents.map((bytes) => createNewSpecFile(uri, bytes)),
    );

    assert.strictEqual(results.filter(Boolean).length, 1);
    const winner = results.findIndex(Boolean);
    assert.deepStrictEqual(
      Array.from(await vscode.workspace.fs.readFile(uri)),
      Array.from(contents[winner]),
    );
  });
});
