// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import * as assert from "assert";
import * as sinon from "sinon";
import proxyquire from "proxyquire";

describe("extension activation", () => {
  interface TestDocument {
    languageId: string;
    uri: { scheme: string };
  }
  let reportedClient: unknown;
  beforeEach(() => {
    reportedClient = undefined;
  });

  function load(startClient: sinon.SinonStub, documents: TestDocument[] = []) {
    const started = new Set<() => void>();
    const stubs = {
      registerGradleFeatures: sinon.stub(),
      registerTestingFeatures: sinon.stub(),
      registerDependencyFeatures: sinon.stub(),
      registerFileExecution: sinon.stub(),
      initializeUpdateService: sinon.stub().returns({
        activate: sinon.stub().resolves(),
        dispose: sinon.stub(),
      }),
      executeCommand: sinon.stub().resolves(),
      registerCommands: sinon.stub().returns([]),
      onDidOpenTextDocument: sinon.stub(),
      findFiles: sinon.stub().resolves([]),
    };
    const disposable = { dispose: () => {} };
    const documentListener = { dispose: sinon.stub() };
    stubs.onDidOpenTextDocument.returns(documentListener);
    const vscodeMock = {
      "@global": true,
      window: {
        createOutputChannel: () => disposable,
        showErrorMessage: sinon.stub(),
      },
      commands: { executeCommand: stubs.executeCommand },
      workspace: {
        textDocuments: documents,
        onDidOpenTextDocument: stubs.onDidOpenTextDocument,
        findFiles: stubs.findFiles,
      },
    };
    const ext = proxyquire.noCallThru()("../../src/extension", {
      vscode: vscodeMock,
      "./server/client": {
        initializeClient: () => {},
        startClient: async () => {
          await startClient();
          started.forEach((listener) => listener());
        },
        stopClient: sinon.stub().resolves(),
        getClient: () => reportedClient,
        onClientStarted: (listener: () => void) => {
          started.add(listener);
          return { dispose: () => started.delete(listener) };
        },
      },
      "./ui/statusBar": {
        registerStatusBarItem: () => disposable,
        getStatusBarManager: () => undefined,
      },
      "./ui/languageStatus": {
        createLanguageStatusManager: () => {},
        disposeLanguageStatusManager: () => {},
      },
      "./commands": {
        registerCommands: stubs.registerCommands,
        initializeUpdateService: stubs.initializeUpdateService,
        setServerOutputChannel: () => {},
      },
      "./configuration/watcher": {
        setupConfigurationWatcher: () => disposable,
      },
      "./configuration/settings": {
        getUpdateConfiguration: () => ({ checkOnStartup: false }),
        getConfiguration: () => ({ serverPath: undefined }),
      },
      "./features/repl": { replService: { initialize: () => {} } },
      "./features/fileExecution": {
        registerFileExecution: stubs.registerFileExecution,
      },
      "./features/gradle": {
        registerGradleFeatures: stubs.registerGradleFeatures,
      },
      "./features/testing": {
        registerTestingFeatures: stubs.registerTestingFeatures,
      },
      "./features/dependencies": {
        registerDependencyFeatures: stubs.registerDependencyFeatures,
      },
      "./features/ai/LSPToolService": { LSPToolService: class {} },
      "./features/ai/ToolRegistry": { ToolRegistry: class {} },
      "./features/ai/LMToolProvider": {
        LMToolProvider: class {
          dispose() {}
        },
      },
      "./features/ai/CommandProvider": {
        CommandProvider: class {
          dispose() {}
        },
      },
      "./features/testing/TestFeature": {
        TestFeature: class {
          dispose() {}
        },
      },
      "./features/ast/AstProvider": { registerAstFeatures: () => {} },
    });
    return { ext, stubs, documentListener };
  }

  const context = () => ({
    subscriptions: [] as unknown[],
    extension: { packageJSON: { version: "1.2.3" } },
  });

  it("registers every feature even when the server fails to start", async () => {
    const { ext, stubs } = load(
      sinon.stub().rejects(new Error("Java validation failed")),
    );
    await ext.activate(context());

    assert.ok(stubs.registerGradleFeatures.calledOnce, "gradle commands");
    assert.ok(
      stubs.registerFileExecution.calledOnce,
      "file execution commands",
    );
    assert.ok(stubs.registerTestingFeatures.calledOnce, "test commands");
    assert.ok(
      stubs.registerDependencyFeatures.calledOnce,
      "dependency view commands",
    );
    assert.ok(stubs.initializeUpdateService.calledOnce, "update service");
    assert.ok(
      !stubs.executeCommand.calledWith("setContext", "groovy.hasProject", true),
    );
  });

  it("marks the project context once the server is running", async () => {
    const { ext, stubs } = load(sinon.stub().resolves());
    await ext.activate(context());
    assert.ok(
      stubs.executeCommand.calledWith("setContext", "groovy.hasProject", true),
    );
  });

  const gdsl: TestDocument = {
    languageId: "gdsl",
    uri: { scheme: "file" },
  };

  it("keeps GDSL-only activation independent of server and Java setup", async () => {
    const start = sinon.stub().resolves();
    const { ext, stubs } = load(start, [gdsl]);
    await ext.activate(context());
    assert.ok(start.notCalled);
    assert.ok(stubs.registerTestingFeatures.calledOnce);
    assert.ok(stubs.onDidOpenTextDocument.calledOnce);
  });

  it("starts once when Groovy opens after GDSL activation", async () => {
    const start = sinon.stub().resolves();
    const { ext, stubs, documentListener } = load(start, [gdsl]);
    await ext.activate(context());
    const open = stubs.onDidOpenTextDocument.firstCall.args[0] as (
      document: TestDocument,
    ) => Promise<void>;
    const groovy = { languageId: "groovy", uri: { scheme: "file" } };
    await Promise.all([open(groovy), open(groovy)]);
    assert.ok(start.calledOnce);
    assert.ok(documentListener.dispose.calledOnce);
    assert.ok(
      stubs.executeCommand.calledWith("setContext", "groovy.hasProject", true),
    );
  });

  it("preserves eager startup for an existing Groovy project", async () => {
    const start = sinon.stub().resolves();
    const { ext, stubs } = load(start, [gdsl]);
    stubs.findFiles.resolves([{ path: "src/Main.groovy" }]);
    await ext.activate(context());
    assert.ok(start.calledOnce);
  });

  it("can explicitly request the deferred initial server startup", async () => {
    const start = sinon.stub().resolves();
    const { ext, stubs } = load(start, [gdsl]);
    await ext.activate(context());
    const request = stubs.registerCommands.firstCall
      .args[1] as () => Promise<boolean>;
    assert.strictEqual(await request(), true);
    assert.strictEqual(await request(), true);
    assert.ok(start.calledOnce);
  });

  it("keeps activation usable if the workspace search fails", async () => {
    const start = sinon.stub().resolves();
    const { ext, stubs } = load(start, [gdsl]);
    stubs.findFiles.rejects(new Error("Search provider unavailable"));
    await ext.activate(context());
    assert.ok(start.notCalled);
    const open = stubs.onDidOpenTextDocument.firstCall.args[0] as (
      document: TestDocument,
    ) => Promise<void>;
    await open({ languageId: "jenkinsfile", uri: { scheme: "untitled" } });
    assert.ok(start.calledOnce);
  });

  it("ignores further GDSL and unsupported document opens", async () => {
    const start = sinon.stub().resolves();
    const { ext, stubs } = load(start, [gdsl]);
    await ext.activate(context());
    const open = stubs.onDidOpenTextDocument.firstCall.args[0] as (
      document: TestDocument,
    ) => Promise<void>;
    await open(gdsl);
    await open({ languageId: "groovy", uri: { scheme: "git" } });
    await open({ languageId: "plaintext", uri: { scheme: "file" } });
    assert.ok(start.notCalled);
  });

  it("does not launch after disposal while checking project files", async () => {
    const start = sinon.stub().resolves();
    const { ext, stubs } = load(start, [gdsl]);
    let finishScan: ((files: unknown[]) => void) | undefined;
    stubs.findFiles.returns(
      new Promise<unknown[]>((resolve) => {
        finishScan = resolve;
      }),
    );
    const ctx = context();
    const activation = ext.activate(ctx);
    for (const subscription of ctx.subscriptions) {
      (subscription as { dispose(): void }).dispose();
    }
    assert.ok(finishScan);
    finishScan([{ path: "Main.groovy" }]);
    await activation;
    assert.ok(start.notCalled);
  });

  it("checks updates against the server's version, not the extension's", async () => {
    const { ext, stubs } = load(sinon.stub().resolves());
    await ext.activate({
      ...context(),
      asAbsolutePath: () => "/nonexistent/server/.gls-version",
    });
    const currentVersion = stubs.initializeUpdateService.firstCall.args[1];
    assert.strictEqual(typeof currentVersion, "function");
    assert.strictEqual(
      currentVersion(),
      "unknown",
      "no version file, no server",
    );
    reportedClient = { initializeResult: { serverInfo: { version: "0.4.8" } } };
    assert.strictEqual(currentVersion(), "0.4.8");
  });
});
