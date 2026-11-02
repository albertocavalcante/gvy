import * as assert from "assert";
import * as sinon from "sinon";
import proxyquire from "proxyquire";

describe("configuration/watcher", () => {
  let listener: (event: unknown) => Promise<void>;
  let client:
    { isRunning: () => boolean; sendNotification: sinon.SinonStub } | undefined;
  let showInformationMessage: sinon.SinonStub;
  let restartClient: sinon.SinonStub;

  beforeEach(() => {
    showInformationMessage = sinon.stub().resolves(undefined);
    restartClient = sinon.stub().resolves();
    client = undefined;
    const { setupConfigurationWatcher } = proxyquire.noCallThru()(
      "../../../src/configuration/watcher",
      {
        vscode: {
          workspace: {
            onDidChangeConfiguration: (cb: typeof listener) => {
              listener = cb;
              return { dispose: () => {} };
            },
          },
          window: { showInformationMessage },
          "@global": true,
        },
        "../server/client": {
          restartClient,
          getClient: () => client,
          buildServerSettingsMap: () => ({ "groovy.codenarc.enabled": false }),
        },
      },
    );
    setupConfigurationWatcher();
  });

  const change = (key: string) => ({
    affectsConfiguration: (section: string) =>
      key === section || key.startsWith(`${section}.`),
  });

  it("does not notify a server that is not running", async () => {
    client = {
      isRunning: () => false,
      sendNotification: sinon.stub().rejects(new Error("not running")),
    };
    await listener(change("groovy.codenarc.enabled"));
    assert.ok(client.sendNotification.notCalled);
  });

  it("handles a rejected didChangeConfiguration notification", async () => {
    client = {
      isRunning: () => true,
      sendNotification: sinon.stub().rejects(new Error("connection closed")),
    };
    await listener(change("groovy.codenarc.enabled"));
    assert.ok(client.sendNotification.calledOnce);
  });

  it("offers a restart when the engine setting changes", async () => {
    showInformationMessage.resolves("Restart Now");
    await listener(change("groovy.languageServer.engine"));
    assert.ok(restartClient.calledOnce);
  });
});
