// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import * as assert from "assert";
import * as sinon from "sinon";
import proxyquire from "proxyquire";

describe("groovy.retryDependencyResolution", () => {
  function load(running = true, initialStart?: () => Promise<boolean>) {
    const handlers = new Map<string, () => Promise<void>>();
    // The server has no groovy.resolveDependencies command: an unknown
    // workspace/executeCommand resolves to null instead of failing.
    const sendRequest = sinon.stub().resolves(null);
    const restartClient = sinon.stub().resolves();
    const window = {
      showWarningMessage: sinon.stub(),
      showErrorMessage: sinon.stub(),
      showInformationMessage: sinon.stub(),
      withProgress: sinon.stub().callsFake((_options, task) => task()),
    };
    const vscodeMock = {
      commands: {
        registerCommand: (id: string, handler: () => Promise<void>) => {
          handlers.set(id, handler);
          return { dispose: () => {} };
        },
      },
      window,
      Uri: { parse: sinon.stub() },
      ProgressLocation: { Notification: 15 },
      version: "1.0.0",
    };
    const mod = proxyquire.noCallThru()("../../../src/commands/index", {
      vscode: vscodeMock,
      "vscode-languageclient": {
        ExecuteCommandRequest: { type: "workspace/executeCommand" },
      },
      "../utils/reportIssue": { buildIssueBody: () => "" },
      "../server/client": {
        restartClient,
        stopClient: sinon.stub(),
        getClient: () =>
          running ? { sendRequest, initializeResult: {} } : undefined,
      },
      "../features/update/UpdateService": { UpdateService: class {} },
      "../ui/statusBar": {
        showStatusMenu: sinon.stub(),
        getStatusBarManager: () => undefined,
      },
      "./logLevel": {
        toggleDebugLogs: sinon.stub(),
        selectLogLevel: sinon.stub(),
      },
      "../java/commands": { registerJavaCommands: () => {} },
    });
    mod.registerCommands(
      { subscriptions: [] },
      initialStart
        ? async () => {
            const ready = await initialStart();
            if (ready) running = true;
            return ready;
          }
        : undefined,
    );
    const retry = handlers.get("groovy.retryDependencyResolution");
    assert.ok(retry, "retry command is registered");
    return { retry, sendRequest, restartClient, window, handlers };
  }

  it("does not send a command the server does not implement", async () => {
    const { retry, sendRequest } = load();
    await retry();
    const commandsSent = sendRequest.getCalls().map((c) => c.args[1]?.command);
    assert.ok(
      !commandsSent.includes("groovy.resolveDependencies"),
      `sent ${JSON.stringify(commandsSent)}`,
    );
  });

  it("retries by restarting the server, which resolves dependencies at startup", async () => {
    const { retry, restartClient } = load();
    await retry();
    assert.ok(restartClient.calledOnce, "server was not restarted");
  });

  it("starts the server when it is not running instead of asking the user to", async () => {
    const { retry, restartClient, window } = load(false);
    await retry();
    assert.ok(restartClient.calledOnce, "server was not started");
    assert.ok(window.showWarningMessage.notCalled);
  });

  it("starts a deferred server before querying its version", async () => {
    const start = sinon.stub().resolves(true);
    const { handlers, sendRequest } = load(false, start);
    await handlers.get("groovy.showVersion")!();
    assert.ok(start.calledOnce);
    assert.ok(sendRequest.calledOnce);
  });

  it("avoids querying and duplicating an already reported startup failure", async () => {
    const start = sinon.stub().resolves(false);
    const { handlers, sendRequest, window } = load(false, start);
    await handlers.get("groovy.showVersion")!();
    assert.ok(start.calledOnce);
    assert.ok(sendRequest.notCalled);
    assert.ok(window.showErrorMessage.notCalled);
  });
});
