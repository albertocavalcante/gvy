import * as assert from "assert";
import * as sinon from "sinon";
import proxyquire from "proxyquire";

describe("extension activation", () => {
  function load(startClient: sinon.SinonStub) {
    const stubs = {
      registerGradleFeatures: sinon.stub(),
      registerTestingFeatures: sinon.stub(),
      registerDependencyFeatures: sinon.stub(),
      initializeUpdateService: sinon.stub().returns({
        activate: sinon.stub().resolves(),
        dispose: sinon.stub(),
      }),
      executeCommand: sinon.stub().resolves(),
    };
    const disposable = { dispose: () => {} };
    const vscodeMock = {
      window: {
        createOutputChannel: () => disposable,
        showErrorMessage: sinon.stub(),
      },
      commands: { executeCommand: stubs.executeCommand },
    };
    const ext = proxyquire.noCallThru()("../../src/extension", {
      vscode: vscodeMock,
      "./server/client": {
        initializeClient: () => {},
        startClient,
        stopClient: sinon.stub().resolves(),
        getClient: () => undefined,
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
        registerCommands: () => [],
        initializeUpdateService: stubs.initializeUpdateService,
        setServerOutputChannel: () => {},
      },
      "./configuration/watcher": { setupConfigurationWatcher: () => disposable },
      "./configuration/settings": {
        getUpdateConfiguration: () => ({ checkOnStartup: false }),
      },
      "./features/repl": { replService: { initialize: () => {} } },
      "./features/gradle": { registerGradleFeatures: stubs.registerGradleFeatures },
      "./features/testing": { registerTestingFeatures: stubs.registerTestingFeatures },
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
    return { ext, stubs };
  }

  const context = () => ({
    subscriptions: [] as unknown[],
    extension: { packageJSON: { version: "1.2.3" } },
  });

  it("registers every feature even when the server fails to start", async () => {
    const { ext, stubs } = load(sinon.stub().rejects(new Error("Java validation failed")));
    await ext.activate(context());

    assert.ok(stubs.registerGradleFeatures.calledOnce, "gradle commands");
    assert.ok(stubs.registerTestingFeatures.calledOnce, "test commands");
    assert.ok(stubs.registerDependencyFeatures.calledOnce, "dependency view commands");
    assert.ok(stubs.initializeUpdateService.calledOnce, "update service");
    assert.ok(
      !stubs.executeCommand.calledWith("setContext", "groovy.hasProject", true),
    );
  });

  it("marks the project context once the server is running", async () => {
    const { ext, stubs } = load(sinon.stub().resolves());
    await ext.activate(context());
    assert.ok(stubs.executeCommand.calledWith("setContext", "groovy.hasProject", true));
  });
});
