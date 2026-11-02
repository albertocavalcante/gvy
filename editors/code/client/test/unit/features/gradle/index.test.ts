import * as assert from "assert";
import * as sinon from "sinon";
import proxyquire from "proxyquire";

type Handler = (...args: unknown[]) => unknown;

function load() {
  const handlers = new Map<string, Handler>();
  const stubs = {
    restartClient: sinon.stub().resolves(),
    showInformationMessage: sinon.stub(),
    showErrorMessage: sinon.stub(),
    executeTask: sinon.stub().resolves(),
    fetchTasks: sinon.stub().resolves([]),
    registerTaskProvider: sinon.stub().returns({ dispose: () => {} }),
  };
  const vscodeMock = {
    commands: {
      registerCommand: (id: string, handler: Handler) => {
        handlers.set(id, handler);
        return { dispose: () => {} };
      },
      executeCommand: sinon.stub(),
    },
    tasks: {
      registerTaskProvider: stubs.registerTaskProvider,
      fetchTasks: stubs.fetchTasks,
      executeTask: stubs.executeTask,
    },
    window: {
      showInformationMessage: stubs.showInformationMessage,
      showErrorMessage: stubs.showErrorMessage,
    },
  };
  const mod = proxyquire.noCallThru()("../../../../src/features/gradle", {
    vscode: vscodeMock,
    "../../server/client": {
      getClient: () => undefined,
      restartClient: stubs.restartClient,
    },
    "./GradleService": { GradleService: class {} },
    "./GradleTaskProvider": {
      GradleTaskProvider: class {},
    },
  });
  mod.registerGradleFeatures({ subscriptions: [] });
  return { handlers, stubs };
}

describe("gradle feature commands", () => {
  it("refreshes the project by restarting the server, which re-imports it", async () => {
    const { handlers, stubs } = load();
    await handlers.get("groovy.gradle.refresh")?.();
    assert.ok(stubs.restartClient.calledOnce);
    assert.ok(
      !stubs.showInformationMessage.calledWithMatch(/Not implemented/),
      "must not report the command as unimplemented",
    );
  });
});
