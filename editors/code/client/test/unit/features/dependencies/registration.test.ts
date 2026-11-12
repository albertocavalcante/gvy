import * as assert from "assert";
import * as sinon from "sinon";
import * as proxyquire from "proxyquire";

describe("dependency view lifecycle registration", () => {
  it("refreshes on explicit starts and owns provider/listener disposal", () => {
    const provider = { refresh: sinon.spy(), dispose: sinon.spy() };
    const listenerSubscription = { dispose: sinon.spy() };
    let started: (() => void) | undefined;
    const context = { subscriptions: [] as { dispose(): void }[] };
    const module = proxyquire.noCallThru()(
      "../../../../src/features/dependencies",
      {
        vscode: {
          window: { registerTreeDataProvider: () => ({ dispose() {} }) },
          commands: { registerCommand: () => ({ dispose() {} }) },
        },
        "../../server/client": {
          getClient: () => undefined,
          onClientStarted: (listener: () => void) => {
            started = listener;
            return listenerSubscription;
          },
        },
        "./DependencyService": { DependencyService: class {} },
        "./DependencyTreeProvider": {
          DependencyTreeProvider: class {
            constructor() {
              return provider;
            }
          },
        },
      },
    ) as {
      registerDependencyFeatures(
        extensionContext: typeof context,
      ): typeof provider;
    };

    assert.strictEqual(module.registerDependencyFeatures(context), provider);
    assert.ok(started);
    started();
    sinon.assert.calledOnce(provider.refresh);
    context.subscriptions.forEach((subscription) => subscription.dispose());
    sinon.assert.calledOnce(provider.dispose);
    sinon.assert.calledOnce(listenerSubscription.dispose);
  });
});
