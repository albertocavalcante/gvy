// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import { assert } from "chai";
import * as sinon from "sinon";
import proxyquire from "proxyquire";

describe("analysis status UI", () => {
  it("shows details for the document named by a Problems remedy", async () => {
    const target = {
      uri: { toString: () => "file:///target.groovy" },
      version: 1,
    };
    const active = {
      uri: { toString: () => "file:///other.groovy" },
      version: 1,
    };
    const disposable = { dispose: () => {} };
    const picker = sinon.stub().resolves(undefined);
    let notification: (value: unknown) => void = () => {};
    let showDetails: (uri?: string) => Promise<void> = async () => {};
    const vscode = {
      StatusBarAlignment: { Right: 1 },
      window: {
        createStatusBarItem: () => ({
          show: () => {},
          hide: () => {},
          dispose: () => {},
        }),
        activeTextEditor: { document: active },
        onDidChangeActiveTextEditor: () => disposable,
        showQuickPick: picker,
      },
      workspace: {
        textDocuments: [target, active],
        onDidOpenTextDocument: () => disposable,
        onDidCloseTextDocument: () => disposable,
        onDidChangeTextDocument: () => disposable,
      },
      commands: {
        registerCommand: (name: string, handler: typeof showDetails) => {
          if (name === "groovy.showAnalysisDetails") showDetails = handler;
          return disposable;
        },
      },
    };
    const { AnalysisUi } = proxyquire.noCallThru()("../../../src/analysis/ui", {
      vscode,
    });
    const ui = new AnalysisUi();
    ui.setClient({
      onNotification: (_name: string, callback: typeof notification) => {
        notification = callback;
        return disposable;
      },
    });
    const sessionId = ui.beginSession(target);
    notification({
      uri: target.uri.toString(),
      sessionId,
      version: 1,
      runId: 1,
      compiler: { complete: false, reason: "Target dependency is missing" },
      providerFailures: [],
    });

    await showDetails(target.uri.toString());

    assert.isTrue(picker.calledOnce);
    assert.equal(
      picker.firstCall.args[0][0].description,
      "Target dependency is missing",
    );
    ui.dispose();
  });

  it("shows only the active snapshot and hides it on edit and recovery", () => {
    const doc = { uri: { toString: () => "file:///a.groovy" }, version: 1 };
    const status = {
      show: sinon.spy(),
      hide: sinon.spy(),
      dispose: sinon.spy(),
      text: "",
      command: "",
      tooltip: "",
    };
    let changed: (event: unknown) => void = () => {};
    let notification: (value: unknown) => void = () => {};
    const disposable = { dispose: () => {} };
    const vscode = {
      StatusBarAlignment: { Right: 1 },
      window: {
        createStatusBarItem: () => status,
        activeTextEditor: { document: doc },
        onDidChangeActiveTextEditor: () => disposable,
      },
      workspace: {
        textDocuments: [doc],
        onDidOpenTextDocument: () => disposable,
        onDidCloseTextDocument: () => disposable,
        onDidChangeTextDocument: (callback: typeof changed) => {
          changed = callback;
          return disposable;
        },
      },
      commands: { registerCommand: () => disposable },
    };
    const { AnalysisUi } = proxyquire.noCallThru()("../../../src/analysis/ui", {
      vscode,
    });
    const ui = new AnalysisUi();
    ui.setClient({
      onNotification: (_name: string, callback: typeof notification) => {
        notification = callback;
        return disposable;
      },
    });
    const sessionId = ui.beginSession(doc);
    notification({
      uri: "file:///a.groovy",
      sessionId,
      version: 1,
      runId: 1,
      compiler: { complete: false },
      providerFailures: [],
    });
    assert.isTrue(status.show.calledOnce);
    assert.equal(status.tooltip, "Type analysis is limited");
    doc.version = 2;
    changed({ document: doc });
    assert.isTrue(status.hide.called);
    notification({
      uri: "file:///a.groovy",
      sessionId,
      version: 1,
      runId: 2,
      compiler: { complete: false },
      providerFailures: [],
    });
    assert.isTrue(status.show.calledOnce);
    notification({
      uri: "file:///a.groovy",
      sessionId,
      version: 2,
      runId: 3,
      compiler: { complete: true },
      providerFailures: [],
    });
    assert.isTrue(status.show.calledOnce);
    ui.dispose();
  });
});
