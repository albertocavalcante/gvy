// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import * as assert from "assert";
import * as path from "path";
import * as fs from "fs";
import * as vscode from "vscode";

suite("GDSL cold startup", () => {
  const config = vscode.workspace.getConfiguration("groovy");
  const previousJava = config.inspect<string>(
    "languageServer.javaHome",
  )?.globalValue;
  const previousServer = config.inspect<string>("server.path")?.globalValue;
  const previousUpdates = config.inspect<boolean>(
    "update.checkOnStartup",
  )?.globalValue;

  suiteTeardown(async () => {
    await config.update(
      "languageServer.javaHome",
      previousJava,
      vscode.ConfigurationTarget.Global,
    );
    await config.update(
      "server.path",
      previousServer,
      vscode.ConfigurationTarget.Global,
    );
    await config.update(
      "update.checkOnStartup",
      previousUpdates,
      vscode.ConfigurationTarget.Global,
    );
    await vscode.commands.executeCommand(
      "workbench.action.revertAndCloseActiveEditor",
    );
    await vscode.commands.executeCommand("workbench.action.closeAllEditors");
  });

  test("keeps standalone GDSL editing quiet with an unusable Java configuration", async () => {
    const extension = vscode.extensions.getExtension("albertocavalcante.gvy");
    assert.ok(extension);
    if (process.env.GVY_TEST_EXTENSION_ROOT) {
      assert.strictEqual(
        fs.realpathSync(extension.extensionPath),
        fs.realpathSync(process.env.GVY_TEST_EXTENSION_ROOT),
        "The packaged extension must be loaded",
      );
    }
    assert.ok(!extension.isActive, "This case requires a cold extension host");
    const folder = vscode.workspace.workspaceFolders?.[0];
    assert.ok(folder);
    assert.deepStrictEqual(await vscode.workspace.findFiles("**/*.groovy"), []);
    await config.update(
      "update.checkOnStartup",
      false,
      vscode.ConfigurationTarget.Global,
    );
    await config.update(
      "languageServer.javaHome",
      path.join(folder.uri.fsPath, "missing-java"),
      vscode.ConfigurationTarget.Global,
    );
    // A missing server is also irrelevant to static authoring. No startup failure should be reported.
    await config.update(
      "server.path",
      path.join(folder.uri.fsPath, "missing-server.jar"),
      vscode.ConfigurationTarget.Global,
    );
    const errors: unknown[][] = [];
    const originalError = console.error;
    console.error = (...args: unknown[]) => {
      errors.push(args);
      originalError(...args);
    };
    try {
      const document = await vscode.workspace.openTextDocument(
        vscode.Uri.joinPath(folder.uri, "pipeline.gdsl"),
      );
      await vscode.window.showTextDocument(document);
      await extension.activate();
      assert.strictEqual(document.languageId, "gdsl");
      assert.deepStrictEqual(
        errors.filter((args) =>
          /Groovy Language Server|Java validation/.test(
            args.map(String).join(" "),
          ),
        ),
        [],
      );
    } finally {
      console.error = originalError;
    }
  });

  test("starts the language service when a Groovy document opens afterward", async () => {
    const extensionRoot = path.resolve(__dirname, "../../../..");
    const server = process.env.GVY_TEST_EXTENSION_ROOT
      ? path.join(process.env.GVY_TEST_EXTENSION_ROOT, "server", "gls.jar")
      : process.env.GVY_TEST_SERVER_JAR ||
        path.resolve(extensionRoot, "../../gls/build/editor-server/gls.jar");
    assert.ok(fs.existsSync(server), `Server JAR not found: ${server}`);
    await config.update(
      "languageServer.javaHome",
      previousJava,
      vscode.ConfigurationTarget.Global,
    );
    await config.update(
      "server.path",
      server,
      vscode.ConfigurationTarget.Global,
    );
    const document = await vscode.workspace.openTextDocument({
      language: "groovy",
      content: "class AfterGdsl { String hello() { 'hello' } }",
    });
    await vscode.window.showTextDocument(document);
    const deadline = Date.now() + 20_000;
    while (Date.now() < deadline) {
      const symbols = await vscode.commands.executeCommand<
        vscode.DocumentSymbol[]
      >("vscode.executeDocumentSymbolProvider", document.uri);
      if (symbols?.some((symbol) => symbol.name === "AfterGdsl")) return;
      await new Promise<void>((resolve) => setTimeout(resolve, 100));
    }
    assert.fail(
      "The real Groovy language service did not start after GDSL editing",
    );
  });
});
