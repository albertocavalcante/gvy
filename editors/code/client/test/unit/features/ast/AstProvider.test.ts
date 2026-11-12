// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import * as assert from "assert";
import * as sinon from "sinon";
import proxyquire from "proxyquire";

describe("AstProvider", () => {
  it("keeps targeting the Groovy editor after the AST panel takes focus", async () => {
    const doc = {
      uri: { toString: () => "file:///w/A.groovy" },
      languageId: "groovy",
    };
    const editor = {
      document: doc,
      viewColumn: 1,
      selection: undefined as unknown,
      revealRange: sinon.stub(),
    };
    const sendRequest = sinon.stub().resolves({ ast: "{}", parser: "core" });
    let onMessage: ((m: unknown) => void) | undefined;
    const posted: Array<{ type: string }> = [];
    const webview = {
      html: "",
      cspSource: "csp",
      asWebviewUri: (u: unknown) => u,
      postMessage: (m: { type: string }) => posted.push(m),
      onDidReceiveMessage: (cb: (m: unknown) => void) => {
        onMessage = cb;
        return { dispose: () => {} };
      },
    };
    const window = {
      activeTextEditor: editor as unknown,
      visibleTextEditors: [editor],
      createWebviewPanel: () => ({
        webview,
        visible: true,
        reveal: () => {},
        onDidDispose: () => ({ dispose: () => {} }),
        onDidChangeViewState: () => ({ dispose: () => {} }),
        dispose: () => {},
      }),
      onDidChangeActiveTextEditor: () => ({ dispose: () => {} }),
    };
    let command: (() => void) | undefined;
    const vscodeMock = {
      window,
      workspace: { onDidSaveTextDocument: () => ({ dispose: () => {} }) },
      commands: {
        registerCommand: (_id: string, cb: () => void) => {
          command = cb;
          return { dispose: () => {} };
        },
      },
      Uri: { joinPath: (...parts: unknown[]) => parts.join("/") },
      ViewColumn: { One: 1 },
      Disposable: { from: () => ({ dispose: () => {} }) },
      Position: class {
        constructor(
          public line: number,
          public character: number,
        ) {}
      },
      Selection: class {
        constructor(
          public anchor: unknown,
          public active: unknown,
        ) {}
      },
      TextEditorRevealType: { InCenterIfOutsideViewport: 2 },
    };
    const { registerAstFeatures } = proxyquire.noCallThru()(
      "../../../../src/features/ast/AstProvider",
      {
        vscode: vscodeMock,
        "../../server/client": { getClient: () => ({ sendRequest }) },
      },
    );
    registerAstFeatures({ subscriptions: [], extensionUri: "ext" });
    command!();

    // Webview resources must come from the packaged resources/ folder
    // (node_modules is not in the VSIX nor in localResourceRoots).
    assert.ok(!webview.html.includes("node_modules"));
    assert.ok(
      webview.html.includes("ext/resources/ast-view/codicons/codicon.css"),
    );

    // The webview panel is now the active editor.
    window.activeTextEditor = undefined;
    onMessage!({ type: "ready" });
    await new Promise((r) => setImmediate(r));

    assert.ok(sendRequest.calledOnce, "AST should be requested");
    assert.strictEqual(sendRequest.firstCall.args[1].uri, "file:///w/A.groovy");
    assert.ok(!posted.some((m) => m.type === "error"));

    onMessage!({
      type: "highlight",
      range: { startLine: 1, startColumn: 1, endLine: 1, endColumn: 4 },
    });
    assert.ok(
      editor.revealRange.calledOnce,
      "node click should reveal in the editor",
    );

    // Malformed ranges from the webview are ignored instead of throwing.
    onMessage!({ type: "highlight", range: { startLine: "x" } });
    assert.ok(editor.revealRange.calledOnce);
  });
});
