// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import * as assert from "assert";
import { createHash } from "crypto";
import { existsSync, readFileSync, realpathSync } from "fs";
import { mkdtemp, rm, stat, writeFile } from "fs/promises";
import { tmpdir } from "os";
import * as path from "path";
import * as vscode from "vscode";

suite("Jenkins parameter authoring evidence", () => {
  let serverSha256: string;

  suiteSetup(async function () {
    this.timeout(60000);
    const extension = vscode.extensions.getExtension("albertocavalcante.gvy");
    assert.ok(extension);
    if (process.env.GVY_TEST_EXTENSION_ROOT) {
      assert.strictEqual(
        realpathSync(extension.extensionUri.fsPath),
        realpathSync(process.env.GVY_TEST_EXTENSION_ROOT),
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
    const hash = (file: string) =>
      createHash("sha256").update(readFileSync(file)).digest("hex");
    serverSha256 = hash(candidate);
    assert.strictEqual(
      hash(
        vscode.Uri.joinPath(extension.extensionUri, "server/gls.jar").fsPath,
      ),
      serverSha256,
      "The editor must load the candidate server",
    );
    assert.ok(
      !vscode.workspace.getConfiguration("groovy").get<string>("server.path"),
      "Server startup must use the bundled candidate without a workspace override",
    );
    await extension.activate();
  });

  async function withFixture(
    name: string,
    check: (
      editor: vscode.TextEditor,
      source: string,
      offset: number,
    ) => Promise<void>,
    language: "jenkinsfile" | "groovy" = "jenkinsfile",
  ) {
    const fixture = path.resolve(
      __dirname,
      `../../../../../../gls/src/test/resources/fixtures/jenkins-declarative/${name}.Jenkinsfile`,
    );
    const marked = readFileSync(fixture, "utf8");
    const marker = "/*caret*/";
    const offset = marked.indexOf(marker);
    assert.ok(offset >= 0 && offset === marked.lastIndexOf(marker));
    const source = marked.replace(marker, "");
    const directory = await mkdtemp(
      path.join(tmpdir(), "gvy-jenkins-authoring-"),
    );
    const uri = vscode.Uri.file(path.join(directory, "Jenkinsfile"));
    try {
      await writeFile(uri.fsPath, source);
      const opened = await vscode.workspace.openTextDocument(uri);
      assert.strictEqual(opened.languageId, "jenkinsfile");
      const document =
        language === "groovy"
          ? await vscode.languages.setTextDocumentLanguage(opened, "groovy")
          : opened;
      assert.strictEqual(document.languageId, language);
      assert.ok(
        !vscode.workspace
          .getConfiguration("groovy", uri)
          .get<string>("server.path"),
        "Use the candidate bundled server without a path override",
      );
      const editor = await vscode.window.showTextDocument(document);
      const position = document.positionAt(offset);
      editor.selection = new vscode.Selection(position, position);
      await check(editor, source, offset);
    } finally {
      const open = vscode.workspace.textDocuments.find(
        (document) => document.uri.toString() === uri.toString(),
      );
      if (open && !open.isClosed) {
        await vscode.window.showTextDocument(open);
        await vscode.commands.executeCommand("hideSuggestWidget");
        await vscode.commands.executeCommand(
          "workbench.action.revertAndCloseActiveEditor",
        );
      }
      await rm(directory, { recursive: true, force: true });
    }
  }

  async function capture(name: string, observation: object) {
    const directory = process.env.GVY_JENKINS_EVIDENCE_DIR;
    if (!directory) return;
    assert.ok(
      path.isAbsolute(directory) && (await stat(directory)).isDirectory(),
    );
    await writeFile(
      path.join(directory, `editor-${name}.json`),
      JSON.stringify(
        {
          vscodeVersion: vscode.version,
          serverSha256,
          ...observation,
        },
        null,
        2,
      ),
    );
  }

  for (const name of [
    "parameters-blank",
    "parameters-partial",
    "parameters-arguments",
  ]) {
    test(`captures the separate VS Code provider response for ${name}`, async () => {
      await withFixture(name, async (editor, source, offset) => {
        const result =
          await vscode.commands.executeCommand<vscode.CompletionList>(
            "vscode.executeCompletionItemProvider",
            editor.document.uri,
            editor.document.positionAt(offset),
          );
        assert.ok(result && result.items.length > 0);
        assert.strictEqual(editor.document.getText(), source);
        await capture(name, {
          layer: "vscode-provider-api",
          languageId: editor.document.languageId,
          snippetSuggestions: vscode.workspace
            .getConfiguration("editor", editor.document.uri)
            .get("snippetSuggestions"),
          source,
          cursorOffset: offset,
          result,
        });
      });
    });
  }

  for (const site of [
    {
      name: "parameters-blank",
      typed: "",
      entry: "booleanParam",
      language: "jenkinsfile" as const,
    },
    {
      name: "parameters-partial",
      typed: "str",
      entry: "string",
      language: "jenkinsfile" as const,
    },
    {
      name: "parameters-blank",
      typed: "",
      entry: "booleanParam",
      language: "groovy" as const,
    },
  ]) {
    test(`accepts ${site.entry} through the suggest widget in ${site.language} mode`, async () => {
      await withFixture(
        site.name,
        async (editor, source, offset) => {
          // Readiness only: acceptance below uses the widget, never insertSnippet or an API item.
          const ready =
            await vscode.commands.executeCommand<vscode.CompletionList>(
              "vscode.executeCompletionItemProvider",
              editor.document.uri,
              editor.document.positionAt(offset),
            );
          assert.ok(ready?.items.some((item) => item.label === site.entry));
          await vscode.commands.executeCommand("editor.action.triggerSuggest");
          if (process.env.GVY_JENKINS_WIDGET_PAUSE === "true") {
            assert.ok(
              process.env.GVY_JENKINS_EVIDENCE_DIR,
              "A widget capture needs a private evidence directory",
            );
            await capture(`${site.name}-${site.language}-widget-ready`, {
              layer: "widget-capture-readiness",
              languageId: editor.document.languageId,
              snippetSuggestions: vscode.workspace
                .getConfiguration("editor", editor.document.uri)
                .get("snippetSuggestions"),
              showSnippets: vscode.workspace
                .getConfiguration("editor", editor.document.uri)
                .get("suggest.showSnippets"),
              builtInGroovyPresent:
                !!vscode.extensions.getExtension("vscode.groovy"),
              source,
              cursorOffset: offset,
            });
            // Gives an external desktop capture time to record the actual rendered list.
            await new Promise((resolve) => setTimeout(resolve, 15000));
          }
          const deadline = Date.now() + 5000;
          while (
            editor.document.getText() === source &&
            Date.now() < deadline
          ) {
            await new Promise((resolve) => setTimeout(resolve, 50));
            await vscode.commands.executeCommand("acceptSelectedSuggestion");
          }
          const accepted = editor.document.getText();
          assert.notStrictEqual(
            accepted,
            source,
            "The suggest widget must accept an entry",
          );
          const prefix = source.slice(0, offset - site.typed.length);
          const suffix = source.slice(offset);
          assert.ok(
            accepted.startsWith(prefix + site.entry),
            "Accept the Jenkins parameter entry",
          );
          assert.ok(
            accepted.endsWith(suffix),
            "Preserve the rest of the Pipeline",
          );
          await capture(`${site.name}-${site.language}-accepted`, {
            layer: "suggest-widget-acceptance",
            languageId: editor.document.languageId,
            source,
            accepted,
            cursorOffset: offset,
          });
        },
        site.language,
      );
    });
  }
});
